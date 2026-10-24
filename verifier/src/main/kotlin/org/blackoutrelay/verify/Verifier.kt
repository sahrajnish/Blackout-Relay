package org.blackoutrelay.verify

import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.crypto.SecretStream
import org.blackoutrelay.model.Cbor
import org.blackoutrelay.model.Manifest
import org.blackoutrelay.model.manifestSigInput
import java.io.File
import java.util.HexFormat

/** A loaded bundle (SPEC.md §9.2 directory layout). */
data class Bundle(
    val bundleId: String,
    val manifest: Map<Long, Any>,
    val thumbEnc: ByteArray?,
    val proxyEnc: ByteArray?,
    val originalEnc: ByteArray?,
) {
    companion object {
        fun fromDirectory(dir: File): Bundle {
            val manifestBytes = File(dir, "manifest.cbor").readBytes()
            val manifest = Cbor.decode(manifestBytes) as Map<Long, Any>
            val bundleId = HexFormat.of().formatHex(manifest[Manifest.BUNDLE_ID] as ByteArray)
            fun read(name: String): ByteArray? {
                val f = File(dir, name)
                return if (f.exists()) f.readBytes() else null
            }
            return Bundle(bundleId, manifest, read("thumb.enc"), read("proxy.enc"), read("original.enc"))
        }
    }
}

data class Check(val id: String, val ok: Boolean, val detail: String)

data class Verdict(
    val bundleId: String,
    val verdict: String,
    val checks: List<Check>,
    val capture: Map<String, Any>,
    val hashes: Map<String, String>,
    val accountId: String,
    val warnings: List<String>,
)

/**
 * The reviewer verification chain (SPEC.md §16.2). Produces a Verdict; never
 * writes decrypted content to disk.
 */
object Verifier {

    const val TOOL_NAME = "relay-verify"
    const val TOOL_VERSION = "0.1.0"

    private val hex = HexFormat.of()

    fun verify(bundle: Bundle, identity: Identity, now: Long,
               revocations: Set<String>? = null): Verdict {
        val checks = mutableListOf<Check>()
        val warnings = mutableListOf<String>()
        var fatal = false

        fun fail(id: String, detail: String) { checks += Check(id, false, detail); fatal = true }
        fun pass(id: String, detail: String = "") { checks += Check(id, true, detail) }
        fun warn(msg: String) { warnings += msg }

        val m = bundle.manifest
        val accountPk = m[Manifest.ACCOUNT_PK] as? ByteArray
        val accountId = accountPk?.let { hex.formatHex(Crypto.sha256(it).copyOfRange(0, 16)) } ?: "?"

        // 1. Canonical CBOR — already enforced by Cbor.decode during load.
        pass("cbor.canonical")

        // 2. Identity document signature binds X25519 to Ed25519.
        if (identity.verifyIdentitySig()) pass("identity.sig") else fail("identity.sig", "identity signature invalid")

        // The manifest must be signed by the same account the identity describes.
        if (accountPk != null && accountPk.contentEquals(identity.ed25519Pk)) {
            pass("binding.account")
        } else {
            fail("binding.account", "manifest account_pk != identity ed25519_pk")
        }

        // 3. Manifest signature over fields 1..24.
        val sig = m[Manifest.SIG] as? ByteArray
        val accountPkForSig = accountPk ?: ByteArray(0)
        if (sig != null && Crypto.verify(sig, manifestSigInput(m), accountPkForSig)) {
            pass("sig.manifest")
        } else {
            fail("sig.manifest", "manifest signature invalid")
        }

        // 4. Destination binding.
        val destPrefix = m[Manifest.DEST_PREFIX] as? ByteArray
        val expected = accountPk?.let { Crypto.sha256(it).copyOfRange(0, 16) }
        if (destPrefix != null && expected != null && destPrefix.contentEquals(expected)) {
            pass("binding.dest_prefix")
        } else {
            fail("binding.dest_prefix", "dest_prefix != SHA256(account_pk)[0:16]")
        }

        // 5. URL prefix binding.
        val urls = m[Manifest.DEST_URLS] as? Map<*, *>
        val prefixHex = destPrefix?.let { hex.formatHex(it) } ?: ""
        val allUrlsBound = urls != null && urls.values.all { (it as String).contains(prefixHex) }
        if (allUrlsBound) pass("binding.urls") else fail("binding.urls", "a dest_url lacks the dest_prefix")

        // 6. Expiry (warning, not invalid).
        val expiresAt = (m[Manifest.EXPIRES_AT] as? Long) ?: 0L
        if (now < expiresAt) pass("expiry") else { pass("expiry", "expired"); warn("bundle expired at $expiresAt") }

        val urlsExpire = (m[Manifest.DEST_URLS_EXPIRE] as? Long) ?: 0L
        if (now < urlsExpire) pass("urls.expiry") else { pass("urls.expiry", "expired"); warn("upload URLs expired at $urlsExpire") }

        // 7. Decrypt the sealed key.
        val sealedKey = m[Manifest.SEALED_KEY] as? ByteArray
        val key: ByteArray? = if (sealedKey != null) {
            try {
                val k = Crypto.boxSealOpen(sealedKey, identity.x25519Pk, identity.x25519Sk)
                pass("sealed_key.decrypt")
                k
            } catch (e: Exception) {
                fail("sealed_key.decrypt", e.message ?: "decrypt failed")
                null
            }
        } else { fail("sealed_key.decrypt", "missing sealed_key"); null }

        // 8-9. Decrypt proxy, thumbnail, and (if present) original.
        val proxyPlain = key?.let { k -> decryptArtefact("proxy", k, bundle.proxyEnc, checks, ::fail, ::pass) }
        val thumbPlain = key?.let { k -> decryptArtefact("thumb", k, bundle.thumbEnc, checks, ::fail, ::pass) }
        val originalPlain = if (bundle.originalEnc != null) {
            key?.let { k -> decryptArtefact("original", k, bundle.originalEnc, checks, ::fail, ::pass) }
        } else {
            warn("original not supplied; content_hash not verified")
            null
        }

        // 10. Plaintext hashes.
        checkHash(checks, ::fail, ::pass, "hash.proxy", proxyPlain, m[Manifest.PROXY_HASH] as? ByteArray)
        checkHash(checks, ::fail, ::pass, "hash.thumb", thumbPlain, m[Manifest.THUMB_HASH] as? ByteArray)
        if (bundle.originalEnc != null) {
            checkHash(checks, ::fail, ::pass, "hash.content", originalPlain, m[Manifest.CONTENT_HASH] as? ByteArray)
        }

        // 11. Ciphertext hashes.
        checkHash(checks, ::fail, ::pass, "hash.proxy_cipher", bundle.proxyEnc, m[Manifest.PROXY_CIPHER_HASH] as? ByteArray)
        checkHash(checks, ::fail, ::pass, "hash.thumb_cipher", bundle.thumbEnc, m[Manifest.THUMB_CIPHER_HASH] as? ByteArray)

        // 12. Revocation.
        if (revocations != null) {
            if (accountId in revocations) fail("revocation", "account_id is revoked")
            else pass("revocation")
        }

        val verdict = when {
            fatal -> "INVALID"
            warnings.isNotEmpty() -> "VALID_WITH_WARNINGS"
            else -> "VALID"
        }

        fun hexField(k: Long): String = (m[k] as? ByteArray)?.let { hex.formatHex(it) } ?: ""

        return Verdict(
            bundleId = bundle.bundleId,
            verdict = verdict,
            checks = checks,
            capture = mapOf(
                "created_at" to (m[Manifest.CREATED_AT] ?: 0L),
                "duration_ms" to (m[Manifest.DURATION_MS] ?: 0L),
                "lat_e7" to (m[Manifest.LAT_E7] ?: 0L),
                "lon_e7" to (m[Manifest.LON_E7] ?: 0L),
            ),
            hashes = mapOf(
                "content" to hexField(Manifest.CONTENT_HASH),
                "proxy" to hexField(Manifest.PROXY_HASH),
                "thumb" to hexField(Manifest.THUMB_HASH),
            ),
            accountId = accountId,
            warnings = warnings,
        )
    }

    private fun decryptArtefact(name: String, key: ByteArray, enc: ByteArray?,
                                checks: MutableList<Check>, fail: (String, String) -> Unit,
                                pass: (String, String) -> Unit): ByteArray? {
        if (enc == null) {
            fail("$name.decrypt", "$name.enc missing")
            return null
        }
        if (enc.size < Crypto.SECRETSTREAM_HEADERBYTES + Crypto.SECRETSTREAM_ABYTES) {
            fail("$name.decrypt", "$name.enc too short")
            return null
        }
        return try {
            val header = enc.copyOfRange(0, Crypto.SECRETSTREAM_HEADERBYTES)
            val ciphertext = enc.copyOfRange(Crypto.SECRETSTREAM_HEADERBYTES, enc.size)
            val plain = Crypto.secretStreamOpen(key, header, ciphertext)
            pass("$name.decrypt", "")
            plain
        } catch (e: Exception) {
            fail("$name.decrypt", e.message ?: "decrypt failed")
            null
        }
    }

    private fun checkHash(checks: MutableList<Check>, fail: (String, String) -> Unit,
                          pass: (String, String) -> Unit, id: String,
                          data: ByteArray?, expected: ByteArray?) {
        if (data == null || expected == null) { fail(id, "missing data or hash"); return }
        val actual = Crypto.sha256(data)
        if (actual.contentEquals(expected)) pass(id, "") else fail(id, "hash mismatch")
    }

    // --- verdict -> JSON (SPEC.md §16.3) ------------------------------------

    fun verdictToJson(v: Verdict): String {
        val fields = LinkedHashMap<String, Json.Value>()
        fields["bundle_id"] = Json.Str(v.bundleId)
        fields["verdict"] = Json.Str(v.verdict)
        fields["checks"] = Json.Arr(v.checks.map { c ->
            Json.Obj(linkedMapOf<String, Json.Value>(
                "id" to Json.Str(c.id), "ok" to Json.Bool(c.ok), "detail" to Json.Str(c.detail)))
        })
        fields["capture"] = Json.Obj(v.capture.mapValues { (_, value) -> Json.Num(value as Long) })
        fields["hashes"] = Json.Obj(v.hashes.mapValues { (_, value) -> Json.Str(value) })
        fields["account_id"] = Json.Str(v.accountId)
        fields["warnings"] = Json.Arr(v.warnings.map { Json.Str(it) })
        fields["tool"] = Json.Obj(linkedMapOf<String, Json.Value>(
            "name" to Json.Str(TOOL_NAME), "version" to Json.Str(TOOL_VERSION)))
        return Json.stringify(Json.Obj(fields))
    }
}
