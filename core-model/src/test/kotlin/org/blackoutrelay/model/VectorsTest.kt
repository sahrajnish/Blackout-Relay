package org.blackoutrelay.model

import org.blackoutrelay.crypto.Crypto
import org.junit.jupiter.api.Test
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Validates the canonical CBOR codec and manifest/pool builders against the
 * frozen M0 vectors V3 and V5 (SPEC.md §23.5).
 */
class VectorsTest {

    private val hex = HexFormat.of()
    private fun h(s: String) = hex.parseHex(s)

    private val ed25519Seed = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val v3SigInput = "b81801010250b0b1b2b3b4b5b6b7b8b9babbbcbdbebf03582003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b80458206298ec5444955957d630c84103544a3522811b11c61beef464fb73b25c5229660558204a43d32567af14f320365379972274e70910d324c2331ee21538ea0c1ff6ab570658202b24d5590d56d617c94cdba62dba457cce21174a25fcb8255246e7f4f607bdf8075820d587f7fe24544a03b492c09b80ab907d54f85ac88d9175822d616aef3451239c08582090f4aa087f268b627167779f910438f7c8b63f65214dd3692543758321bb8ce7095850e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f0a000b1a6553f1010c19a4100d1a07bb40d90e1a2e491bea0f181910186411011201135056475aa75463474c0285df5dbf2bcab714a301785b68747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f6d616e69666573742e63626f7202785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f7468756d622e656e6303785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f70726f78792e656e63151a655d2b80161818170118181a6557e581"
    private val v4ManifestCbor = "b81901010250b0b1b2b3b4b5b6b7b8b9babbbcbdbebf03582003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b80458206298ec5444955957d630c84103544a3522811b11c61beef464fb73b25c5229660558204a43d32567af14f320365379972274e70910d324c2331ee21538ea0c1ff6ab570658202b24d5590d56d617c94cdba62dba457cce21174a25fcb8255246e7f4f607bdf8075820d587f7fe24544a03b492c09b80ab907d54f85ac88d9175822d616aef3451239c08582090f4aa087f268b627167779f910438f7c8b63f65214dd3692543758321bb8ce7095850e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f0a000b1a6553f1010c19a4100d1a07bb40d90e1a2e491bea0f181910186411011201135056475aa75463474c0285df5dbf2bcab714a301785b68747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f6d616e69666573742e63626f7202785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f7468756d622e656e6303785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f70726f78792e656e63151a655d2b80161818170118181a6557e58118195840f9723d6706c0144cba2e102e11733cfa201d8963acdcda346d189a9016f67ba15dff90326015ed0c35f648d199d46fb0ccc07c93050f15fe6d2a404ee7ab170a"
    private val v5PoolCbor = "a50101025056475aa75463474c0285df5dbf2bcab7031a6553f100041a655d2b800582a3010002a301785b68747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f6d616e69666573742e63626f7202785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f7468756d622e656e6303785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030302f70726f78792e656e63031a655d2b80a3010102a301785b68747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030312f6d616e69666573742e63626f7202785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030312f7468756d622e656e6303785768747470733a2f2f6275636b65742e72322e636c6f7564666c61726573746f726167652e636f6d2f35363437356161373534363334373463303238356466356462663262636162372f303030312f70726f78792e656e63031a655d2b80"

    private fun manifestFields(): Map<Long, Any> {
        val (accountPk, _) = Crypto.ed25519Keypair(h(ed25519Seed))
        val prefix = Identifiers.destPrefix(accountPk)
        val base = "https://bucket.r2.cloudflarestorage.com/${hex.formatHex(prefix)}/0000"
        return mapOf(
            Manifest.V to 1L,
            Manifest.BUNDLE_ID to ByteArray(16) { (0xB0 + it).toByte() },
            Manifest.ACCOUNT_PK to accountPk,
            Manifest.CONTENT_HASH to Crypto.sha256("BLACKOUT-RELAY-V3-content".toByteArray()),
            Manifest.PROXY_HASH to Crypto.sha256("BLACKOUT-RELAY-V3-proxy".toByteArray()),
            Manifest.THUMB_HASH to Crypto.sha256("BLACKOUT-RELAY-V3-thumb".toByteArray()),
            Manifest.PROXY_CIPHER_HASH to Crypto.sha256("BLACKOUT-RELAY-V3-proxy-cipher".toByteArray()),
            Manifest.THUMB_CIPHER_HASH to Crypto.sha256("BLACKOUT-RELAY-V3-thumb-cipher".toByteArray()),
            Manifest.SEALED_KEY to ByteArray(80) { ((0xE0 + it) and 0xFF).toByte() },
            Manifest.KDF_INFO_LEN to 0L,
            Manifest.CREATED_AT to 1700000001L,
            Manifest.DURATION_MS to 42000L,
            Manifest.LAT_E7 to 129712345L,
            Manifest.LON_E7 to 776543210L,
            Manifest.GPS_ACCURACY_M to 25L,
            Manifest.PUBLIC_GPS_PRECISION_M to 100L,
            Manifest.MEDIA_KIND to 1L,
            Manifest.ORIENTATION to 1L,
            Manifest.DEST_PREFIX to prefix,
            Manifest.DEST_URLS to mapOf(
                1L to "$base/manifest.cbor",
                2L to "$base/thumb.enc",
                3L to "$base/proxy.enc",
            ),
            Manifest.DEST_URLS_EXPIRE to 1700604800L,
            Manifest.MAX_HOPS to 24L,
            Manifest.PRIORITY to 1L,
            Manifest.EXPIRES_AT to 1700259201L,
        )
    }

    @Test
    fun `V3 manifest sig-input matches frozen vector`() {
        val sigInput = manifestSigInput(manifestFields())
        assertContentEquals(h(v3SigInput), sigInput)
    }

    @Test
    fun `V4 full manifest matches frozen vector`() {
        val (_, sk) = Crypto.ed25519Keypair(h(ed25519Seed))
        val manifest = buildManifest(manifestFields(), sk)
        assertContentEquals(h(v4ManifestCbor), Cbor.encode(manifest))
    }

    @Test
    fun `V5 pool matches frozen vector`() {
        val (accountPk, _) = Crypto.ed25519Keypair(h(ed25519Seed))
        val prefix = Identifiers.destPrefix(accountPk)
        fun urls(slot: Int) = mapOf(
            1L to "https://bucket.r2.cloudflarestorage.com/${hex.formatHex(prefix)}/${
                slot.toString().padStart(4, '0')}/manifest.cbor",
            2L to "https://bucket.r2.cloudflarestorage.com/${hex.formatHex(prefix)}/${
                slot.toString().padStart(4, '0')}/thumb.enc",
            3L to "https://bucket.r2.cloudflarestorage.com/${hex.formatHex(prefix)}/${
                slot.toString().padStart(4, '0')}/proxy.enc",
        )
        val slots = listOf(buildSlot(0L, urls(0), 1700604800L), buildSlot(1L, urls(1), 1700604800L))
        val pool = buildPool(1L, prefix, 1700000000L, 1700604800L, slots)
        assertContentEquals(h(v5PoolCbor), Cbor.encode(pool))
    }

    @Test
    fun `deterministic CBOR round-trips`() {
        val bytes = h(v4ManifestCbor)
        val decoded = Cbor.decode(bytes)
        assertContentEquals(bytes, Cbor.encode(decoded))
    }

    @Test
    fun `CBOR rejects non-deterministic encodings`() {
        val cases = listOf(
            "1805" to "non-shortest uint",
            "a203040102" to "unsorted map keys",
            "0100" to "trailing bytes",
            "5f4100ff" to "indefinite length",
            "fb3ff0000000000000" to "float",
        )
        for ((hexStr, name) in cases) {
            assertFailsWith<Cbor.CborException>(name) { Cbor.decode(h(hexStr)) }
        }
    }

    @Test
    fun `destination binding`() {
        val (accountPk, _) = Crypto.ed25519Keypair(h(ed25519Seed))
        val prefix = Identifiers.destPrefix(accountPk)
        assertContentEquals(Crypto.sha256(accountPk).copyOfRange(0, 16), prefix)
        val m = Cbor.decode(h(v4ManifestCbor)) as Map<*, *>
        assertContentEquals(prefix, m[Manifest.DEST_PREFIX] as ByteArray)
    }
}
