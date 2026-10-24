package org.blackoutrelay.app

/** Minimal hex helpers (Android minSdk 26 has no java.util.HexFormat). */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02x".format(b.toInt() and 0xff))
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
