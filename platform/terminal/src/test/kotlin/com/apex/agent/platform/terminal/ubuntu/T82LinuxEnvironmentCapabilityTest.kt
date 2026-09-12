package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.environment.ProxyConfig
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * T82 — Linux 环境能力回归：locale.gen / timezone 写入、镜像注册表与切换、
 * guest 代理 env 注入（Termux 基线 §3.5/§3.6/§5.3/§9.3）。
 */
class T82LinuxEnvironmentCapabilityTest {

    private fun tempRoot(): File = File.createTempFile("rootfs", "").let { it.delete(); it.mkdirs(); it }

    // ─── RootfsConfigurator: locale + timezone（基线 §3.5/§3.6）───

    @Test fun `configurator writes locale gen lines idempotently`() {
        val root = tempRoot()
        val c1 = RootfsConfigurator(localeGen = listOf("zh_CN.UTF-8 UTF-8", "en_US.UTF-8 UTF-8"))
        c1.configure(root)
        val localeGen = File(root, "etc/locale.gen")
        assertTrue(localeGen.isFile)
        val text = localeGen.readText()
        assertTrue(text.contains("zh_CN.UTF-8 UTF-8"))
        assertTrue(text.contains("en_US.UTF-8 UTF-8"))
        // 幂等：第二次 configure 不重复追加
        c1.configure(root)
        assertEquals(text, localeGen.readText())
    }

    @Test fun `configurator writes timezone file and localtime link`() {
        val root = tempRoot()
        // tzdata 尚未安装 → 链接 dangling（如实，安装后 postinst 收敛）
        RootfsConfigurator(timezone = "Asia/Shanghai").configure(root)
        assertEquals("Asia/Shanghai\n", File(root, "etc/timezone").readText())
        val link = java.nio.file.Files.readSymbolicLink(File(root, "etc/localtime").toPath()).toFile()
        assertEquals(File(root, "usr/share/zoneinfo/Asia/Shanghai").toPath(), link.toPath())
    }

    @Test fun `default configurator keeps legacy behavior (no locale gen, no timezone write)`() {
        val root = tempRoot()
        RootfsConfigurator().configure(root)
        assertFalse(File(root, "etc/locale.gen").isFile)
        assertFalse(File(root, "etc/timezone").isFile)
    }

    @Test fun `essential profile includes locales and tzdata (T82 baseline)`() {
        val essential = BasePackageProfile.DEFAULT.essential
        assertTrue(essential.contains("locales"))
        assertTrue(essential.contains("tzdata"))
    }

    // ─── 镜像（基线 §5.3）───

    @Test fun `mirror registry resolves per architecture`() {
        // arm64 → ports 家族；amd64 → archive 家族
        val tunaArm = AptMirrorRegistry.mirrorFor("tuna", CpuArchitecture.ARM64)!!
        assertEquals("mirrors.tuna.tsinghua.edu.cn", tunaArm.host)
        assertEquals("ubuntu-ports", tunaArm.path)
        val tunaAmd = AptMirrorRegistry.mirrorFor("tuna", CpuArchitecture.X86_64)!!
        assertEquals("ubuntu", tunaAmd.path)
        val officialArm = AptMirrorRegistry.mirrorFor(null, CpuArchitecture.ARM64)!!
        assertEquals("ports.ubuntu.com", officialArm.host)
        val officialAmd = AptMirrorRegistry.mirrorFor("official", CpuArchitecture.X86_64)!!
        assertEquals("archive.ubuntu.com", officialAmd.host)
        assertNull(AptMirrorRegistry.mirrorFor("nonexistent", CpuArchitecture.ARM64))
    }

    @Test fun `sources apply switches mirror with force and is idempotent afterwards`() {
        val root = tempRoot()
        val sources = UbuntuSourcesList()
        // 初始：官方（写入）
        val first = sources.ensure(root, CpuArchitecture.ARM64)
        assertTrue(first.written)
        assertEquals("ports.ubuntu.com", first.mirrorHost)
        // 切到 TUNA（force 重写）
        val switched = sources.apply(root, CpuArchitecture.ARM64, "tuna", force = true)
        assertTrue(switched.written)
        assertEquals("mirrors.tuna.tsinghua.edu.cn", switched.mirrorHost)
        val content = File(root, "etc/apt/sources.list.d/ubuntu.sources").readText()
        assertTrue(content.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"))
        // 幂等：再次 ensure（当前 = TUNA 构造的实例）不重写
        val tunaSources = UbuntuSourcesList(mirrorId = "tuna")
        val again = tunaSources.ensure(root, CpuArchitecture.ARM64)
        assertFalse(again.written)
        // 未知镜像 → 结构化失败，不碰文件
        val before = content
        val bad = sources.apply(root, CpuArchitecture.ARM64, "nope", force = true)
        assertFalse(bad.written)
        assertTrue(bad.actions.first().startsWith("SourcesError:UnknownMirror"))
        assertEquals(before, File(root, "etc/apt/sources.list.d/ubuntu.sources").readText())
    }

    // ─── 代理 env（基线 §9.3）───

    @Test fun `proxy config injects guest env and request env still wins`() {
        val env = LinuxEnvironmentManager(
            proxy = ProxyConfig(host = "192.168.1.8", port = 8888, noProxy = listOf("localhost", "10.0.0.0/8"))
        )
        val guest = env.interactiveGuestEnv()
        assertEquals("http://192.168.1.8:8888", guest["http_proxy"])
        assertEquals("http://192.168.1.8:8888", guest["https_proxy"])
        assertEquals("http://192.168.1.8:8888", guest["all_proxy"])
        assertEquals("localhost,10.0.0.0/8", guest["no_proxy"])
        // apt 变体同样携带
        assertEquals("http://192.168.1.8:8888", env.aptGuestEnv()["http_proxy"])
        // 显式 requestEnv 覆盖（调用方意图优先）
        val overridden = env.interactiveGuestEnv(mapOf("http_proxy" to "http://direct:1"))
        assertEquals("http://direct:1", overridden["http_proxy"])
    }

    @Test fun `no proxy config keeps legacy env keys exactly`() {
        val env = LinuxEnvironmentManager()
        val guest = env.interactiveGuestEnv()
        assertNull(guest["http_proxy"])
        assertNull(guest["no_proxy"])
        assertEquals(11, guest.size)
    }
}
