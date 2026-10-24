package org.blackoutrelay.app

import android.content.Context
import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.model.Cbor
import org.blackoutrelay.model.Manifest
import org.blackoutrelay.model.manifestSigInput
import org.json.JSONObject
import java.io.File

/** Reads the on-device bundle store and runs a local verification chain. */
private data class ArtefactSpec(val name: String, val file: String, val hashField: Long, val cipherField: Long?)

object QueueStore {

    data class Entry(
        val bundleIdHex: String,
        val createdAtSec: Long,
        val state: String,
        val manifestBytes: Int,
        val exportOnly: Boolean,
    )

    data class VerifyResult(val ok: Boolean, val lines: List<String>)

    private fun root(context: Context) = File(context.filesDir, "bundles")

    fun list(context: Context): List<Entry> {
        val r = root(context)
        if (!r.exists()) return emptyList()
        return (r.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, "manifest.cbor").exists() }
            .mapNotNull { dir ->
                try {
                    val m = Cbor.decode(File(dir, "manifest.cbor").readBytes()) as Map<*, *>
                    val created = (m[Manifest.CREATED_AT] as? Long) ?: 0L
                    var state = "SEALED"
                    var exportOnly = true
                    val sf = File(dir, "state.json")
                    if (sf.exists()) {
                        val j = JSONObject(sf.readText())
                        state = j.optString("state", "SEALED")
                        exportOnly = j.optBoolean("export_only", true)
                    }
                    Entry(dir.name, created, state, File(dir, "manifest.cbor").length().toInt(), exportOnly)
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.createdAtSec }
    }

    fun verify(context: Context, identity: IdentityStore.Identity, bundleIdHex: String): VerifyResult {
        val lines = mutableListOf<String>()
        var ok = true
        fun fail(msg: String) { ok = false; lines += "FAIL  $msg" }
        fun pass(msg: String) { lines += "ok    $msg" }

        val dir = File(root(context), bundleIdHex)
        val m = try {
            Cbor.decode(File(dir, "manifest.cbor").readBytes()) as Map<*, *>
        } catch (e: Exception) {
            return VerifyResult(false, listOf("FAIL  manifest does not parse as canonical CBOR"))
        }

        val accountPk = m[Manifest.ACCOUNT_PK] as? ByteArray
        val sig = m[Manifest.SIG] as? ByteArray
        @Suppress("UNCHECKED_CAST")
        val fields = m as Map<Long, Any>

        if (accountPk != null && accountPk.contentEquals(identity.edPk)) pass("manifest signed by this account")
        else fail("manifest account_pk != device identity")

        if (sig != null && accountPk != null && Crypto.verify(sig, manifestSigInput(fields), accountPk))
            pass("manifest signature")
        else fail("manifest signature")

        val prefix = m[Manifest.DEST_PREFIX] as? ByteArray
        if (prefix != null && accountPk != null && prefix.contentEquals(Crypto.sha256(accountPk).copyOfRange(0, 16)))
            pass("dest_prefix bound to account")
        else fail("dest_prefix binding")

        val key = try {
            Crypto.boxSealOpen(m[Manifest.SEALED_KEY] as ByteArray, identity.xPk, identity.xSk)
        } catch (e: Exception) {
            fail("sealed key decrypt")
            return VerifyResult(false, lines)
        }
        pass("sealed key decrypt")

        val artefacts = listOf(
            ArtefactSpec("thumb", "thumb.enc", Manifest.THUMB_HASH, Manifest.THUMB_CIPHER_HASH),
            ArtefactSpec("proxy", "proxy.enc", Manifest.PROXY_HASH, Manifest.PROXY_CIPHER_HASH),
            ArtefactSpec("original", "original.enc", Manifest.CONTENT_HASH, null),
        )
        for ((name, file, hashField, cipherField) in artefacts) {
            val f = File(dir, file)
            if (!f.exists()) continue
            val enc = f.readBytes()
            val plain = try {
                Crypto.secretStreamOpen(key, enc.copyOfRange(0, Crypto.SECRETSTREAM_HEADERBYTES),
                    enc.copyOfRange(Crypto.SECRETSTREAM_HEADERBYTES, enc.size))
            } catch (e: Exception) {
                fail("$name decrypt"); continue
            }
            if ((m[hashField] as? ByteArray)?.contentEquals(Crypto.sha256(plain)) == true)
                pass("$name hash")
            else fail("$name hash")
            if (cipherField != null) {
                if ((m[cipherField] as? ByteArray)?.contentEquals(Crypto.sha256(enc)) == true)
                    pass("$name cipher hash")
                else fail("$name cipher hash")
            }
        }
        return VerifyResult(ok, lines)
    }

    fun delete(context: Context, bundleIdHex: String) {
        File(root(context), bundleIdHex).deleteRecursively()
    }

    /** Decrypt only the thumbnail (triage-first, SPEC.md §17). */
    fun decryptThumb(context: Context, identity: IdentityStore.Identity, bundleIdHex: String): ByteArray? {
        return try {
            val dir = File(root(context), bundleIdHex)
            val m = Cbor.decode(File(dir, "manifest.cbor").readBytes()) as Map<*, *>
            val key = Crypto.boxSealOpen(m[Manifest.SEALED_KEY] as ByteArray, identity.xPk, identity.xSk)
            val enc = File(dir, "thumb.enc").readBytes()
            Crypto.secretStreamOpen(key, enc.copyOfRange(0, Crypto.SECRETSTREAM_HEADERBYTES),
                enc.copyOfRange(Crypto.SECRETSTREAM_HEADERBYTES, enc.size))
        } catch (e: Exception) {
            null
        }
    }
}
