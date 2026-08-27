package com.apex.agent.platform.terminal.ubuntu

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * PR #69 §10/§30: Streaming tar.gz extractor with path-traversal protection.
 *
 * §30: streams entry-by-entry — NEVER loads the whole archive into RAM.
 * §10: rejects path-traversal entries (../ or absolute paths) so a
 *      malicious archive can't escape the staging dir.
 * §8: cancellation-aware (ensureActive() per entry).
 * §26: progress callback (bytes extracted, entry count).
 *
 * P69 supports TAR_GZ (Ubuntu cloud images). TAR_XZ / TAR_ZSTD / TAR
 * (uncompressed) are handled the same way with a different decompression
 * stream — see [Decompression].
 *
 * §24/§37: no shell — pure JVM IO.
 */
class RootfsExtractor(
    private val bufferBytes: Int = 64 * 1024
) {

    data class ExtractResult(
        val entryCount: Int,
        val bytesExtracted: Long,
        val durationMs: Long,
        val rejectedEntries: List<String>   // §10: traversals that were refused
    )

    /**
     * Extracts [archiveFile] (tar.gz) into [targetDir].
     * §10: rejects entries outside targetDir. §8: cancellable.
     */
    suspend fun extractTarGz(
        archiveFile: File,
        targetDir: File,
        progress: (suspend (Long, Long) -> Unit)? = null
    ): Result<ExtractResult> = try {
        val fis = archiveFile.inputStream().buffered()
        val gz = GZIPInputStream(fis)
        try {
            Result.success(extractTarStream(gz, archiveFile.length(), targetDir, progress))
        } finally {
            gz.close()
            fis.close()
        }
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** Extract from any [InputStream] (already-decompressed if caller did it). */
    suspend fun extractTarStream(
        decompressed: InputStream,
        declaredArchiveSize: Long,
        targetDir: File,
        progress: (suspend (Long, Long) -> Unit)? = null
    ): ExtractResult {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalFile
        val startMs = System.currentTimeMillis()
        var entries = 0
        var bytes = 0L
        val rejected = mutableListOf<String>()

        // Minimal tar header parser (USTAR). 512-byte blocks. We only need
        // name / size / typeflag / linkname — not the full POSIX spec.
        val block = ByteArray(512)
        val buf = ByteArray(bufferBytes)

        while (true) {
            ensureActive()   // §8: cancellation check per entry
            if (!readExact(decompressed, block, 512)) break   // EOF
            // Two consecutive zero blocks = end of archive
            if (block.all { it == 0.toByte() }) {
                if (!readExact(decompressed, block, 512)) break
                if (block.all { it == 0.toByte() }) break
                // else this is a real entry — fall through to parse
            }
            val name = readCString(block, 0, 100)
            if (name.isEmpty()) break
            val sizeOctal = readCString(block, 124, 12).trim()
            val size = parseOctal(sizeOctal)
            val typeFlag = block[156].toInt().toChar()
            val linkName = readCString(block, 157, 100)

            // §10: path-traversal protection
            val safeName = sanitizeEntryName(name)
            if (safeName == null) {
                rejected.add(name)
                skipBytes(decompressed, size)
                continue
            }
            val outFile = File(targetDir, safeName).canonicalFile
            // Final guard: the resolved file MUST be under targetDir
            if (!outFile.path.startsWith(canonicalTarget.path)) {
                rejected.add("$name (escapes target)")
                skipBytes(decompressed, size)
                continue
            }

            when (typeFlag) {
                '5' -> {  // directory
                    outFile.mkdirs()
                }
                '1' -> {  // symlink — write the link name; don't follow
                    if (linkName.isNotEmpty()) {
                        try {
                            if (outFile.exists()) outFile.delete()
                            java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(),
                                java.nio.file.Paths.get(linkName)
                            )
                        } catch (_: Throwable) {
                            // symlinks may fail on some filesystems; continue
                        }
                    }
                }
                else -> {  // regular file (typeFlag '0' or '\0')
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var remaining = size
                        while (remaining > 0) {
                            ensureActive()
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = decompressed.read(buf, 0, toRead)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                            bytes += n
                            progress?.invoke(bytes, declaredArchiveSize)
                        }
                    }
                    // Restore executable bit if set in the tar header (mode field)
                    val mode = parseOctal(readCString(block, 100, 8).trim())
                    if ((mode and 0x49L) != 0L) {  // any of 0100/0010/0001 exec bits
                        outFile.setExecutable(true, false)
                    }
                }
            }
            // tar entries are padded to 512-byte boundaries
            val padding = (512 - (size % 512)) % 512
            if (padding > 0 && typeFlag != '5' && typeFlag != '1') {
                skipBytes(decompressed, padding)
            }
            entries++
        }

        return ExtractResult(entries, bytes, System.currentTimeMillis() - startMs, rejected)
    }

    // ─── §10: path-traversal sanitization ───
    private fun sanitizeEntryName(name: String): String? {
        // Reject absolute paths
        if (name.startsWith("/")) return null
        // Reject parent-dir traversal (../ anywhere in the path)
        val parts = name.split("/")
        for (p in parts) {
            if (p == "..") return null
        }
        // Normalize redundant separators
        return name.replace("\\", "/").trimStart('.').let {
            if (it.isEmpty() || it == ".") null else it
        }
    }

    private fun readExact(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val s = input.skip(remaining)
            if (s <= 0) break
            remaining -= s
        }
    }

    private fun readCString(buf: ByteArray, off: Int, maxLen: Int): String {
        var end = off
        while (end < off + maxLen && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.US_ASCII)
    }

    private fun parseOctal(s: String): Long {
        if (s.isEmpty()) return 0
        return try {
            // tar fields can have trailing NUL/space; only parse the octal digits
            val clean = s.takeWhile { it in '0'..'7' }
            if (clean.isEmpty()) 0 else clean.toLong(8)
        } catch (_: Throwable) { 0 }
    }
}
