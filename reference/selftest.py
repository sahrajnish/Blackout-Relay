"""
Selftest for M0: recompute V1–V5, check them byte-for-byte against the frozen
vectors, and run the functional/property checks from SPEC.md §23.1.

Run: `python3 -m reference.vectors --selftest`  (exit 0 = pass)
"""

from __future__ import annotations

import hashlib

from . import cbor, model, sodium, vectors
from .frozen import FROZEN

_PASS = 0
_FAIL = 0
_FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    global _PASS, _FAIL
    if ok:
        _PASS += 1
        print(f"  PASS  {name}")
    else:
        _FAIL += 1
        _FAILURES.append(name)
        print(f"  FAIL  {name}  {detail}")


def expect_eq(name: str, got: bytes, want_hex: str):
    check(name, got.hex() == want_hex, f"got {got.hex()[:24]}…")


def run():
    global _PASS, _FAIL
    r = vectors.compute_all()
    print("Blackout Relay — M0 selftest")
    print("=" * 60)

    # --- V1: identity ---
    print("\n[V1] identity")
    expect_eq("v1_ed25519_pk", r["v1"]["ed25519_pk"], FROZEN["v1_ed25519_pk"])
    expect_eq("v1_x25519_pk", r["v1"]["x25519_pk"], FROZEN["v1_x25519_pk"])
    expect_eq("v1_sig_input", r["v1"]["sig_input"], FROZEN["v1_sig_input"])
    expect_eq("v1_signature", r["v1"]["signature"], FROZEN["v1_signature"])
    expect_eq("v1_identity_cbor", r["v1"]["identity_cbor"], FROZEN["v1_identity_cbor"])
    check("v1 signature verifies under ed25519_pk",
          sodium.ed25519_verify(r["v1"]["signature"], r["v1"]["sig_input"],
                                r["v1"]["ed25519_pk"]))

    # --- V2: secretstream ---
    print("\n[V2] secretstream")
    expect_eq("v2_cipher_chunk1", r["v2"]["cipher_chunk1"], FROZEN["v2_cipher_chunk1"])
    expect_eq("v2_cipher_chunk2", r["v2"]["cipher_chunk2"], FROZEN["v2_cipher_chunk2"])
    expect_eq("v2_on_disk", r["v2"]["on_disk"], FROZEN["v2_on_disk"])

    # Round-trip decryption of the on-disk encoding.
    on_disk = r["v2"]["on_disk"]
    header, ciphertext = on_disk[:24], on_disk[24:]
    s = sodium.SecretStream(r["v2"]["key"], header)
    c1len = len(r["v2"]["chunk1"]) + sodium.crypto_secretstream_xchacha20poly1305_ABYTES
    p1, t1 = s.pull(ciphertext[:c1len])
    p2, t2 = s.pull(ciphertext[c1len:])
    check("v2 decrypt chunk1 matches, tag=MESSAGE",
          p1 == r["v2"]["chunk1"] and t1 == sodium.TAG_MESSAGE)
    check("v2 decrypt chunk2 matches, tag=FINAL",
          p2 == r["v2"]["chunk2"] and t2 == sodium.TAG_FINAL)

    # Tamper detection.
    bad = bytearray(on_disk)
    bad[40] ^= 0x01
    s2 = sodium.SecretStream(r["v2"]["key"], header)
    raised = False
    try:
        s2.pull(bytes(bad[24:24 + c1len]))
    except ValueError:
        raised = True
    check("v2 tampered ciphertext is rejected", raised)

    # --- V3/V4: manifest ---
    print("\n[V3/V4] manifest")
    expect_eq("v3_sig_input", r["v3"]["sig_input"], FROZEN["v3_sig_input"])
    expect_eq("v4_signature", r["v4"]["signature"], FROZEN["v4_signature"])
    expect_eq("v4_manifest_cbor", r["v4"]["manifest_cbor"], FROZEN["v4_manifest_cbor"])
    check("v4 signature verifies over v3 sig_input",
          sodium.ed25519_verify(r["v4"]["signature"], r["v3"]["sig_input"],
                                r["v1"]["ed25519_pk"]))

    # Full manifest decodes, and field 25 == detached sig over fields 1..24.
    m = cbor.decode(r["v4"]["manifest_cbor"])
    check("manifest decodes to 25 fields", len(m) == 25 and set(m) == set(range(1, 26)))
    if len(m) == 25:
        body = {k: v for k, v in m.items() if k != 25}
        check("manifest.sig == signature over fields 1..24",
              m[25] == r["v4"]["signature"] and cbor.encode(body) == r["v3"]["sig_input"])

    # --- V5: pool ---
    print("\n[V5] pool")
    expect_eq("v5_pool_cbor", r["v5"]["pool_cbor"], FROZEN["v5_pool_cbor"])
    pool = cbor.decode(r["v5"]["pool_cbor"])
    check("pool decodes: 2 slots, slot_index 0/1",
          pool[5][0][1] == 0 and pool[5][1][1] == 1)

    # --- crypto_box_seal round-trip (randomised; not a vector) ---
    print("\n[crypto] box_seal round-trip")
    sealed = sodium.box_seal(b"blackout relay seal test", r["v1"]["x25519_pk"])
    check("sealed length = 48 + len(message)",
          len(sealed) == 48 + len(b"blackout relay seal test"))
    opened = sodium.box_seal_open(sealed, r["v1"]["x25519_pk"], r["v1"]["x25519_sk"])
    check("box_seal_open recovers plaintext", opened == b"blackout relay seal test")

    # --- Destination binding (SPEC.md §7.4, §21.1 rule 1) ---
    print("\n[model] destination binding")
    pk = r["v1"]["ed25519_pk"]
    prefix = model.dest_prefix(pk)
    check("dest_prefix == SHA256(account_pk)[0:16]",
          prefix == hashlib.sha256(pk).digest()[:16])
    check("manifest field 19 == dest_prefix", m[19] == prefix)
    urls = m[20]
    ok_urls = all(prefix.hex() in u for u in (urls[1], urls[2], urls[3]))
    check("all dest_urls contain dest_prefix", ok_urls)

    # --- Canonical CBOR determinism and rejection ---
    print("\n[cbor] determinism and rejection")
    check("decode(re-encode) is identity", cbor.encode(cbor.decode(r["v4"]["manifest_cbor"]))
          == r["v4"]["manifest_cbor"])
    for name, bad_bytes in [
        ("reject non-shortest uint", b"\x18\x05"),
        ("reject unsorted map keys", b"\xa2\x03\x04\x01\x02"),
        ("reject trailing bytes", b"\x01\x00"),
        ("reject indefinite length", b"\x5f\x41\x00\xff"),
        ("reject float", b"\xfb\x3f\xf0\x00\x00\x00\x00\x00\x00"),
    ]:
        raised = False
        try:
            cbor.decode(bad_bytes)
        except cbor.CBORError:
            raised = True
        check(name, raised)

    # --- Summary ---
    print("\n" + "=" * 60)
    print(f"PASS {_PASS}  FAIL {_FAIL}")
    if _FAILURES:
        print("Failures:", ", ".join(_FAILURES))
        raise SystemExit(1)
    print("OK: all M0 vectors and checks pass.")
