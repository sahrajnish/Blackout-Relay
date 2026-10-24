package org.blackoutrelay.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.model.Cbor
import org.blackoutrelay.model.Identifiers
import org.blackoutrelay.model.Manifest
import org.blackoutrelay.model.buildManifest
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Capture→seal pipeline (SPEC.md §10). Handles photos and videos: the thumbnail
 * is derived from a downscaled photo or a decoded video frame; the proxy is a
 * 480p downscale of a photo, or (for video, pending transcode) the original.
 * Only ciphertext is written to disk.
 */
object Sealer {

    const val KIND_VIDEO = 1L
    const val KIND_PHOTO = 2L

    data class Sealed(
        val bundleIdHex: String,
        val originalBytes: Int,
        val proxyBytes: Int,
        val thumbBytes: Int,
        val kind: Long,
        val queued: Boolean,
    )

    fun seal(
        context: Context,
        identity: IdentityStore.Identity,
        original: ByteArray,
        kind: Long,
        thumbJpeg: ByteArray?,
        durationMs: Long,
        nowMs: Long,
        latE7: Long = 0,
        lonE7: Long = 0,
        slot: PoolStore.Slot? = null,
    ): Sealed {
        val thumb = thumbJpeg ?: if (kind == KIND_PHOTO) {
            downscaleJpeg(original, 320, 180, 50)
        } else {
            placeholderThumb("VIDEO")
        }
        val proxy = if (kind == KIND_PHOTO) downscaleJpeg(original, 854, 480, 60) else original

        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (th, tc) = Crypto.secretStreamSeal(k, thumb)
        val (ph, pc) = Crypto.secretStreamSeal(k, proxy)
        val (oh, oc) = Crypto.secretStreamSeal(k, original)
        val thumbEnc = th + tc
        val proxyEnc = ph + pc
        val originalEnc = oh + oc

        val bundleId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val prefix = Identifiers.destPrefix(identity.edPk)
        val prefixHex = prefix.toHex()
        val nowSec = nowMs / 1000
        val base = "https://export.invalid/$prefixHex/0000"
        val destUrls = slot?.urls ?: mapOf(
            1L to "$base/manifest.cbor",
            2L to "$base/thumb.enc",
            3L to "$base/proxy.enc",
        )
        val destExpire = slot?.expiresAt ?: 0L
        val exportOnly = slot == null

        val fields = mapOf(
            Manifest.V to 1L,
            Manifest.BUNDLE_ID to bundleId,
            Manifest.ACCOUNT_PK to identity.edPk,
            Manifest.CONTENT_HASH to Crypto.sha256(original),
            Manifest.PROXY_HASH to Crypto.sha256(proxy),
            Manifest.THUMB_HASH to Crypto.sha256(thumb),
            Manifest.PROXY_CIPHER_HASH to Crypto.sha256(proxyEnc),
            Manifest.THUMB_CIPHER_HASH to Crypto.sha256(thumbEnc),
            Manifest.SEALED_KEY to Crypto.boxSeal(k, identity.xPk),
            Manifest.KDF_INFO_LEN to 0L,
            Manifest.CREATED_AT to nowSec,
            Manifest.DURATION_MS to durationMs,
            Manifest.LAT_E7 to latE7,
            Manifest.LON_E7 to lonE7,
            Manifest.GPS_ACCURACY_M to 0L,
            Manifest.PUBLIC_GPS_PRECISION_M to 100L,
            Manifest.MEDIA_KIND to kind,
            Manifest.ORIENTATION to 1L,
            Manifest.DEST_PREFIX to prefix,
            Manifest.DEST_URLS to destUrls,
            Manifest.DEST_URLS_EXPIRE to destExpire,
            Manifest.MAX_HOPS to 24L,
            Manifest.PRIORITY to 1L,
            Manifest.EXPIRES_AT to (nowSec + 72 * 3600),
        )
        val manifest = buildManifest(fields, identity.edSk)

        val dir = File(context.filesDir, "bundles/${bundleId.toHex()}")
        dir.mkdirs()
        File(dir, "manifest.cbor").writeBytes(Cbor.encode(manifest))
        File(dir, "thumb.enc").writeBytes(thumbEnc)
        File(dir, "proxy.enc").writeBytes(proxyEnc)
        File(dir, "original.enc").writeBytes(originalEnc)
        val stateName = if (exportOnly) "EXPORT_ONLY" else "QUEUED"
        File(dir, "state.json").writeText(
            """{"state":"$stateName","created_at":$nowSec,"kind":$kind,"original_bytes":${original.size},"export_only":$exportOnly,"slot":${slot?.index ?: -1}}"""
        )
        return Sealed(bundleId.toHex(), original.size, proxy.size, thumb.size, kind, !exportOnly)
    }

    /** Extract a 320x180 JPEG frame from a video Uri (MediaMetadataRetriever). */
    fun videoThumbnailJpeg(context: Context, uri: Uri): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val frame = mmr.getFrameAtTime(0) ?: mmr.getFrameAtTime() ?: return null
            val scaled = Bitmap.createScaledBitmap(frame, 320, 180, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 50, out)
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    fun videoDurationMs(context: Context, uri: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun downscaleJpeg(src: ByteArray, maxW: Int, maxH: Int, quality: Int): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return src
        val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height, 1f)
        val w = maxOf(1, (bmp.width * scale).toInt())
        val h = maxOf(1, (bmp.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    private fun placeholderThumb(label: String): ByteArray {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.argb(255, 30, 38, 54))
        val p = Paint().apply {
            color = Color.WHITE; textSize = 34f; isAntiAlias = true
        }
        c.drawText(label, 24f, 100f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 50, out)
        return out.toByteArray()
    }
}
