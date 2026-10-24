# Blackout Relay — Work Log

**Last updated:** 2026-10-25 (session 2)
**Status:** M0 ✅ · M1 ✅ · M2 ✅ (capture → seal → queue → **upload** → verify, on device) · M3 next

This file records what has actually been built, why, what broke, and what
remains. It is updated at the end of each work session. `SPEC.md` remains the
ground truth; this file is the build history.

---

## Executive summary

Blackout Relay is an Android store-carry-forward courier for evidence captured
inside an internet shutdown. The spec locks the design; this log records the
implementation against it.

Built and validated this session:

- **M0 — normative test vectors V1–V5** (Python reference + frozen bytes, 31
  selftest checks).
- **M1 — mesh simulator** (escape/latency for 3 routers × 4 adoption rates, plus
  the "ring" and relay-placement sensitivities).
- **M2 (JVM half) — `core-crypto`, `core-model`, `verifier` CLI**, all passing
  the frozen vectors and a full seal→verify round trip.
- **M2 `poolctl`** — SigV4 presigned-pool minting/verify/audit (7 tests, matches
  the AWS signature vector).
- **M2 Android app — functional** — on-device identity, capture (camera or test),
  real seal (lazysodium), encrypted queue, decrypt-and-view thumbnail, and a
  local verification chain that reports **VALID**. Runs on a physical OnePlus
  (Android 16) and the API-37 emulator.

M2 is now **complete end-to-end**: the app imports a pre-signed drop pool,
consumes one slot per capture, uploads ciphertext to the pool's PUT URLs
(WorkManager + retry/circuit-breaker), and the reviewer CLI verifies the
downloaded bundle. The next milestone is M3 (two-phone BLE/Wi-Fi mesh); `.ohb`
export remains a follow-up.

---

## Milestone status

| Milestone | Deliverable | Status |
|---|---|---|
| **M0** | Spec + test vectors V1–V5 + reference script | ✅ done |
| **M1** | Python simulator | ✅ done |
| **M2** | Single-phone demo: record → seal → queue → upload → verify | ✅ **complete** (proved on a physical device against a mock S3 endpoint) |
| **M3** | Two-phone mesh | ⬜ |
| **M4** | Working prototype | ⬜ |
| **M5** | Field-testable alpha | ⬜ |
| **M6** | External audit | ⬜ |

---

## M0 — Test vectors and reference implementation

**Location:** `reference/`, `VECTORS.md`

Dependency-free Python reference producing the five normative vectors (SPEC
§23.5), plus a 31-check selftest.

| File | Purpose |
|---|---|
| `reference/cbor.py` | Deterministic CBOR (RFC 8949 §4.2.1): encode + strict decode |
| `reference/sodium.py` | `ctypes` binding to libsodium |
| `reference/model.py` | CDDL-faithful builders (identity, manifest, pool, proof frame, envelope) |
| `reference/vectors.py` | Fixed inputs + V1–V5 generators |
| `reference/frozen.py` | Frozen V1–V5 (normative) |
| `reference/selftest.py` | Byte-exact vectors + round-trips + tamper + CBOR rejection |
| `reference/vectordoc.py` | Emits `VECTORS.md` |

**Vectors:** V1 identity (Ed25519/X25519 from seed), V2 secretstream, V3
manifest sig-input CBOR, V4 manifest signature + full manifest, V5 pool CBOR.

**Key decisions:**
1. V3 `sealed_key` is a fixed 80-byte field — `crypto_box_seal` is randomised
   (ephemeral sender key), so it cannot appear in a byte-exact vector; it is
   tested by round-trip instead.
2. V2 uses a fixed header injected through `init_pull` (identical keystream
   state to `init_push`, which randomises the header). Documented in `VECTORS.md`.
3. `crypto_box_SEALBYTES = 48`, not 16 — the under-allocation corrupted the heap
   and caused intermittent SIGSEGVs until fixed.

**Validation:** `python3 -m reference.vectors --selftest` → `PASS 31, FAIL 0`,
deterministic across runs.

---

## M1 — Mesh simulator

**Location:** `sim/`, `results/`

Dependency-free Python simulator. 5 km zone, 750 m crowd disk, empty ring,
protester random walk + courier leavers, BLE 100 m contacts, artefact budgets
(manifest 32 / proxy 4), three routers.

**Escape rate (manifest / proxy), trials = 6:**

| Router | 5% | 10% | 20% | 40% |
|---|---|---|---|---|
| epidemic | 47 / 47 | 42 / 42 | 68 / 68 | 85 / 85 |
| spray_wait | 47 / 43 | 40 / 30 | 56 / 28 | 55 / **8** |
| spray_wait_utility | 47 / 46 | 40 / 35 | 61 / 48 | 63 / **36** |

**Findings:**
- Plain spray-and-wait collapses for the bulky proxy (8% at 40% adoption) —
  copies get stuck on random protesters and "wait"; the utility router fixes it
  (36–48%, rising to 95% at 5% couriers).
- Energy ≈ 144 mAh/24 h for every router: **scanning dominates, bytes are
  nearly free**; duty cycling is the lever.
- **Zero couriers → 0% escape for every router** (the ring problem).
- Kettle relays: 0% with none; 99.6%/70.8% (manifest/proxy) with 4 cordon
  relays; 100%/93.3% with 8; **0%** for mid-ring or boundary relays.

**Bugs found/fixed:** utility used 1-tick displacement (noise) → 2-min window +
100 m "unknown" threshold; fake directional motion from a soft boundary →
reflecting boundary; forced courier at 0% → removed; global RNG → seeded RNG.

---

## M2 — JVM core + verifier (done)

**Location:** `core-crypto/`, `core-model/`, `verifier/`

- **`core-crypto`** — JNA binding to system `libsodium.so.23`; Ed25519/X25519,
  `crypto_box_seal`, SHA-256, `SecretStream` (deterministic `forHeader` for
  vectors + production `initPush`). Tests: V1/V2/V4 + box_seal round-trip.
- **`core-model`** — deterministic CBOR codec + model builders. Tests: V3/V4/V5,
  round-trip, non-deterministic rejection.
- **`verifier`** — full §16.2 chain (17 checks), §16.3 verdict JSON, identity
  loading, seal demo, and CLI (`verify`/`inspect`/`thumb`/`selftest`). Tests:
  VALID end-to-end; tampered proxy/signature, wrong key, expiry, revocation.

```
$ gradle :verifier:run --args="selftest"
verdict: VALID   (17/17 checks ok)
selftest OK: sealed a bundle and verified the full chain.
```

---

## M2 — `poolctl` (done)

**Location:** `poolctl/`

Pre-signed drop-pool tooling (§14.1, §14.4), pure Python.

- `sigv4.py` — AWS SigV4 query-string presigning, **validated against the
  published AWS vector** (`aeeed9bb…f604d404`).
- `mint.py` — mint/verify/audit pools; structural checks (16-byte prefix, URL
  prefix binding, duplicate slots, signature length); health (slots left,
  expiry).
- CLI: `mint`, `verify`, `audit`; offline placeholder mode and online SigV4 mode.
- **7 unittest tests pass.**

---

## M2 — Android app (functional: capture → seal → queue → view → verify)

**Location:** `app/`, screenshots in `docs/`

A working Android app: it creates an on-device identity, captures a photo (camera
or a synthetic test capture), **seals** it with real crypto, stores only
ciphertext in a queue, decrypts the thumbnail on demand, and runs the full
verification chain locally — all proven on a physical device.

- **Crypto backend:** `AndroidBackend` over `lazysodium-android` (SPEC §8). The
  shared `core-model`/`core-crypto` code is reused via a `SodiumBackend`
  abstraction (`JnaBackend` on JVM, `AndroidBackend` on device).
- **Capture sources:** system camera (photo), system camera (video, with a frame
  thumbnail extracted via `MediaMetadataRetriever`), and a **gallery picker**
  (`PickVisualMedia`, images + videos). Photo proxy is a 480p downscale; video
  proxy is the original for now (480p transcode is a TODO).
- **Flow:** IdentityStore (persisted) → `Sealer` (thumbnail + 480p proxy +
  encrypted original, sealed key, signed manifest, `state.json`) → `QueueStore`
  (list, decrypt-thumb, verify, delete).
- **On-device proof (physical OnePlus, Android 16):**
  - Queue shows sealed bundles (`state: SEALED (export only)`, 663 B manifest).
  - **View** decrypts the thumbnail → the actual captured photo.
  - **Verify** → **VALID** with 9/9 checks: manifest signature, dest_prefix
    binding, sealed-key decrypt, thumb/proxy/original plaintext hashes, and
    thumb/proxy ciphertext hashes.
  - A real 4.4 MB camera photo sealed successfully.
- **Build:** AGP 8.7.3, compileSdk 34, minSdk 26, build-tools 34.0.0, Kotlin
  2.0.21 + Compose. APK ~12 MB (arm64-v8a libsodium + JNA).
- **Fixed:** JNA's Android dispatch library ships only in the **AAR** variant
  (`jni/<abi>/libjnidispatch.so`), not the jar — the app depends on
  `jna:5.12.1@aar`, with JNA excluded from the jar-based transitive deps.
- **Honesty:** states are real (`SEALED`, `export only`); no delivery checkmarks.

Screenshots: `docs/app-queue-sealed.png`, `docs/app-verify-valid.png`,
`docs/app-decrypted-thumb.png`, `docs/device-home.png`, `docs/app-cannot-do.png`.

> Not yet wired: `.ohb` export (the manual fallback). Upload is live.

---

## M2 — upload, pool & verification (complete)

**Location:** `app/` (`PoolStore`, `Uploader`, `UploadWorker`), `tools/mock_s3.py`

The "leave the phone" half of M2, proved end-to-end on the physical device:

- **Pool import + health** — `PoolStore` reads a `poolctl`-minted pool (CBOR or
  hex), validates that its `dest_prefix` matches this device's identity, and
  shows slots-left/expiry. One slot is consumed per capture; if none is
  available the bundle is `EXPORT_ONLY`.
- **Manifest carries the real capability** — the chosen slot's three URLs and
  expiry go into the signed manifest (so the manifest signature covers them).
- **Upload** — `Uploader` PUTs `manifest.cbor`, `thumb.enc`, `proxy.enc` to the
  pre-signed URLs (`If-None-Match: *`, 30 s/120 s timeouts). 200/201/204/412/409
  = success; 403 = expired (no retry); 5xx/IO = retry with backoff. State →
  `UPLOADED`. The original is **not** uploaded (export-only in v1).
- **Silent auto-upload** — `UploadWorker` (WorkManager, `NetworkType.CONNECTED`,
  exponential backoff) is enqueued on seal and on app launch; a **Sync now**
  button forces it for testing.
- **Identity document (§7.3)** — the app now creates and stores a signed identity
  document (`identity.json`) binding Ed25519 ↔ X25519, and **Export identity**
  shares it with the reviewer.

**End-to-end proof (mock S3 over LAN):**

```
app  → seal + upload  → mock server received manifest.cbor (676 B),
                        thumb.enc (2685 B), proxy.enc (8836 B)   [all HTTP 200]
app  → state: UPLOADED
CLI  → relay-verify on the downloaded bundle → VALID_WITH_WARNINGS
       (15/15 crypto checks pass; only "original not supplied", which is
        correct because the original is export-only in v1)
```

**Bugs found and fixed in this phase:**
- `Cbor.decode` yields **Long** map keys; `PoolStore` used **Int** keys, so the
  pool read as 0 slots. Fixed to `1L..5L`.
- The app had **no `INTERNET` permission**, so `socket()` failed with `EPERM`.
  Added `INTERNET` + `ACCESS_NETWORK_STATE`.
- Cleartext to the test host was blocked; added a scoped
  `network-security-config` (localhost + test IP only).

---

## Environment setup notes

- **JDK 21** (Temurin) → `/tmp/opencode/tools/jdk-21.0.13+9`
- **Gradle 8.10.2** → `/tmp/opencode/tools/gradle-8.10.2`
- **libsodium** system-wide (`libsodium.so.23`), bound via JNA.
- **Android SDK** → `/home/rajnish/Android/Sdk` (platforms 34 & 37.0,
  build-tools 34/36, platform-tools, emulator, AVD `Medium_Phone_API_37.0`).
- **Network caveat:** Java TLS to IPv6-fronted hosts (Maven Central, Google
  Maven `dl.google.com`) is flaky, while curl/OpenSSL works. Mitigations:
  `preferIPv4Stack=true`, retries, and the **Huawei mirror**
  (`https://repo.huaweicloud.com/repository/maven/`) which is reachable from Java.
  `android.useAndroidX=true` is required for the Android module.

Build commands:

```sh
export JAVA_HOME=/tmp/opencode/tools/jdk-21.0.13+9
export PATH=$JAVA_HOME/bin:/tmp/opencode/tools/gradle-8.10.2/bin:$PATH
export ANDROID_HOME=/home/rajnish/Android/Sdk

# JVM modules
gradle :core-crypto:test :core-model:test :verifier:test
# Android APK
gradle :app:assembleDebug
# Python
python3 -m reference.vectors --selftest
python3 -m sim.run --trials 6
python3 -m poolctl.test_poolctl
```

> Note: `gradle test` at the root trips over the root/app task; run the module
> tasks explicitly.

---

## Decisions and spec gaps (for review)

| # | Gap / decision | Chosen for now |
|---|---|---|
| G1 | `bundle_id = SHA-256(cipher_hash \|\| created_at \|\| nonce)` — `created_at` width/endianness unspecified | deferred (not in V1–V5) |
| G2 | Secretstream chunk size / multi-chunk framing unspecified | single-chunk (FINAL) per artefact in the demo |
| G3 | `original.enc` vs §10 "delete plaintext O" | demo stores an encrypted original; verifies `content_hash` after decrypt |
| G4 | `.ohb` envelope schema (§12.6) underspecified | verifier supports the §9.2 **directory** format; `.ohb` deferred |
| G5 | Identity file schema (`global.json`) undefined | JSON with public doc + private keys (reviewer-held, local) |
| G6 | Android crypto backend vs JVM | **resolved**: `SodiumBackend` interface with `JnaBackend` (JVM) and `AndroidBackend` (lazysodium-android) |
| G7 | App's shipped name | "Pocket Notes" placeholder pending §28 naming decision |
| G8 | Video proxy (§9.1 wants a 480p transcode) | uses the original video for now; transcode is a follow-up |

---

## File inventory

```
SPEC.md                 Ground-truth specification
VECTORS.md              Normative M0 vectors (generated)
README.md               Project overview + status
PROGRESS.md             This work log
docs/                   Device/emulator screenshots

reference/              Python reference + frozen vectors + selftest
sim/                    M1 mesh simulator + results/
poolctl/                Pool minting/verify/audit + SigV4
tools/                  mock_s3.py (dev upload endpoint)

settings.gradle.kts     Gradle multi-module root (core-crypto, core-model, verifier, app)
core-crypto/            libsodium binding + crypto API (Kotlin/JVM)
core-model/             deterministic CBOR + model builders (Kotlin/JVM)
verifier/               reviewer CLI (Kotlin/JVM)
app/                    Android app: identity, capture, seal, queue, upload, verify (Compose)
```

---

## Next steps (in order)

1. **Export** — write the `.ohb` bundle + `receipt.txt` via SAF (§12.6), the
   manual fallback that works with no network at all.
2. **Capture upgrade** — CameraX video + in-app recording (currently system
   camera), 480p **video transcode** for the proxy (currently the original),
   §10 consent screen, EXIF strip.
3. **Hardening** — panic wipe, `FLAG_SECURE`, Android Keystore key wrapping,
   OEM battery guide, foreground service for relay + upload.
4. **Then M3** — two-phone BLE/Wi-Fi mesh (transport-ble / transport-wifi).
