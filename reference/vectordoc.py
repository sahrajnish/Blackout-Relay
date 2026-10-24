"""
Emit VECTORS.md (the normative, human-readable test-vector document).

Run: `python3 -m reference.vectordoc > VECTORS.md`
"""

from __future__ import annotations

from . import vectors
from .frozen import FROZEN


def _h(b: bytes) -> str:
    return b.hex()


def emit() -> str:
    r = vectors.compute_all()
    L = []
    A = L.append

    A("# Blackout Relay — M0 test vectors V1–V5")
    A("")
    A("Normative. Generated once by `reference/vectors` at M0; frozen in "
      "`reference/frozen.py`. Any implementation (Android `core-crypto`/`core-model`, "
      "the Kotlin verifier, the Python simulator) is correct only if it reproduces "
      "these bytes exactly. Do not regenerate these values silently.")
    A("")
    A("All crypto is libsodium (SPEC.md §8). Canonical CBOR is RFC 8949 §4.2.1 "
      "deterministic encoding (SPEC.md §27.1).")
    A("")
    A("## General notes for implementers")
    A("")
    A("- **V2 (secretstream) determinism.** libsodium's `crypto_secretstream_"
      "xchacha20poly1305_init_push` generates its 24-byte header from the system RNG "
      "and offers no public \"init with a given header\". To reproduce V2, initialise "
      "the stream with `crypto_secretstream_xchacha20poly1305_init_pull(state, header, "
      "key)` — which derives the *identical* keystream state from (key, header) — and "
      "then call `..._push` to encrypt. `init_push` and `init_pull` differ only in that "
      "`init_push` randomises the header first, so a fixed header reproduces libsodium's "
      "own `push` output byte-for-byte.")
    A("- **V3 `sealed_key` is a fixed 80-byte field**, not a fresh seal. "
      "`crypto_box_seal` uses an ephemeral sender key and is therefore randomised; it "
      "cannot appear in a deterministic vector. The seal construction is exercised "
      "separately by a round-trip test (not part of V1–V5).")
    A("- **V3/V4 split.** V3 is the canonical CBOR of manifest fields 1–24 "
      "(the exact bytes the signature covers). V4 is `Ed25519(account_sk, V3)`. The "
      "full `manifest.cbor` is the 25-entry map whose field 25 is V4.")
    A("")
    A("---")
    A("")

    # V1
    A("## V1 — identity (fixed seed → keypairs → signed identity)")
    A("")
    A("```")
    A(f"ed25519_seed = {_h(vectors.ED25519_SEED)}")
    A(f"x25519_seed  = {_h(vectors.X25519_SEED)}")
    A(f"created_at   = {vectors.CREATED_AT}")
    A(f"key_epoch    = {vectors.KEY_EPOCH}")
    A("```")
    A("")
    A("Procedure: `crypto_sign_seed_keypair(ed25519_seed)`, "
      "`crypto_box_seed_keypair(x25519_seed)`; identity body = canonical CBOR of "
      "`{1: pk, 2: xpk, 3: created_at, 4: key_epoch}`; signature = Ed25519 over that "
      "body.")
    A("")
    A("```")
    A(f"ed25519_pk    = {FROZEN['v1_ed25519_pk']}")
    A(f"x25519_pk     = {FROZEN['v1_x25519_pk']}")
    A(f"sig_input     = {FROZEN['v1_sig_input']}")
    A(f"signature     = {FROZEN['v1_signature']}")
    A(f"identity.cbor = {FROZEN['v1_identity_cbor']}")
    A("```")
    A("")

    # V2
    A("## V2 — secretstream (fixed key + header → ciphertext)")
    A("")
    A("```")
    A(f"key       = {_h(r['v2']['key'])}")
    A(f"header    = {_h(r['v2']['header'])}")
    A(f"plaintext = {_h(r['v2']['plaintext'])}")
    A("chunks    = plaintext[0:40] with tag MESSAGE(0), plaintext[40:] with tag FINAL(3)")
    A("```")
    A("")
    A("```")
    A(f"cipher_chunk1 = {FROZEN['v2_cipher_chunk1']}")
    A(f"cipher_chunk2 = {FROZEN['v2_cipher_chunk2']}")
    A(f"on_disk       = {FROZEN['v2_on_disk']}")
    A("```")
    A("")
    A("`on_disk` = `header || cipher_chunk1 || cipher_chunk2`, matching the §9.2 "
      "layout (`24 B header || ciphertext`).")
    A("")

    # V3
    A("## V3 — manifest fields 1–24 → canonical CBOR")
    A("")
    A("```")
    A(f"sig_input = {FROZEN['v3_sig_input']}")
    A("```")
    A("")

    # V4
    A("## V4 — V3 + account key → signature (and full manifest)")
    A("")
    A("```")
    A(f"signature     = {FROZEN['v4_signature']}")
    A(f"manifest.cbor = {FROZEN['v4_manifest_cbor']}")
    A("```")
    A("")

    # V5
    A("## V5 — pool → canonical CBOR")
    A("")
    A("```")
    A(f"pool.cbor = {FROZEN['v5_pool_cbor']}")
    A("```")
    A("")

    return "\n".join(L)


if __name__ == "__main__":
    import sys
    sys.stdout.write(emit())
