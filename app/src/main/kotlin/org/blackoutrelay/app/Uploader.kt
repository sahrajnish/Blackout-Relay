package org.blackoutrelay.app

import android.content.Context
import org.blackoutrelay.model.Cbor
import org.blackoutrelay.model.Manifest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads sealed ciphertext to the pool's pre-signed PUT URLs (SPEC.md §15).
 * Only manifest/thumb/proxy are uploaded; the original is export-only in v1.
 */
object Uploader {

    data class Result(val ok: Boolean, val uploaded: Int, val attempted: Int, val reason: String?)
    data class Batch(val ok: Boolean, val bundlesUploaded: Int, val bundlesSkipped: Int, val reason: String?)

    private const val CONNECT_TIMEOUT = 30_000
    private const val READ_TIMEOUT = 120_000

    /** Uploads every bundle that has a usable drop slot. Export-only bundles are skipped. */
    fun uploadAll(c: Context): Batch {
        val root = File(c.filesDir, "bundles")
        var uploadedBundles = 0
        var skipped = 0
        var failure: String? = null
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach { dir ->
            val state = readState(dir)
            if (state.optBoolean("export_only", false) || state.optString("state") == "UPLOADED") {
                skipped++
                return@forEach
            }
            val r = uploadBundle(c, dir)
            if (r.ok) uploadedBundles++ else { failure = r.reason; skipped++ }
        }
        return Batch(failure == null, uploadedBundles, skipped, failure)
    }

    fun uploadBundle(c: Context, dir: File): Result {
        val manifestFile = File(dir, "manifest.cbor")
        if (!manifestFile.exists()) return Result(false, 0, 0, "no manifest")
        val m = try {
            Cbor.decode(manifestFile.readBytes()) as Map<*, *>
        } catch (e: Exception) {
            return Result(false, 0, 0, "bad manifest")
        }
        val urls = m[Manifest.DEST_URLS] as? Map<*, *> ?: return Result(false, 0, 0, "no urls")
        val expiry = (m[Manifest.DEST_URLS_EXPIRE] as? Long) ?: 0L
        if (expiry <= System.currentTimeMillis() / 1000) return Result(false, 0, 0, "urls expired")

        val targets = listOf(
            1L to "manifest.cbor",
            2L to "thumb.enc",
            3L to "proxy.enc",
        )
        var uploaded = 0
        for ((artId, fileName) in targets) {
            val url = urls[artId] as? String ?: return Result(false, uploaded, targets.size, "missing url $artId")
            val f = File(dir, fileName)
            if (!f.exists()) continue
            val code = putWithRetry(url, f.readBytes())
            if (!isSuccess(code)) {
                return Result(false, uploaded, targets.size, "PUT $fileName -> $code")
            }
            uploaded++
        }

        val stateFile = File(dir, "state.json")
        val state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        state.put("state", "UPLOADED")
        state.put("uploaded_at", System.currentTimeMillis() / 1000)
        state.put("export_only", false)
        stateFile.writeText(state.toString())
        return Result(true, uploaded, targets.size, null)
    }

    private fun readState(dir: File): JSONObject = try {
        val f = File(dir, "state.json")
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun putWithRetry(url: String, bytes: ByteArray): Int {
        var last = -1
        var delayMs = 1_000L
        repeat(4) { attempt ->
            last = try {
                put(url, bytes)
            } catch (e: Exception) {
                android.util.Log.w("RelayUpload", "PUT failed: ${e.javaClass.simpleName}: ${e.message}", e)
                -1
            }
            android.util.Log.i("RelayUpload", "PUT $url -> $last")
            if (isSuccess(last) || last == 403) return last   // 403 = expired, don't retry
            if (attempt < 3) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
                delayMs *= 3
            }
        }
        return last
    }

    private fun put(url: String, bytes: ByteArray): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("If-None-Match", "*")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    // 412 = already exists (single-use), 409 = collision: both treated as success (§15.3).
    private fun isSuccess(code: Int) = code == 200 || code == 201 || code == 204 || code == 412 || code == 409
}
