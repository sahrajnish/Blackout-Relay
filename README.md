# Blackout Relay

An Android app that gets evidence (video, photo, signed metadata) out of an
internet-shutdown zone to storage the creator controls, via store-carry-forward
over Bluetooth LE and Wi-Fi — without operating any server and without
auto-publishing anything.

> **Research prototype. Not audited. Not for use where personal safety depends
> on it.** See `SPEC.md` §21.4.

The single source of truth is [`SPEC.md`](SPEC.md). Every implementation
decision defers to it; where it is silent, stop and ask. The build history is in
[`PROGRESS.md`](PROGRESS.md).

## Status

| Milestone | Deliverable | Status |
|---|---|---|
| **M0** | Spec + test vectors V1–V5 + reference script | ✅ done |
| **M1** | Python simulator | ✅ done |
| **M2** | Single-phone demo: record → seal → queue → upload → verify | ✅ **complete** (on device, against a mock S3 endpoint) |
| **M3** | Two-phone mesh | ⬜ |
| **M4** | Working prototype | ⬜ |
| **M5** | Field-testable alpha | ⬜ |
| **M6** | External audit | ⬜ |

## What works today

- **Test vectors** V1–V5 are frozen and pass in both the Python reference and
  the Kotlin `core-crypto`/`core-model` (`PASS 31, FAIL 0`; JUnit green).
- **Verifier** seals a bundle and verifies the full §16.2 chain (17 checks →
  `VALID`), and rejects tampering, wrong keys, expiry, and revocation.
- **Simulator** produces the M1 curves and reproduces the core findings (the
  empty-ring problem, the utility router's proxy advantage, the energy insight).
- **`poolctl`** mints/verifies/audits pre-signed pools; its SigV4 matches the
  published AWS vector.
- **Android app** works end-to-end on a physical device: identity → capture
  (photo/video/gallery) → seal → queue → decrypt/view → verify, and **upload**
  to an imported pre-signed pool (WorkManager). The reviewer CLI then verifies
  the uploaded bundle (`VALID_WITH_WARNINGS` — the original is export-only).

## Layout

```
SPEC.md             Ground-truth technical specification
VECTORS.md          Normative M0 test vectors V1–V5
PROGRESS.md         Detailed work log (updated per session)
docs/               Emulator screenshots

reference/          Python reference + frozen vectors + selftest (M0)
sim/                Mesh simulator + results/ (M1)
poolctl/            Pre-signed pool minting/verify/audit

core-crypto/        libsodium binding + crypto API (Kotlin/JVM)
core-model/         deterministic CBOR + model builders (Kotlin/JVM)
verifier/           reviewer CLI (Kotlin/JVM)
app/                Android app (Compose UI shell)
```

## Quick check

```sh
# Python reference vectors
python3 -m reference.vectors --selftest

# Simulator
python3 -m sim.run --trials 6

# poolctl
python3 -m poolctl.test_poolctl

# JVM modules (needs JDK 21 + Gradle; see PROGRESS.md for environment)
gradle :core-crypto:test :core-model:test :verifier:test

# Android APK (needs ANDROID_HOME)
gradle :app:assembleDebug
```
