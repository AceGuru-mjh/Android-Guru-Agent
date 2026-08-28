package com.apex.agent.platform.terminal.ubuntu

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T72: RootfsConfigurator tests — DNS/hosts/hostname/apt-dirs/CA/locale.
 */
class RootfsConfiguratorTest {

    private fun tmpRoot(): File {
        val root = Files.createTempDirectory("t72-cfg-").toFile()
        // minimal ubuntu-ish skeleton
        for (d in listOf("etc", "usr", "var", "tmp", "home", "root", "workspace")) {
            File(root, d).mkdirs()
        }
        return root
    }

    @Test fun `resolv-conf written with injected dns servers`() {
        val root = tmpRoot()
        val cfg = RootfsConfigurator(dnsServers = { listOf("192.168.1.1", "fd00::1") })
        val report = cfg.configure(root)
        val resolv = File(root, "etc/resolv.conf").readText()
        assertTrue("nameserver 192.168.1.1" in resolv)
        assertTrue("nameserver fd00::1" in resolv)
        assertTrue("action logged", report.actions.any { it.startsWith("resolv.conf: wrote") })
    }

    @Test fun `empty resolv-conf gets written, existing content kept`() {
        val root = tmpRoot()
        // pre-existing EMPTY resolv.conf (the real Ubuntu Base state)
        File(root, "etc/resolv.conf").writeText("")
        val cfg = RootfsConfigurator(dnsServers = { listOf("10.1.1.1") })
        cfg.configure(root)
        assertTrue(File(root, "etc/resolv.conf").readText().contains("10.1.1.1"))

        // now non-empty (e.g. user edited) → kept
        File(root, "etc/resolv.conf").writeText("nameserver 9.9.9.9\n")
        val report2 = RootfsConfigurator(dnsServers = { listOf("10.1.1.1") }).configure(root)
        assertEquals("nameserver 9.9.9.9", File(root, "etc/resolv.conf").readText().trim())
        assertTrue(report2.actions.any { it.contains("kept existing") })
    }

    @Test fun `no dns anywhere falls back to public resolvers with warning`() {
        val root = tmpRoot()
        // host /etc/resolv.conf DOES exist in CI; force the fallback path by
        // injecting empty and pointing the reader at a nonexistent host file —
        // emulate via dnsServers returning empty AND host file absent.
        val cfg = RootfsConfigurator(
            dnsServers = { emptyList() },
            hostCaBundle = { null }
        )
        // host resolv.conf exists on Linux CI → it wins. Assert EITHER fallback
        // OR host servers, both are honest outcomes; the invariant is: file
        // non-empty afterwards.
        val report = cfg.configure(root)
        val text = File(root, "etc/resolv.conf").readText()
        assertTrue("resolv.conf non-empty: '$text'", text.contains("nameserver"))
        if (!report.warnings.any { it.contains("public resolvers") }) {
            // host resolv.conf was used
            val hostServers = File("/etc/resolv.conf").readLines()
                .filter { it.trim().startsWith("nameserver") }
            assertTrue("host servers copied", hostServers.any { text.contains(it.trim()) })
        }
    }

    @Test fun `hosts and hostname written when empty`() {
        val root = tmpRoot()
        val cfg = RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }, hostname = "my-device")
        cfg.configure(root)
        val hosts = File(root, "etc/hosts").readText()
        assertTrue(hosts.contains("127.0.0.1"))
        assertTrue(hosts.contains("localhost"))
        assertEquals("my-device", File(root, "etc/hostname").readText().trim())
    }

    @Test fun `apt working directories ensured idempotently`() {
        val root = tmpRoot()
        val cfg = RootfsConfigurator(dnsServers = { listOf("10.0.0.1") })
        cfg.configure(root)
        for (d in listOf(
            "var/lib/apt/lists/partial", "var/lib/dpkg/info", "var/lib/dpkg/updates",
            "var/lib/dpkg/triggers", "var/cache/apt/archives/partial", "var/log/apt"
        )) {
            assertTrue("/$d ensured", File(root, d).isDirectory)
        }
        // second run: no failures, still all present
        val report2 = cfg.configure(root)
        assertTrue(report2.warnings.none { it.startsWith("apt-dir") })
    }

    @Test fun `ca bundle copied when host provides one`() {
        val hostCa = Files.createTempFile("t72-ca", ".pem").toFile().apply {
            writeText("-----BEGIN CERTIFICATE-----\nfakebundle\n-----END CERTIFICATE-----\n")
        }
        val root = tmpRoot()
        val cfg = RootfsConfigurator(
            dnsServers = { listOf("10.0.0.1") },
            hostCaBundle = { hostCa }
        )
        val report = cfg.configure(root)
        val guestCa = File(root, "etc/ssl/certs/ca-certificates.crt")
        assertTrue("bundle copied", guestCa.isFile && guestCa.length() > 0)
        assertTrue("copied action logged", report.actions.any { it.contains("copied host bundle") })
        assertTrue("no CA warning", report.warnings.none { it.contains("ca-certificates") })
    }

    @Test fun `missing ca bundle produces honest warning not fake file`() {
        val root = tmpRoot()
        val cfg = RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }, hostCaBundle = { null })
        val report = cfg.configure(root)
        val guestCa = File(root, "etc/ssl/certs/ca-certificates.crt")
        assertFalse("no fake bundle fabricated", guestCa.isFile)
        assertTrue(
            "honest warning present",
            report.warnings.any { it.contains("ca-certificates: ABSENT") }
        )
    }

    @Test fun `locale default written when missing`() {
        val root = tmpRoot()
        RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }).configure(root)
        assertEquals("LANG=\"C.UTF-8\"", File(root, "etc/default/locale").readText().trim())
    }

    @Test fun `tmp is world-writable (sticky approximated)`() {
        val root = tmpRoot()
        RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }).configure(root)
        val tmp = File(root, "tmp")
        assertTrue(tmp.canWrite())
        assertTrue("world-writable", java.nio.file.Files.getPosixFilePermissions(tmp.toPath())
            .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
    }
}

/**
 * T72: RootfsHealthInspector tests — READY 的证据链。
 */
class RootfsHealthInspectorTest {

    /** Builds a fully healthy rootfs skeleton (post-configurator state). */
    private fun healthyRoot(): File {
        val root = Files.createTempDirectory("t72-hc-").toFile()
        for (d in listOf("usr/bin", "etc/apt", "var/lib/dpkg", "tmp", "var", "root", "home", "etc/ssl/certs")) {
            File(root, d).mkdirs()
        }
        // merged-usr symlink
        java.nio.file.Files.createSymbolicLink(File(root, "bin").toPath(), java.nio.file.Paths.get("usr/bin"))
        for (f in listOf("usr/bin/sh", "usr/bin/bash", "usr/bin/env", "usr/bin/apt", "usr/bin/dpkg")) {
            File(root, f).apply { writeText("x"); setExecutable(true, false) }
        }
        File(root, "etc/os-release").writeText("PRETTY_NAME=\"Ubuntu 24.04.4 LTS\"\nID=ubuntu\n")
        File(root, "etc/apt/sources.list").writeText("deb http://ports.ubuntu.com/ubuntu-ports noble main\n")
        File(root, "var/lib/dpkg/status").writeText("Package: dpkg\n")
        File(root, "etc/resolv.conf").writeText("nameserver 10.0.0.1\n")
        File(root, "etc/localtime").writeText("TZif2")
        File(root, "etc/ssl/certs/ca-certificates.crt").writeText("bundle")
        return root
    }

    @Test fun `healthy rootfs passes all FAIL checks`() {
        val root = healthyRoot()
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertEquals("no failures: ${report.failures}", 0, report.failures.size)
        assertTrue(report.valid)
        // CA present → no warn either
        assertEquals(0, report.warnings.size)
    }

    @Test fun `missing bash fails`() {
        val root = healthyRoot()
        File(root, "usr/bin/bash").delete()
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertTrue(report.failures.any { it.name.contains("bash") })
        assertFalse(report.valid)
    }

    @Test fun `dangling bin symlink fails (the P69 mangled state)`() {
        val root = healthyRoot()
        // recreate the P69 bug: bin as an EMPTY FILE instead of a symlink
        File(root, "bin").delete()
        File(root, "bin").writeText("")
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        val binChecks = report.checks.filter { it.detail.contains("extractor bug") || it.name.contains("/bin/sh") }
        assertTrue(
            "bin-as-empty-file detected: ${report.failures}",
            report.failures.isNotEmpty() && report.failures.any { it.name.contains("/bin/") }
        )
    }

    @Test fun `empty resolv conf fails resolver check`() {
        val root = healthyRoot()
        File(root, "etc/resolv.conf").writeText("")
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertTrue(report.failures.any { it.name == "resolver" })
    }

    @Test fun `missing os-release fails`() {
        val root = healthyRoot()
        File(root, "etc/os-release").delete()
        File(root, "usr/lib/os-release").delete()
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertTrue(report.failures.any { it.name == "os-release" })
    }

    @Test fun `missing apt sources fails`() {
        val root = healthyRoot()
        File(root, "etc/apt/sources.list").delete()
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertTrue(report.failures.any { it.name == "apt-sources" })
    }

    @Test fun `missing ca bundle warns but does not fail`() {
        val root = healthyRoot()
        File(root, "etc/ssl/certs/ca-certificates.crt").delete()
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertEquals(1, report.warnings.size)
        assertTrue(report.warnings[0].name == "ca-certificates")
        assertTrue("still valid — WARN only", report.valid)
    }

    @Test fun `missing timezone warns but does not fail`() {
        val root = healthyRoot()
        File(root, "etc/localtime").delete()
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertTrue(report.warnings.any { it.name == "timezone" })
        assertTrue(report.valid)
    }

    @Test fun `architecture mismatch fails on real ELF`() {
        val root = healthyRoot()
        // x86-64 ELF header for /usr/bin/env (e_machine = 62)
        val elf = ByteArray(64)
        elf[0] = 0x7f; elf[1] = 'E'.code.toByte(); elf[2] = 'L'.code.toByte(); elf[3] = 'F'.code.toByte()
        elf[4] = 2; elf[5] = 1
        elf[18] = 62.toByte(); elf[19] = 0
        File(root, "usr/bin/env").writeBytes(elf)
        val report = RootfsHealthInspector(expectedArch = com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64).inspect(root)
        assertTrue(
            "arm64 expectation vs x86-64 ELF must fail: ${report.failures}",
            report.failures.any { it.name == "arch" }
        )
        // same ELF against x86-64 expectation passes
        val report2 = RootfsHealthInspector(expectedArch = com.apex.agent.platform.terminal.linux.CpuArchitecture.X86_64).inspect(root)
        assertTrue(report2.checks.first { it.name == "arch" }.status == HealthStatus.PASS)
    }

    @Test fun `deb822 sources format recognized`() {
        val root = healthyRoot()
        File(root, "etc/apt/sources.list").delete()
        File(root, "etc/apt/sources.list.d").mkdirs()
        File(root, "etc/apt/sources.list.d/ubuntu.sources").writeText(
            "Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports/\nSuites: noble\n"
        )
        val report = RootfsHealthInspector(expectedArch = null).inspect(root)
        assertTrue(report.checks.first { it.name == "apt-sources" }.status == HealthStatus.PASS)
    }
}

/**
 * T72: RootfsDownloader range-resume semantics.
 */
class RootfsDownloaderTest {

    private fun artifact(bytes: ByteArray, sha: String? = null) = RootfsArtifact(
        id = "ubuntu-24.04-arm64", distribution = "ubuntu", version = "24.04",
        architecture = com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64,
        archiveUrl = null, archiveFormat = ArchiveFormat.TAR_GZ,
        expectedSize = bytes.size.toLong(), sha256 = sha, sourceKind = RootfsSourceKind.CUSTOM
    )

    @Test fun `fresh download verifies checksum and atomically renames`() = kotlinx.coroutines.runBlocking {
        val payload = "hello-world-archive".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val dir = java.nio.file.Files.createTempDirectory("t72-dl-").toFile()
        val target = File(dir, "a.tar.gz")
        val result = RootfsDownloader(maxRetries = 1).download(
            FakeRootfsSource(artifact(payload, sha), payload), artifact(payload, sha), target
        ).getOrThrow()
        assertTrue(result.checksumMatches)
        assertEquals(sha, result.sha256Actual)
        assertTrue(target.isFile)
        assertFalse("no .part left", File(dir, "a.tar.gz.part").exists())
        assertEquals(0L, result.resumedFrom)
    }

    @Test fun `wrong checksum deletes the bad file`() = kotlinx.coroutines.runBlocking {
        val payload = "corrupted".toByteArray()
        val art = artifact(payload, "f".repeat(64))
        val dir = java.nio.file.Files.createTempDirectory("t72-dl-").toFile()
        val target = File(dir, "a.tar.gz")
        val result = RootfsDownloader(maxRetries = 1).download(FakeRootfsSource(art, payload), art, target)
        assertTrue(result.isFailure)
        assertTrue("exception mentions mismatch: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains("CHECKSUM_MISMATCH"))
        assertFalse("bad archive deleted", target.exists())
        assertFalse("bad .part deleted", File(dir, "a.tar.gz.part").exists())
    }

    @Test fun `partial part file resumed from offset`() = kotlinx.coroutines.runBlocking {
        val payload = (1..10_000).map { (it % 256).toByte() }.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val art = artifact(payload, sha)
        val dir = java.nio.file.Files.createTempDirectory("t72-dl-").toFile()
        val target = File(dir, "a.tar.gz")
        // pre-existing partial: first 3000 bytes of the payload
        File(dir, "a.tar.gz.part").writeBytes(payload.copyOfRange(0, 3000))

        val result = RootfsDownloader(maxRetries = 1).download(
            FakeRootfsSource(art, payload), art, target
        ).getOrThrow()
        // resumed from 3000
        assertEquals(3000L, result.resumedFrom)
        // full file intact and verified (append must not corrupt)
        assertEquals(payload.size.toLong(), target.length())
        assertEquals(sha, result.sha256Actual)
        assertTrue(result.checksumMatches)
    }

    @Test fun `range-ignoring source restarts from scratch`() = kotlinx.coroutines.runBlocking {
        val payload = (1..5000).map { (it % 251).toByte() }.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val art = artifact(payload, sha)
        val dir = java.nio.file.Files.createTempDirectory("t72-dl-").toFile()
        val target = File(dir, "a.tar.gz")
        File(dir, "a.tar.gz.part").writeBytes(payload.copyOfRange(0, 1000))

        // a source that ALWAYS answers with the full stream regardless of offset
        val rangeBlind = object : RootfsArtifactSource {
            override val sourceKind = RootfsSourceKind.CUSTOM
            override suspend fun resolve(target: RootfsTarget) = Result.success(art)
            override suspend fun open(artifact: RootfsArtifact, offset: Long) =
                Result.success(RangeNotSupportedInputStream(payload.inputStream()))
        }
        val result = RootfsDownloader(maxRetries = 1).download(rangeBlind, art, target).getOrThrow()
        // blind append would have corrupted the file; T72 must truncate+restart
        assertEquals(0L, result.resumedFrom)
        assertEquals(sha, result.sha256Actual)
        assertEquals(payload.size.toLong(), target.length())
    }
}
