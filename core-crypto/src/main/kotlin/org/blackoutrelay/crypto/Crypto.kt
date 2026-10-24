package org.blackoutrelay.crypto

import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.LongByReference

/**
 * The low-level crypto operations, implemented per platform:
 *  - `JnaBackend` (JVM: system libsodium via JNA) — used by the verifier.
 *  - `AndroidBackend` (app: lazysodium-android) — used on device.
 *
 * Both call the same libsodium C functions; only the binding differs.
 */
interface SodiumBackend {
    fun ed25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray>
    fun x25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray>
    fun sign(message: ByteArray, sk: ByteArray): ByteArray
    fun verify(sig: ByteArray, message: ByteArray, pk: ByteArray): Boolean
    fun boxSeal(message: ByteArray, pk: ByteArray): ByteArray
    fun boxSealOpen(ciphertext: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray

    /** Encrypt a whole buffer as one FINAL chunk; returns (header, ciphertext). */
    fun secretStreamSeal(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray>

    /** Decrypt a single FINAL chunk. */
    fun secretStreamOpen(key: ByteArray, header: ByteArray, ciphertext: ByteArray): ByteArray
}

/**
 * High-level crypto API over the active [backend] (SPEC.md §8). Mirrors
 * `reference/sodium.py`. The Android app installs [AndroidBackend] at startup;
 * the JVM verifier uses the default [JnaBackend].
 */
object Crypto {
    const val SIGN_PUBLICKEYBYTES = 32
    const val SIGN_SECRETKEYBYTES = 64
    const val SIGN_SEEDBYTES = 32
    const val SIGN_BYTES = 64

    const val BOX_PUBLICKEYBYTES = 32
    const val BOX_SECRETKEYBYTES = 32
    const val BOX_SEEDBYTES = 32
    const val BOX_SEALBYTES = 48

    const val SECRETSTREAM_KEYBYTES = 32
    const val SECRETSTREAM_HEADERBYTES = 24
    const val SECRETSTREAM_ABYTES = 17

    const val TAG_MESSAGE: Byte = 0
    const val TAG_PUSH: Byte = 1
    const val TAG_REKEY: Byte = 2
    const val TAG_FINAL: Byte = 3

    @Volatile
    var backend: SodiumBackend = JnaBackend()

    fun ed25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray> = backend.ed25519Keypair(seed)
    fun x25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray> = backend.x25519Keypair(seed)
    fun sign(message: ByteArray, sk: ByteArray): ByteArray = backend.sign(message, sk)
    fun verify(sig: ByteArray, message: ByteArray, pk: ByteArray): Boolean =
        backend.verify(sig, message, pk)
    fun boxSeal(message: ByteArray, pk: ByteArray): ByteArray = backend.boxSeal(message, pk)
    fun boxSealOpen(ciphertext: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray =
        backend.boxSealOpen(ciphertext, pk, sk)
    fun sha256(data: ByteArray): ByteArray = backend.sha256(data)
    fun secretStreamSeal(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> =
        backend.secretStreamSeal(key, plaintext)
    fun secretStreamOpen(key: ByteArray, header: ByteArray, ciphertext: ByteArray): ByteArray =
        backend.secretStreamOpen(key, header, ciphertext)
}

/**
 * Deterministic view of `crypto_secretstream_xchacha20poly1305` over the system
 * libsodium (JNA). Used by the JVM test vectors and by [JnaBackend]; the Android
 * backend uses lazysodium's State instead.
 */
class SecretStream private constructor(
    private val state: ByteArray,
    val header: ByteArray,
) {
    companion object {
        private val lib: Libsodium get() = Libsodium.INSTANCE
        private val EMPTY_AD = ByteArray(1)

        fun forHeader(key: ByteArray, header: ByteArray): SecretStream {
            require(key.size == Crypto.SECRETSTREAM_KEYBYTES) { "secretstream key must be 32 bytes" }
            require(header.size == Crypto.SECRETSTREAM_HEADERBYTES) { "secretstream header must be 24 bytes" }
            val state = ByteArray(lib.crypto_secretstream_xchacha20poly1305_statebytes().toInt())
            check(lib.crypto_secretstream_xchacha20poly1305_init_pull(state, header, key) == 0)
            return SecretStream(state, header)
        }

        fun initPush(key: ByteArray): SecretStream {
            require(key.size == Crypto.SECRETSTREAM_KEYBYTES) { "secretstream key must be 32 bytes" }
            val state = ByteArray(lib.crypto_secretstream_xchacha20poly1305_statebytes().toInt())
            val header = ByteArray(Crypto.SECRETSTREAM_HEADERBYTES)
            check(lib.crypto_secretstream_xchacha20poly1305_init_push(state, header, key) == 0)
            return SecretStream(state, header)
        }
    }

    fun push(chunk: ByteArray, tag: Byte = Crypto.TAG_FINAL, ad: ByteArray? = null): ByteArray {
        val adBuf = ad ?: EMPTY_AD
        val adLen = ad?.size?.toLong() ?: 0L
        val c = ByteArray(chunk.size + Crypto.SECRETSTREAM_ABYTES)
        val clen = LongByReference()
        check(lib.crypto_secretstream_xchacha20poly1305_push(
            state, c, clen, chunk, chunk.size.toLong(), adBuf, adLen, tag) == 0)
        return c.copyOf(clen.value.toInt())
    }

    fun pull(ciphertext: ByteArray, ad: ByteArray? = null): Pair<ByteArray, Byte> {
        val adBuf = ad ?: EMPTY_AD
        val adLen = ad?.size?.toLong() ?: 0L
        val m = ByteArray(ciphertext.size)
        val mlen = LongByReference()
        val tag = ByteByReference()
        check(lib.crypto_secretstream_xchacha20poly1305_pull(
            state, m, mlen, tag, ciphertext, ciphertext.size.toLong(), adBuf, adLen) == 0) {
            "secretstream pull failed (corrupt or wrong key)"
        }
        return m.copyOf(mlen.value.toInt()) to tag.value
    }
}
