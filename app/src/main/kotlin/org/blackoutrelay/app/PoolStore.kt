package org.blackoutrelay.app

import android.content.Context
import org.blackoutrelay.model.Cbor
import org.json.JSONArray
import java.io.File

/**
 * Imported pre-signed drop pool (SPEC.md §14.1). One slot per capture; the app
 * consumes slots and never sees the R2 secret key.
 *
 * NOTE: `Cbor.decode` yields integer map keys as `Long`, so all field lookups
 * here use Long literals (1L..5L).
 */
object PoolStore {

    data class Slot(val index: Long, val urls: Map<Long, String>, val expiresAt: Long)

    data class Health(
        val hasPool: Boolean,
        val slotsTotal: Int,
        val slotsRemaining: Int,
        val poolExpiresAt: Long,
        val prefixHex: String,
    )

    private fun poolFile(c: Context) = File(c.filesDir, "pool.cbor")

    // Side-load path: the app's own external files dir (no permission needed).
    // Also used by adb for testing; the file is validated on import.
    private fun externalPoolFile(c: Context) =
        File(c.getExternalFilesDir(null) ?: c.filesDir, "pool.cbor")

    private fun usedFile(c: Context) = File(c.filesDir, "pool_used.json")

    fun import(c: Context, identity: IdentityStore.Identity, raw: ByteArray): Result<Unit> {
        return try {
            val bytes = maybeHex(raw)
            val pool = Cbor.decode(bytes) as? Map<*, *>
                ?: return Result.failure(IllegalArgumentException("not a pool"))
            val prefix = pool[2L] as? ByteArray
                ?: return Result.failure(IllegalArgumentException("pool has no dest_prefix"))
            if (!prefix.contentEquals(identity.accountId)) {
                return Result.failure(
                    IllegalArgumentException(
                        "pool was minted for a different identity (${prefix.toHex().take(8)}…) than this device (${identity.accountIdHex.take(8)}…)"
                    )
                )
            }
            poolFile(c).writeBytes(bytes)
            usedFile(c).delete()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clear(c: Context) {
        poolFile(c).delete()
        externalPoolFile(c).delete()
        usedFile(c).delete()
    }

    fun hasPool(c: Context) = poolFile(c).exists() || externalPoolFile(c).exists()

    private fun load(c: Context): Map<*, *>? {
        val f = when {
            poolFile(c).exists() -> poolFile(c)
            externalPoolFile(c).exists() -> externalPoolFile(c)
            else -> return null
        }
        return try {
            Cbor.decode(f.readBytes()) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
    }

    private fun loadUsed(c: Context): MutableSet<Long> = try {
        val f = usedFile(c)
        if (!f.exists()) mutableSetOf() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getLong(it) }.toMutableSet()
        }
    } catch (e: Exception) {
        mutableSetOf()
    }

    private fun saveUsed(c: Context, used: Set<Long>) {
        usedFile(c).writeText(JSONArray(used.sorted()).toString())
    }

    /** Next unused, unexpired slot; marks it used. Null if none. */
    fun nextSlot(c: Context, nowSec: Long): Slot? {
        val pool = load(c) ?: return null
        val slots = pool[5L] as? List<*> ?: return null
        val used = loadUsed(c)
        for (s in slots) {
            val sm = s as? Map<*, *> ?: continue
            val idx = sm[1L] as? Long ?: continue
            val exp = sm[3L] as? Long ?: 0L
            if (idx in used || exp <= nowSec) continue
            val rawUrls = sm[2L] as? Map<*, *> ?: continue
            val urls = rawUrls.entries.associate { (k, v) -> (k as Long) to (v as String) }
            used.add(idx)
            saveUsed(c, used)
            return Slot(idx, urls, exp)
        }
        return null
    }

    fun health(c: Context, nowSec: Long): Health {
        val pool = load(c) ?: return Health(false, 0, 0, 0, "")
        val slots = pool[5L] as? List<*> ?: emptyList<Any?>()
        val used = loadUsed(c)
        val remaining = slots.count { s ->
            val sm = s as? Map<*, *>
            val idx = sm?.get(1L) as? Long
            val exp = sm?.get(3L) as? Long ?: 0L
            idx != null && idx !in used && exp > nowSec
        }
        return Health(
            hasPool = true,
            slotsTotal = slots.size,
            slotsRemaining = remaining,
            poolExpiresAt = (pool[4L] as? Long) ?: 0L,
            prefixHex = (pool[2L] as? ByteArray)?.toHex() ?: "",
        )
    }

    private fun maybeHex(raw: ByteArray): ByteArray {
        val text = String(raw, Charsets.US_ASCII).trim()
        val isHex = text.isNotEmpty() && text.length % 2 == 0 &&
            text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return if (isHex) text.hexToBytes() else raw
    }
}
