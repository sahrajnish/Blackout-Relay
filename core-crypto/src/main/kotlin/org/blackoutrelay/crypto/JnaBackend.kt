package org.blackoutrelay.crypto

import com.sun.jna.ptr.LongByReference
import java.security.MessageDigest

/**
 * JVM backend: system libsodium via JNA. `Libsodium.INSTANCE` is loaded lazily,
 * so constructing this class on Android (where the system libsodium is absent)
 * is harmless as long as the app installs [Crypto.backend] before use.
 */
class JnaBackend : SodiumBackend {

    private val lib: Libsodium get() = Libsodium.INSTANCE

    override fun ed25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray> = seedKeypair(true, seed)

    override fun x25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray> = seedKeypair(false, seed)

    private fun seedKeypair(sign: Boolean, seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == Crypto.SIGN_SEEDBYTES) { "seed must be 32 bytes" }
        val pk = ByteArray(Crypto.SIGN_PUBLICKEYBYTES)
        val sk = ByteArray(if (sign) Crypto.SIGN_SECRETKEYBYTES else Crypto.BOX_SECRETKEYBYTES)
        val rc = if (sign) lib.crypto_sign_seed_keypair(pk, sk, seed)
                 else lib.crypto_box_seed_keypair(pk, sk, seed)
        check(rc == 0) { "seed keypair failed" }
        return pk to sk
    }

    override fun sign(message: ByteArray, sk: ByteArray): ByteArray {
        require(sk.size == Crypto.SIGN_SECRETKEYBYTES) { "Ed25519 secret key must be 64 bytes" }
        val sig = ByteArray(Crypto.SIGN_BYTES)
        val siglen = LongByReference()
        check(lib.crypto_sign_detached(sig, siglen, message, message.size.toLong(), sk) == 0)
        check(siglen.value == Crypto.SIGN_BYTES.toLong())
        return sig
    }

    override fun verify(sig: ByteArray, message: ByteArray, pk: ByteArray): Boolean =
        lib.crypto_sign_verify_detached(sig, message, message.size.toLong(), pk) == 0

    override fun boxSeal(message: ByteArray, pk: ByteArray): ByteArray {
        require(pk.size == Crypto.BOX_PUBLICKEYBYTES) { "recipient public key must be 32 bytes" }
        val c = ByteArray(Crypto.BOX_SEALBYTES + message.size)
        check(lib.crypto_box_seal(c, message, message.size.toLong(), pk) == 0)
        return c
    }

    override fun boxSealOpen(ciphertext: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray {
        require(ciphertext.size > Crypto.BOX_SEALBYTES) { "ciphertext too short" }
        val m = ByteArray(ciphertext.size - Crypto.BOX_SEALBYTES)
        check(lib.crypto_box_seal_open(m, ciphertext, ciphertext.size.toLong(), pk, sk) == 0) {
            "crypto_box_seal_open failed (bad key or ciphertext)"
        }
        return m
    }

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun secretStreamSeal(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val s = SecretStream.initPush(key)
        val c = s.push(plaintext, Crypto.TAG_FINAL)
        return s.header to c
    }

    override fun secretStreamOpen(key: ByteArray, header: ByteArray, ciphertext: ByteArray): ByteArray {
        val s = SecretStream.forHeader(key, header)
        val (plain, tag) = s.pull(ciphertext)
        check(tag == Crypto.TAG_FINAL) { "stream did not end with a FINAL tag" }
        return plain
    }
}
