"""
Thin ctypes wrapper over the system libsodium.

The spec (§8) locks all crypto to libsodium. Binding the C library directly
(not PyNaCl's abstractions) guarantees the reference vectors are byte-identical
to what `lazysodium-android` (the same C code) will produce on Android.

Only the constructions named in SPEC.md are exposed; nothing is invented here.
"""

from __future__ import annotations

import ctypes
import ctypes.util
from ctypes import c_void_p, c_ulonglong

# Fixed sizes (stable ABI; kept as literals for clarity).
crypto_sign_PUBLICKEYBYTES = 32
crypto_sign_SECRETKEYBYTES = 64
crypto_sign_SEEDBYTES = 32
crypto_sign_BYTES = 64

crypto_box_PUBLICKEYBYTES = 32
crypto_box_SECRETKEYBYTES = 32
crypto_box_SEEDBYTES = 32
crypto_box_SEALBYTES = 48  # = PUBLICKEYBYTES(32) + MACBYTES(16), the sealed-box overhead

crypto_secretstream_xchacha20poly1305_KEYBYTES = 32
crypto_secretstream_xchacha20poly1305_HEADERBYTES = 24
crypto_secretstream_xchacha20poly1305_ABYTES = 17

TAG_MESSAGE = 0
TAG_PUSH = 1
TAG_REKEY = 2
TAG_FINAL = 3

_libname = ctypes.util.find_library("sodium") or "libsodium.so"
_lib = ctypes.CDLL(_libname)
_lib.sodium_init.restype = ctypes.c_int
if _lib.sodium_init() < 0:
    raise RuntimeError("sodium_init() failed")

# Non-NULL dummy buffer for empty additional-data (avoids passing NULL pointers).
_EMPTY_AD = (ctypes.c_ubyte * 1)()


def _out(n: int):
    """Writable output buffer of n bytes."""
    return (ctypes.c_ubyte * n)()


def _in(b: bytes):
    """Read-only input buffer (copy of b) suitable for c_void_p."""
    return (ctypes.c_ubyte * len(b)).from_buffer_copy(b)


def _seed_keypair(sign: bool, seed: bytes):
    if len(seed) != crypto_sign_SEEDBYTES:
        raise ValueError("seed must be 32 bytes")
    pk = _out(crypto_sign_PUBLICKEYBYTES)
    sk = _out(crypto_sign_SECRETKEYBYTES if sign else crypto_box_SECRETKEYBYTES)
    fn = _lib.crypto_sign_seed_keypair if sign else _lib.crypto_box_seed_keypair
    fn.argtypes = [c_void_p, c_void_p, c_void_p]
    fn.restype = ctypes.c_int
    if fn(pk, sk, _in(seed)) != 0:
        raise RuntimeError("seed_keypair failed")
    return bytes(pk), bytes(sk)


def ed25519_keypair(seed: bytes) -> tuple[bytes, bytes]:
    """Deterministic Ed25519 keypair from a 32-byte seed -> (pk, sk)."""
    return _seed_keypair(True, seed)


def x25519_keypair(seed: bytes) -> tuple[bytes, bytes]:
    """Deterministic X25519 keypair from a 32-byte seed -> (pk, sk)."""
    return _seed_keypair(False, seed)


def ed25519_sign(message: bytes, sk: bytes) -> bytes:
    if len(sk) != crypto_sign_SECRETKEYBYTES:
        raise ValueError("Ed25519 secret key must be 64 bytes")
    sig = _out(crypto_sign_BYTES)
    siglen = c_ulonglong(0)
    fn = _lib.crypto_sign_detached
    fn.argtypes = [c_void_p, ctypes.POINTER(c_ulonglong),
                   c_void_p, c_ulonglong, c_void_p]
    fn.restype = ctypes.c_int
    if fn(sig, ctypes.byref(siglen), _in(message), len(message), _in(sk)) != 0:
        raise RuntimeError("crypto_sign_detached failed")
    assert siglen.value == crypto_sign_BYTES
    return bytes(sig)


def ed25519_verify(sig: bytes, message: bytes, pk: bytes) -> bool:
    fn = _lib.crypto_sign_verify_detached
    fn.argtypes = [c_void_p, c_void_p, c_ulonglong, c_void_p]
    fn.restype = ctypes.c_int
    return fn(_in(sig), _in(message), len(message), _in(pk)) == 0


def box_seal(message: bytes, pk: bytes) -> bytes:
    """crypto_box_seal — anonymous-sender encryption to a recipient pk."""
    if len(pk) != crypto_box_PUBLICKEYBYTES:
        raise ValueError("recipient public key must be 32 bytes")
    c = _out(crypto_box_SEALBYTES + len(message))
    fn = _lib.crypto_box_seal
    fn.argtypes = [c_void_p, c_void_p, c_ulonglong, c_void_p]
    fn.restype = ctypes.c_int
    if fn(c, _in(message), len(message), _in(pk)) != 0:
        raise RuntimeError("crypto_box_seal failed")
    return bytes(c)


def box_seal_open(ciphertext: bytes, pk: bytes, sk: bytes) -> bytes:
    m = _out(len(ciphertext) - crypto_box_SEALBYTES)
    fn = _lib.crypto_box_seal_open
    fn.argtypes = [c_void_p, c_void_p, c_ulonglong, c_void_p, c_void_p]
    fn.restype = ctypes.c_int
    if fn(m, _in(ciphertext), len(ciphertext), _in(pk), _in(sk)) != 0:
        raise ValueError("crypto_box_seal_open failed (bad key or ciphertext)")
    return bytes(m)


class SecretStream:
    """Deterministic view of crypto_secretstream_xchacha20poly1305.

    libsodium's init_push generates a *random* 24-byte header internally and
    offers no public "init with a given header". To make the test vector
    reproducible, we initialise the stream with init_pull(state, header, key),
    which derives the *identical* keystream state from (key, header) — the only
    difference between init_push and init_pull is that init_push randomises the
    header bytes first. Feeding a fixed header therefore reproduces libsodium's
    own push output byte-for-byte (verified in selftest).
    """

    def __init__(self, key: bytes, header: bytes):
        if len(key) != crypto_secretstream_xchacha20poly1305_KEYBYTES:
            raise ValueError("secretstream key must be 32 bytes")
        if len(header) != crypto_secretstream_xchacha20poly1305_HEADERBYTES:
            raise ValueError("secretstream header must be 24 bytes")
        self.key = key
        self.header = header
        fn = _lib.crypto_secretstream_xchacha20poly1305_statebytes
        fn.argtypes = []
        fn.restype = ctypes.c_size_t
        statebytes = fn()
        self._state = _out(statebytes)
        fn = _lib.crypto_secretstream_xchacha20poly1305_init_pull
        fn.argtypes = [c_void_p, c_void_p, c_void_p]
        fn.restype = ctypes.c_int
        if fn(self._state, _in(header), _in(key)) != 0:
            raise RuntimeError("secretstream init_pull failed")

    def push(self, chunk: bytes, tag: int = TAG_FINAL, ad: bytes = b"") -> bytes:
        fn = _lib.crypto_secretstream_xchacha20poly1305_push
        fn.argtypes = [c_void_p, c_void_p, ctypes.POINTER(c_ulonglong),
                       c_void_p, c_ulonglong, c_void_p, c_ulonglong,
                       ctypes.c_ubyte]
        fn.restype = ctypes.c_int
        c = _out(len(chunk) + crypto_secretstream_xchacha20poly1305_ABYTES)
        clen = c_ulonglong(0)
        adbuf = _in(ad) if ad else _EMPTY_AD
        if fn(self._state, c, ctypes.byref(clen), _in(chunk), len(chunk),
              adbuf, len(ad), tag) != 0:
            raise RuntimeError("secretstream push failed")
        return bytes(c[:clen.value])

    def pull(self, ciphertext: bytes, ad: bytes = b"") -> tuple[bytes, int]:
        fn = _lib.crypto_secretstream_xchacha20poly1305_pull
        fn.argtypes = [c_void_p, c_void_p, ctypes.POINTER(c_ulonglong),
                       ctypes.POINTER(ctypes.c_ubyte), c_void_p, c_ulonglong,
                       c_void_p, c_ulonglong]
        fn.restype = ctypes.c_int
        m = _out(len(ciphertext))
        mlen = c_ulonglong(0)
        tag = ctypes.c_ubyte(0)
        adbuf = _in(ad) if ad else _EMPTY_AD
        if fn(self._state, m, ctypes.byref(mlen), ctypes.byref(tag),
              _in(ciphertext), len(ciphertext), adbuf, len(ad)) != 0:
            raise ValueError("secretstream pull failed (corrupt or wrong key)")
        return bytes(m[:mlen.value]), tag.value
