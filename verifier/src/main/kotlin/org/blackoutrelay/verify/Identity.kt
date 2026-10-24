package org.blackoutrelay.verify

import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.model.Cbor
import org.blackoutrelay.model.Identity as IdentityModel
import java.util.HexFormat

/**
 * A reviewer-held identity: the public identity document plus the private keys
 * needed to decrypt. The document's signature (field 5) binds the X25519 key to
 * the Ed25519 key (SPEC.md §7.3).
 */
data class Identity(
    val ed25519Pk: ByteArray,
    val x25519Pk: ByteArray,
    val createdAt: Long,
    val keyEpoch: Long,
    val identitySig: ByteArray,
    val ed25519Sk: ByteArray,
    val x25519Sk: ByteArray,
) {
    fun verifyIdentitySig(): Boolean {
        val body = Cbor.encode(mapOf(
            IdentityModel.ED25519_PK to ed25519Pk,
            IdentityModel.X25519_PK to x25519Pk,
            IdentityModel.CREATED_AT to createdAt,
            IdentityModel.KEY_EPOCH to keyEpoch,
        ))
        return Crypto.verify(identitySig, body, ed25519Pk)
    }

    companion object {
        private val hex = HexFormat.of()

        fun fromJson(text: String): Identity {
            val obj = (Json.parse(text) as Json.Obj).fields
            fun bytes(key: String) = hex.parseHex((obj[key] as Json.Str).s)
            fun long(key: String) = (obj[key] as Json.Num).v
            return Identity(
                ed25519Pk = bytes("ed25519_pk"),
                x25519Pk = bytes("x25519_pk"),
                createdAt = long("created_at"),
                keyEpoch = long("key_epoch"),
                identitySig = bytes("identity_sig"),
                ed25519Sk = bytes("ed25519_sk"),
                x25519Sk = bytes("x25519_sk"),
            )
        }
    }
}
