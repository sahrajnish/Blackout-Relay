package org.blackoutrelay.verify

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.HexFormat
import kotlin.test.assertEquals

/**
 * End-to-end: seal a bundle with the real construction (§10), then run the full
 * reviewer chain (§16.2) and confirm VALID; tamper and confirm INVALID.
 */
class VerifierTest {

    private val hex = HexFormat.of()
    private val seed = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val xSeed = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    private val now = 1_700_000_000L

    private fun seal(dir: File): Identity = SealDemo.seal(
        dir,
        originalPlain = "BLACKOUT-RELAY demo original content".toByteArray(),
        proxyPlain = "BLACKOUT-RELAY demo proxy content".toByteArray(),
        thumbPlain = "BLACKOUT-RELAY demo thumbnail content".toByteArray(),
        createdAt = now, latE7 = 129712345, lonE7 = 776543210,
        edSeed = hex.parseHex(seed), xSeed = hex.parseHex(xSeed),
        bundleId = ByteArray(16) { (0xB0 + it).toByte() },
    )

    @Test
    fun `sealed bundle verifies VALID`(@TempDir dir: File) {
        val identity = seal(dir)
        val verdict = Verifier.verify(Bundle.fromDirectory(dir), identity, now + 3600, null)
        assertEquals("VALID", verdict.verdict, verdict.checks.toString())
    }

    @Test
    fun `tampered proxy ciphertext is INVALID`(@TempDir dir: File) {
        val identity = seal(dir)
        val f = File(dir, "proxy.enc")
        val b = f.readBytes()
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        f.writeBytes(b)
        val verdict = Verifier.verify(Bundle.fromDirectory(dir), identity, now + 3600, null)
        assertEquals("INVALID", verdict.verdict)
    }

    @Test
    fun `tampered manifest signature is INVALID`(@TempDir dir: File) {
        val identity = seal(dir)
        val f = File(dir, "manifest.cbor")
        val b = f.readBytes()
        // Flip a byte inside the trailing signature field (field 25).
        b[b.size - 10] = (b[b.size - 10].toInt() xor 0xFF).toByte()
        f.writeBytes(b)
        val verdict = Verifier.verify(Bundle.fromDirectory(dir), identity, now + 3600, null)
        assertEquals("INVALID", verdict.verdict)
    }

    @Test
    fun `wrong decryption key is INVALID`(@TempDir dir: File) {
        val identity = seal(dir)
        // An identity whose X25519 private key does not match the recipient key
        // cannot open the sealed key, so decryption fails.
        val otherSeed = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
        val (_, wrongXSk) = org.blackoutrelay.crypto.Crypto.x25519Keypair(hex.parseHex(otherSeed))
        val wrong = identity.copy(x25519Sk = wrongXSk)
        val verdict = Verifier.verify(Bundle.fromDirectory(dir), wrong, now + 3600, null)
        assertEquals("INVALID", verdict.verdict)
    }

    @Test
    fun `expired bundle is VALID_WITH_WARNINGS`(@TempDir dir: File) {
        val identity = seal(dir)
        val verdict = Verifier.verify(Bundle.fromDirectory(dir), identity, now + 100 * 3600, null)
        assertEquals("VALID_WITH_WARNINGS", verdict.verdict)
        assertEquals(true, verdict.warnings.isNotEmpty())
    }

    @Test
    fun `revoked account is INVALID`(@TempDir dir: File) {
        val identity = seal(dir)
        val accountId = HexFormat.of().formatHex(
            org.blackoutrelay.crypto.Crypto.sha256(identity.ed25519Pk).copyOfRange(0, 16))
        val verdict = Verifier.verify(Bundle.fromDirectory(dir), identity, now + 3600, setOf(accountId))
        assertEquals("INVALID", verdict.verdict)
    }
}
