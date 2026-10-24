package org.blackoutrelay.crypto

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.LongByReference

/**
 * JNA binding to the system libsodium (SPEC.md §8).
 *
 * The spec locks every construction to libsodium; this is the JVM counterpart
 * to `reference/sodium.py`. On Android, `lazysodium-android` provides the same
 * symbols; the constructions must remain identical.
 */
interface Libsodium : Library {

    fun sodium_init(): Int
    fun crypto_sign_seed_keypair(pk: ByteArray, sk: ByteArray, seed: ByteArray): Int
    fun crypto_sign_detached(sig: ByteArray, siglen: LongByReference, m: ByteArray, mlen: Long, sk: ByteArray): Int
    fun crypto_sign_verify_detached(sig: ByteArray, m: ByteArray, mlen: Long, pk: ByteArray): Int
    fun crypto_box_seed_keypair(pk: ByteArray, sk: ByteArray, seed: ByteArray): Int
    fun crypto_box_seal(c: ByteArray, m: ByteArray, mlen: Long, pk: ByteArray): Int
    fun crypto_box_seal_open(m: ByteArray, c: ByteArray, clen: Long, pk: ByteArray, sk: ByteArray): Int
    fun crypto_secretstream_xchacha20poly1305_init_pull(state: ByteArray, header: ByteArray, key: ByteArray): Int
    fun crypto_secretstream_xchacha20poly1305_init_push(state: ByteArray, header: ByteArray, key: ByteArray): Int
    fun crypto_secretstream_xchacha20poly1305_push(
        state: ByteArray, c: ByteArray, clen: LongByReference, m: ByteArray, mlen: Long,
        ad: ByteArray, adlen: Long, tag: Byte
    ): Int

    fun crypto_secretstream_xchacha20poly1305_pull(
        state: ByteArray, m: ByteArray, mlen: LongByReference, tag: ByteByReference,
        c: ByteArray, clen: Long, ad: ByteArray, adlen: Long
    ): Int

    fun crypto_secretstream_xchacha20poly1305_statebytes(): Long

    companion object {
        val INSTANCE: Libsodium by lazy { load() }

        private fun load(): Libsodium {
            val candidates = listOf(
                "libsodium.so.23",
                "sodium",
                "/lib/x86_64-linux-gnu/libsodium.so.23",
                "/usr/lib/x86_64-linux-gnu/libsodium.so.23",
            )
            var last: UnsatisfiedLinkError? = null
            for (name in candidates) {
                try {
                    val lib = Native.load(name, Libsodium::class.java)
                    if (lib.sodium_init() >= 0) return lib
                } catch (e: UnsatisfiedLinkError) {
                    last = e
                }
            }
            throw UnsatisfiedLinkError("libsodium not found: $last")
        }
    }
}
