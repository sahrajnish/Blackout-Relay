package org.blackoutrelay.model

import java.io.ByteArrayOutputStream

/**
 * Deterministic CBOR (RFC 8949 §4.2.1 "Core Deterministic Encoding"), per
 * SPEC.md §27.1:
 *   - definite-length only, shortest-form integers and lengths,
 *   - map keys sorted by encoded byte length, then bytewise,
 *   - no floats, no tags, no indefinite items, no duplicate keys.
 *
 * Values used on the wire: integers (Long), byte strings (ByteArray), text
 * strings (String), arrays (List), and maps keyed by Long or String.
 */

object Cbor {

    private const val MAJOR_UINT = 0
    private const val MAJOR_NINT = 1
    private const val MAJOR_BYTES = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5
    private const val MAJOR_TAG = 6
    private const val MAJOR_SIMPLE = 7

    class CborException(message: String) : IllegalArgumentException(message)

    // --- encoding ----------------------------------------------------------

    fun encode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        writeValue(out, value)
        return out.toByteArray()
    }

    private fun writeValue(out: ByteArrayOutputStream, v: Any) {
        when (v) {
            is Int -> writeInt(out, v.toLong())
            is Long -> writeInt(out, v)
            is ByteArray -> writeHead(out, MAJOR_BYTES, v.size.toLong()).also { out.write(v) }
            is String -> {
                val raw = v.toByteArray(Charsets.UTF_8)
                writeHead(out, MAJOR_TEXT, raw.size.toLong())
                out.write(raw)
            }
            is List<*> -> {
                writeHead(out, MAJOR_ARRAY, v.size.toLong())
                for (item in v) writeValue(out, item!!)
            }
            is Map<*, *> -> writeMap(out, v)
            else -> throw CborException("unsupported value type: ${v.javaClass}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        if (v >= 0) writeHead(out, MAJOR_UINT, v) else writeHead(out, MAJOR_NINT, -1L - v)
    }

    private fun writeHead(out: ByteArrayOutputStream, major: Int, arg: Long) {
        when {
            arg < 24L -> out.write((major shl 5) or arg.toInt())
            arg < 0x100L -> {
                out.write((major shl 5) or 24); out.write(arg.toInt())
            }
            arg < 0x10000L -> {
                out.write((major shl 5) or 25)
                out.write((arg shr 8).toInt()); out.write(arg.toInt())
            }
            arg < 0x100000000L -> {
                out.write((major shl 5) or 26)
                out.write((arg shr 24).toInt()); out.write((arg shr 16).toInt())
                out.write((arg shr 8).toInt()); out.write(arg.toInt())
            }
            else -> {
                out.write((major shl 5) or 27)
                for (shift in 56 downTo 0 step 8) out.write((arg shr shift).toInt())
            }
        }
    }

    private fun writeMap(out: ByteArrayOutputStream, map: Map<*, *>) {
        data class Entry(val key: Any, val value: Any, val keyEnc: ByteArray)
        val entries = map.entries.map {
            val key = it.key ?: throw CborException("null map key")
            val value = it.value ?: throw CborException("null map value")
            Entry(key, value, encodeKey(key))
        }.sortedWith(Comparator { a, b ->
            val c = a.keyEnc.size - b.keyEnc.size
            if (c != 0) c else compareBytes(a.keyEnc, b.keyEnc)
        })
        writeHead(out, MAJOR_MAP, entries.size.toLong())
        for (e in entries) {
            out.write(e.keyEnc)
            writeValue(out, e.value)
        }
    }

    private fun encodeKey(key: Any): ByteArray = when (key) {
        is Int -> {
            val v = key.toLong()
            if (v >= 0) encUint(v) else encNint(v)
        }
        is Long -> if (key >= 0) encUint(key) else encNint(key)
        is String -> encText(key)
        else -> throw CborException("unsupported map key type: ${key.javaClass}")
    }

    private fun encUint(v: Long): ByteArray = headBytes(MAJOR_UINT, v)
    private fun encNint(v: Long): ByteArray = headBytes(MAJOR_NINT, -1L - v)
    private fun encText(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        return headBytes(MAJOR_TEXT, raw.size.toLong()) + raw
    }

    private fun headBytes(major: Int, arg: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeHead(out, major, arg)
        return out.toByteArray()
    }

    // --- decoding (strict) -------------------------------------------------

    fun decode(data: ByteArray): Any {
        val pos = intArrayOf(0)
        val value = readValue(data, pos)
        if (pos[0] != data.size) throw CborException("trailing bytes after CBOR value")
        // Canonical round-trip check.
        if (!encode(value).contentEquals(data)) {
            throw CborException("value is not byte-identical to its canonical encoding")
        }
        return value
    }

    private fun readValue(data: ByteArray, pos: IntArray): Any {
        if (pos[0] >= data.size) throw CborException("unexpected end of input")
        val head = data[pos[0]].toInt() and 0xff
        pos[0]++
        val major = head shr 5
        val info = head and 0x1f

        return when (major) {
            MAJOR_UINT -> readArg(data, pos, info)
            MAJOR_NINT -> -1L - readArg(data, pos, info)
            MAJOR_BYTES -> {
                val len = readArg(data, pos, info).toInt()
                if (pos[0] + len > data.size) throw CborException("truncated byte string")
                val b = data.copyOfRange(pos[0], pos[0] + len)
                pos[0] += len
                b
            }
            MAJOR_TEXT -> {
                val len = readArg(data, pos, info).toInt()
                if (pos[0] + len > data.size) throw CborException("truncated text string")
                val raw = data.copyOfRange(pos[0], pos[0] + len)
                pos[0] += len
                try {
                    raw.toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    throw CborException("invalid UTF-8 in text string")
                }
            }
            MAJOR_ARRAY -> {
                val count = readArg(data, pos, info).toInt()
                val items = ArrayList<Any>(count)
                repeat(count) { items.add(readValue(data, pos)) }
                items
            }
            MAJOR_MAP -> {
                val count = readArg(data, pos, info).toInt()
                val map = LinkedHashMap<Any, Any>(count)
                var prevKeyEnc: ByteArray? = null
                repeat(count) {
                    val key = readValue(data, pos)
                    val keyEnc = encodeKey(key)
                    if (prevKeyEnc != null) {
                        val c = keyEnc.size - prevKeyEnc.size
                        if (c == 0 && compareBytes(keyEnc, prevKeyEnc) == 0) {
                            throw CborException("duplicate map key")
                        }
                        if (c < 0 || (c == 0 && compareBytes(keyEnc, prevKeyEnc) < 0)) {
                            throw CborException("map keys out of deterministic order")
                        }
                    }
                    prevKeyEnc = keyEnc
                    if (map.containsKey(key)) throw CborException("duplicate map key")
                    map[key] = readValue(data, pos)
                }
                map
            }
            MAJOR_TAG -> throw CborException("tags are not allowed")
            MAJOR_SIMPLE -> throw CborException("floats/simple values are not allowed")
            else -> throw CborException("unsupported major type: $major")
        }
    }

    private fun readArg(data: ByteArray, pos: IntArray, info: Int): Long {
        if (info < 24) return info.toLong()
        return when (info) {
            24 -> {
                requireLen(data, pos, 1)
                val v = (data[pos[0]].toInt() and 0xff).toLong(); pos[0]++
                if (v < 24) throw CborException("non-shortest uint8")
                v
            }
            25 -> {
                requireLen(data, pos, 2)
                val v = ((data[pos[0]].toInt() and 0xff) shl 8 or (data[pos[0] + 1].toInt() and 0xff)).toLong()
                pos[0] += 2
                if (v < 0x100) throw CborException("non-shortest uint16")
                v
            }
            26 -> {
                requireLen(data, pos, 4)
                var v = 0L
                for (i in 0 until 4) v = (v shl 8) or (data[pos[0] + i].toInt() and 0xff).toLong()
                pos[0] += 4
                if (v < 0x10000) throw CborException("non-shortest uint32")
                v
            }
            27 -> {
                requireLen(data, pos, 8)
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (data[pos[0] + i].toInt() and 0xff).toLong()
                pos[0] += 8
                if (v < 0x100000000L) throw CborException("non-shortest uint64")
                v
            }
            31 -> throw CborException("indefinite-length items are not allowed")
            else -> throw CborException("bad additional info: $info")
        }
    }

    private fun requireLen(data: ByteArray, pos: IntArray, n: Int) {
        if (pos[0] + n > data.size) throw CborException("truncated argument")
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}
