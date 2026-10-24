# Blackout Relay — Technical Specification v1.0

**Status:** Draft for review. Not audited. Not for use where personal safety depends on it.
**Date:** 2026-10-24
**Scope:** Android client + verification tooling. No servers operated by the project.
**Purpose of this document:** ground truth for agentic development. Every implementation session gets this file and must not contradict it. Where the spec is silent, stop and ask; do not invent.

---

## 0. One-paragraph summary

Blackout Relay is an Android app that lets a person inside an internet shutdown get evidence (video, photo, signed metadata) out of the blackout zone to storage they control, without operating any server and without publishing anything automatically. The phone seals a bundle (original, proxy, thumbnail, signed manifest) at capture time, attaches a pre-minted, single-use, expiring upload capability, and hands copies to nearby phones over Bluetooth LE and Wi-Fi. The bundle physically leaves the zone on a phone (or a fixed relay), uploads itself silently the moment any network appears, and then waits. A human the creator designated downloads it, verifies it, and decides whether to publish. The app is a courier and a notary, not a publisher.

---

## 1. Problem statement

During protests and disasters, authorities suspend mobile data and broadband across a geographic area (typically a radius of a few kilometres). Voices inside the zone can still talk to each other locally, but nothing reaches the outside world in time. The result is that documented abuse arrives late or never, and the people who filmed it are the people most exposed.

Three separable problems:

| # | Problem | Difficulty | Addressed in v1? |
|---|---|---|---|
| A | Local communication inside the zone | Medium (solved by others) | Partially — bundle relay only, no chat |
| B | Proving a recording is real and unedited | Medium | **Yes — this is the core value** |
| C | Getting data out of the zone | Hard (physics + logistics) | **Yes — store-carry-forward** |

Most projects in this space build A, ignore B, and hand-wave C. This project inverts that: **C is the product, B is the differentiator, A is borrowed.**

---

## 2. What this spec locks in (and what changed from the design discussion)

Read this table first — it is the review checklist.

| # | Decision | Rationale |
|---|---|---|
| D1 | **Manual publication only.** The app's last act is delivering a bundle to the creator's own storage. | Publishing is irreversible; storage is reversible. Cutting at that boundary removes the project's legal role, KYC walls, moderation duty, and cost. |
| D2 | **No servers operated by the project.** Three pluggable drop endpoints (see §14). | Removes cost, liability, and a single point of legal compulsion. |
| D3 | **The app never carries arbitrary files.** Typed, validated records only. | Structurally prevents the "virus over the mesh" class of attack. |
| D4 | **Payload is encrypted to the creator.** Carriers hold ciphertext only. | Kills the "blind mule knowingly carried it" problem and protects the carrier from content exposure. |
| D5 | **Destination is cryptographically bound to the creator's account key.** | Prevents a malicious creator from framing an innocent person by writing their bucket into a manifest. |
| D6 | **Pseudonymous account key, rotating radio-layer identifiers.** Two separate layers, deliberately. | Content needs a stable, revocable identity; the radio link must not build a social graph. |
| D7 | **Android only.** | iOS cannot do background BLE advertising or Wi-Fi Direct. India is ~95% Android. |
| D8 | **v1 transports manifest + thumbnail + proxy. Original is export-only.** | A 63 MB upload through pre-signed URLs is a v2 problem. Proxy is what the public actually watches. |
| D9 | **LoRa is out of scope for v1.** | It is a placement/hardware problem, and it only helps if a gateway operator exists. Design the seam, ship later. |
| D10 | **Dead-man's switch is out of scope for v1.** | Its false-positive mode (phone dies → video publishes without consent) is a safety failure. Revisit with two-stage confirm in v2. |
| D11 | **Manifest carries a full upload capability (all four object URLs), ~2 KB.** | Removes the need for a resolver service and the 16-byte-pointer-over-a-network design, which required infrastructure we do not have. |
| D12 | **Carrier integrity check is on ciphertext; provenance check is on plaintext.** | Resolves the contradiction between "carriers can't read it" and "carriers must verify it". |
| D13 | **Two-tier relay policy:** ambient for manifest/thumb, contact-only for proxy/original. | Spread the cheap thing widely, restrict the bulky thing. Reduces abuse surface without hurting reach. |
| D14 | **All crypto from a reviewed library. No invented constructions.** | Unreviewed crypto in a tool used by at-risk people is how people get arrested. |

### Changes from the earlier discussion, flagged for your review

1. **M_tiny is split into two things.** A 400-byte "manifest" that also carries 2 KB of upload URLs cannot exist. Spec now has a **`Manifest`** (~2 KB, travels over BLE/Wi-Fi with the bundle) and a separate **`ProofFrame`** (~170 B, the LoRa-shaped object, out of scope in v1 but specified so the seam is real). The proof frame carries *no* upload capability — it is a "this exists, here is its fingerprint" announcement for a gateway operator, and it is deliberately not a delivery mechanism.
2. **`dest_id` (16 bytes) + resolver is gone.** It required a lookup service. Replaced by the manifest carrying all four pre-signed URLs. Cost: manifest grows to ~2 KB. That is 70 ms over BLE, which is free.
3. **Original video (O) is not uploaded in v1.** Export via USB/SAF with a signed receipt instead. This removes multipart-upload complexity and 63 MB of bandwidth from strangers' phones.
4. **Duress/panic wipe is in v1, minimal form.** Cheap, and it is the highest-value safety feature per line of code.
5. **Face blurring is out of scope.** No FOSS, F-Droid-compatible, reliable on-device detector that justifies the complexity. Documented as a known gap instead of half-built.

---

## 3. Non-goals (explicit, to prevent scope creep)

- Not a messenger. No chat, no calls, no group feed, no "friends".
- Not a social network. No discovery, no profiles, no follows.
- Not a livestream. Physics forbids it; see §12.4.
- Not a VPN, proxy, or Tor bridge.
- Not a jamming, spoofing, or interception tool. Ever.
- Not a publishing service. No front door, no feed, no ledger operated by us.
- Not a replacement for Briar/Meshtastic/Signal. It deliberately reinvents only the evidence-delivery slice.
- Not anonymous against a state that physically holds your unlocked phone. Pseudonymous, not magic.
- Not safe. Unaudited. Say so everywhere.

---

## 4. Design principles

1. **Store-carry-forward, never "send".** Nothing is transmitted with an expectation of delivery. Everything is queued, replicated, and eventually carried.
2. **Small things flood; big things ride.** Metadata spreads to dozens of phones. Video moves on 2–5 deliberate paths.
3. **Every irreversible action sits behind a human.** The app's final action is a reversible write to the creator's own bucket.
4. **Carriers are blind and innocent.** They hold ciphertext, cannot read it, cannot redirect it, and cannot be accused of knowing it.
5. **Degrade gracefully.** Useful with one phone and no network. Better with a crowd. Best with a relay and couriers. Never *requires* the ideal case.
6. **Never lie to the user about delivery.** "Queued" ≠ "delivered" ≠ "published". The UI must never show a checkmark it cannot prove.
7. **Battery is a security property.** An app that dies at hour six of a 48-hour shutdown is a liability.
8. **Boring beats clever.** If a mechanism needs a server, a registry, or a coordinator, it is probably wrong for this project.

---

## 5. System overview

```
 ┌──────────────────────────── INSIDE THE BLACKOUT ────────────────────────────┐
 │                                                                             │
 │  [Capture]  CameraX → O (original)                                          │
 │      │                → X (480p proxy)                                      │
 │      │                → M_thumb (JPEG)                                      │
 │      ▼                                                                      │
 │  [Seal]  hash → encrypt to creator → sign manifest → bind destination       │
 │      │                                                                      │
 │      ▼                                                                      │
 │  [Queue]  SQLite + encrypted file store, per-copy state machine             │
 │      │                                                                      │
 │      ├── BLE (manifest, thumb)  ──►  nearby phones, sprayed widely          │
 │      ├── Wi-Fi Aware/Direct (proxy) ──►  2–5 outward-moving carriers        │
 │      ├── ProofFrame over LoRa  ──►  out of scope v1, seam defined           │
 │      └── USB/SAF export (original) ──►  signed .ohb bundle + paper receipt  │
 │                                                                             │
 └──────────────────────────────────┬──────────────────────────────────────────┘
                                    │  a phone physically crosses the line
                                    ▼
 ┌──────────────────────── OUTSIDE THE BLACKOUT ───────────────────────────────┐
 │  Any network appears (4G / Wi-Fi / hotspot)                                 │
 │      │                                                                      │
 │      ▼                                                                      │
 │  [Auto-upload]  silent PUT of ciphertext → creator's private R2 bucket      │
 │      │           (pre-signed, single-use, expiring URL from the manifest)   │
 │      ▼                                                                      │
 │  [Wait]  nothing else happens. No notification. No publication.             │
 │      │                                                                      │
 │      ▼                                                                      │
 │  [Reviewer]  a human the creator designated, holding the key,               │
 │              downloads → verifier CLI → decides → publishes manually        │
 └─────────────────────────────────────────────────────────────────────────────┘
```

**Honest geometry:** a 5 km blackout radius is 78 km². A protest crowd occupies roughly 1–1.5 km across. The remaining 3.5 km is empty. Bluetooth at ~100 m per hop cannot cross 3.5 km of nobody. Therefore the swarm solves the crowd; **a person or a fixed relay must solve the ring.** v1 depends on people. That is a limitation, not a bug.

---

## 6. Glossary (for the implementer and for the reviewer)

| Term | Meaning |
|---|---|
| **Bundle** | One capture event: manifest + thumbnail + proxy + optional original, plus a drop capability. |
| **Manifest** | Signed CBOR record describing a bundle and containing its upload capability. |
| **ProofFrame** | ~170-byte signed announcement of a bundle's existence, shaped for LoRa. Not a delivery path. |
| **Carrier** | Any phone that holds and forwards a copy. Blind by design. |
| **Drop endpoint** | Where ciphertext goes. Pluggable: pre-signed pool, org API, or local export. |
| **Sealed box** | `crypto_box_seal` — anonymous-sender public-key encryption. Recipient can decrypt; nobody else can. |
| **Bloom filter** | Compact probabilistic set summary used to exchange "what I already have". |
| **Spray-and-wait** | Routing where a node hands out a bounded number of copies, halving each time. |
| **TTL / hop budget** | Maximum number of forwards before a copy stops circulating. |
| **Utility** | A score estimating how likely a neighbour is to reach the boundary. |
| **Kettle** | Police cordon enclosing a crowd so nobody can leave. The scenario where LoRa is the only path. |

---

## 7. Identities and keys

### 7.1 Two layers, never mixed

| Layer | Identifier | Lifetime | Visible to | Purpose |
|---|---|---|---|---|
| **Radio/transport** | `transport_id`, 8 random bytes | Rotates every 10–15 min | Nearby radios only | Discovery and handshake, with no linkability across time |
| **Content/account** | `account_pk`, Ed25519 public key, 32 B | Persistent (per install or per pseudonym) | Everyone who handles a bundle | Signing, revocation, destination binding |

**Why both:** the radio layer must not let a seized phone reconstruct who met whom. The content layer must be stable enough that a repeat abuser can be cut off and a creator's destination can be verified offline. Conflating them is the classic mistake; this spec separates them explicitly.

### 7.2 Key material

| Key | Algorithm | Size | Storage |
|---|---|---|---|
| `account_sk` / `account_pk` | Ed25519 | 32 / 32 B | Private key encrypted with an AES-256-GCM key held in Android Keystore (StrongBox if available) |
| `account_x25519_sk` / `pk` | X25519 | 32 / 32 B | Same file as account key; bound by a signature |
| Per-bundle file key `K` | Random 32 B | 32 B | Never stored in plaintext outside the sealed blob |
| Keystore wrapper | AES-256-GCM | — | Android Keystore, non-exportable, user-auth not required (background operation) |

### 7.3 Identity document

```cddl
identity = {
  1: bytes .size 32,   ; ed25519_pk
  2: bytes .size 32,   ; x25519_pk
  3: uint,             ; created_at (unix seconds)
  4: uint,             ; key_epoch (monotonic; bumps on rotation)
  5: bytes .size 64,   ; sig_ed25519 over canonical CBOR of fields 1..4
}
```

The signature over the X25519 key is what binds encryption identity to signing identity. A verifier must reject a bundle whose encryption key is not covered by a valid identity document.

### 7.4 Identifier derivations (normative)

```
account_id      = SHA-256(account_pk)[0:16]              ; 16 bytes
dest_prefix     = account_id                             ; 16 bytes, ASCII hex in object keys
transport_id    = random(8)                              ; rotates, never persisted long-term
bundle_id       = SHA-256(cipher_hash || created_at || nonce)[0:16]
```

`dest_prefix` appears in every object key, which is what makes destination binding a string comparison rather than a registry lookup.

### 7.5 Key rotation and revocation

- **Rotation:** user-initiated; bumps `key_epoch`, keeps the old identity to verify old bundles. New bundles use the new key. Rotation also rotates `dest_prefix`, so the user must supply a new drop pool.
- **Revocation:** a published (opt-in, org-hosted) revocation list of `account_id`s. The app checks it opportunistically; the verifier checks it before approving. A revoked key's bundles are refused by default.
- **Revocation is best-effort.** It cannot reach phones that are offline. Document that.

### 7.6 Duress behaviour (v1, minimal)

- A secondary PIN (set during onboarding) triggers **panic wipe**: delete identity, delete queue, delete pool, clear DB, overwrite free space as far as Android allows, then show an empty app.
- No decoy volume in v1. Deniable storage is a v2 item; a half-built decoy is worse than none.
- Panic wipe is also reachable from a long-press on the launcher icon's app shortcut (Android shortcut API), so it does not require opening the app.

---

## 8. Cryptographic primitives (locked — do not substitute)

| Purpose | Construction | Library |
|---|---|---|
| Content signing | Ed25519 | libsodium via `lazysodium-android` |
| Anonymous public-key encryption of the file key | `crypto_box_seal` (X25519 + XSalsa20-Poly1305) | libsodium |
| File body encryption | `crypto_secretstream_xchacha20poly1305` (chunked, streaming) | libsodium |
| Hashing | SHA-256 | libsodium (or `MessageDigest`, must match test vectors) |
| Key derivation (nonces, subkeys) | HKDF-SHA256 | libsodium |
| At-rest key wrapping | AES-256-GCM | Android Keystore |
| Canonical encoding | CBOR, RFC 8949 deterministic encoding | `kotlinx-serialization-cbor` with a deterministic writer, or `cbor-java` pinned |

**Hard rules for implementers and agents:**

1. **Never implement a primitive.** No hand-rolled AES modes, no custom KDFs, no "simple XOR for speed", no nonce counters that roll.
2. **Nonces:** 24-byte random nonces from `randombytes_buf` for secretstream. Never a counter. Never reused.
3. **Verify before use.** Reject on any authentication failure without partial output.
4. **One construction per purpose.** Do not reuse a key for signing and encryption.
5. **Test vectors are normative.** §27.3 contains vectors that any implementation must match byte for byte.
6. If an agent proposes a different construction, the answer is no. Change this document first, deliberately.

**Known gap:** libsodium's `crypto_box_seal` does not authenticate the sender. That is intentional here (carriers must be able to deposit without being the sender). Sender authenticity comes from the manifest signature, not from the encryption layer.

---

## 9. Artefacts and bundle format

### 9.1 The four artefacts

| ID | Name | Plaintext size | Contents | Travels by | Encrypted? |
|---|---|---|---|---|---|
| **O** | Original | up to 200 MB | Raw capture, container as produced by CameraX (MP4/H.264) | USB/SAF export only in v1 | Yes (v2: transit) |
| **X** | Proxy | ~0.5–3 MB | 480p, H.264, ~400 kbps, AAC 64 kbps mono | Wi-Fi Aware/Direct to 2–5 carriers | Yes |
| **T** | Thumbnail | ≤ 20 KB | 320×180 JPEG, quality 50, EXIF stripped | BLE, sprayed widely | Yes |
| **M** | Manifest | ≤ 4 KB | Signed CBOR, see §9.3 | BLE, sprayed widely | No (it is the envelope) |
| **PRF** | ProofFrame | ≤ 220 B | Signed CBOR announcement for LoRa (v2 seam) | LoRa | No |

Design consequence, stated plainly: **the manifest is public metadata and must never contain identity beyond the account key.** Everything that could identify a person is either stripped or encrypted to the account.

### 9.2 On-disk layout

```
files/bundles/<bundle_id>/
    manifest.cbor          # plaintext, signed, ~2 KB
    thumb.enc              # secretstream: 24 B header || ciphertext
    proxy.enc              # secretstream: 24 B header || ciphertext
    original.enc           # optional; export-only
    state.json             # local copy/queue state, never leaves the device
```

- `state.json` is local only and is destroyed on purge or panic wipe.
- The directory name is the hex `bundle_id`, which leaks nothing.
- Storage cap default **2 GB**, user-adjustable; the app refuses new captures beyond it and says so.
- Files are written to app-private internal storage. Never to shared storage without an explicit export.

### 9.3 Manifest schema (normative)

```cddl
manifest = {
  1:  uint,               ; v = 1
  2:  bytes .size 16,     ; bundle_id
  3:  bytes .size 32,     ; account_pk (Ed25519)
  4:  bytes .size 32,     ; content_hash   = SHA-256(plaintext O)
  5:  bytes .size 32,     ; proxy_hash     = SHA-256(plaintext X)
  6:  bytes .size 32,     ; thumb_hash     = SHA-256(plaintext T)
  7:  bytes .size 32,     ; proxy_cipher_hash = SHA-256(ciphertext proxy.enc)
  8:  bytes .size 32,     ; thumb_cipher_hash = SHA-256(ciphertext thumb.enc)
  9:  bytes .size 80,     ; sealed_key = crypto_box_seal(K, account_x25519_pk)
  10: uint,               ; kdf_info_len (bytes of HKDF info used, for forward compat)
  11: uint,               ; created_at (unix seconds, capture start)
  12: uint,               ; duration_ms
  13: int,                ; lat_e7  (degrees × 1e7, signed)
  14: int,                ; lon_e7
  15: uint,               ; gps_accuracy_m
  16: uint,               ; public_gps_precision_m (0 = do not publish coordinates)
  17: uint,               ; media_kind: 1 = video, 2 = photo
  18: uint,               ; orientation: 1..8 (EXIF orientation code, stored deliberately)
  19: bytes .size 16,     ; dest_prefix
  20: {                   ; dest_urls — pre-signed single-use PUT capabilities
        1: tstr,          ; manifest object
        2: tstr,          ; thumb object
        3: tstr,          ; proxy object
      ? 4: tstr,          ; original object (absent in v1)
      },
  21: uint,               ; dest_urls_expire (unix seconds)
  22: uint,               ; max_hops (default 24)
  23: uint,               ; priority (0 = alert, 1 = manifest, 2 = thumb, 3 = proxy)
  24: uint,               ; expires_at (unix seconds; default created_at + 72 h)
  25: bytes .size 64,     ; sig = Ed25519(account_sk, canonical_cbor(1..24))
}
```

**Field rules:**

- All integers are minimal-width CBOR per RFC 8949 deterministic encoding. Maps use integer keys in ascending order.
- `sig` covers fields 1–24 exactly as encoded, with no trailing bytes.
- `dest_prefix` **must** equal `SHA256(account_pk)[0:16]`. A receiver that finds a mismatch **must refuse to forward** and **must not upload**.
- Every URL in `dest_urls` **must** contain the hex `dest_prefix` as its first path segment (after the bucket). Receivers verify this with a plain string check before using any URL.
- `public_gps_precision_m` controls what the verifier displays publicly. `0` means "location withheld"; the precise coordinates remain in the manifest for the verifier but are never rendered in the public summary.
- `expires_at` is advisory for routing and binding for the verifier: an expired bundle is not evidence, it is a stale file.
- No free-text field exists in v1. Free text is the single largest leak vector and the single largest abuse vector; it arrives in v2 with a schema and a length cap.

### 9.4 ProofFrame schema (v2 seam, implemented as an encoder now)

```cddl
proof_frame = {
  1: uint,              ; v = 1
  2: bytes .size 16,    ; bundle_id
  3: bytes .size 32,    ; account_pk
  4: bytes .size 32,    ; content_hash
  5: uint,              ; created_at
  6: int,               ; lat_e7
  7: int,               ; lon_e7
  8: bytes .size 64,    ; sig = Ed25519(account_sk, canonical_cbor(1..7))
}
```

Encoded size ≈ **168 bytes**. It carries **no upload URL**, because a 300–500 byte pre-signed URL does not fit and because a proof frame is an announcement, not a delivery. This is the correction to the "16-byte dest_id + resolver" idea: there is no resolver in this project.

### 9.5 Wire envelope (BLE and Wi-Fi)

Every transfer is one envelope:

```cddl
envelope = {
  1: uint,              ; type: 1=INVENTORY, 2=MANIFEST, 3:THUMB, 4:PROXY, 5:CHUNK, 6:ACK, 7:REFUSE
  2: bytes .size 16,    ; bundle_id
  3: uint,              ; seq (chunk index, 0 for whole-object messages)
  4: uint,              ; total (total chunks, 1 for whole-object)
  5: uint,              ; hops_taken
  6: bytes,             ; payload
  7: bytes .size 64,    ; sig by sender's transport key over fields 1..6
}
```

- Chunk payload max **480 bytes** on BLE (fits a 512-byte MTU minus envelope overhead) and **16 KB** on Wi-Fi.
- `ACK`/`REFUSE` are advisory only. No delivery guarantee exists and none is claimed.
- Transport keys are ephemeral per session, exchanged in the handshake; they are **not** the account key.

---

## 10. Capture pipeline

Sequence, in order, with failure behaviour:

1. **Start recording.** CameraX `VideoCapture` + `AudioRecord`. Immediately create `bundle_id` from a random nonce so the identity of the bundle exists before the first frame.
2. **Stream the proof frame.** As soon as the first frame is encoded, sign and emit `PRF` into the local queue. *Rationale: if the creator is stopped at second 3, the fingerprint is already out.*
3. **Stop recording.** Finalise O. If O > 200 MB, downscale transcode and warn.
4. **Transcode proxy X.** 480p, H.264 baseline, ~400 kbps, mono AAC 64 kbps, faststart. Target ≤ 3 MB for 60 s.
5. **Generate thumbnail T.** 320×180, JPEG q50, EXIF fully stripped (rebuild the JPEG, do not edit in place).
6. **Hash.** `content_hash`, `proxy_hash`, `thumb_hash` on **plaintext**. `*_cipher_hash` on **ciphertext**.
7. **Generate file key K.** 32 random bytes.
8. **Encrypt X and T** with `crypto_secretstream_xchacha20poly1305`, fresh nonce/header per file, K reused across the two files **only because each stream has an independent header and the same key with distinct streams is safe under secretstream's construction** — implementers must use one stream per file and never reset a stream.
9. **Seal K.** `sealed_key = crypto_box_seal(K, account_x25519_pk)`.
10. **Select drop slot.** Take the next unconsumed slot from the pool. If the pool is empty or expires within 24 h, the app must: capture anyway, mark the bundle `EXPORT_ONLY`, and surface a visible warning. It must never silently capture a bundle that can never leave.
11. **Build and sign the manifest.**
12. **Persist and enqueue.** State → `SEALED`, then `QUEUED`.
13. **Delete the plaintext temporaries** (O, X, T) after encryption. Keep only ciphertext. This is the single most valuable privacy action in the app.

**Consent screen (mandatory before step 12):** plain-language list of exactly what will travel — "video 42 s, 2.1 MB proxy, thumbnail, coordinates rounded to 100 m, time, a random key that is not your name, and an upload link to your storage". One tap to cancel. No dark patterns.

---

## 11. Queue and state machine

### 11.1 Bundle-level states

```
DRAFT ──seal──► SEALED ──enqueue──► QUEUED ──any copy uploaded──► UPLOADED ──► PURGED
                   │                     │
                   │                     ├──pool unusable──► EXPORT_ONLY
                   └──capture aborted──► DELETED
```

### 11.2 Per-copy states (the important ones)

Each bundle has a set of **copies** it has emitted, and each copy has independent state. There is no global "delivered" state, because there is no return path.

```
HELD ──offer──► OFFERED ──accept──► SENT ──(carrier's own queue)──► …
```

Local bookkeeping only:

| Field | Meaning |
|---|---|
| `copies_remaining` | Spray budget for this bundle on this device |
| `hops_taken` | Incremented on every forward |
| `last_utility` | Utility score of the last carrier we gave a copy to |
| `bytes_sent` | Diagnostics, local only |
| `first_seen_peer` | Never persisted beyond the session (§19) |

### 11.3 Priority

Upload and forwarding order is fixed:

1. `priority 0` — ALERT (v2; reserved)
2. `priority 1` — MANIFEST
3. `priority 2` — THUMB
4. `priority 3` — PROXY
5. Original export — user-initiated only

Within a priority class: oldest `created_at` first, then smallest size first (to clear more items per unit of energy).

### 11.4 Quotas

| Quota | Default | Enforced where |
|---|---|---|
| Max bundle size | 200 MB original, 5 MB proxy | Capture |
| Max stored bytes | 2 GB | Queue |
| Max bundles held for others | 100 | Queue (evict oldest non-own bundle first) |
| Max bytes accepted per peer per hour | 50 MB | Transport |
| Max forwards per bundle | `max_hops` (24) | Transport |
| Max bundles accepted per peer per hour | 20 | Transport |
| Min battery to transmit | 30% | Scheduler |
| Min battery to receive | 15% | Scheduler |

---

## 12. Transport layer

### 12.1 Bluetooth LE (manifest + thumbnail; discovery everywhere)

**Advertisement**

- 128-bit service UUID, randomly generated at build time and never reused in another app.
- Manufacturer/service data: `transport_id` (8 B) + `flags` (1 B: has-manifest, has-thumb, accepts, needs-charger) + `proto_version` (1 B). Total 10 B, fits legacy 31-byte advertisement.
- Advertisement rotates with `transport_id` every 10–15 minutes plus a random jitter of 0–120 s, so timing cannot be used to link rotations.

**Connection and transfer**

- GATT service with two characteristics: `INVENTORY` (read/write/notify) and `DATA` (write-without-response + notify).
- Negotiate MTU 517, use 480-byte chunks, `WRITE_TYPE_DEFAULT` for reliability.
- Realistic throughput: **20–60 KB/s** on 2M PHY at close range; **5–15 KB/s** on Coded PHY at long range.
- A 2 KB manifest = **~40–100 ms**. A 20 KB thumbnail = **~0.4–1 s**. A 2 MB proxy over BLE = **1–2 minutes**, which is why proxy uses Wi-Fi.

**Session flow**

```
ADV (transport_id)
  → CONNECT
  → HELLO: ephemeral transport key, proto version, capabilities
  → AUTH: both sign a fresh 32-byte challenge with their ephemeral key
  → INVENTORY: Bloom filter + counts (see §12.5)
  → DIFF: decide what to send, in priority order
  → TRANSFER: envelopes, windowed, with per-chunk ACK
  → CLOSE (or timeout)
```

- Whole session target: **≤ 20 s**, hard timeout 45 s, then disconnect and clear state.
- No pairing, no bonding, no user interaction, screen off.
- One connection at a time per device in v1. Concurrency is a v2 optimisation; multi-connection on Android BLE is a reliability trap.

### 12.2 Wi-Fi Aware / Wi-Fi Direct (proxy)

- Prefer **Wi-Fi Aware (NAN)** on API 29+ — lower battery, no user prompt, better discovery.
- Fall back to **Wi-Fi Direct** (`WifiP2pManager`) on older devices. Note: group formation on some OEMs shows a system dialog; detect and degrade rather than block.
- Established link runs a **minimal HTTP/1.1 server** on a fixed port over the P2P interface with a shared one-time token from the BLE session. Do not invent a custom framing over TCP; HTTP keeps debugging sane and lets a laptop test the same path.
- Throughput target: **≥ 5 MB/s**; measured floor 2 MB/s on mid-range hardware.
- Bulk transfer only when: peer utility > our utility, `copies_remaining > 0`, battery > 30% or charging, and the peer is not already holding this `bundle_id`.
- Transfer window: at most **60 s** per peer, then disconnect. Long Wi-Fi sessions are the second-largest battery cost after scanning.

### 12.3 LoRa (v2, seam only)

- The app emits `ProofFrame` bytes and exposes a `ExternalRelay` interface. In v1 it is implemented by **file export** and by **a BLE-UART bridge to an external node** if a user supplies one.
- No LoRa radio code in v1. No hardware assumptions in v1.
- Legal note for the future implementer: 865–867 MHz is licence-exempt in India for low-power devices, but power, antenna, and duty-cycle limits must be verified against current WPC/DoT rules before any hardware ships. Amateur radio forbids encryption and is not a path.

### 12.4 Why video cannot be streamed out (normative note)

| Link | Real throughput | 2 MB proxy |
|---|---|---|
| BLE long range (Coded PHY) | 5–15 KB/s | 2–7 min |
| Wi-Fi Aware/Direct | 2–8 MB/s | < 1 s |
| LoRa (SF7, 125 kHz) | 2–3 KB/s shared | 11–17 min |
| A person walking | ~infinite | 20–40 min, but requires a person |

A 63 MB original over LoRa is **6–9 hours** of exclusive airtime. It is not a software limitation. Any feature request that implies real-time upload during a shutdown is rejected against this table.

### 12.5 Inventory exchange

- **Bloom filter:** 256 bytes, k = 4, sized for ~200 items at ~2.5% false-positive rate. False positives cost a wasted offer; false negatives never happen (Bloom filters have none), so correctness is preserved.
- Each side sends: Bloom filter, item count, bytes free, battery, `utility` inputs (see §13.3).
- On a false-positive rejection (`REFUSE` with reason `already_have`), the sender drops that item for that peer only and continues.
- **Dedup key:** `bundle_id` for the bundle, `(bundle_id, artefact)` for each artefact. `cipher_hash` mismatch on arrival means corruption → discard chunk set and retry once.

### 12.6 USB / SAF export (original video)

- Writes a single **`.ohb`** file: a deterministic CBOR envelope containing the manifest, the encrypted original, and a `receipt` block.
- Also writes **`receipt.txt`** — human-readable: bundle id, hashes (first 16 hex chars), capture time, coarse location, account id, and a line-by-line verification instruction for a third party.
- Export is the only path that moves O in v1. It is intentionally unglamorous and works 100% of the time.

---

## 13. Routing

### 13.1 Algorithm: binary spray-and-wait, with utility gating at the tail

```
on_peer_found(peer):
    if peer.dest_prefix_blocked: return
    if item.hops_taken >= item.max_hops: return
    if item.copies_remaining > 1:
        give = floor(item.copies_remaining / 2)
        item.copies_remaining -= give
        send(item, copies=give)
    else:                                  # last copy — only give it to a better carrier
        if peer.utility > self.utility + MARGIN:
            send(item, copies=1)
            item.copies_remaining = 0
        else:
            keep(item)
```

- **Initial copy budgets:** manifest 32, thumbnail 16, proxy 4. Rationale: the manifest is ~2 KB, so spraying 32 copies costs 64 KB and buys a very high escape probability. The proxy is 2 MB, so 4 copies is already 8 MB of other people's bandwidth.
- **`MARGIN` = 0.15.** Prevents two phones from ping-ponging a copy forever at a density boundary.
- **TTL:** `max_hops = 24`. In a dense crowd that is ~2–3 km of lateral spread; combined with mobility it is enough. Raising it increases both reach and abuse surface.

### 13.2 Dedup and anti-replay

- `bundle_id` is unique per capture; every device stores a `seen` set of `bundle_id` + `cipher_hash` pairs, capped at 10,000 entries (LRU), persisted.
- A bundle whose `expires_at` has passed is not forwarded and is purged after a 24-hour grace period (so a late arrival can still be reported, not re-spread).
- Replay of an old manifest with a rewritten `created_at` is impossible without the account private key. A replay of a *verbatim* old manifest is harmless: it dedups on `bundle_id`.

### 13.3 Utility function (normative weights)

```
utility(peer) =
    0.45 * normalize(battery, 0..100)
  + 0.40 * outward(peer, origin)          # +1 moving away, -1 moving toward, 0 unknown
  + 0.10 * (peer.screen_active ? 1 : 0)   # awake people are better carriers
  + 0.05 * (peer.on_charger ? 1 : 0)
```

- `outward` = sign of the dot product between the peer's recent displacement and the vector from `origin_gps` (the manifest's capture point) to the peer. It needs **no knowledge of the blackout boundary**, which the app cannot see. Using the capture point as the reference is a deliberate, cheap proxy.
- Displacement is computed from GPS over a 2-minute window. GPS works during a shutdown because it is receive-only — note this in the UI so users know location still leaks while recording.
- `self.utility` is computed the same way, from the device's own recent displacement.

### 13.4 What the router must never do

- Never forward an artefact whose `dest_prefix` does not match `SHA256(account_pk)[0:16]` of the manifest.
- Never forward after `expires_at`.
- Never forward to a peer that failed authentication.
- Never accept a bundle whose signature does not verify.
- Never accept or forward a blob whose type is not in the allow-list (§16.1).
- Never decode or parse video on a forwarding device.

---

## 14. Destination model

The app's final action is a write to storage the **creator** controls. Three pluggable implementations of one interface:

```kotlin
interface DropEndpoint {
    fun nextTarget(bundle: Bundle, artefact: Artefact): PutTarget?
    fun onSuccess(bundle: Bundle, artefact: Artefact)
    fun onFailure(bundle: Bundle, artefact: Artefact, e: Throwable): FailureAction
    fun health(): PoolHealth          // slots left, soonest expiry
}
```

### 14.1 `PresignedPoolEndpoint` — v1 default, no server

- A **pool file** is generated on a laptop by the `poolctl` CLI (§14.4) and imported into the app (QR, file, or USB).
- One **slot** = one bundle's worth of capabilities: 3–4 pre-signed PUT URLs, all bound to the same object prefix and expiry.
- The app consumes one slot per capture and never needs the R2 secret key.
- The pool is the only credential-like object on the device. It grants **write-only, prefix-scoped, expiring** access and nothing else.

```cddl
pool = {
  1: uint,                 ; pool_id
  2: bytes .size 16,       ; dest_prefix
  3: uint,                 ; minted_at
  4: uint,                 ; expires_at
  5: [ * slot ],
  6: bytes .size 64,       ; sig by the minting operator key (optional but recommended)
}
slot = {
  1: uint,                 ; slot_index
  2: { 1: tstr, 2: tstr, 3: tstr, ? 4: tstr },   ; manifest/thumb/proxy/(original) URLs
  3: uint,                 ; slot_expires_at
}
```

**Object keys (normative):**

```
<dest_prefix_hex>/<slot_index:04d>/manifest.cbor
<dest_prefix_hex>/<slot_index:04d>/thumb.enc
<dest_prefix_hex>/<slot_index:04d>/proxy.enc
<dest_prefix_hex>/<slot_index:04d>/original.enc
```

**Hard operational constraint:** SigV4 pre-signed URLs expire after at most **7 days**. A pool minted for a protest is valid for one week. The app must show pool health prominently ("14 slots left, expire in 3 days") and must switch to `EXPORT_ONLY` rather than silently fail. This is the single largest operational weakness of the no-server design, and it is documented rather than hidden.

### 14.2 `DropApiEndpoint` — optional, org-run

- `POST /v1/target` with `{bundle_id, dest_prefix, artefact, cipher_hash}` → `{url, expires_at, headers}`.
- Requires an organisation to run ~100 lines of code. That organisation becomes an intermediary with legal obligations; it must have a policy and a takedown path.
- Not part of v1. Included because it is the realistic path to "a normal person can use this without a laptop".

### 14.3 `LocalExportEndpoint` — always available

- Writes the `.ohb` bundle to a user-chosen SAF location or USB device, plus `receipt.txt`.
- Used when the pool is exhausted/expired, when the user prefers physical transfer, or when there is no connectivity at all.

### 14.4 `poolctl` CLI (laptop, Python)

Responsibilities: create/verify a bucket configuration, mint a pool of N slots, print a QR for import, audit pool usage, and generate a **revocation-ready inventory** of what was minted.

It must never store the R2 secret in the repository, never log it, and must refuse to run if the target bucket has public access enabled.

---

## 15. Upload protocol

### 15.1 Trigger

`WorkManager` job with constraints `NetworkType.CONNECTED`, exponential backoff, and a foreground service of type `dataSync` for long transfers. Also triggered by a `ConnectivityManager` network callback so upload starts within seconds of signal returning, not on the next periodic wake.

**This is the single most important reliability mechanism in the app.** The phone outside the zone must upload without the owner tapping anything, without a notification, and without opening the app.

### 15.2 Request shape

```
PUT <presigned url>
Content-Type: application/octet-stream
Content-Length: <bytes>
If-None-Match: *                # single-use attempt; see 15.4
Body: ciphertext bytes
```

- One object per request. No multipart in v1.
- Timeout: 30 s connect, 120 s write.
- Verify the response is `200`/`201`/`204`/`412`. Anything else is a failure with a classified reason.
- After success: mark the artefact uploaded, delete the local ciphertext copy for non-owner bundles, and record a local counter. **No server-side callback exists and none is assumed.**

### 15.3 Retry and circuit breaker

| Failure | Action |
|---|---|
| DNS / connect timeout | Retry with backoff 30 s → 2 m → 10 m → 1 h, cap 6 attempts |
| 403 (expired signature) | Permanent failure for that artefact; mark `POOL_EXPIRED`; try next slot if one exists |
| 412 (already exists) | Treat as **success** if a slot is bound to a single object |
| 409 | Treat as success (content-addressed collision) and move on |
| 5xx | Retryable, backoff |
| Repeated 403 across 3 artefacts | Disable endpoint, surface "pool expired" to the user |

### 15.4 Conditional write caveat

R2's support for `If-None-Match: *` on PUT must be verified before implementation. If unsupported, the design still holds: keys are unique per slot, and any substitution is detectable because the verifier compares `proxy_cipher_hash` in the signed manifest against the downloaded bytes. **Do not silently assume single-use semantics.** Write the behaviour you actually get into the README.

### 15.5 What the uploader's phone must not do

- Must not upload plaintext. Ever.
- Must not upload to a URL that does not contain the manifest's `dest_prefix` in its path.
- Must not upload after `dest_urls_expire`.
- Must not notify the user with content. A generic "network task finished" is the maximum.
- Must not keep the bundle after a confirmed upload + 10-minute grace period.

---

## 16. Verification

### 16.1 Carrier checks (cheap, offline, no keys)

A forwarding phone must verify, in this order, before accepting anything:

1. Envelope parses, `sig` verifies under the session transport key.
2. Artefact type ∈ `{MANIFEST, THUMB, PROXY}`. Anything else → `REFUSE`.
3. Manifest signature verifies under `account_pk`.
4. `dest_prefix == SHA256(account_pk)[0:16]`.
5. Every URL in `dest_urls` contains `dest_prefix` as its first path segment.
6. `now < expires_at` and `now < dest_urls_expire`.
7. `hops_taken < max_hops`.
8. Received bytes hash to the relevant `*_cipher_hash`.
9. `account_id` is not on the local revocation list.

A carrier **cannot** verify `content_hash`, `proxy_hash`, or `thumb_hash` — those are plaintext hashes and the carrier has no key. That is correct and intentional (D12). The carrier verifies **integrity of the ciphertext** and **authenticity of the metadata**; the reviewer verifies **provenance of the content**.

That division is the answer to "how can a phone verify something it can't read": it verifies the sealed box, not the contents.

### 16.2 Reviewer checks (with the key)

The verifier CLI (§17) performs the full chain:

1. Parse canonical CBOR; reject non-deterministic encodings.
2. Verify identity document signature (binds X25519 to Ed25519).
3. Verify manifest signature.
4. Verify `dest_prefix` binding and URL prefix binding.
5. Verify `now < expires_at` (warn if expired, do not silently pass).
6. Decrypt `sealed_key` → K.
7. Decrypt proxy and thumb; verify each stream's final tag.
8. Verify `proxy_hash`, `thumb_hash`, and if the original is supplied, `content_hash`.
9. Verify `proxy_cipher_hash` / `thumb_cipher_hash` against the downloaded bytes.
10. Check revocation list (if provided).
11. Print a verdict object and a human summary.

### 16.3 Verdict object

```json
{
  "bundle_id": "…",
  "verdict": "VALID | VALID_WITH_WARNINGS | INVALID",
  "checks": [{"id": "sig.manifest", "ok": true, "detail": "…"}],
  "capture": {"created_at": "…", "duration_ms": 42000, "location": {"lat": 0.0, "lon": 0.0, "precision_m": 100}},
  "hashes": {"content": "…", "proxy": "…", "thumb": "…"},
  "account_id": "…",
  "identity": {"attribution": "none | encrypted | plaintext", "value": "…"},
  "warnings": ["bundle expires in 3 h", "encrypted attribution present but no key"],
  "tool": {"name": "relay-verify", "version": "1.0.0"}
}
```

Exit codes: `0` valid, `2` valid with warnings, `1` invalid, `3` usage error.

---

## 17. Verifier CLI

- Language: **Kotlin/JVM** reusing `core-crypto` and `core-model` from the Android app, so there is exactly one implementation of the format. (A Python verifier is acceptable for the simulator only; it must be cross-checked against the test vectors.)
- Commands:
  - `relay-verify verify <bundle.ohb | dir> --identity <global.json> [--revocations <file>] [--json]`
  - `relay-verify inspect <bundle>` — metadata only, no decryption (for triage)
  - `relay-verify thumb <bundle>` — decrypt and write the thumbnail only (triage-first workflow)
  - `relay-verify selftest` — run built-in vectors
- Must run offline. No telemetry. No network calls whatsoever.
- Must never write decrypted content to disk unless `--out` is explicit.

**Reviewer policy (shipped as `REVIEWER.md`):** if a bundle appears to contain illegal content, do not download the proxy, do not play it, do not forward it; record the `bundle_id` and hashes and report to the destination owner's abuse contact. Triage with metadata and thumbnail first, always.

---

## 18. Android application architecture

### 18.1 Modules

| Module | Contents | Depends on |
|---|---|---|
| `core-crypto` | libsodium JNI wrapper, test vectors, key wrapping | — |
| `core-model` | CBOR codecs, manifest, pool, envelope, state machine | `core-crypto` |
| `core-queue` | Room DB + file store + quotas + eviction | `core-model` |
| `transport-ble` | Advertising, GATT server/client, session state machine | `core-model` |
| `transport-wifi` | Aware + Direct discovery, P2P HTTP server, token auth | `core-model` |
| `transport-export` | `.ohb` writer, SAF, receipt generator | `core-model` |
| `capture` | CameraX, transcoder, thumbnailer, consent UI | `core-model` |
| `upload` | WorkManager, OkHttp, presigned PUT, circuit breaker | `core-model`, `core-queue` |
| `app` | Compose UI, onboarding, pool import, panic wipe, settings | all |
| `verifier` | JVM library + CLI | `core-crypto`, `core-model` |
| `sim` | Python simulator (separate repo directory) | — |

### 18.2 Background strategy

- Foreground service, type `connectedDevice` for radio work and `dataSync` for upload. Declare both with the correct permissions (`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC`).
- Persistent low-priority notification with a **boring, content-free** title ("Relay is running" / "Maintenance"). No counts, no previews, no names.
- `FLAG_SECURE` on all content screens; no content in the recent-apps thumbnail.
- Doze: request exemption **with an explanation screen**, not silently. If refused, degrade to opportunistic scanning when the screen turns on.
- OEM battery killers (Xiaomi, Realme, Oppo, Vivo, Samsung) are the **number one field failure**. Ship a per-OEM guide with deep links to the autostart/battery screens. Detect known OEMs and show the guide at onboarding.
- Crash reporting: **off by default**. If enabled, content-free, no stack traces that include file paths of bundles.

### 18.3 Scheduler

| State | Scan duty cycle | Rationale |
|---|---|---|
| Charging + app foreground | 100% | Free energy |
| Charging + background | 50% (6 s / 6 s) | Plenty of contacts |
| Battery > 60% | 20% (6 s / 24 s) | Dense crowd, contacts are cheap |
| Battery 30–60% | 10% (6 s / 54 s) | Balanced |
| Battery 15–30% | Receive-only, 5% | Preserve ability to accept |
| Battery < 15% | Radios off, queue persists | Survive |
| No movement 10 min | Suspend scanning until accelerometer wakes | The pocket case |
| Gateway/relay mode | 100%, mains power assumed | Fixed node |

### 18.4 UI surface (deliberately tiny)

1. **Record** (big button, works with the screen off via a hardware shortcut).
2. **Queue** — list of bundles with an honest state: "waiting", "handed to 6 phones", "uploaded". Never "delivered".
3. **Pool health** — slots left, expiry.
4. **Export** — `.ohb` + receipt.
5. **Panic wipe** — long-press shortcut.
6. **Onboarding** — identity creation, pool import, OEM guide, and one screen titled **"What this app cannot do."**

That last screen is mandatory and non-dismissible on first run. It states: no real-time upload, no guarantee of delivery, not audited, does not protect you if your phone is seized unlocked, and no anonymity from a determined state adversary.

---

## 19. Privacy and metadata policy

| Data | Policy |
|---|---|
| EXIF | Strip entirely; rebuild the JPEG. Provenance fields are added deliberately, not inherited. |
| GPS | Stored precisely in the manifest for verification; **rounded to `public_gps_precision_m` (default 100 m) for display**; `0` means withheld. |
| Device model / serial / IMEI / Android ID | Never collected, never transmitted. |
| Phone number / contacts / accounts | Never requested. No permissions for them. |
| Interaction log (who met whom) | In memory only for the session; purged on session end. Never written to disk. |
| `transport_id` | Rotates every 10–15 min + jitter; never persisted beyond a session. |
| Peer history / reputation | Not persisted in v1. Utility is computed from the current session only. |
| Screenshots / recents | `FLAG_SECURE`; excluded from recents where possible. |
| Notifications | Content-free. |
| Crash logs | Off by default; content-free if enabled. |
| Analytics | None. Not even self-hosted. |
| Plaintext temporaries | Deleted immediately after encryption. |
| Queue | Encrypted at rest; deleted 10 minutes after confirmed upload (non-owner bundles) or on purge. |

**Threat that this policy does not solve:** an unlocked, seized phone reveals the queue and the identity. Panic wipe and disk encryption raise the cost; nothing more. Say so.

---

## 20. Threat model

### 20.1 Attacker profiles

| ID | Adversary | Capability | Primary goal |
|---|---|---|---|
| **A1** | Curious passer-by / thief | Physical access for minutes; may install a malicious app | Read content, find the creator |
| **A2** | Malicious mesh participant | Floods the mesh, sends malformed data, runs many fake nodes | Disrupt, deanonymise, inject |
| **A3** | Police at a checkpoint | Physical seizure, coercion, forensic tools, unlimited time with the device | Identify the creator, destroy evidence |
| **A4** | State network adversary | DPI, DNS blocking, domain/IP blocking, baseband-level control, tower shutdown | Prevent egress |
| **A5** | Platform / legal adversary | Takedown, subpoena, Section 69A blocking orders, app-store removal | Remove the tool and the content |
| **A6** | The project itself | Bugs, over-promising, unaudited crypto | Cause harm through false confidence |

### 20.2 Threat matrix

| # | Threat | Profile | Mitigation | Residual |
|---|---|---|---|---|
| T1 | Arbitrary file / malware distribution | A2 | No file channel; allow-list of record types; carriers never decode; no archives/executables/scripting | Low |
| T2 | Decoder exploit via malformed video | A2 | Carriers never parse; decode only on the reviewer's device, in the OS media stack, patched; thumbnail regenerated locally | Medium (zero-click exploits exist) |
| T3 | Sybil flood / eclipse | A2 | Per-peer quotas, rate limits, path diversity in spray budget, no persistent peer trust to subvert | High (inherent to P2P) |
| T4 | Disinformation injection | A2 | Signature + `account_id` + revocation list; corroboration counting at the reviewer; no auto-publish | High (provenance ≠ truth) |
| T5 | Illegal content transit | A2, A5 | Encrypted payload → carrier cannot know; destination bound to creator; no auto-publish; no host; published abuse policy | Medium (carrier still possesses ciphertext) |
| T6 | Framing attack (write someone else's bucket into a manifest) | A2 | `dest_prefix == SHA256(account_pk)[0:16]`; URL path must contain the prefix; refuse otherwise | Low |
| T7 | Device seizure | A3 | Full-disk encryption (OS), encrypted queue, panic wipe, no plaintext cache, no contact graph | Medium |
| T8 | Coercion | A3 | Panic wipe; **no decoy volume in v1** (a bad decoy is worse than none) | High |
| T9 | Social-graph reconstruction | A3 | Rotating transport IDs, no persisted peer history, no contact list | Medium |
| T10 | Domain / IP blocking | A4 | Multiple pre-signed URLs to different providers in the pool; no single domain dependency | Medium |
| T11 | App removal from stores | A5 | F-Droid + direct APK + reproducible builds; boring name and icon | Low |
| T12 | Content invisibility (Section 69A) | A5 | We do not publish, so there is nothing to block on our side; the destination owner's platform is their problem | Out of scope, by design |
| T13 | False sense of safety | A6 | "Cannot do" onboarding screen, honest queue states, README disclaimer, no checkmarks we cannot prove | Medium |
| T14 | Battery death at hour 6 | A6 | Duty cycling, receive-only below 30%, accelerometer wake, field-test on real OEMs | Medium |
| T15 | Crypto bug | A6 | Library-only crypto, test vectors, no invented constructions, external audit before any safety claim | **High until audited** |
| T16 | Pool expiry at the worst moment | A6, operational | Pool health UI, 24-hour pre-expiry warning, `EXPORT_ONLY` fallback, receipt path | Medium |
| T17 | Upload never happens (app killed) | A6 | Foreground service, network callback, WorkManager, OEM guide | Medium — this is the #1 real-world bug |
| T18 | Metadata leak via EXIF/GPS | A1, A3 | EXIF stripped, GPS rounded, precision field | Low |
| T19 | Metadata leak via filename/thumbnail | A1 | Random bundle ids, no descriptive names, thumbnails only decrypted on device | Low |
| T20 | Replay of old bundles | A2 | `bundle_id` dedup, `expires_at`, signed timestamps | Low |
| T21 | Bulk extraction for surveillance | A3 | No readable content without the key; no index; no listing | Medium |

### 20.3 Explicit non-mitigations

These are **not** solved by this design and must not be claimed:

- A state that watches the radio spectrum can see that Bluetooth/Wi-Fi traffic exists.
- A state that physically controls the destination (the R2 bucket owner's account) can compel access.
- Provenance is not truth. A perfectly signed recording of a staged scene is still a staged scene.
- Metadata can be inferred from behaviour even when identifiers rotate.
- An unaudited app is not safe.

---

## 21. Trust and safety

### 21.1 The five transport rules (normative, enforced in code)

1. **Destination bound to signer.** `dest_prefix == SHA256(account_pk)[0:16]`, checked by every forwarder.
2. **Payload encrypted to the destination.** Carriers hold ciphertext only.
3. **Auto-delete after confirmed upload.** Ten-minute grace period, then gone for non-owner bundles.
4. **Signed manifests, revocable keys.** Every artefact is attributable to an `account_id`.
5. **Two-tier relay policy.** Ambient relay for manifest/thumbnail; contact-only (or explicit opt-in) for proxy/original.

### 21.2 Why this stops the "blind mule" problem

The carrier cannot open the box, cannot redirect it, and cannot know what is inside. The creator is the only entity that can publish, because the creator holds the key and the destination is bound to their key. A carrier is a postal worker with a sealed parcel, which is a defensible legal position in most jurisdictions. **This is a legal argument, not a guarantee.** Local law may differ; do not oversell it.

### 21.3 Abuse policy (shipped as `ABUSE.md`)

- This project is a transport. It cannot inspect content.
- Carriers hold ciphertext and have no ability to inspect, report, or refuse based on content. This is a deliberate property, and it is also the reason content moderation is impossible at the transport layer.
- The only party who can publish is the creator, to storage the creator controls.
- Organisations running a `DropApiEndpoint` are intermediaries and must publish their own abuse policy and takedown path.
- Reports concerning a bundle: include `bundle_id`, hashes, and `account_id`. Never include the content itself.
- The project operates no service, holds no keys, and cannot delete anything. State this plainly.

### 21.4 Honest limitations paragraph (ship this verbatim in the README)

> This is a research prototype. It is not audited. It does not guarantee delivery, it cannot upload in real time, and it does not protect you if your phone is seized while unlocked. It is a transport for evidence that you choose to record, delivered to storage that you control. Censorship resistance is indivisible: the same properties that protect a protester also protect an abuser. We mitigate by keeping the mesh ephemeral, by binding every destination to its creator's key, by encrypting payloads so carriers cannot read them, and by never automating publication. We cannot guarantee misuse will not occur, and we publish that limitation openly.

---

## 22. Failure modes and the degradation ladder

| Situation | What the system is | What still works |
|---|---|---|
| 1 phone, no network, no pool | A vault | Capture, seal, queue; export later. **Useful on day one.** |
| 1 phone + pool | A vault with a mailbox | Auto-upload when the owner leaves the zone |
| 5 phones, no relay | A tiny mesh | Manifests hop ~100 m per hop; small-group evidence reaches the edge |
| 50 phones, dense crowd | A swarm | Manifests flood; proxy moves on 4 carriers |
| + 1 person leaving | A bridge | First successful upload, typically minutes to hours |
| + 5 fixed relays | A reliable bridge | Proof reaches the world in ~90 s; works under a kettle |
| + couriers with Wi-Fi Direct | Full system | Proxy within ~30 min, original via export |

**Design rule:** adding one relay must upgrade everyone. Nothing in v1 should require a critical mass of users to be *useful* — only to be *fast*.

Known failure modes, documented rather than fixed:

- Android kills the foreground service on aggressive OEMs.
- Wi-Fi Direct group formation prompts on some devices.
- BLE throughput collapses in a crowd of thousands of advertising devices.
- Pre-signed pool expires.
- The carrier never leaves the zone.
- The creator's phone dies before the bundle is sealed.
- The reviewer never looks.

---

## 23. Testing

### 23.1 Unit and property tests

- **Canonical CBOR:** round-trip, integer-key ordering, rejection of non-deterministic encodings.
- **Manifest signing:** sign/verify, tamper every field, expect failure.
- **Destination binding:** forged `dest_prefix`, forged URL path, expect refusal.
- **Crypto:** all test vectors in §27.3, byte-exact.
- **State machine:** every transition, every illegal transition, every crash point (idempotent recovery).
- **Quotas:** eviction order, storage cap, hop cap, expiry.

### 23.2 Fuzzing

- CBOR parser: libFuzzer/Jazzer, 24 h before release.
- Envelope parser: malformed lengths, huge `total`, zero `total`, chunk gaps.
- Container sniffing: truncated MP4, wrong magic bytes, oversized JPEG (must reject before decode).
- Never fuzz by decoding a video on an emulator; fuzz the *type check*, not the codec.

### 23.3 Integration rig

- **Two physical Android phones** minimum, from different OEMs (one aggressive-background-killer OEM).
- Scenarios: transfer while screen off; transfer while battery saver on; transfer after 30 min idle; transfer with 200 bundles queued; interrupted transfer resumption; Wi-Fi Direct dialog path; BLE MTU 23 fallback.
- **Ten-device drill:** one room, one phone records, nine relay, one walks out. Measure time-to-upload and hop count. Run it monthly; it is the only honest validation available without field users.

### 23.4 Simulator (M1 deliverable, Python)

Inputs: 5 km × 5 km map, crowd density field, mobility model (random walk + directional bias + couriers on roads), adoption rate and placement, radio ranges, duty cycles, copy budgets.

Outputs:
- escape rate (% of bundles reaching a drop point) vs adoption (5/10/20/40%)
- time-to-escape distribution (p50/p90)
- energy per phone per 24 h
- sensitivity to the density cliff and to relay placement

Compare three routers: epidemic, spray-and-wait, spray-and-wait + outward utility. The simulator is the primary evidence that the design works, because the project will not have field users.

### 23.5 Test vectors (normative, to be filled at M0 completion)

| Vector | Input | Expected |
|---|---|---|
| V1 | fixed seed → identity | exact 32-byte pk, exact signature bytes |
| V2 | fixed plaintext → secretstream | exact ciphertext including header |
| V3 | fixed manifest fields → canonical CBOR | exact hex string |
| V4 | V3 + fixed key → signature | exact 64-byte signature |
| V5 | fixed pool JSON → pool CBOR | exact hex string |

These vectors are what make a second implementation (verifier, simulator, future iOS) possible. They are not optional.

---

## 24. Milestones and acceptance criteria

Calendar estimates assume ~2–3 h/day with heavy agent use. **Note the shape: agents compress the first 80% and almost none of the last 20%.**

| ID | Deliverable | Est. | Acceptance criteria |
|---|---|---|---|
| **M0** | This spec + threat model + abuse policy + test vectors | 1–2 wk | Vectors V1–V5 pass in a reference script; spec reviewed and frozen at v1.0 |
| **M1** | Python simulator | 1 wk | Produces escape-rate and latency curves for 3 routers × 4 adoption rates; README explains how to run |
| **M2** | Single-phone demo | 3–4 wk | Record → seal → queue → upload to real R2 via a minted pool → verify with CLI; screenshots + a 60 s demo video |
| **M3** | Two-phone mesh | 3–5 wk | Manifest transfers phone→phone with screen off; dedup works; a bundle captured on A verifies on B |
| **M4** | Working prototype | 6–10 wk | 5+ phones; proxy over Wi-Fi; battery-aware scheduling; foreground service survives 12 h on 2 OEMs |
| **M5** | Field-testable alpha | 8–14 wk | 10-device drill passes; OEM guide; F-Droid build; repair/upgrade path; `EXPORT_ONLY` fallback |
| **M6** | Trustworthy | +3–6 mo | External crypto/security review; findings fixed; then, and only then, remove the "not audited" banner |

**Realistic checkpoints:** demo ~2 months; working prototype ~4–5 months; F-Droid alpha ~8–10 months. Anything claiming a safe, audited tool in under a year is wrong.

---

## 25. F-Droid and release constraints

- **No Google Play Services.** No FCM (use WorkManager + network callbacks), no fused location (use `LocationManager`), no Play Integrity, no ML Kit.
- **FOSS licences only.** libsodium (ISC), lazysodium (Apache-2.0), Kotlin/AndroidX (Apache-2.0). No proprietary blobs, no binary-only dependencies.
- **Reproducible builds** strongly preferred; F-Droid reviewers will ask.
- **No anti-features** that F-Droid flags: no tracking, no ads, no non-free network services required.
- `minSdk 26`, `targetSdk 35` (or current F-Droid requirement at build time). Wi-Fi Aware requires 29; degrade to Direct below that.
- App name and icon must be **boring and non-descriptive**. The label should not mention protest, shutdown, evidence, relay, or freedom. Choose a neutral utility-sounding name. This is a real safety measure, not aesthetics.
- Release channels: F-Droid + direct APK with signature. Document how to verify the APK hash.

---

## 26. Open questions — decide before M2

These are genuinely unresolved; do not let an agent pick silently.

| # | Question | Options | Recommendation |
|---|---|---|---|
| Q1 | Default drop endpoint for a normal user | (a) imported pool only, (b) partner `DropApi`, (c) both | **Ship (a) in v1, design (b).** Pool-only means a laptop step; partner API means a server someone must run. Be explicit that v1 is for organisers and technically capable users. |
| Q2 | Account key: persistent vs per-bundle | persistent pseudonym / fresh per bundle | **Persistent** in v1 (enables revocation and destination binding). Unlinkable mode is v2 research. |
| Q3 | Include original upload in v1? | yes / export-only | **Export-only.** Removes multipart, 63 MB transfers, and most of the bandwidth burden on strangers. |
| Q4 | Does R2 support `If-None-Match: *` on presigned PUT? | verify | Test before implementing; document actual behaviour. |
| Q5 | LoRa in v1? | yes / no | **No.** Ship the seam, not the hardware. |
| Q6 | Panic wipe default | on / opt-in | **On**, with a clear onboarding explanation. A wipe you had to enable is a wipe you forgot to enable. |
| Q7 | Minimum viable UI language set | English / + Hindi / + more | **English + Hindi** at launch, strings externalised. |
| Q8 | Corroboration counting | local / verifier-side | **Verifier-side.** No server means no global count; the reviewer tool can count matching hashes it has seen before. |
| Q9 | Contact-only relay for proxy | on / off | **On by default**, ambient for manifests only. |
| Q10 | Security review path | none / friendly org / paid audit | Approach **SFLC.in / IFF / Guardian Project** with the spec and simulator. An unaffiliated solo audit is unlikely to be funded and unlikely to be trusted. |

---

## 27. Appendices

### 27.1 Deterministic CBOR rules (normative)

- Definite-length items only.
- Shortest-form integer/length encodings.
- Map keys sorted by encoded byte length, then bytewise.
- No floating point anywhere in signed structures. Coordinates are integers (`×1e7`).
- No tags, no indefinite strings, no duplicate keys.
- Any decoder must reject a manifest that is not byte-identical to its canonical re-encoding.

### 27.2 Size budget (must hold at M2)

| Object | Budget | Notes |
|---|---|---|
| Manifest | ≤ 4 KB | ~2 KB typical with 3 URLs |
| ProofFrame | ≤ 220 B | ~168 B typical |
| Thumbnail | ≤ 20 KB | 320×180 q50 |
| Proxy | ≤ 5 MB | 60 s at 480p/400 kbps |
| Original | ≤ 200 MB | export only |
| Envelope overhead | ≤ 100 B | 480 B payload on BLE |

### 27.3 Runtime R2 runbook (for `poolctl` and the README)

1. Create a Cloudflare account; R2 requires a payment method on file even for free tier.
2. Create a bucket with a **random name** (bucket names appear in URLs).
3. **Leave public access off.** No `r2.dev`, no custom domain, no listing.
4. Create a scoped API token: Object Read & Write, **this bucket only**, TTL ≤ 90 days.
5. Generate the pool with `poolctl mint --bucket … --prefix <dest_prefix> --slots 50 --days 7`.
6. Import the pool into the app (QR or file).
7. Bucket settings: lifecycle delete after 30 days; access logging **off**; default encryption on.
8. Never put the secret key in the APK, the repo, or a log. The secret exists only on the minting laptop.
9. Rotate the token before expiry; re-mint pools before the 7-day limit.
10. Test with a single slot end-to-end before minting 50.

### 27.4 Pre-commit checklist for every agent session

```
□ Did this change touch crypto?            → stop, re-read §8, use test vectors
□ Did this change the manifest schema?     → bump v, update CDDL, update vectors
□ Did this add a permission?               → justify it in the PR body, prefer denial
□ Did this add a network call?             → is it in the allow-list (PUT to a manifest URL)?
□ Did this add a log line?                 → could it contain content, hashes, or identifiers?
□ Did this add a dependency?               → FOSS? no Play Services? reproducible?
□ Does the UI claim delivery we cannot prove? → remove it
□ Does this widen the relay policy?        → justify against §21.1 rule 5
□ Are the quota and expiry checks still enforced on every path?
```

### 27.5 What this document deliberately does not specify

- Chat, presence, calls, groups, feeds.
- LoRa hardware, frequencies, or firmware.
- Dead-man's switch semantics.
- Face/plate blurring.
- iOS.
- A public front door or ledger.
- Any server we operate.

If a feature requires one of these, it needs a new spec document, not a patch to this one.

---

## 28. Review checklist for the human (you)

Before this becomes ground truth, confirm:

1. **Scope:** are you happy that v1 is "deliver manifest + thumb + proxy to the creator's storage, manual publish, Android only, no servers"? (If not, say so now — every section depends on it.)
2. **D8:** is dropping original-video upload acceptable for v1? (I recommend yes.)
3. **Q1:** are you willing to accept the laptop/pool step for v1, or do you want a partner drop API in scope?
4. **Q2:** persistent pseudonymous account key — acceptable, or do you want per-bundle unlinkability even at the cost of revocation?
5. **Q6:** panic wipe on by default?
6. **§8:** are you comfortable committing the exact crypto constructions, so agents cannot improvise?
7. **§21.4 / §25:** are you willing to ship the "not audited" language and a boring app name? (Both are safety requirements, not style choices.)
8. **Naming:** pick a codename and a boring shipped name.

Once you sign off, the next artefacts are M0's test vectors and M1's simulator — in that order. Do not start the Android app before the simulator, because the simulator is the only way to answer "does 20% adoption work" without 1,000 phones.
