package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * `uuid_generate` — generate UUIDs (v4 random / v7 time-ordered).
 *
 * Why: the model CANNOT invent UUIDs (they look plausible and collide).
 * Whenever a workflow needs identifiers — session keys, request ids,
 * file names, test fixtures — the model should draw them here instead of
 * hallucinating. v7 (timestamp-prefixed, sortable) is included because
 * it's become the default choice for database keys and log correlation.
 *
 * Security note: v4 uses [SecureRandom] — safe for session tokens / CSRF
 * nonces, not just for uniqueness.
 */
class UuidGenerateTool : BaseTool(
    id = "uuid_generate",
    name = "UUID Generator",
    description = """
        Generate UUIDs: version 4 (random, SecureRandom) or version 7 (time-ordered).
        Input: {"count": 1, "version": "v4", "uppercase": false, "hyphens": true}
        v7 UUIDs sort chronologically (good for keys/ids); v4 are fully random.
        Output: one UUID per line (max 100 per call).
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer("count", description = "How many UUIDs (default 1, max 100)", minimum = 1.0, maximum = 100.0)
        string("version", description = "v4 (random) or v7 (time-ordered, default v4)", enumValues = listOf("v4", "v7"))
        boolean("uppercase", description = "Uppercase output (default false)")
        boolean("hyphens", description = "Include hyphens (default true)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("uuid", "id", "random", "generate")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val count = args.intWithDefault("count", 1).coerceIn(1, MAX_COUNT)
        val version = args.stringWithDefault("version", "v4")
        val uppercase = args.booleanWithDefault("uppercase", false)
        val hyphens = args.booleanWithDefault("hyphens", true)

        if (version !in setOf("v4", "v7")) {
            return ToolResult.invalid("version", "unknown version '$version'", "use v4 or v7")
        }

        val uuids = (0 until count).map {
            if (version == "v4") UUID.randomUUID() else uuidV7()
        }
        var rendered = uuids.joinToString("\n") { it.toString() }
        if (!hyphens) rendered = rendered.replace("-", "")
        if (uppercase) rendered = rendered.uppercase()
        return ToolResult.ok(rendered)
    }

    /**
     * UUIDv7: 48-bit big-endian Unix-millis prefix + 12 random bits
     * (version) + 62 random bits (variant + entropy). Sortable by time,
     * collision-resistant across processes (SecureRandom entropy).
     */
    private fun uuidV7(): UUID {
        val timestamp = System.currentTimeMillis()
        val random = ByteArray(10).also { SECURE_RANDOM.nextBytes(it) }

        // 16 bytes, big-endian layout:
        //  [0..5]   48-bit timestamp (ms)
        //  [6]      version nibble (0b0111) + 12 random bits across [6..7]
        //  [8]      variant (0b10) + 6 random bits
        //  [9..15]  random
        val bytes = ByteArray(16)
        for (i in 0..5) {
            bytes[i] = ((timestamp shr (8 * (5 - i))) and 0xFF).toByte()
        }
        bytes[6] = ((0x07 shl 4) or (random[0].toInt() and 0x0F)).toByte()
        bytes[7] = random[1]
        bytes[8] = ((0x02 shl 6) or (random[2].toInt() and 0x3F)).toByte()
        System.arraycopy(random, 3, bytes, 9, 7)

        // bytes → two Longs (RFC 4122 bit layout).
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8..15) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }

    private companion object {
        const val MAX_COUNT = 100
        val SECURE_RANDOM = SecureRandom()
    }
}

/**
 * `file_hash` — streaming hash of a file inside the workspace sandbox.
 *
 * Why: integrity verification ("did this file actually change?", "is the
 * download complete?") without shelling out to `md5sum` — which needs the
 * command gate and isn't available in every execution context. Hashing is
 * a pure read; it should be as cheap as reading a file.
 *
 * Design notes:
 * - **Streaming** — 64 KB chunks via [MessageDigest.update]; the file is
 *   never fully loaded, so multi-hundred-MB media files hash fine.
 * - **Sandbox** — same normalization/traversal rules as the other file
 *   tools ([resolveInRoot]): absolute paths are treated as relative to the
 *   workspace root; `..` escapes are rejected with SANDBOX_VIOLATION.
 * - **Output** — all requested algorithms (up to all four) in one pass,
 *   plus size and mtime, so the model can cite "sha256 + size" pairs.
 */
class FileHashTool(
    /** Workspace root the sandbox enforces (app injects filesDir/workspace). */
    private val rootDir: File
) : BaseTool(
    id = "file_hash",
    name = "File Hash",
    description = """
        Compute MD5/SHA-1/SHA-256/SHA-512 hashes of a workspace file (streaming, one pass for all).
        Input: {"path": "notes/data.json", "algorithm": "sha256"}
        algorithm: md5 | sha1 | sha256 | sha512 | all (default sha256).
        Path is sandboxed to the workspace; traversal (../) is rejected.
        Output: <hex digest>  <path>  (<size> bytes, <algo>)
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true, description = "File path inside the workspace sandbox")
        string("algorithm", description = "md5 | sha1 | sha256 | sha512 | all (default sha256)", enumValues = listOf("md5", "sha1", "sha256", "sha512", "all"))
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("hash", "file", "md5", "sha256", "integrity")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val requested = args.stringWithDefault("algorithm", "sha256").lowercase()
        if (requested !in ALGORITHMS) {
            return ToolResult.invalid("algorithm", "unknown algorithm '$requested'", "use ${ALGORITHMS.joinToString("|")}")
        }

        val path = args.requireString("path")
        val target = resolveInRoot(rootDir, path)
            ?: return ToolResult.sandbox("path escapes the workspace sandbox: '$path'")

        if (!target.exists()) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "no such file: '${target.relativeToOrSelf(rootDir).path}'")
        }
        if (target.isDirectory) {
            return ToolResult.invalid("path", "'$path' is a directory (hash files, not directories)", "list its files first, then hash each")
        }

        val algorithmsToRun = if (requested == "all") listOf("md5", "sha1", "sha256", "sha512") else listOf(requested)
        val digests = algorithmsToRun.associateWith { MessageDigest.getInstance(it) }

        try {
            target.inputStream().use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digests.values.forEach { it.update(buffer, 0, read) }
                }
            }
        } catch (e: java.io.IOException) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "cannot read '$path': ${e.message}")
        }

        val size = target.length()
        return ToolResult.ok(
            buildString {
                digests.forEach { (name, digest) ->
                    appendLine("${toHex(digest.digest())}  $path  ($size bytes, $name)")
                }
            }.trimEnd()
        )
    }

    private fun toHex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            bytes.forEach { b ->
                append(HEX[(b.toInt() shr 4) and 0x0F])
                append(HEX[b.toInt() and 0x0F])
            }
        }

    /**
     * Sandbox resolution: normalize the path, reject `..` escapes, resolve
     * against [root]. Mirrors the other file tools' contract: absolute
     * paths lose their leading separator and land inside the root.
     */
    private fun resolveInRoot(root: File, path: String): File? {
        val normalized = path.trim().removePrefix("/").replace('\\', '/')
        if (normalized.isBlank()) return null
        val resolved = File(root, normalized).canonicalFile
        val rootCanonical = root.canonicalFile
        if (!resolved.startsWith(rootCanonical)) return null
        return resolved
    }

    private companion object {
        val ALGORITHMS = setOf("md5", "sha1", "sha256", "sha512", "all")
        const val BUFFER_SIZE = 64 * 1024
        const val HEX = "0123456789abcdef"
    }
}
