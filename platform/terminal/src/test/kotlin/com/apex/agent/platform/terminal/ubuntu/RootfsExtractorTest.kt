package com.apex.agent.platform.terminal.ubuntu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * T72: RootfsExtractor format tests.
 *
 * The P69 extractor tests built only plain-USTAR tars with regular files and
 * directories — the exact shapes the (buggy) parser happened to handle. These
 * tests exercise EVERY tar feature the real world (and the real
 * ubuntu-base-24.04.4 tarball) actually contains:
 *
 *  - symlink entries ('2')               ← P69 wrote them as EMPTY FILES
 *  - hardlink entries ('1')              ← P69 wrote them as symlinks
 *  - ustar prefix field (>100 char paths)
 *  - GNU longname/longlink ('L'/'K')
 *  - PAX extended headers ('x') with path override
 *  - truncated archives                   ← P69 treated as clean EOF
 *  - corrupt header checksums             ← P69 never validated
 *  - full mode restoration (0600/0755/0700)
 *  - traversal/escape rejection (kept from P69, hardened)
 */
class RootfsExtractorTest {

    private fun tmpDir(): File = Files.createTempDirectory("t72-ext-").toFile()

    // ─── low-level tar writer (full-featured, unlike the P69 test helper) ───

    private class TarWriter {
        val out = ByteArrayOutputStream()

        fun entry(
            name: String,
            content: ByteArray = ByteArray(0),
            typeFlag: Byte = '0'.code.toByte(),
            linkName: String = "",
            mode: Long = 0x1A4,      // 0644
            prefix: String = "",
            skipChecksum: Boolean = false
        ) {
            val block = ByteArray(512)
            val nb = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nb, 0, block, 0, minOf(nb.size, 100))
            System.arraycopy((oct(mode, 7) + "\u0000").toByteArray(), 0, block, 100, 8)
            System.arraycopy((oct(content.size.toLong(), 11) + "\u0000").toByteArray(), 0, block, 124, 12)
            System.arraycopy("00000000000\u0000".toByteArray(), 0, block, 136, 12)
            block[156] = typeFlag
            val lb = linkName.toByteArray(Charsets.UTF_8)
            System.arraycopy(lb, 0, block, 157, minOf(lb.size, 100))
            val magic = "ustar\u000000".toByteArray(Charsets.US_ASCII)
            System.arraycopy(magic, 0, block, 257, 8)
            val pb = prefix.toByteArray(Charsets.UTF_8)
            System.arraycopy(pb, 0, block, 345, minOf(pb.size, 155))
            if (!skipChecksum) {
                for (i in 148..155) block[i] = ' '.code.toByte()
                var sum = 0
                for (b in block) sum += (b.toInt() and 0xFF)
                System.arraycopy(String.format("%06o\u0000 ", sum).toByteArray(), 0, block, 148, 8)
            }
            out.write(block)
            if (content.isNotEmpty()) {
                out.write(content)
                val pad = (512 - (content.size % 512)) % 512
                if (pad > 0) out.write(ByteArray(pad))
            }
        }

        fun rawExtensionHeader(typeFlag: Byte, content: String) {
            // extension headers are entries whose CONTENT is the payload
            val bytes = content.toByteArray(Charsets.UTF_8)
            entry(name = "./PaxHeaders.0/fake", content = bytes, typeFlag = typeFlag, mode = 0x180)
        }

        fun finish() {
            out.write(ByteArray(1024))   // two zero blocks
        }

        private fun oct(v: Long, digits: Int): String = String.format("%0${digits}o", v)
    }

    private fun gunzip(bytes: ByteArray, file: File) {
        GZIPOutputStream(file.outputStream().buffered()).use { it.write(bytes) }
    }

    // ─── symlink handling: THE P69 killer bug ───

    @Test fun `symlink entries become real symlinks not empty files`() = runBlocking {
        val tar = TarWriter().apply {
            entry("usr/", typeFlag = '5'.code.toByte(), mode = 0x1ED)
            entry("usr/bin/", typeFlag = '5'.code.toByte(), mode = 0x1ED)
            entry("bin", typeFlag = '2'.code.toByte(), linkName = "usr/bin", mode = 0x1FF)
            entry("etc/os-release", typeFlag = '2'.code.toByte(), linkName = "../usr/lib/os-release", mode = 0x1FF)
            entry("usr/bin/mawk", content = "ELF".toByteArray(), mode = 0x1ED)
            entry("etc/alternatives/awk", typeFlag = '2'.code.toByte(), linkName = "/usr/bin/mawk", mode = 0x1FF)
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()

        assertEquals("all symlinks created", 3, result.symlinks)
        // bin -> usr/bin (merged-usr): a real symlink, NOT an empty regular file
        val bin = File(out, "bin")
        assertTrue("bin exists", bin.exists())
        assertTrue("bin is a symlink", java.nio.file.Files.isSymbolicLink(bin.toPath()))
        assertEquals("usr/bin", java.nio.file.Files.readSymbolicLink(bin.toPath()).toString())
        assertTrue("bin/sh reachable through symlink dir", File(out, "bin/mawk").exists())
        // relative target
        assertEquals("../usr/lib/os-release",
            java.nio.file.Files.readSymbolicLink(File(out, "etc/os-release").toPath()).toString())
        // absolute target preserved verbatim
        assertEquals("/usr/bin/mawk",
            java.nio.file.Files.readSymbolicLink(File(out, "etc/alternatives/awk").toPath()).toString())
    }

    @Test fun `hardlink entries become hardlinks when target exists`() = runBlocking {
        val tar = TarWriter().apply {
            entry("usr/bin/", typeFlag = '5'.code.toByte(), mode = 0x1ED)
            entry("usr/bin/perl", content = "perl-binary".toByteArray(), mode = 0x1ED)
            // the REAL ubuntu-base hardlink: usr/bin/perl5.38.2 → usr/bin/perl
            entry("usr/bin/perl5.38.2", typeFlag = '1'.code.toByte(), linkName = "usr/bin/perl")
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()

        assertEquals("one real hardlink", 1, result.hardlinks)
        val perl = File(out, "usr/bin/perl")
        val perl5 = File(out, "usr/bin/perl5.38.2")
        assertTrue(perl5.exists())
        assertFalse("NOT a symlink", java.nio.file.Files.isSymbolicLink(perl5.toPath()))
        // hardlink = same inode (Files.isSameFile, NOT path equality)
        assertTrue("same inode (hardlink)", java.nio.file.Files.isSameFile(perl.toPath(), perl5.toPath()))
        // content visible through both names
        assertEquals("perl-binary", perl5.readText())
    }

    @Test fun `hardlink with missing target falls back to symlink honestly`() = runBlocking {
        val tar = TarWriter().apply {
            entry("usr/bin/", typeFlag = '5'.code.toByte(), mode = 0x1ED)
            // target NOT yet extracted (out-of-order tar) → fallback
            entry("usr/bin/later-link", typeFlag = '1'.code.toByte(), linkName = "usr/bin/not-yet-there")
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()
        assertEquals("fallback recorded", 1, result.linkFallbacks.size)
        assertTrue(java.nio.file.Files.isSymbolicLink(File(out, "usr/bin/later-link").toPath()))
    }

    // ─── long paths: prefix / GNU 'L' / PAX 'x' ───

    @Test fun `ustar prefix field joins into the real path`() = runBlocking {
        val longDir = "very/deep/nested/directory/structure/that/exceeds/one/hundred/chars/in/total/length"
        val name = "leaf-file.txt"
        val tar = TarWriter().apply {
            entry(name, content = "content".toByteArray(), prefix = longDir)
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()
        assertEquals(1, result.regularFiles)
        val expected = File(out, "$longDir/$name")
        assertTrue("prefix+name path extracted: ${expected.absolutePath}", expected.isFile)
        assertEquals("content", expected.readText())
    }

    @Test fun `GNU longname L header overrides next entry name`() = runBlocking {
        val longName = (1..130).map { 'a' }.joinToString("")
        val tar = TarWriter().apply {
            // 'L' header: content = the long path for the NEXT entry
            entry("././@LongLink", content = longName.toByteArray(), typeFlag = 'L'.code.toByte())
            entry("truncated-name", content = "payload".toByteArray())
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()
        assertEquals("one extension header consumed", 1, result.extensionHeaders)
        assertEquals(1, result.regularFiles)
        assertTrue("long-named file extracted", File(out, longName).isFile)
        assertEquals("payload", File(out, longName).readText())
    }

    @Test fun `GNU longlink K header overrides next entry linkname`() = runBlocking {
        val longTarget = (1..120).map { 'z' }.joinToString("")
        val tar = TarWriter().apply {
            entry("././@LongLink", content = longTarget.toByteArray(), typeFlag = 'K'.code.toByte())
            entry("link", typeFlag = '2'.code.toByte(), linkName = "short")
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()
        assertEquals(1, result.symlinks)
        assertEquals(longTarget, java.nio.file.Files.readSymbolicLink(File(out, "link").toPath()).toString())
    }

    @Test fun `PAX x header with path override works`() = runBlocking {
        // PAX record format: "<total-len> <key>=<value>\n" where total-len counts
        // the digits, the space, key, '=', value and trailing '\n'.
        fun paxRecord(key: String, value: String): String {
            var len = 0
            while (true) {
                val candidate = "$len $key=$value\n"
                if (candidate.length == len) return candidate
                len++
            }
        }
        val pathValue = "pax/overridden/name.txt"
        val record = paxRecord("path", pathValue)
        val tar = TarWriter().apply {
            entry("PaxHeaders/fake", content = record.toByteArray(), typeFlag = 'x'.code.toByte())
            entry("original-name", content = "data".toByteArray())
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()
        assertEquals("pax header consumed", 1, result.extensionHeaders)
        assertEquals("pax path override applied", 1, result.regularFiles)
        assertTrue("overridden path used: $pathValue", File(out, pathValue).isFile)
        assertFalse("original name not materialized", File(out, "original-name").exists())
        assertEquals("data", File(out, pathValue).readText())
    }

    // ─── corruption detection ───

    @Test fun `truncated archive mid-entry fails (not silent EOF)`() = runBlocking {
        val tar = TarWriter().apply {
            entry("big-file", content = ByteArray(5000))
            // NO finish() — and we cut the content below
        }
        val bytes = tar.out.toByteArray()
        val cut = bytes.copyOfRange(0, 512 + 1000)   // header + half the content
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(cut, it) }
        val result = RootfsExtractor().extractTarGz(archive, File(dir, "out"))
        assertTrue("truncated archive must fail: $result", result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        assertTrue("mentions truncation: $msg", msg.contains("truncated") || msg.contains("truncat"))
    }

    @Test fun `corrupt header checksum fails`() = runBlocking {
        val tar = TarWriter().apply {
            entry("good-file", content = "x".toByteArray())
            entry("corrupt-file", content = "y".toByteArray(), skipChecksum = true)
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val result = RootfsExtractor().extractTarGz(archive, File(dir, "out"))
        // skipChecksum leaves all-space checksum field → stored=0 → tolerated.
        // Write a REAL wrong checksum instead:
        val tar2 = TarWriter()
        tar2.entry("good-file", content = "x".toByteArray())
        val out2 = tar2.out
        // hand-craft a header with a wrong checksum
        val block = ByteArray(512)
        System.arraycopy("corrupt".toByteArray(), 0, block, 0, 7)
        System.arraycopy((oct(0x1A4, 7) + "\u0000").toByteArray(), 0, block, 100, 8)
        System.arraycopy("00000000000\u0000".toByteArray(), 0, block, 124, 12)
        block[156] = '0'.code.toByte()
        System.arraycopy("ustar\u000000".toByteArray(), 0, block, 257, 8)
        for (i in 148..155) block[i] = ' '.code.toByte()
        System.arraycopy("777777\u0000 ".toByteArray(), 0, block, 148, 8)   // wrong checksum
        out2.write(block)
        out2.write(ByteArray(1024))
        val archive2 = File(dir, "b.tar.gz").also { gunzip(out2.toByteArray(), it) }
        val result2 = RootfsExtractor().extractTarGz(archive2, File(dir, "out2"))
        assertTrue("wrong checksum must fail: $result2", result2.isFailure)
        val msg = result2.exceptionOrNull()!!.message ?: ""
        assertTrue("mentions checksum: $msg", msg.contains("checksum"))
    }

    @Test fun `corrupt gzip stream fails`() = runBlocking {
        val dir = tmpDir()
        val archive = File(dir, "bad.tar.gz").apply {
            writeBytes(ByteArray(3000) { (it % 251).toByte() })   // not gzip at all
        }
        val result = RootfsExtractor().extractTarGz(archive, File(dir, "out"))
        assertTrue(result.isFailure)
    }

    // ─── mode restoration ───

    @Test fun `file and directory modes restored from tar headers`() = runBlocking {
        val tar = TarWriter().apply {
            entry("secret/", typeFlag = '5'.code.toByte(), mode = 0x1C0)      // 0700
            entry("normal/", typeFlag = '5'.code.toByte(), mode = 0x1ED)      // 0755
            entry("secret/shadow", content = "x".toByteArray(), mode = 0x180) // 0600
            entry("normal/script.sh", content = "#!/bin/sh".toByteArray(), mode = 0x1ED)  // 0755
            entry("normal/readonly.txt", content = "r".toByteArray(), mode = 0x124)       // 0444
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        RootfsExtractor().extractTarGz(archive, out).getOrThrow()

        val shadow = java.nio.file.Files.getPosixFilePermissions(File(out, "secret/shadow").toPath())
        assertEquals("0600 restored", setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), shadow)
        val script = java.nio.file.Files.getPosixFilePermissions(File(out, "normal/script.sh").toPath())
        assertEquals("0755 restored", setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE), script)
        val ro = java.nio.file.Files.getPosixFilePermissions(File(out, "normal/readonly.txt").toPath())
        assertEquals("0444 restored", setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ), ro)
    }

    // ─── safety (kept + hardened from P69) ───

    @Test fun `device entries skipped not materialized`() = runBlocking {
        val tar = TarWriter().apply {
            entry("dev/null", typeFlag = '3'.code.toByte())   // char device
            entry("dev/sda", typeFlag = '4'.code.toByte())    // block device
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val result = RootfsExtractor().extractTarGz(archive, File(dir, "out")).getOrThrow()
        assertEquals(2, result.skippedSpecials)
        assertEquals(0, result.regularFiles)
    }

    @Test fun `symlink chain cannot escape target dir`() = runBlocking {
        val outside = tmpDir()
        val outsideFile = File(outside, "target.txt").apply { writeText("outside") }
        val tar = TarWriter().apply {
            // symlink pointing OUTSIDE, then a file through it
            entry("escape", typeFlag = '2'.code.toByte(), linkName = outside.absolutePath)
            entry("escape/pwned", content = "evil".toByteArray())
            finish()
        }
        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(tar.out.toByteArray(), it) }
        val out = File(dir, "out")
        val result = RootfsExtractor().extractTarGz(archive, out).getOrThrow()
        // the file-through-symlink attempt must be rejected
        assertTrue("escape attempt rejected", result.rejectedEntries.isNotEmpty())
        assertEquals("outside file untouched", "outside", outsideFile.readText())
    }

    @Test fun `base-256 size field parsed for large files`() = runBlocking {
        // GNU base-256: first byte high bit set, then big-endian value.
        val block = ByteArray(512)
        System.arraycopy("big.dat".toByteArray(), 0, block, 0, 7)
        System.arraycopy((oct(0x1A4, 7) + "\u0000").toByteArray(), 0, block, 100, 8)
        // size = 600 (0x258) in GNU base-256: flag byte 0x80, then 11 big-endian
        // data bytes; 0x0258 lands in the last two data bytes (134, 135)
        block[124] = 0x80.toByte()
        block[134] = 0x02.toByte()
        block[135] = 0x58.toByte()
        System.arraycopy("00000000000\u0000".toByteArray(), 0, block, 136, 12)
        block[156] = '0'.code.toByte()
        System.arraycopy("ustar\u000000".toByteArray(), 0, block, 257, 8)
        for (i in 148..155) block[i] = ' '.code.toByte()
        var sum = 0
        for (b in block) sum += (b.toInt() and 0xFF)
        System.arraycopy(String.format("%06o\u0000 ", sum).toByteArray(), 0, block, 148, 8)

        val content = ByteArray(600) { 'x'.code.toByte() }
        val out = ByteArrayOutputStream()
        out.write(block); out.write(content)
        val pad = (512 - (600 % 512)) % 512
        if (pad > 0) out.write(ByteArray(pad))
        out.write(ByteArray(1024))

        val dir = tmpDir()
        val archive = File(dir, "a.tar.gz").also { gunzip(out.toByteArray(), it) }
        val result = RootfsExtractor().extractTarGz(archive, File(dir, "out")).getOrThrow()
        assertEquals(1, result.regularFiles)
        assertEquals(600L, result.bytesExtracted)
        assertEquals(600L, File(dir, "out/big.dat").length())
    }

    private fun oct(v: Long, digits: Int): String = String.format("%0${digits}o", v)
}
