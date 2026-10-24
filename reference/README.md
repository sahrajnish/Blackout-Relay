# reference — M0 test vectors and reference implementation

Normative Python reference for Blackout Relay's wire formats and crypto
constructions (SPEC.md §8, §9, §14.1). It produces the frozen vectors V1–V5
(SPEC.md §23.5) that every other implementation must match byte-for-byte.

This is **not** the shipping implementation — that is Kotlin/JVM
(`core-crypto` + `core-model`). This package is the ground truth those modules
are validated against, and the seed for the M1 simulator.

## Requirements

- Python 3.10+
- system libsodium (`libsodium.so`), linked via `ctypes`. No pip packages.

## Run

```sh
# regenerate + print the vectors
python3 -m reference.vectors

# run the selftest (31 checks: frozen-vector comparison + round-trips +
# tamper detection + canonical-CBOR rejection)
python3 -m reference.vectors --selftest

# regenerate VECTORS.md (the human-readable normative document)
python3 -m reference.vectordoc > VECTORS.md
```

## Layout

| File | Purpose |
|---|---|
| `cbor.py` | Deterministic CBOR (RFC 8949 §4.2.1) encode + strict decode |
| `sodium.py` | `ctypes` wrapper over libsodium (Ed25519, X25519, `crypto_box_seal`, `crypto_secretstream_xchacha20poly1305`) |
| `model.py` | CDDL-faithful builders: identity, manifest, pool, ProofFrame, envelope |
| `vectors.py` | Fixed inputs + V1–V5 generators + printer |
| `frozen.py` | The frozen V1–V5 outputs (normative, do not regenerate silently) |
| `selftest.py` | Byte-for-byte check + functional/property tests |
| `vectordoc.py` | Emits `VECTORS.md` |

## Crypto construction map (SPEC.md §8)

| Purpose | libsodium symbol |
|---|---|
| Signing | `crypto_sign_detached` (Ed25519) |
| Keypairs from seed | `crypto_sign_seed_keypair`, `crypto_box_seed_keypair` |
| Anonymous encryption of the file key | `crypto_box_seal` / `crypto_box_seal_open` |
| File body encryption | `crypto_secretstream_xchacha20poly1305_*` |
| Hashing | SHA-256 (`hashlib.sha256`) |

Note for implementers: `crypto_box_SEALBYTES = 48` (ephemeral pubkey 32 + MAC
16), not 16. And the V2 secretstream header is fixed via `init_pull` (see
`VECTORS.md` and the `SecretStream` docstring) so the vector is deterministic.
