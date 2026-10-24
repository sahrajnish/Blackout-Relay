package org.blackoutrelay.crypto

import org.junit.jupiter.api.Test
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the JVM crypto against the frozen M0 vectors (SPEC.md §23.5),
 * so `core-crypto` is byte-identical to the Python reference.
 */
class VectorsTest {

    private val hex = HexFormat.of()

    private fun h(s: String) = hex.parseHex(s)

    // V1
    private val ed25519Seed = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val x25519Seed = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    private val v1Ed25519Pk = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
    private val v1X25519Pk = "5730800ab340fcb18ce5111eda9d705f91388b41e4544cbd103ba5942db2233e"

    // V2
    private val v2Key = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
    private val v2Header = "808182838485868788898a8b8c8d8e8f9091929394959697"
    private val v2Plaintext = "424c41434b4f55542d52454c41592073656372657473747265616d207465737420" +
            "766563746f722076312e30202d2d20666978656420706c61696e7465787420666f7220" +
            "63726f73732d696d706c656d656e746174696f6e20636865636b696e672e"
    private val v2CipherChunk1 = "797e261cb2629773104b2bc60de69d4580f537f839c23e497008d49175e2561c7c" +
            "65018793d0dea9ffad12414d3ab2d186e0e9fb623c1abfef"
    private val v2CipherChunk2 = "18570b2d074acaecc1fc9a08cd92b0e8e9cce5f3b45407859dbec59e92866d8210" +
            "20693e45de324dec6bc2e2c41b620c93b4bea3690e79f72ebb301d50fa1383bafb057d" +
            "17bc8367d83966"

    // V4 (sign the V3 sig-input bytes with the account key)
    private val v3SigInput = "b81801010250b0b1b2b3b4b5b6b7b8b9babbbcbdbebf03582003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b80458206298ec5444955957d630c84103544a3522811b11c61beef464fb73b25c5229660558204a43d32567af14f320365379972274e70910d324c2331ee21538ea0c1ff6ab570658202b24d5590d56d617c94cdba62dba457cce21174a25fcb8255246e7f4f607bdf8075820d587f7fe24544a03b492c09b80ab907d54f85ac88d9175822d616aef3451239c08582090f4aa087f268b627167779f910438f7c8b63f65214dd3692543758321bb8ce7095850e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f0a000b1a6553f1010c19a4100d1a07bb40d90e1a2e491bea0f181910186411011201135056475aa75463474c0285df5dbf2bcab714a301785b68747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f6d616e69666573742e63626f7202785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f7468756d622e656e6303785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f70726f78792e656e63151a655d2b80161818170118181a6557e581"
    private val v4Signature = "f9723d6706c0144cba2e102e11733cfa201d8963acdcda346d189a9016f67ba15dff" +
            "90326015ed0c35f648d199d46fb0ccc07c93050f15fe6d2a404ee7ab170a"

    @Test
    fun `V1 identity keypairs match frozen vectors`() {
        val (edPk, edSk) = Crypto.ed25519Keypair(h(ed25519Seed))
        val (xPk, _) = Crypto.x25519Keypair(h(x25519Seed))
        assertContentEquals(h(v1Ed25519Pk), edPk, "ed25519 pk")
        assertContentEquals(h(v1X25519Pk), xPk, "x25519 pk")
        assertEquals(64, edSk.size)
    }

    @Test
    fun `V2 secretstream matches frozen ciphertext`() {
        val pt = h(v2Plaintext)
        val chunk1 = pt.copyOfRange(0, 40)
        val chunk2 = pt.copyOfRange(40, pt.size)
        val s = SecretStream.forHeader(h(v2Key), h(v2Header))
        val c1 = s.push(chunk1, Crypto.TAG_MESSAGE)
        val c2 = s.push(chunk2, Crypto.TAG_FINAL)
        assertContentEquals(h(v2CipherChunk1), c1, "chunk1")
        assertContentEquals(h(v2CipherChunk2), c2, "chunk2")

        // Round-trip decryption.
        val d = SecretStream.forHeader(h(v2Key), h(v2Header))
        val (p1, t1) = d.pull(c1)
        val (p2, t2) = d.pull(c2)
        assertContentEquals(chunk1, p1)
        assertContentEquals(chunk2, p2)
        assertEquals(Crypto.TAG_MESSAGE, t1)
        assertEquals(Crypto.TAG_FINAL, t2)
    }

    @Test
    fun `V4 signature over v3 sig-input matches frozen vector`() {
        val (edPk, edSk) = Crypto.ed25519Keypair(h(ed25519Seed))
        assertContentEquals(h(v1Ed25519Pk), edPk)
        val sig = Crypto.sign(h(v3SigInput), edSk)
        assertContentEquals(h(v4Signature), sig)
        assertTrue(Crypto.verify(sig, h(v3SigInput), edPk))
    }

    @Test
    fun `box_seal round-trips and rejects a wrong key`() {
        val (pk, sk) = Crypto.x25519Keypair(h(x25519Seed))
        val sealed = Crypto.boxSeal("blackout relay seal test".toByteArray(), pk)
        assertEquals(48 + "blackout relay seal test".length, sealed.size)
        assertContentEquals("blackout relay seal test".toByteArray(), Crypto.boxSealOpen(sealed, pk, sk))

        val (pk2, sk2) = Crypto.x25519Keypair(h("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"))
        val failed = runCatching { Crypto.boxSealOpen(sealed, pk2, sk2) }.isFailure
        assertTrue(failed, "wrong key must be rejected")
    }
}
