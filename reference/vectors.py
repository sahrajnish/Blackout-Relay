"""
M0 test vectors V1–V5 (SPEC.md §23.5).

Run `python3 -m reference.vectors` to regenerate and print the vectors, and
`python3 -m reference.vectors --selftest` to check them against the frozen
values in `frozen.py`.

Every fixed input below is chosen for determinism and documented; production
code uses randombytes_buf, but the *constructions* must match these vectors
byte-for-byte.
"""

from __future__ import annotations

import hashlib

from . import cbor, model, sodium


# --- Fixed inputs -----------------------------------------------------------

# V1: identity seeds (32 bytes each)
ED25519_SEED = bytes(range(0x00, 0x20))          # 00 01 ... 1f
X25519_SEED = bytes(range(0x20, 0x40))           # 20 21 ... 3f
CREATED_AT = 1700000000
KEY_EPOCH = 1

# V2: secretstream key (32 B) and fixed header (24 B)
K = bytes(range(0x40, 0x60))                     # 40 41 ... 5f
HEADER = bytes(range(0x80, 0x98))                # 80 81 ... 97
PLAINTEXT = (
    b"BLACKOUT-RELAY secretstream test vector v1.0 -- "
    b"fixed plaintext for cross-implementation checking."
)
CHUNK1_LEN = 40                                  # split point for two chunks

# V3: sealed_key is a *fixed* 80-byte field in the vector. crypto_box_seal is
# randomised (ephemeral sender key), so it cannot appear in a deterministic
# vector; the seal construction is exercised separately by a round-trip test.
SEALED_KEY = bytes((i & 0xFF) for i in range(0xE0, 0xE0 + 80))  # 80 bytes, e0..ff 00..2f

# V3/V4: manifest fixed fields
BUNDLE_ID = bytes(range(0xB0, 0xC0))             # b0 b1 ... bf
KDF_INFO_LEN = 0
DURATION_MS = 42000
LAT_E7 = 129712345                               # 12.9712345 N
LON_E7 = 776543210                               # 77.6543210 E
GPS_ACCURACY_M = 25
PUBLIC_GPS_PRECISION_M = 100
MEDIA_KIND = 1
ORIENTATION = 1
DEST_URLS_EXPIRE = 1700604800                    # created_at + 7 days
MAX_HOPS = 24
PRIORITY = 1
EXPIRES_AT = 1700259201                          # created_at + 72 h

# V5: pool fixed fields
POOL_ID = 1
MINTED_AT = 1700000000
POOL_EXPIRES_AT = 1700604800


def _sha(b: bytes) -> bytes:
    return hashlib.sha256(b).digest()


def _urls(dest_prefix_hex: str, slot: int) -> dict:
    base = f"https://bucket.r2.cloudflarestorage.com/{dest_prefix_hex}/{slot:04d}"
    return {
        1: f"{base}/manifest.cbor",
        2: f"{base}/thumb.enc",
        3: f"{base}/proxy.enc",
    }


# --- Vector computation -----------------------------------------------------

def v1():
    ed_pk, ed_sk = sodium.ed25519_keypair(ED25519_SEED)
    x_pk, x_sk = sodium.x25519_keypair(X25519_SEED)
    sig_input = model.identity_sig_input(ed_pk, x_pk, CREATED_AT, KEY_EPOCH)
    identity = model.build_identity(ed_pk, x_pk, CREATED_AT, KEY_EPOCH, ed_sk)
    return {
        "ed25519_pk": ed_pk,
        "ed25519_sk": ed_sk,
        "x25519_pk": x_pk,
        "x25519_sk": x_sk,
        "sig_input": sig_input,
        "signature": identity[5],
        "identity_cbor": cbor.encode(identity),
    }


def v2():
    key = K
    header = HEADER
    p = PLAINTEXT
    stream = sodium.SecretStream(key, header)
    c1 = stream.push(p[:CHUNK1_LEN], sodium.TAG_MESSAGE)
    c2 = stream.push(p[CHUNK1_LEN:], sodium.TAG_FINAL)
    return {
        "key": key,
        "header": header,
        "plaintext": p,
        "chunk1": p[:CHUNK1_LEN],
        "chunk2": p[CHUNK1_LEN:],
        "cipher_chunk1": c1,
        "cipher_chunk2": c2,
        "ciphertext": c1 + c2,
        "on_disk": header + c1 + c2,   # matches §9.2: 24 B header || ciphertext
    }


def _manifest_fields(v1res, account_pk):
    prefix = model.dest_prefix(account_pk)
    fields = {
        1: 1,                                   # v
        2: BUNDLE_ID,
        3: account_pk,
        4: _sha(b"BLACKOUT-RELAY-V3-content"),
        5: _sha(b"BLACKOUT-RELAY-V3-proxy"),
        6: _sha(b"BLACKOUT-RELAY-V3-thumb"),
        7: _sha(b"BLACKOUT-RELAY-V3-proxy-cipher"),
        8: _sha(b"BLACKOUT-RELAY-V3-thumb-cipher"),
        9: SEALED_KEY,
        10: KDF_INFO_LEN,
        11: CREATED_AT + 1,
        12: DURATION_MS,
        13: LAT_E7,
        14: LON_E7,
        15: GPS_ACCURACY_M,
        16: PUBLIC_GPS_PRECISION_M,
        17: MEDIA_KIND,
        18: ORIENTATION,
        19: prefix,
        20: _urls(prefix.hex(), 0),
        21: DEST_URLS_EXPIRE,
        22: MAX_HOPS,
        23: PRIORITY,
        24: EXPIRES_AT,
    }
    return fields


def v3(v1res):
    account_pk = v1res["ed25519_pk"]
    fields = _manifest_fields(v1res, account_pk)
    sig_input = model.manifest_sig_input(fields)
    return {"fields_1_24": fields, "sig_input": sig_input}


def v4(v1res, v3res):
    sig = sodium.ed25519_sign(v3res["sig_input"], v1res["ed25519_sk"])
    full = model.build_manifest(v3res["fields_1_24"], v1res["ed25519_sk"])
    return {"signature": sig, "manifest_cbor": cbor.encode(full)}


def v5(v1res):
    prefix = model.dest_prefix(v1res["ed25519_pk"])
    slots = [
        model.build_slot(0, _urls(prefix.hex(), 0), POOL_EXPIRES_AT),
        model.build_slot(1, _urls(prefix.hex(), 1), POOL_EXPIRES_AT),
    ]
    pool = model.build_pool(POOL_ID, prefix, MINTED_AT, POOL_EXPIRES_AT, slots)
    return {"pool": pool, "pool_cbor": cbor.encode(pool)}


def compute_all():
    v1res = v1()
    v2res = v2()
    v3res = v3(v1res)
    v4res = v4(v1res, v3res)
    v5res = v5(v1res)
    return {"v1": v1res, "v2": v2res, "v3": v3res, "v4": v4res, "v5": v5res}


# --- Presentation -----------------------------------------------------------

def _h(b: bytes) -> str:
    return b.hex()


def print_vectors():
    r = compute_all()
    print("=" * 72)
    print("Blackout Relay — M0 test vectors V1–V5")
    print("=" * 72)

    print("\n[V1] identity: fixed seed -> Ed25519/X25519 keypairs -> signed identity")
    print(f"  ed25519_seed     = {_h(ED25519_SEED)}")
    print(f"  x25519_seed      = {_h(X25519_SEED)}")
    print(f"  created_at       = {CREATED_AT}")
    print(f"  key_epoch        = {KEY_EPOCH}")
    print(f"  ed25519_pk       = {_h(r['v1']['ed25519_pk'])}")
    print(f"  x25519_pk        = {_h(r['v1']['x25519_pk'])}")
    print(f"  sig_input (cbor) = {_h(r['v1']['sig_input'])}")
    print(f"  signature        = {_h(r['v1']['signature'])}")
    print(f"  identity.cbor    = {_h(r['v1']['identity_cbor'])}")

    print("\n[V2] secretstream: fixed key + header -> exact ciphertext (incl. header)")
    print(f"  key              = {_h(r['v2']['key'])}")
    print(f"  header           = {_h(r['v2']['header'])}")
    print(f"  plaintext        = {_h(r['v2']['plaintext'])}")
    print(f"  chunk1 (0:{CHUNK1_LEN}, tag MESSAGE) = {_h(r['v2']['chunk1'])}")
    print(f"  chunk2 ({CHUNK1_LEN}:, tag FINAL)   = {_h(r['v2']['chunk2'])}")
    print(f"  cipher_chunk1    = {_h(r['v2']['cipher_chunk1'])}")
    print(f"  cipher_chunk2    = {_h(r['v2']['cipher_chunk2'])}")
    print(f"  ciphertext       = {_h(r['v2']['ciphertext'])}")
    print(f"  on_disk          = {_h(r['v2']['on_disk'])}")

    print("\n[V3] manifest fields 1..24 -> canonical CBOR")
    print(f"  sig_input (cbor) = {_h(r['v3']['sig_input'])}")

    print("\n[V4] V3 + account key -> 64-byte signature (and full manifest)")
    print(f"  signature        = {_h(r['v4']['signature'])}")
    print(f"  manifest.cbor    = {_h(r['v4']['manifest_cbor'])}")

    print("\n[V5] pool -> canonical CBOR")
    print(f"  pool.cbor        = {_h(r['v5']['pool_cbor'])}")

    print("\n" + "=" * 72)
    print("Done.")


if __name__ == "__main__":
    import sys
    if "--selftest" in sys.argv:
        from .selftest import run
        run()
    else:
        print_vectors()
