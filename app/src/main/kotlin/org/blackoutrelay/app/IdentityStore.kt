package org.blackoutrelay.app

import android.content.Context
import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.model.identitySigInput
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * The device's pseudonymous account identity (SPEC.md §7): Ed25519 + X25519
 * keys plus the signed identity document that binds them. Stored as JSON so it
 * can be handed to a reviewer for offline verification.
 */
object IdentityStore {

    class Identity(
        val edPk: ByteArray,
        val xPk: ByteArray,
        val edSk: ByteArray,
        val xSk: ByteArray,
        val createdAt: Long,
        val keyEpoch: Long,
        val identitySig: ByteArray,
    ) {
        val accountId: ByteArray get() = Crypto.sha256(edPk).copyOfRange(0, 16)
        val accountIdHex: String get() = accountId.toHex()
    }

    private fun jsonFile(c: Context) = File(c.filesDir, "identity.json")
    private fun legacyFile(c: Context) = File(c.filesDir, "identity.bin")

    fun exists(c: Context): Boolean = jsonFile(c).exists() || legacyFile(c).exists()

    fun loadOrCreate(c: Context): Identity {
        val jf = jsonFile(c)
        if (jf.exists()) return parse(jf.readText())

        // Migrate the old 160-byte binary identity if present, so existing
        // bundles (signed by the same key) keep verifying.
        val lf = legacyFile(c)
        val (edPk, xPk, edSk, xSk) = if (lf.exists() && lf.length() == 160L) {
            val b = lf.readBytes()
            Legacy(b.copyOfRange(0, 32), b.copyOfRange(32, 64), b.copyOfRange(64, 128), b.copyOfRange(128, 160))
        } else {
            val rnd = SecureRandom()
            val (ep, es) = Crypto.ed25519Keypair(ByteArray(32).also { rnd.nextBytes(it) })
            val (xp, xs) = Crypto.x25519Keypair(ByteArray(32).also { rnd.nextBytes(it) })
            Legacy(ep, xp, es, xs)
        }

        val id = createDocument(edPk, xPk, edSk, xSk)
        jf.writeText(toJson(id))
        lf.delete()
        return id
    }

    private data class Legacy(
        val edPk: ByteArray, val xPk: ByteArray, val edSk: ByteArray, val xSk: ByteArray,
    )

    private fun createDocument(edPk: ByteArray, xPk: ByteArray, edSk: ByteArray, xSk: ByteArray): Identity {
        val createdAt = System.currentTimeMillis() / 1000
        val keyEpoch = 1L
        val sig = Crypto.sign(identitySigInput(edPk, xPk, createdAt, keyEpoch), edSk)
        return Identity(edPk, xPk, edSk, xSk, createdAt, keyEpoch, sig)
    }

    fun toJson(id: Identity): String = JSONObject().apply {
        put("ed25519_pk", id.edPk.toHex())
        put("x25519_pk", id.xPk.toHex())
        put("created_at", id.createdAt)
        put("key_epoch", id.keyEpoch)
        put("identity_sig", id.identitySig.toHex())
        put("ed25519_sk", id.edSk.toHex())
        put("x25519_sk", id.xSk.toHex())
    }.toString(2)

    private fun parse(text: String): Identity {
        val o = JSONObject(text)
        return Identity(
            edPk = o.getString("ed25519_pk").hexToBytes(),
            xPk = o.getString("x25519_pk").hexToBytes(),
            edSk = o.getString("ed25519_sk").hexToBytes(),
            xSk = o.getString("x25519_sk").hexToBytes(),
            createdAt = o.getLong("created_at"),
            keyEpoch = o.getLong("key_epoch"),
            identitySig = o.getString("identity_sig").hexToBytes(),
        )
    }
}
