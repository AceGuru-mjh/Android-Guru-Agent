package com.apex.agent.mcp.proot

import com.apex.agent.platform.terminal.proot.PRootArgvCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T92（D5 完成度）：ProotMcpProcessLauncher 的 argv 能力门测试。
 *
 * 本类是全仓库唯一**不走** PRootCommandBuilder 的 proot argv 构造点
 * （argv 手工内联拼装）—— T91 贯通了 builder 路径却留下这里硬编码
 * `--kill-on-exit` 与 `--`（-E 事故同构风险的最后一个残留点）。本测试
 * 锁定手工能力门与 builder 行为逐项一致：
 *
 * | 能力集 | `--kill-on-exit` | `--` |
 * |---|---|---|
 * | TERMUX_BUNDLED（默认/设备生产） | 发 | 发 |
 * | UPSTREAM_SAFE（upstream 5.1.0） | 省 | 省 |
 * | Debian 5.4 混合形态 | 发 | 省 |
 */
class ProotMcpProcessLauncherCapabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun launcher(capabilities: () -> PRootArgvCapabilities): ProotMcpProcessLauncher =
        ProotMcpProcessLauncher(
            hostEnv = mapOf("PATH" to "/system/bin"),
            libprootPath = "/fake/nativeDir/libproot.so",
            rootfsDir = tmp.newFolder("rootfs"),
            isRootfsReady = { true },
            capabilities = capabilities
        )

    private fun argvOf(
        launcher: ProotMcpProcessLauncher,
        command: List<String> = listOf("node", "mcp-server.js")
    ): List<String> = launcher.buildArgv(
        rootfs = tmp.newFolder("r"),
        command = command,
        requestEnv = emptyMap()
    )

    private val envIdx: (List<String>) -> Int = { argv -> argv.indexOf("/usr/bin/env") }

    @Test
    fun `TERMUX_BUNDLED baseline argv is byte-identical to pre-T92 shape`() {
        val argv = argvOf(launcher { PRootArgvCapabilities.TERMUX_BUNDLED })
        assertTrue("kill-on-exit kept", argv.contains("--kill-on-exit"))
        assertEquals("separator right before env trampoline", "--", argv[envIdx(argv) - 1])
        assertEquals("/fake/nativeDir/libproot.so", argv[0])
        assertEquals("-r", argv[1])
        assertEquals("-0", argv[3])
        assertEquals("/usr/bin/env", argv[envIdx(argv)])
        assertEquals("-i", argv[envIdx(argv) + 1])
        assertEquals("guest 命令尾随 argv 之后", listOf("node", "mcp-server.js"), argv.takeLast(2))
    }

    @Test
    fun `UPSTREAM_SAFE omits both hardcoded flags but keeps trampoline boundary`() {
        val argv = argvOf(launcher { PRootArgvCapabilities.UPSTREAM_SAFE })
        assertFalse("upstream 5.1.0 不认 --kill-on-exit（拒启）", argv.contains("--kill-on-exit"))
        assertFalse("upstream 不认 --（拒启）", argv.contains("--"))
        // trampoline 首 token /usr/bin/env 是非选项 token —— 天然分界
        assertTrue("env trampoline present", envIdx(argv) > 0)
        assertEquals("-i", argv[envIdx(argv) + 1])
        assertEquals("guest 命令尾随 argv 之后", listOf("node", "mcp-server.js"), argv.takeLast(2))
    }

    @Test
    fun `Debian 5_4 hybrid keeps kill-on-exit and omits separator`() {
        val argv = argvOf(launcher { PRootArgvCapabilities(supportsKillOnExit = true, supportsOptionSeparator = false) })
        assertTrue("Debian 5.4 支持 --kill-on-exit", argv.contains("--kill-on-exit"))
        assertFalse("Debian 5.4 不支持 --", argv.contains("--"))
        assertTrue("env trampoline present", envIdx(argv) > 0)
    }

    @Test
    fun `default constructor baseline stays TERMUX_BUNDLED (existing fixtures unchanged)`() {
        // 不传 capabilities 的既有构造形态 → 默认 Termux 基线（既有测试夹具
        // 与 DI 未接线场景的兼容语义 —— 生产 DI 由 TerminalModule 注入真实源）
        val argv = argvOf(
            ProotMcpProcessLauncher(
                hostEnv = mapOf("PATH" to "/system/bin"),
                libprootPath = "/fake/libproot.so",
                rootfsDir = tmp.newFolder("rootfs2"),
                isRootfsReady = { true }
            )
        )
        assertTrue(argv.contains("--kill-on-exit"))
        assertEquals("--", argv[envIdx(argv) - 1])
    }

    /**
     * P1 修复（home bind 路径推导）回归锁：生产布局
     * `rootfsDir = <filesDir>/rootfs/ubuntu` 时，持久化 home 必须解析到
     * `<filesDir>/linux/home`（上推两级），bind 为 `.../linux/home:/root`。
     * 旧实现只上推一级（`<filesDir>/rootfs/linux/home` 永不存在）→ bind
     * 被静默跳过，KDoc 承诺的终端/MCP 共享 home 全部落空。
     */
    @Test
    fun `persistent home bind resolves to filesDir slash linux slash home in production layout`() {
        val filesDir = tmp.newFolder("app-files")
        // 生产布局：<filesDir>/rootfs/ubuntu（TerminalModule 的 rootfsBaseDir）
        val rootfsBase = File(filesDir, "rootfs/ubuntu").apply { mkdirs() }
        // 持久化 home：<filesDir>/linux/home（TerminalModule provideGuestUserHome）
        val home = File(filesDir, "linux/home").apply { mkdirs() }

        val launcher = ProotMcpProcessLauncher(
            hostEnv = mapOf("PATH" to "/system/bin"),
            libprootPath = "/fake/nativeDir/libproot.so",
            rootfsDir = rootfsBase,
            isRootfsReady = { true }
        )

        val homeBind = launcher.buildBinds().firstOrNull { it.second == "/root" }
        assertNotNull("home bind 必须存在（旧实现推导错路径被静默跳过）", homeBind)
        assertEquals(
            "bind 源必须是 <filesDir>/linux/home（上推两级），而不是 <filesDir>/rootfs/linux/home",
            home.absolutePath,
            homeBind!!.first
        )
    }

    /** home 目录不存在（终端从未初始化）→ 诚实跳过 bind，不抛错。 */
    @Test
    fun `persistent home bind is skipped honestly when directory missing`() {
        val filesDir = tmp.newFolder("app-files-2")
        val rootfsBase = File(filesDir, "rootfs/ubuntu").apply { mkdirs() }

        val launcher = ProotMcpProcessLauncher(
            hostEnv = mapOf("PATH" to "/system/bin"),
            libprootPath = "/fake/nativeDir/libproot.so",
            rootfsDir = rootfsBase,
            isRootfsReady = { true }
        )

        assertFalse(
            "home 目录不存在时不应有 /root bind",
            launcher.buildBinds().any { it.second == "/root" }
        )
    }
}
