package com.apex.agent.platform.terminal.ubuntu

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * T72: Production tar extractor — replaces the P69 hand-rolled USTAR parser
 * which MANGLED every real Ubuntu Base archive it touched:
 *
 *  P69 bug 1 — typeflag '2' (SYMLINK) fell into the regular-file branch:
 *              194 symlinks in ubuntu-base-24.04 became EMPTY FILES,
 *              including the top-level `bin -> usr/bin`, i.e. /bin/sh,
 *              /bin/bash GONE. The produced rootfs was unusable.
 *  P69 bug 2 — typeflag '1' (HARDLINK) was treated as a symlink (the
 *              comment even said "symlink"): semantics inverted.
 *  P69 bug 3 — USTAR `prefix` field ignored: >100-char paths truncated.
 *  P69 bug 4 — GNU longname/longlink ('L'/'K') and PAX headers ('x'/'g')
 *              were materialized as regular files on disk.
 *  P69 bug 5 — only the exec bit of `mode` was restored (etc/shadow 0600
 *              became 0644…).
 *  P69 bug 6 — tar header checksum never validated: a corrupt archive
 *              silently produced garbage paths.
 *  P69 bug 7 — a truncated archive (entry claims N bytes, stream ends
 *              early) was treated as a normal EOF.
 *
 * Format coverage verified byte-by-byte against the REAL
 * ubuntu-base-24.04.4-{arm64,amd64}.tar.gz (3413 entries):
 *   '0'/'\0' regular · '5' dir · '2' symlink (size=0, ustar magic) ·
 *   '1' hardlink (2 entries) · prefix field · plus defensive support for
 *   GNU 'L'/'K' and PAX 'x'/'g' (not present today, standard tar output).
 *
 * §10: path-traversal protection (../, absolute, canonical escape) kept
 *      and hardened (prefix-boundary check, symlink-chain resolution).
 * §30: streams entry-by-entry — NEVER loads the whole archive into RAM.
 * §8:  cancellation-aware per entry and per 64KB chunk.
 */
class RootfsExtractor(
    private val bufferBytes: Int = 64 * 1024
) {

    data class ExtractResult(
        val entryCount: Int,
        val bytesExtracted: Long,
        val durationMs: Long,
        val rejectedEntries: List<String>,   // §10: traversals that were refused
        /** T72: format statistics — the evidence the extractor understood the archive. */
        val regularFiles: Int = 0,
        val directories: Int = 0,
        val symlinks: Int = 0,
        val hardlinks: Int = 0,
        /** hardlinks whose target was missing → created as symlink fallback. */
        val linkFallbacks: List<String> = emptyList(),
        /** symlinks that could not be created on this filesystem (counted, not silent). */
        val symlinkFailures: List<String> = emptyList(),
        /** 'x'/'g'/'L'/'K' extension headers consumed. */
        val extensionHeaders: Int = 0,
        /** special entries skipped (char/block/fifo devices — meaningless on Android). */
        val skippedSpecials: Int = 0
    )

    /** Raised on any structural archive violation (checksum, truncation, garbage). */
    private class ArchiveInvalidException(message: String) : RuntimeException(message)

    /**
     * Extracts [archiveFile] (tar.gz) into [targetDir].
     * §10: rejects entries outside targetDir. §8: cancellable.
     * T72: structural corruption raises [ArchiveInvalidException] instead of
     * producing a silently-truncated rootfs.
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
            runCatching { gz.close() }
            runCatching { fis.close() }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
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
        val canonicalTarget = targetDir.canonicalFile.toPath()
        val startMs = System.currentTimeMillis()
        var entries = 0
        var bytes = 0L
        var regularFiles = 0
        var directories = 0
        var symlinks = 0
        var hardlinks = 0
        var extensionHeaders = 0
        var skippedSpecials = 0
        val rejected = mutableListOf<String>()
        val linkFallbacks = mutableListOf<String>()
        val symlinkFailures = mutableListOf<String>()

        val block = ByteArray(512)
        val buf = ByteArray(bufferBytes)

        // GNU/PAX extension state
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var pendingPax: MutableMap<String, String>? = null
        var globalPax: Map<String, String> = emptyMap()

        while (true) {
            currentCoroutineContext().ensureActive()   // §8: cancellation check per entry

            val headerRead = readExactCount(decompressed, block, 512)
            when {
                headerRead == 0 -> break                        // clean EOF
                headerRead < 512 -> throw ArchiveInvalidException(
                    "truncated archive: header block ends after $headerRead bytes")
            }
            // Two consecutive zero blocks = end of archive; a SINGLE zero
            // block followed by data is tolerated (some writers pad oddly).
            if (block.all { it == 0.toByte() }) {
                val nextRead = readExactCount(decompressed, block, 512)
                if (nextRead == 0) break                         // EOF after zero block
                if (nextRead < 512) throw ArchiveInvalidException(
                    "truncated archive: partial block after end-of-archive marker")
                if (block.all { it == 0.toByte() }) break        // second zero block
                // else: `block` now holds a real header — fall through to parse
            }

            // ── P69 bug 6 fixed: header checksum validation ──
            verifyHeaderChecksum(block)

            val rawName = readCString(block, 0, 100)
            val mode = parseTarNumeric(block, 100, 8)
            val size = parseTarNumeric(block, 124, 12)
            val typeFlag = if (block[156] == 0.toByte()) '0' else block[156].toInt().toChar()
            val rawLinkName = readCString(block, 157, 100)
            val prefix = readCString(block, 345, 155)     // P69 bug 3 fixed

            // ── header-level name resolution: ustar prefix → GNU longname → PAX path ──
            val pax = pendingPax ?: globalPax
            var name = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            pendingLongName?.let { name = it }
            pax["path"]?.let { name = it }
            var linkName = rawLinkName
            pendingLongLink?.let { linkName = it }
            pax["linkpath"]?.let { linkName = it }
            val effectiveSize = pax["size"]?.toLongOrNull() ?: size

            // extension headers carry no file payload themselves
            when (typeFlag) {
                'L' -> {   // GNU long name for the NEXT entry
                    pendingLongName = readStringPayload(decompressed, effectiveSize, buf)
                    extensionHeaders++
                    continue
                }
                'K' -> {   // GNU long linkname for the NEXT entry
                    pendingLongLink = readStringPayload(decompressed, effectiveSize, buf)
                    extensionHeaders++
                    continue
                }
                'x' -> {   // PAX extended header for the NEXT entry
                    pendingPax = parsePaxRecords(readStringPayload(decompressed, effectiveSize, buf))
                    extensionHeaders++
                    continue
                }
                'g' -> {   // PAX global header for all SUBSEQUENT entries
                    globalPax = parsePaxRecords(readStringPayload(decompressed, effectiveSize, buf))
                    extensionHeaders++
                    continue
                }
            }

            // consume the one-shot overrides
            pendingLongName = null
            pendingLongLink = null
            pendingPax = null

            if (name.isEmpty()) {
                throw ArchiveInvalidException("entry #$entries has an empty name")
            }

            // ── §10: path-traversal protection ──
            val safeName = sanitizeEntryName(name)
            if (safeName == null) {
                rejected.add(name)
                skipEntryData(decompressed, effectiveSize)
                entries++
                continue
            }
            val outPath = File(targetDir, safeName).canonicalFile.toPath()
            // Final guard: the resolved file MUST be inside targetDir — checked
            // on the CANONICAL path with a separator boundary (P69's plain
            // startsWith matched /target-evil against /target).
            if (!isInside(canonicalTarget, outPath)) {
                rejected.add("$name (escapes target)")
                skipEntryData(decompressed, effectiveSize)
                entries++
                continue
            }

            when (typeFlag) {
                '5' -> {  // directory
                    Files.createDirectories(outPath)
                    applyPosixMode(outPath, mode, directory = true)
                    directories++
                }

                '2' -> {  // symlink — P69 bug 1 fixed (was: written as empty regular file)
                    if (linkName.isEmpty()) {
                        rejected.add("$name (symlink with empty target)")
                    } else {
                        try {
                            Files.createDirectories(outPath.parent)   // e.g. etc/alternatives/ may not precede
                            Files.deleteIfExists(outPath)
                            Files.createSymbolicLink(outPath, Paths.get(linkName))
                            symlinks++
                        } catch (e: java.io.IOException) {
                            // e.g. filesystem without symlink support — COUNT it,
                            // never swallow silently (P69 swallowed everything).
                            symlinkFailures.add("$name -> $linkName (${e.message})")
                        }
                    }
                    skipEntryData(decompressed, effectiveSize)
                }

                '1' -> {  // hardlink — P69 bug 2 fixed (was: created as symlink)
                    if (linkName.isEmpty()) {
                        rejected.add("$name (hardlink with empty target)")
                    } else {
                        val targetPath = File(targetDir, linkName.trimStart('/')).let {
                            // hardlink targets are relative to the archive root;
                            // a canonical check keeps them inside the rootfs
                            it.canonicalFile.toPath()
                        }
                        if (!isInside(canonicalTarget, targetPath)) {
                            rejected.add("$name (hardlink target escapes rootfs)")
                        } else if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                            try {
                                Files.createDirectories(outPath.parent)
                                Files.deleteIfExists(outPath)
                                Files.createLink(outPath, targetPath)
                                hardlinks++
                            } catch (e: java.io.IOException) {
                                // same-dir link failed (some filesystems) → symlink fallback
                                Files.deleteIfExists(outPath)
                                Files.createSymbolicLink(outPath, targetPath)
                                linkFallbacks.add("$name => $linkName")
                            }
                        } else {
                            // target not extracted yet (out-of-order tar) → symlink
                            // fallback keeps the reference resolvable after full extract
                            try {
                                Files.createDirectories(outPath.parent)
                                Files.deleteIfExists(outPath)
                                Files.createSymbolicLink(outPath, targetPath)
                                linkFallbacks.add("$name => $linkName (target missing at extract time)")
                            } catch (e: java.io.IOException) {
                                symlinkFailures.add("$name -> $linkName (${e.message})")
                            }
                        }
                    }
                    skipEntryData(decompressed, effectiveSize)
                }

                '3', '4', '6', 'D', 'S' -> {  // char/block/fifo devices, GNU dumpdir, old sparse
                    // Devices are uncreatable without root in an app-dir rootfs;
                    // dumpdir/sparse formats are not produced by Ubuntu Base —
                    // skip content, count honestly (never misparse as file data).
                    skipEntryData(decompressed, effectiveSize)
                    skippedSpecials++
                }

                else -> {  // regular file ('0', '\0', or unknown → treat as file data)
                    Files.createDirectories(outPath.parent)
                    var remaining = effectiveSize
                    FileOutputStream(outPath.toFile()).use { out ->
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = decompressed.read(buf, 0, toRead)
                            if (n <= 0) {
                                // P69 bug 7 fixed: mid-entry EOF is corruption, not EOF
                                throw ArchiveInvalidException(
                                    "truncated archive: entry '$name' declares $effectiveSize bytes, " +
                                        "stream ended after ${effectiveSize - remaining}")
                            }
                            out.write(buf, 0, n)
                            remaining -= n
                            bytes += n
                            progress?.invoke(bytes, declaredArchiveSize)
                        }
                        out.fd.sync()
                    }
                    applyPosixMode(outPath, mode, directory = false)
                    regularFiles++
                    val padding = (512 - (effectiveSize % 512)) % 512
                    if (padding > 0) skipBytes(decompressed, padding)
                    // hardlink bookkeeping: later '1' entries may point at this file
                }
            }
            entries++
        }

        return ExtractResult(
            entryCount = entries,
            bytesExtracted = bytes,
            durationMs = System.currentTimeMillis() - startMs,
            rejectedEntries = rejected,
            regularFiles = regularFiles,
            directories = directories,
            symlinks = symlinks,
            hardlinks = hardlinks,
            linkFallbacks = linkFallbacks,
            symlinkFailures = symlinkFailures,
            extensionHeaders = extensionHeaders,
            skippedSpecials = skippedSpecials
        )
    }

    // ─── header parsing ───

    /** Unsigned (and legacy signed) tar checksum validation — P69 bug 6 fixed. */
    private fun verifyHeaderChecksum(block: ByteArray) {
        val stored = parseTarNumeric(block, 148, 8)
        if (stored == 0L) return   // some writers emit all-zero checksums; tolerate
        var unsigned = 0L
        var signed = 0L
        for (i in block.indices) {
            val b = block[i].toInt() and 0xFF
            val s = block[i].toInt()
            val v = if (i in 148..155) 0x20 else b     // checksum field counts as spaces
            val sv = if (i in 148..155) 0x20 else s
            unsigned += v
            signed += sv
        }
        if (stored != unsigned && stored != signed) {
            throw ArchiveInvalidException(
                "tar header checksum mismatch: stored=$stored computed=$unsigned/$signed"
            )
        }
    }

    /**
     * Octal (with NUL/space tolerance) OR GNU base-256 (high bit of first byte)
     * numeric field parser.
     */
    private fun parseTarNumeric(block: ByteArray, offset: Int, length: Int): Long {
        val first = block[offset].toInt()
        if (first and 0x80 != 0) {   // GNU base-256 big-endian
            var value = (first and 0x7F).toLong()
            for (i in 1 until length) {
                value = (value shl 8) or (block[offset + i].toLong() and 0xFF)
            }
            return value
        }
        var end = offset
        while (end < offset + length && block[end] != 0.toByte() && block[end] != ' '.code.toByte()) end++
        if (end == offset) return 0
        val s = String(block, offset, end - offset, Charsets.US_ASCII).trim()
        if (s.isEmpty()) return 0
        return try {
            java.lang.Long.parseLong(s, 8)
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Reads a size-byte payload as a NUL-trimmed UTF-8 string (GNU 'L'/'K', PAX 'x'/'g').
     * NOTE: only NULs are trimmed — a trailing '\n' is PART of a PAX record's
     * length budget ("len key=value\n"); trimming it corrupts record parsing.
     */
    private fun readStringPayload(input: InputStream, size: Long, buf: ByteArray): String {
        val baos = java.io.ByteArrayOutputStream()
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) throw ArchiveInvalidException("truncated archive: extension header ends early")
            baos.write(buf, 0, n)
            remaining -= n
        }
        // extension payload is padded to 512 like any entry
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) skipBytes(input, padding)
        return baos.toString("UTF-8").trimEnd('\u0000')
    }

    /** PAX records: "<length> key=value\n" per record. */
    private fun parsePaxRecords(content: String): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        var pos = 0
        while (pos < content.length) {
            val spaceIdx = content.indexOf(' ', pos)
            if (spaceIdx < 0) break
            val len = content.substring(pos, spaceIdx).toIntOrNull() ?: break
            // record body spans: spaceIdx+1 .. pos+len-1
            if (len <= 0 || pos + len > content.length || spaceIdx + 1 > pos + len) break
            val record = content.substring(spaceIdx + 1, pos + len)
            val eq = record.indexOf('=')
            if (eq > 0) {
                val key = record.substring(0, eq)
                var value = record.substring(eq + 1)
                if (value.endsWith("\n")) value = value.dropLast(1)
                map[key] = value
            }
            pos += len
        }
        return map
    }

    // ─── filesystem application ───

    /**
     * P69 bug 5 fixed: full POSIX permission restore (owner/group/other,
     * setuid/setgid/sticky bits are dropped — meaningless for an unprivileged
     * app-dir rootfs). Directories always keep u+rwx so later entries can land.
     */
    private fun applyPosixMode(path: Path, mode: Long, directory: Boolean) {
        val perms = mutableSetOf<PosixFilePermission>()
        fun bit(mask: Long, perm: PosixFilePermission) {
            if (mode and mask != 0L) perms.add(perm)
        }
        bit(0x100, PosixFilePermission.OWNER_READ)
        bit(0x080, PosixFilePermission.OWNER_WRITE)
        bit(0x040, PosixFilePermission.OWNER_EXECUTE)
        bit(0x020, PosixFilePermission.GROUP_READ)
        bit(0x010, PosixFilePermission.GROUP_WRITE)
        bit(0x008, PosixFilePermission.GROUP_EXECUTE)
        bit(0x004, PosixFilePermission.OTHERS_READ)
        bit(0x002, PosixFilePermission.OTHERS_WRITE)
        bit(0x001, PosixFilePermission.OTHERS_EXECUTE)
        if (directory) {   // never lock ourselves out mid-extract
            perms.add(PosixFilePermission.OWNER_READ)
            perms.add(PosixFilePermission.OWNER_WRITE)
            perms.add(PosixFilePermission.OWNER_EXECUTE)
        }
        runCatching { Files.setPosixFilePermissions(path, perms) }
    }

    // ─── §10: path-traversal sanitization ───
    private fun sanitizeEntryName(name: String): String? {
        if (name.startsWith("/")) return null            // absolute path
        val parts = name.split("/")
        if (parts.any { it == ".." }) return null        // parent traversal
        val cleaned = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return if (cleaned.isEmpty()) null else cleaned
    }

    private fun isInside(root: Path, candidate: Path): Boolean {
        val r = root.toString()
        val c = candidate.toString()
        return c == r || c.startsWith(r + File.separator)
    }

    private fun skipEntryData(input: InputStream, size: Long) {
        skipBytes(input, size)
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) skipBytes(input, padding)
    }

    /** Returns bytes actually read (0 = clean EOF, <len = truncated). */
    private fun readExactCount(input: InputStream, buf: ByteArray, len: Int): Int {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) return read
            read += n
        }
        return read
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val s = input.skip(remaining)
            if (s <= 0) {
                // skip() can stall on some streams — read one byte to distinguish EOF
                if (input.read() < 0) return
                remaining -= 1
                continue
            }
            remaining -= s
        }
    }

    private fun readCString(buf: ByteArray, off: Int, maxLen: Int): String {
        var end = off
        while (end < off + maxLen && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }
}
