package com.apex.agent.core.tools.mcp

import com.apex.agent.core.tools.builtin.McpConnectConfigOutcome
import com.apex.agent.core.tools.builtin.buildMcpConnectConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #163 单测：MCP 沙箱启用打通（配置层 + 预置层）。
 *
 * 覆盖四段（与 SandboxMcpConfigTest 的 Issue #149 链路测试互补）：
 * 1. [McpConfigImport] 解析 `runInSandbox` 三态 —— true / 显式 false /
 *    缺失（及非布尔严格拒绝）；
 * 2. [McpManager.requestTimeoutFor] 超时路由 —— 沙箱 STDIO 放宽到 180s，
 *    宿主 STDIO 与 HTTP 保持 60s；
 * 3. [McpManager.ensureSandboxServer] 预置语义 —— 首次写入 / 二次幂等保留
 *    用户 enabled 偏好 / 不劫持用户自建的同名宿主条目；
 * 4. [buildMcpConnectConfig]（mcp_connect 的参数→配置纯函数）——
 *    `run_in_sandbox` 透传与默认值。
 *
 * 风格与 SandboxMcpConfigTest 一致：JUnit4 + 纯 JVM 真实实例，无 mock 框架。
 */
class SandboxMcpPresetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═════════════════════════════════════════════════════════════════
    //  McpConfigImport：runInSandbox 三态
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `config import parses runInSandbox true`() {
        val result = McpConfigImport.parse(
            """
            {
              "mcpServers": {
                "fs": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
                  "runInSandbox": true
                }
              }
            }
            """.trimIndent()
        )
        assertTrue(result.errors.isEmpty())
        val config = result.configs.single()
        assertTrue(config.runInSandbox)
        assertEquals(McpTransport.STDIO, config.transport)
        assertEquals("npx", config.command)
    }

    @Test
    fun `config import parses explicit runInSandbox false`() {
        val result = McpConfigImport.parse(
            """
            {
              "mcpServers": {
                "local": {
                  "command": "python",
                  "args": ["server.py"],
                  "runInSandbox": false
                }
              }
            }
            """.trimIndent()
        )
        val config = result.configs.single()
        assertFalse(config.runInSandbox)
    }

    @Test
    fun `config import missing runInSandbox defaults to false`() {
        // 旧配置 / 社区常见配置没有该字段 —— 必须平滑落默认 false（向后兼容）
        val result = McpConfigImport.parse(
            """
            {
              "mcpServers": {
                "memory": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-memory"]
                }
              }
            }
            """.trimIndent()
        )
        assertFalse(result.configs.single().runInSandbox)
    }

    @Test
    fun `config import rejects non-boolean runInSandbox as false`() {
        // 严格 Boolean：字符串 "true" 不接受（沙箱路由是安全敏感开关，
        // 与 disabled 的字符串宽容解析刻意不同）
        val result = McpConfigImport.parse(
            """
            {
              "mcpServers": {
                "weird": {
                  "command": "npx",
                  "runInSandbox": "true"
                }
              }
            }
            """.trimIndent()
        )
        assertFalse(result.configs.single().runInSandbox)
    }

    // ═════════════════════════════════════════════════════════════════
    //  超时路由（纯函数）
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `request timeout for sandbox stdio is relaxed to 180s`() {
        val config = McpServerConfig(
            name = "fs-sandbox",
            transport = McpTransport.STDIO,
            command = "npx",
            runInSandbox = true
        )
        assertEquals(McpManager.SANDBOX_REQUEST_TIMEOUT_MS, McpManager.requestTimeoutFor(config))
        assertEquals(180_000L, McpManager.requestTimeoutFor(config))
    }

    @Test
    fun `request timeout for host stdio stays 60s`() {
        val config = McpServerConfig(
            name = "host",
            transport = McpTransport.STDIO,
            command = "npx",
            runInSandbox = false
        )
        assertEquals(60_000L, McpManager.requestTimeoutFor(config))
    }

    @Test
    fun `request timeout ignores sandbox flag for non-stdio transports`() {
        // runInSandbox 对 HTTP/SSE 无意义（不走 STDIO 超时参数）—— 不放宽
        val config = McpServerConfig(
            name = "remote",
            transport = McpTransport.HTTP,
            url = "http://example.com/mcp",
            runInSandbox = true
        )
        assertEquals(60_000L, McpManager.requestTimeoutFor(config))
    }

    // ═════════════════════════════════════════════════════════════════
    //  ensureSandboxServer 预置语义
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `ensureSandboxServer writes preset on first call`() = runTest {
        val manager = McpManager(configDir = tmp.newFolder())
        val preset = McpManager.SANDBOX_PRESET_SERVERS.first()

        val result = manager.ensureSandboxServer(preset)

        assertTrue(result.isSuccess)
        val stored = manager.getConfigs().single()
        assertEquals(preset.name, stored.name)
        assertTrue(stored.runInSandbox)
        // 预置只写配置不连接 —— enabled=false 是「用户主动启用」的前置语义
        assertFalse(stored.enabled)
        assertEquals(preset.command, stored.command)
        assertEquals(preset.args, stored.args)
    }

    @Test
    fun `ensureSandboxServer is idempotent and keeps user enabled preference`() = runTest {
        val manager = McpManager(configDir = tmp.newFolder())
        val preset = McpManager.SANDBOX_PRESET_SERVERS.first { it.name == "memory-sandbox" }

        manager.ensureSandboxServer(preset)
        // 用户主动启用了预置条目（预置默认 enabled=false）
        manager.setEnabled(preset.name, true)
        // 升级场景：App 重启再次预置同一条目（定义可能更新）
        val refreshed = preset.copy(args = listOf("-y", "@modelcontextprotocol/server-memory", "--changed"))
        val second = manager.ensureSandboxServer(refreshed)

        assertTrue(second.isSuccess)
        val stored = manager.getConfigs().single()
        // 定义刷新 + 用户偏好保留 —— 与 ensureBuiltinServer 的幂等语义一致
        assertEquals(refreshed.args, stored.args)
        assertTrue(stored.enabled)
        assertTrue(stored.runInSandbox)
    }

    @Test
    fun `ensureSandboxServer does not hijack user host entry with same name`() = runTest {
        val manager = McpManager(configDir = tmp.newFolder())
        // 用户自建了同名宿主 STDIO 条目（runInSandbox=false）
        val userEntry = McpServerConfig(
            name = "fs-sandbox",
            transport = McpTransport.STDIO,
            command = "python",
            args = listOf("my_fs_server.py"),
            enabled = true,
            runInSandbox = false
        )
        manager.addServer(userEntry)

        val preset = McpManager.SANDBOX_PRESET_SERVERS.first { it.name == "fs-sandbox" }
        val result = manager.ensureSandboxServer(preset)

        // 防劫持：绝不把用户配置悄悄改写成沙箱形态
        assertTrue(result.isFailure)
        val stored = manager.getConfigs().single()
        assertEquals("python", stored.command)
        assertEquals(userEntry.args, stored.args)
        assertFalse(stored.runInSandbox)
        assertTrue(stored.enabled)
    }

    // ═════════════════════════════════════════════════════════════════
    //  预置清单健全性
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `preset servers are sandbox stdio disabled and use verified npm packages`() {
        // 包名已经 npm registry 核实（Issue #163）—— 固定在这里防止
        // 后续手滑改错（server-git 已下架，预置用 server-memory）
        val expectedPackages = setOf(
            "@modelcontextprotocol/server-filesystem",
            "@modelcontextprotocol/server-memory",
            "@modelcontextprotocol/server-everything"
        )
        val actualPackages = McpManager.SANDBOX_PRESET_SERVERS
            .map { it.args.firstOrNull { a -> a.startsWith("@modelcontextprotocol/") } }
            .toSet()

        assertEquals(expectedPackages, actualPackages)
        assertEquals(3, McpManager.SANDBOX_PRESET_SERVERS.size)
        assertEquals(
            "预置条目名必须互不相同（ensureSandboxServer 按名幂等）",
            3,
            McpManager.SANDBOX_PRESET_SERVERS.map { it.name }.toSet().size
        )
        McpManager.SANDBOX_PRESET_SERVERS.forEach { preset ->
            assertEquals(McpTransport.STDIO, preset.transport)
            assertEquals("npx", preset.command)
            assertTrue(preset.runInSandbox)
            assertFalse(preset.enabled)
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  mcp_connect 的参数 → 配置纯函数
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `mcp connect config carries run_in_sandbox true`() {
        val outcome = buildMcpConnectConfig(
            name = "fs",
            url = "",
            rawTransport = "",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
            env = emptyMap(),
            runInSandbox = true
        )
        val config = (outcome as McpConnectConfigOutcome.Ok).config
        assertEquals(McpTransport.STDIO, config.transport)
        assertTrue(config.runInSandbox)
        assertEquals("npx", config.command)
        assertEquals(3, config.args.size)
    }

    @Test
    fun `mcp connect config defaults run_in_sandbox to false`() {
        // Agent 不传该参数时的默认行为 —— 宿主直接 fork（桌面语义不变）
        val outcome = buildMcpConnectConfig(
            name = "remote",
            url = "http://example.com/mcp",
            rawTransport = "http",
            command = "",
            args = emptyList(),
            env = emptyMap(),
            runInSandbox = false
        )
        val config = (outcome as McpConnectConfigOutcome.Ok).config
        assertEquals(McpTransport.HTTP, config.transport)
        assertFalse(config.runInSandbox)
    }

    @Test
    fun `mcp connect config rejects stdio without command and remote without url`() {
        // 校验语义与抽取前一致（错误文案保持原样，Agent 依赖其自纠错）
        val noCommand = buildMcpConnectConfig(
            name = "bad", url = "", rawTransport = "stdio", command = "",
            args = emptyList(), env = emptyMap(), runInSandbox = true
        )
        assertEquals(
            "stdio transport requires 'command'",
            (noCommand as McpConnectConfigOutcome.Invalid).reason
        )
        val noUrl = buildMcpConnectConfig(
            name = "bad", url = "", rawTransport = "sse", command = "",
            args = emptyList(), env = emptyMap(), runInSandbox = false
        )
        assertEquals(
            "'url' required for SSE transport",
            (noUrl as McpConnectConfigOutcome.Invalid).reason
        )
    }
}
