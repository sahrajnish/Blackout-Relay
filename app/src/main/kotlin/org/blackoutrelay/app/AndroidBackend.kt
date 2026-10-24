package org.blackoutrelay.app

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.SecretStream
import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.crypto.SodiumBackend
import java.security.MessageDigest

/**
 * Android crypto backend over lazysodium-android (the spec's choice, §8). The
 * `Native` API is byte[]-based and calls the same libsodium C functions as the
 * JVM `JnaBackend`, so the format is identical across platforms.
 */
class AndroidBackend : SodiumBackend {

    private val s = LazySodiumAndroid(SodiumAndroid())

    override fun ed25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val pk = ByteArray(32)
        val sk = ByteArray(64)
        check(s.cryptoSignSeedKeypair(pk, sk, seed)) { "crypto_sign_seed_keypair failed" }
        return pk to sk
    }

    override fun x25519Keypair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        check(s.cryptoBoxSeedKeypair(pk, sk, seed)) { "crypto_box_seed_keypair failed" }
        return pk to sk
    }

    override fun sign(message: ByteArray, sk: ByteArray): ByteArray {
        val sig = ByteArray(64)
        check(s.cryptoSignDetached(sig, message, message.size.toLong(), sk)) { "sign failed" }
        return sig
    }

    override fun verify(sig: ByteArray, message: ByteArray, pk: ByteArray): Boolean =
        s.cryptoSignVerifyDetached(sig, message, message.size, pk)

    override fun boxSeal(message: ByteArray, pk: ByteArray): ByteArray {
        val c = ByteArray(Crypto.BOX_SEALBYTES + message.size)
        check(s.cryptoBoxSeal(c, message, message.size.toLong(), pk)) { "box_seal failed" }
        return c
    }

    override fun boxSealOpen(ciphertext: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray {
        val m = ByteArray(ciphertext.size - Crypto.BOX_SEALBYTES)
        check(s.cryptoBoxSealOpen(m, ciphertext, ciphertext.size.toLong(), pk, sk)) {
            "box_seal_open failed"
        }
        return m
    }

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun secretStreamSeal(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val state = SecretStream.State()
        val header = ByteArray(Crypto.SECRETSTREAM_HEADERBYTES)
        check(s.cryptoSecretStreamInitPush(state, header, key)) { "init_push failed" }
        val c = ByteArray(plaintext.size + Crypto.SECRETSTREAM_ABYTES)
        val clen = longArrayOf(0)
        check(s.cryptoSecretStreamPush(state, c, clen, plaintext, plaintext.size.toLong(),
            Crypto.TAG_FINAL)) { "secretstream push failed" }
        return header to c.copyOf(clen[0].toInt())
    }

    override fun secretStreamOpen(key: ByteArray, header: ByteArray, ciphertext: ByteArray): ByteArray {
        val state = SecretStream.State()
        check(s.cryptoSecretStreamInitPull(state, header, key)) { "init_pull failed" }
        val m = ByteArray(ciphertext.size)
        val mlen = longArrayOf(0)
        val tag = ByteArray(1)
        val ad = ByteArray(1)
        check(s.cryptoSecretStreamPull(state, m, mlen, tag, ciphertext,
            ciphertext.size.toLong(), ad, 0L)) { "secretstream pull failed" }
        check(tag[0] == Crypto.TAG_FINAL) { "not a final chunk" }
        return m.copyOf(mlen[0].toInt())
    }
}
