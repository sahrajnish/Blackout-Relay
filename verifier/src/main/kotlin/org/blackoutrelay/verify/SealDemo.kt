package org.blackoutrelay.verify

import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.crypto.SecretStream
import org.blackoutrelay.model.Cbor
import org.blackoutrelay.model.Identifiers
import org.blackoutrelay.model.Manifest
import org.blackoutrelay.model.buildIdentity
import org.blackoutrelay.model.buildManifest
import java.io.File
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Seals a bundle to a directory (the "capture → seal → queue" half of the M2
 * demo), mirroring the SPEC.md §10 pipeline. Lives here so the CLI selftest and
 * the end-to-end test share one implementation.
 */
object SealDemo {

    private val hex = HexFormat.of()

    fun seal(dir: File, originalPlain: ByteArray, proxyPlain: ByteArray, thumbPlain: ByteArray,
             createdAt: Long, latE7: Long, lonE7: Long,
             edSeed: ByteArray, xSeed: ByteArray, bundleId: ByteArray): Identity {
        dir.mkdirs()

        val (edPk, edSk) = Crypto.ed25519Keypair(edSeed)
        val (xPk, xSk) = Crypto.x25519Keypair(xSeed)

        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }

        fun encrypt(plain: ByteArray): ByteArray {
            val (header, ciphertext) = Crypto.secretStreamSeal(k, plain)
            return header + ciphertext
        }
        val originalEnc = encrypt(originalPlain)
        val proxyEnc = encrypt(proxyPlain)
        val thumbEnc = encrypt(thumbPlain)

        val prefix = Identifiers.destPrefix(edPk)
        val base = "https://bucket.r2.cloudflarestorage.com/${hex.formatHex(prefix)}/0000"
        val fields = mapOf(
            Manifest.V to 1L,
            Manifest.BUNDLE_ID to bundleId,
            Manifest.ACCOUNT_PK to edPk,
            Manifest.CONTENT_HASH to Crypto.sha256(originalPlain),
            Manifest.PROXY_HASH to Crypto.sha256(proxyPlain),
            Manifest.THUMB_HASH to Crypto.sha256(thumbPlain),
            Manifest.PROXY_CIPHER_HASH to Crypto.sha256(proxyEnc),
            Manifest.THUMB_CIPHER_HASH to Crypto.sha256(thumbEnc),
            Manifest.SEALED_KEY to Crypto.boxSeal(k, xPk),
            Manifest.KDF_INFO_LEN to 0L,
            Manifest.CREATED_AT to createdAt,
            Manifest.DURATION_MS to 42_000L,
            Manifest.LAT_E7 to latE7,
            Manifest.LON_E7 to lonE7,
            Manifest.GPS_ACCURACY_M to 25L,
            Manifest.PUBLIC_GPS_PRECISION_M to 100L,
            Manifest.MEDIA_KIND to 1L,
            Manifest.ORIENTATION to 1L,
            Manifest.DEST_PREFIX to prefix,
            Manifest.DEST_URLS to mapOf(
                1L to "$base/manifest.cbor",
                2L to "$base/thumb.enc",
                3L to "$base/proxy.enc",
            ),
            Manifest.DEST_URLS_EXPIRE to (createdAt + 7 * 86400),
            Manifest.MAX_HOPS to 24L,
            Manifest.PRIORITY to 1L,
            Manifest.EXPIRES_AT to (createdAt + 72 * 3600),
        )
        val manifest = buildManifest(fields, edSk)

        File(dir, "manifest.cbor").writeBytes(Cbor.encode(manifest))
        File(dir, "original.enc").writeBytes(originalEnc)
        File(dir, "proxy.enc").writeBytes(proxyEnc)
        File(dir, "thumb.enc").writeBytes(thumbEnc)

        val identity = Identity(edPk, xPk, createdAt, 1L,
            (buildIdentity(edPk, xPk, createdAt, 1L, edSk)[org.blackoutrelay.model.Identity.SIG] as ByteArray),
            edSk, xSk)
        return identity
    }

    fun identityToJson(identity: Identity): String {
        val fields = linkedMapOf(
            "ed25519_pk" to hex.formatHex(identity.ed25519Pk),
            "x25519_pk" to hex.formatHex(identity.x25519Pk),
            "created_at" to identity.createdAt,
            "key_epoch" to identity.keyEpoch,
            "identity_sig" to hex.formatHex(identity.identitySig),
            "ed25519_sk" to hex.formatHex(identity.ed25519Sk),
            "x25519_sk" to hex.formatHex(identity.x25519Sk),
        )
        val sb = StringBuilder("{\n")
        fields.entries.forEachIndexed { i, (k, v) ->
            val value = if (v is String) "\"$v\"" else v.toString()
            sb.append("  \"$k\": $value").append(if (i < fields.size - 1) ",\n" else "\n")
        }
        return sb.append("}").toString()
    }
}
