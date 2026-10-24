package org.blackoutrelay.model

import org.blackoutrelay.crypto.Crypto

/**
 * Identifiers and artefact builders, faithful to the CDDL in SPEC.md
 * (§7.3 identity, §9.3 manifest, §9.4 proof frame, §9.5 envelope, §14.1 pool).
 * Field numbers are normative. Mirrors `reference/model.py`.
 */

object Identifiers {
    /** account_id = SHA-256(account_pk)[0:16] (SPEC.md §7.4). */
    fun accountId(accountPk: ByteArray): ByteArray = Crypto.sha256(accountPk).copyOfRange(0, 16)

    /** dest_prefix = account_id (16 bytes). */
    fun destPrefix(accountPk: ByteArray): ByteArray = accountId(accountPk)
}

// Manifest field numbers (§9.3)
object Manifest {
    const val V = 1L
    const val BUNDLE_ID = 2L
    const val ACCOUNT_PK = 3L
    const val CONTENT_HASH = 4L
    const val PROXY_HASH = 5L
    const val THUMB_HASH = 6L
    const val PROXY_CIPHER_HASH = 7L
    const val THUMB_CIPHER_HASH = 8L
    const val SEALED_KEY = 9L
    const val KDF_INFO_LEN = 10L
    const val CREATED_AT = 11L
    const val DURATION_MS = 12L
    const val LAT_E7 = 13L
    const val LON_E7 = 14L
    const val GPS_ACCURACY_M = 15L
    const val PUBLIC_GPS_PRECISION_M = 16L
    const val MEDIA_KIND = 17L
    const val ORIENTATION = 18L
    const val DEST_PREFIX = 19L
    const val DEST_URLS = 20L
    const val DEST_URLS_EXPIRE = 21L
    const val MAX_HOPS = 22L
    const val PRIORITY = 23L
    const val EXPIRES_AT = 24L
    const val SIG = 25L
}

// Identity document (§7.3)
object Identity {
    const val ED25519_PK = 1L
    const val X25519_PK = 2L
    const val CREATED_AT = 3L
    const val KEY_EPOCH = 4L
    const val SIG = 5L
}

// Pool and slot (§14.1)
object Pool {
    const val POOL_ID = 1L
    const val DEST_PREFIX = 2L
    const val MINTED_AT = 3L
    const val EXPIRES_AT = 4L
    const val SLOTS = 5L
    const val SIG = 6L

    object Slot {
        const val INDEX = 1L
        const val URLS = 2L
        const val EXPIRES_AT = 3L
    }
}

// --- builders --------------------------------------------------------------

fun identitySigInput(ed25519Pk: ByteArray, x25519Pk: ByteArray,
                     createdAt: Long, keyEpoch: Long): ByteArray =
    Cbor.encode(mapOf(
        Identity.ED25519_PK to ed25519Pk,
        Identity.X25519_PK to x25519Pk,
        Identity.CREATED_AT to createdAt,
        Identity.KEY_EPOCH to keyEpoch,
    ))

fun buildIdentity(ed25519Pk: ByteArray, x25519Pk: ByteArray,
                  createdAt: Long, keyEpoch: Long, ed25519Sk: ByteArray): Map<Long, Any> {
    val body = mapOf(
        Identity.ED25519_PK to ed25519Pk,
        Identity.X25519_PK to x25519Pk,
        Identity.CREATED_AT to createdAt,
        Identity.KEY_EPOCH to keyEpoch,
    )
    return body + (Identity.SIG to Crypto.sign(Cbor.encode(body), ed25519Sk))
}

/** Canonical CBOR of manifest fields 1..24 (the exact bytes the signature covers). */
fun manifestSigInput(fields: Map<Long, Any>): ByteArray =
    Cbor.encode(fields.filterKeys { it != Manifest.SIG })

fun buildManifest(fields: Map<Long, Any>, ed25519Sk: ByteArray): Map<Long, Any> {
    val body = fields.filterKeys { it != Manifest.SIG }
    return body + (Manifest.SIG to Crypto.sign(Cbor.encode(body), ed25519Sk))
}

fun buildSlot(slotIndex: Long, urls: Map<Long, String>, slotExpiresAt: Long): Map<Long, Any> =
    mapOf(Pool.Slot.INDEX to slotIndex, Pool.Slot.URLS to urls, Pool.Slot.EXPIRES_AT to slotExpiresAt)

fun buildPool(poolId: Long, destPrefixBytes: ByteArray, mintedAt: Long, expiresAt: Long,
              slots: List<Any>, operatorSk: ByteArray? = null): Map<Long, Any> {
    val body = mapOf(
        Pool.POOL_ID to poolId,
        Pool.DEST_PREFIX to destPrefixBytes,
        Pool.MINTED_AT to mintedAt,
        Pool.EXPIRES_AT to expiresAt,
        Pool.SLOTS to slots,
    )
    return if (operatorSk != null) body + (Pool.SIG to Crypto.sign(Cbor.encode(body), operatorSk))
    else body
}

/** ProofFrame (§9.4, v2 seam) — encoded now so the seam is real. */
fun buildProofFrame(bundleId: ByteArray, accountPk: ByteArray, contentHash: ByteArray,
                    createdAt: Long, latE7: Long, lonE7: Long,
                    ed25519Sk: ByteArray): Map<Long, Any> {
    val body = mapOf(
        1L to 1L, 2L to bundleId, 3L to accountPk, 4L to contentHash,
        5L to createdAt, 6L to latE7, 7L to lonE7,
    )
    return body + (8L to Crypto.sign(Cbor.encode(body), ed25519Sk))
}
