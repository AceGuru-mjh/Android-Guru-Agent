package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #149 单测：MCP stdio 命令的沙箱启动链路（配置侧）。
 *
 * 覆盖三段：
 * 1. [McpServerConfig.runInSandbox] 的序列化兼容 —— 新字段往返一致；旧版
 *    JSON（无该字段）反序列化为默认 false，不崩；
 * 2. [McpClient] 的 launcher 注入 —— 自定义 [McpProcessLauncher] 被真实
 *    使用，且拿到完整命令行与 env（用「记录参数后抛标记异常」的假 launcher
 *    验证，不需要真进程）；
 * 3. [McpManager] 的路由 —— 只有 runInSandbox=true 的配置走注入的沙箱
 *    launcher，其余配置保持宿主直接 fork 的旧行为。
 *
 * 风格与 ToolV3InfraTest 一致：JUnit4 + 纯 JVM 真实实例，无 mock 框架。
 */
class SandboxMcpConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═════════════════════════════════════════════════════════════════
    //  序列化兼容
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `sandbox flag roundtrips through json persistence`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = McpServerConfig(
            name = "fs-sandbox",
            transport = McpTransport.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/sdcard"),
            env = mapOf("NODE_OPTIONS" to "--max-old-space-size=512"),
            runInSandbox = true
        )
        val encoded = json.encodeToString(original)
        // 值非默认 → 即使 encodeDefaults=false（McpManager 的落盘 Json 正是如此）
        // 也必须写出该键，否则重启后沙箱开关丢失
        assertTrue(encoded.contains("runInSandbox"))
        val decoded = json.decodeFromString<McpServerConfig>(encoded)
        assertEquals(original, decoded)
        assertTrue(decoded.runInSandbox)
    }

    @Test
    fun `legacy config json without sandbox flag decodes to false`() {
        // 旧版本写出的 mcp_servers.json 没有该字段 —— 必须平滑落到默认值
        val legacy = """
            {
                "name": "memory",
                "transport": "STDIO",
                "command": "npx",
                "args": ["-y", "@modelcontextprotocol/server-memory"]
            }
        """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<McpServerConfig>(legacy)
        assertFalse(decoded.runInSandbox)
        assertEquals("npx", decoded.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-memory"), decoded.args)
    }

    // ═════════════════════════════════════════════════════════════════
    //  McpClient 的 launcher 注入
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `custom process launcher receives full command line and env`() = runTest {
        val launcher = RecordingLauncher(LAUNCHER_MARKER)
        val config = McpServerConfig(
            name = "fs-sandbox",
            transport = McpTransport.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/sdcard"),
            env = mapOf("NODE_ENV" to "production"),
            runInSandbox = true
        )
        val client = McpClient(config, processLauncher = launcher)

        val result = client.initialize()

        // 握手必然失败 —— 但失败原因必须来自注入的 launcher（证明它被真实使用，
        // 而不是回退到 JvmProcessLauncher 去真的 fork npx）
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "失败消息应包含假 launcher 的标记，实际是：$message",
            message.contains(LAUNCHER_MARKER)
        )
        assertTrue(launcher.launchCount >= 1)
        assertEquals(
            listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/sdcard"),
            launcher.command
        )
        assertEquals(mapOf("NODE_ENV" to "production"), launcher.env)
        assertNull(launcher.workingDir)
    }

    // ═════════════════════════════════════════════════════════════════
    //  McpManager 的沙箱路由
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `manager routes sandbox configs to injected launcher only`() = runTest {
        val launcher = RecordingLauncher(LAUNCHER_MARKER)
        val manager = McpManager(
            configDir = tmp.newFolder(),
            sandboxProcessLauncher = launcher
        )
        manager.addServer(
            McpServerConfig(
                name = "in-sandbox",
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-filesystem"),
                runInSandbox = true
            )
        )
        manager.addServer(
            McpServerConfig(
                name = "on-host",
                transport = McpTransport.STDIO,
                command = "apex-definitely-missing-executable",
                runInSandbox = false
            )
        )

        // 沙箱配置：失败消息来自注入的 launcher
        val sandboxResult = manager.connect("in-sandbox")
        assertTrue(sandboxResult.isFailure)
        assertTrue(
            sandboxResult.exceptionOrNull()?.message.orEmpty().contains(LAUNCHER_MARKER)
        )
        val countAfterSandbox = launcher.launchCount
        assertTrue(countAfterSandbox >= 1)

        // 对照组：未开沙箱的配置走宿主 fork（命令不存在而失败），
        // 绝不能碰注入的 launcher（调用数零增量）
        val hostResult = manager.connect("on-host")
        assertTrue(hostResult.isFailure)
        assertFalse(
            hostResult.exceptionOrNull()?.message.orEmpty().contains(LAUNCHER_MARKER)
        )
        assertEquals(countAfterSandbox, launcher.launchCount)
    }

    // ═════════════════════════════════════════════════════════════════
    //  测试替身
    // ═════════════════════════════════════════════════════════════════

    /**
     * 假沙箱 launcher：记录收到的参数后抛带标记的异常。
     * 标记出现在 McpClient.initialize 的失败消息里 = 注入的 launcher 被
     * 真实使用（无需在测试里跑真进程）。
     */
    private class RecordingLauncher(
        private val marker: String
    ) : McpProcessLauncher {
        var launchCount = 0
            private set
        var command: List<String> = emptyList()
            private set
        var env: Map<String, String> = emptyMap()
            private set
        var workingDir: File? = null
            private set

        override fun launch(
            command: List<String>,
            env: Map<String, String>,
            workingDir: File?
        ): McpProcessHandle {
            launchCount++
            this.command = command
            this.env = env
            this.workingDir = workingDir
            throw UnsupportedOperationException(marker)
        }
    }

    companion object {
        private const val LAUNCHER_MARKER = "apex-fake-sandbox-launcher-marker"
    }
}
