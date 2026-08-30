package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T76: UbuntuSourcesList 单元测试 —— 幂等 + 架构感知。
 */
class UbuntuSourcesListTest {

    private val sources = UbuntuSourcesList()

    private fun tempRootfs(): File =
        Files.createTempDirectory("t76-rootfs-").toFile().apply {
            File(this, "etc/apt/sources.list.d").mkdirs()
        }

    @Test fun `arm64 uses ports ubuntu com`() {
        val rootfs = tempRootfs()
        val result = sources.ensure(rootfs, CpuArchitecture.ARM64)
        assertEquals("ports.ubuntu.com", result.mirrorHost)
        assertEquals("ubuntu-ports", result.mirrorPath)
        assertTrue(result.written)
    }

    @Test fun `amd64 uses archive ubuntu com`() {
        val rootfs = tempRootfs()
        val result = sources.ensure(rootfs, CpuArchitecture.X86_64)
        assertEquals("archive.ubuntu.com", result.mirrorHost)
        assertEquals("ubuntu", result.mirrorPath)
    }

    @Test fun `second ensure is idempotent`() {
        val rootfs = tempRootfs()
        val first = sources.ensure(rootfs, CpuArchitecture.ARM64)
        assertTrue(first.written)
        val second = sources.ensure(rootfs, CpuArchitecture.ARM64)
        assertFalse("second call must not rewrite", second.written)
    }

    @Test fun `deb822 file contains Codename noble`() {
        val rootfs = tempRootfs()
        sources.ensure(rootfs, CpuArchitecture.ARM64)
        val file = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources")
        assertTrue(file.isFile)
        val content = file.readText()
        assertTrue(content.contains("Codename: noble") || content.contains("noble "))
        assertTrue(content.contains("ports.ubuntu.com"))
    }

    @Test fun `deb822 file does NOT contain Verify-Peer false`() {
        // T76 §8: 禁止关闭 TLS verification
        val rootfs = tempRootfs()
        sources.ensure(rootfs, CpuArchitecture.ARM64)
        val content = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources").readText()
        assertFalse("must not disable TLS verification", content.contains("Verify-Peer"))
        assertFalse(content.contains("Acquire::https"))
    }

    @Test fun `components include main universe restricted multiverse`() {
        val rootfs = tempRootfs()
        val result = sources.ensure(rootfs, CpuArchitecture.X86_64)
        assertTrue(result.components.contains("main"))
        assertTrue(result.components.contains("universe"))
    }

    @Test fun `inspect returns presence info`() {
        val rootfs = tempRootfs()
        sources.ensure(rootfs, CpuArchitecture.ARM64)
        val inspection = sources.inspect(rootfs)
        assertTrue(inspection.present)
        assertTrue(inspection.mirrorHosts.contains("ports.ubuntu.com"))
    }

    @Test fun `inspect on empty rootfs returns not present`() {
        val rootfs = tempRootfs()
        val inspection = sources.inspect(rootfs)
        assertFalse(inspection.present)
    }

    @Test fun `arch field written correctly for arm64`() {
        val rootfs = tempRootfs()
        sources.ensure(rootfs, CpuArchitecture.ARM64)
        val content = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources").readText()
        assertTrue(content.contains("Architectures: arm64"))
    }

    @Test fun `arch field written correctly for amd64`() {
        val rootfs = tempRootfs()
        sources.ensure(rootfs, CpuArchitecture.X86_64)
        val content = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources").readText()
        assertTrue(content.contains("Architectures: amd64"))
    }
}

/**
 * T76: BasePackageProfile 单元测试。
 */
class BasePackageProfileTest {

    @Test fun `default profile contains ca-certificates`() {
        assertTrue(BasePackageProfile.DEFAULT.essential.contains("ca-certificates"))
    }

    @Test fun `default profile contains git and python3`() {
        assertTrue(BasePackageProfile.DEFAULT.essential.contains("git"))
        assertTrue(BasePackageProfile.DEFAULT.essential.contains("python3"))
    }

    @Test fun `default profile has reasonable size`() {
        // T76 §15: 不装几十/上百个包。默认应在 10-30 之间。
        val count = BasePackageProfile.DEFAULT.essentialCount
        assertTrue("essential count $count too small", count >= 10)
        assertTrue("essential count $count too large (over-provisioned)", count <= 30)
    }

    @Test fun `minimal profile is smaller than default`() {
        assertTrue(BasePackageProfile.MINIMAL.essentialCount < BasePackageProfile.DEFAULT.essentialCount)
    }

    @Test fun `minimal profile contains ca-certificates`() {
        assertTrue(BasePackageProfile.MINIMAL.essential.contains("ca-certificates"))
    }

    @Test fun `recommended is separate from essential`() {
        // recommended 不在 essential 中（Agent 按需装）
        for (pkg in BasePackageProfile.DEFAULT.recommended) {
            assertFalse("$pkg should not be in essential", BasePackageProfile.DEFAULT.essential.contains(pkg))
        }
    }
}
