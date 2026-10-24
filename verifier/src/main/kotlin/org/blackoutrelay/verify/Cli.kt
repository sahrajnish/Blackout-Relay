package org.blackoutrelay.verify

import org.blackoutrelay.crypto.Crypto
import org.blackoutrelay.crypto.SecretStream
import org.blackoutrelay.model.Manifest
import java.io.File
import java.util.HexFormat
import kotlin.system.exitProcess

/**
 * relay-verify CLI (SPEC.md §17). Offline only: no network calls whatsoever.
 *
 * Commands:
 *   verify  <dir>  --identity <json> [--revocations <file>] [--json]
 *   inspect <dir>
 *   thumb   <dir>  --identity <json> --out <file>
 *   selftest
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) { usage(); exitProcess(3) }
    when (args[0]) {
        "verify" -> cmdVerify(args.drop(1))
        "inspect" -> cmdInspect(args.drop(1))
        "thumb" -> cmdThumb(args.drop(1))
        "selftest" -> cmdSelftest()
        else -> { usage(); exitProcess(3) }
    }
}

private fun usage() {
    println(
        """
        relay-verify ${Verifier.TOOL_VERSION}
        usage:
          relay-verify verify <dir> --identity <global.json> [--revocations <file>] [--json]
          relay-verify inspect <dir>
          relay-verify thumb <dir> --identity <global.json> --out <file>
          relay-verify selftest
        """.trimIndent()
    )
}

private fun opt(args: List<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private val hex = HexFormat.of()

private fun cmdVerify(args: List<String>) {
    if (args.isEmpty()) { usage(); exitProcess(3) }
    val dir = File(args[0])
    val identityFile = opt(args, "--identity")
    val revocationsFile = opt(args, "--revocations")
    val asJson = args.contains("--json")
    if (identityFile == null) { System.err.println("--identity required"); exitProcess(3) }

    val bundle = try { Bundle.fromDirectory(dir) } catch (e: Exception) {
        System.err.println("INVALID: cannot parse bundle: ${e.message}"); exitProcess(1)
    }
    val identity = try { Identity.fromJson(File(identityFile).readText()) } catch (e: Exception) {
        System.err.println("INVALID: cannot parse identity: ${e.message}"); exitProcess(1)
    }
    val revocations = revocationsFile?.let { File(it).readLines().map { l -> l.trim() }.toSet() }

    val verdict = Verifier.verify(bundle, identity, System.currentTimeMillis() / 1000, revocations)

    if (asJson) println(Verifier.verdictToJson(verdict)) else printHuman(verdict)

    exitProcess(
        when (verdict.verdict) {
            "VALID" -> 0
            "VALID_WITH_WARNINGS" -> 2
            else -> 1
        }
    )
}

private fun cmdInspect(args: List<String>) {
    if (args.isEmpty()) { usage(); exitProcess(3) }
    val bundle = try { Bundle.fromDirectory(File(args[0])) } catch (e: Exception) {
        System.err.println("cannot parse bundle: ${e.message}"); exitProcess(1)
    }
    val m = bundle.manifest
    fun h(k: Long) = (m[k] as? ByteArray)?.let { hex.formatHex(it) } ?: "?"
    val accountPk = m[Manifest.ACCOUNT_PK] as? ByteArray
    val accountId = accountPk?.let { hex.formatHex(Crypto.sha256(it).copyOfRange(0, 16)) } ?: "?"
    println("bundle_id    = ${bundle.bundleId}")
    println("account_id   = $accountId")
    println("created_at   = ${m[Manifest.CREATED_AT]}")
    println("content_hash = ${h(Manifest.CONTENT_HASH)}")
    println("proxy_hash   = ${h(Manifest.PROXY_HASH)}")
    println("thumb_hash   = ${h(Manifest.THUMB_HASH)}")
}

private fun cmdThumb(args: List<String>) {
    if (args.isEmpty()) { usage(); exitProcess(3) }
    val identityFile = opt(args, "--identity") ?: run { System.err.println("--identity required"); exitProcess(3) }
    val out = opt(args, "--out") ?: run { System.err.println("--out required"); exitProcess(3) }
    val bundle = Bundle.fromDirectory(File(args[0]))
    val identity = Identity.fromJson(File(identityFile).readText())
    val thumbEnc = bundle.thumbEnc ?: run { System.err.println("no thumb.enc"); exitProcess(1) }
    val key = Crypto.boxSealOpen(
        bundle.manifest[Manifest.SEALED_KEY] as ByteArray,
        identity.x25519Pk, identity.x25519Sk)
    val header = thumbEnc.copyOfRange(0, Crypto.SECRETSTREAM_HEADERBYTES)
    val ciphertext = thumbEnc.copyOfRange(Crypto.SECRETSTREAM_HEADERBYTES, thumbEnc.size)
    val (plain, tag) = SecretStream.forHeader(key, header).pull(ciphertext)
    check(tag == Crypto.TAG_FINAL) { "not a final chunk" }
    File(out).writeBytes(plain)
    println("wrote thumbnail (${plain.size} bytes) to $out")
}

private fun cmdSelftest() {
    val dir = File.createTempFile("relay-verify", ".d").let { it.delete(); it }
    val now = System.currentTimeMillis() / 1000
    val seed = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    val identity = SealDemo.seal(
        dir,
        originalPlain = "BLACKOUT-RELAY demo original content".toByteArray(),
        proxyPlain = "BLACKOUT-RELAY demo proxy content".toByteArray(),
        thumbPlain = "BLACKOUT-RELAY demo thumbnail content".toByteArray(),
        createdAt = now, latE7 = 129712345, lonE7 = 776543210,
        edSeed = hex.parseHex(seed),
        xSeed = hex.parseHex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"),
        bundleId = ByteArray(16) { (0xB0 + it).toByte() },
    )
    val verdict = Verifier.verify(Bundle.fromDirectory(dir), identity, now, null)
    printHuman(verdict)
    println()
    if (verdict.verdict == "VALID") {
        println("selftest OK: sealed a bundle and verified the full chain.")
        dir.deleteRecursively()
    } else {
        System.err.println("selftest FAILED")
        exitProcess(1)
    }
}

private fun printHuman(v: Verdict) {
    println("verdict: ${v.verdict}")
    for (c in v.checks) {
        val mark = if (c.ok) "ok  " else "FAIL"
        println("  [$mark] ${c.id}${if (c.detail.isNotEmpty()) " — ${c.detail}" else ""}")
    }
    if (v.warnings.isNotEmpty()) {
        println("warnings:")
        v.warnings.forEach { println("  - $it") }
    }
    println("bundle_id:  ${v.bundleId}")
    println("account_id: ${v.accountId}")
}
