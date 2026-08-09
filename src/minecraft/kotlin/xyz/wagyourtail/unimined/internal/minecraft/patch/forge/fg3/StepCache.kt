package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Shared fingerprint helpers for the FG3 step-level caches (both the mcp_config pipeline
 * and the genSources private-step cache). Keeping these in one place so every fingerprint
 * uses the same canonical hex encoding.
 */

internal fun sha256Hex(file: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun sha256Hex(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Fingerprint of a list of input files (absolute path + sha256 per file, one per line).
 * Order matters; callers must pass a stable order.
 */
internal fun fileFingerprint(inputs: List<Path>): String =
    buildString {
        for (input in inputs) {
            append(input.toAbsolutePath()).append('=').append(sha256Hex(input)).append('\n')
        }
    }
