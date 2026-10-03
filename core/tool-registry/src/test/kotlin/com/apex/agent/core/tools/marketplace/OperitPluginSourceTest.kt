package com.apex.agent.core.tools.marketplace

import com.apex.agent.core.tools.mcp.McpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OperitPluginSource] 纯解析单测：GitHub search 响应 → 插件条目；
 * mcp.json / package.json 的 mcpServers 探测（标准外壳 / 裸服务器对象
 * 包壳 / STDIO 沙箱路由）。夹具取自 GitHub API 真实响应形状的最小化切片。
 */
class OperitPluginSourceTest {

    // ═══ 目录列表解析 ═══

    @Test
    fun `parseOperitSearchItems extracts fullName stars topics and mcp flag`() {
        val body = """
            {
              "total_count": 2,
              "items": [
                {
                  "full_name": "x15907982411/playwright-mcp-for-operit",
                  "description": "Playwright MCP for Operit",
                  "html_url": "https://github.com/x15907982411/playwright-mcp-for-operit",
                  "stargazers_count": 3,
                  "topics": ["ai-agent", "android", "mcp"]
                },
                {
                  "full_name": "MengXinSu/LocalDream-Operit-Plugin",
                  "description": "LocalDream 手机本地AI生图插件",
                  "html_url": "https://github.com/MengXinSu/LocalDream-Operit-Plugin",
                  "stargazers_count": 21,
                  "topics": ["operit", "plugin", "stable-diffusion"]
                }
              ]
            }
        """.trimIndent()
        val items = OperitPluginSource.parseOperitSearchItems(body).orEmpty()
        assertEquals(2, items.size)
        val mcp = items[0]
        assertEquals("x15907982411/playwright-mcp-for-operit", mcp.fullName)
        assertEquals(3, mcp.stars)
        assertTrue(mcp.isMcp)
        assertEquals("operit/x15907982411/playwright-mcp-for-operit", mcp.key)
        assertEquals("x15907982411-playwright-mcp-for-operit", mcp.configName)
        // 第二个条目 topics 与名称都无 mcp 特征 → isMcp 必须为 false
        //（防过度打标：非 MCP 插件点安装会得到明确报错而非误装）
        assertTrue(!items[1].isMcp)
    }

    @Test
    fun `parseOperitSearchItems skips entries without fullName and survives garbage`() {
        val body = """
            {"items": [
                {"full_name": "", "stargazers_count": 1},
                {"description": "no full_name"},
                "not-an-object"
            ]}
        """.trimIndent()
        val items = OperitPluginSource.parseOperitSearchItems(body).orEmpty()
        assertTrue(items.isEmpty())
        // 畸形 JSON → null（调用方合并判定）
        assertNull(OperitPluginSource.parseOperitSearchItems("not json at all"))
    }

    // ═══ mcp.json 探测解析 ═══

    @Test
    fun `parseMcpConfigText imports standard mcpServers shell with sandbox routing`() {
        val text = """
            {"mcpServers": {"my-server": {"command": "npx",
                "args": ["-y", "@x/mcp-server"], "env": {"K": "V"}}}}
        """.trimIndent()
        val config = OperitPluginSource.parseMcpConfigText(text, "my-plugin")
        assertEquals("my-plugin", config?.name)
        assertEquals(McpTransport.STDIO, config?.transport)
        assertEquals("npx", config?.command)
        // STDIO 一律路由沙箱（与 mcp.so 源同口径）
        assertTrue(config?.runInSandbox == true)
        // 安装 ≠ 启动
        assertTrue(config?.enabled == false)
    }

    @Test
    fun `parseMcpConfigText accepts operit mcp_config shape with extra keys`() {
        // 夹具取自 RaineIris/operit-xhs-reader-mcp 真实 mcp_config.json：
        // Operit 生态约定文件名，含 autoApprove 等 Operit 专有额外键
        //（统一解析只认社区通用键，未知键自然忽略）。
        val text = """
            {"mcpServers": {"xhs_reader": {"command": "python3",
                "args": ["main.py"],
                "env": {"HUNYUAN_API_KEY": "sk-请在此处填入真实混元APIKey"},
                "autoApprove": []}}}
        """.trimIndent()
        val config = OperitPluginSource.parseMcpConfigText(text, "raineiris-operit-xhs-reader-mcp")
        assertEquals("raineiris-operit-xhs-reader-mcp", config?.name)
        assertEquals(McpTransport.STDIO, config?.transport)
        assertEquals("python3", config?.command)
        assertEquals(listOf("main.py"), config?.args)
        assertEquals("sk-请在此处填入真实混元APIKey", config?.env?.get("HUNYUAN_API_KEY"))
        assertTrue(config?.runInSandbox == true)
        assertTrue(config?.enabled == false)
    }

    @Test
    fun `parseMcpConfigText wraps bare server object into mcpServers shell`() {
        val text = """
            {"command": "python", "args": ["server.py"]}
        """.trimIndent()
        val config = OperitPluginSource.parseMcpConfigText(text, "bare-server")
        assertEquals("bare-server", config?.name)
        assertEquals(McpTransport.STDIO, config?.transport)
        assertEquals("python", config?.command)
        assertTrue(config?.runInSandbox == true)
    }

    @Test
    fun `parseMcpConfigText keeps remote entries out of sandbox`() {
        val text = """
            {"mcpServers": {"remote": {"type": "streamable_http", "url": "https://x/mcp"}}}
        """.trimIndent()
        val config = OperitPluginSource.parseMcpConfigText(text, "remote-plugin")
        assertEquals(McpTransport.HTTP, config?.transport)
        assertTrue(config?.runInSandbox == false)
    }

    @Test
    fun `parseMcpConfigText returns null for unparseable or empty-entry config`() {
        assertNull(OperitPluginSource.parseMcpConfigText("not json", "x"))
        // mcpServers 存在但条目既无 command 也无 url → McpConfigImport 报错 → null
        assertNull(OperitPluginSource.parseMcpConfigText("""{"mcpServers": {"bad": {}}}""", "x"))
    }

    // ═══ package.json 的 mcpServers 键探测 ═══

    @Test
    fun `packageJsonMcpServersText extracts mcpServers from package json`() {
        val packageJson = """
            {"name": "my-mcp-plugin", "version": "1.0.0",
             "bin": {"my-mcp-plugin": "dist/index.js"},
             "mcpServers": {"my-mcp-plugin": {"command": "node", "args": ["dist/index.js"]}}}
        """.trimIndent()
        val serversText = OperitPluginSource.packageJsonMcpServersText(packageJson)
        assertEquals(
            """{"mcpServers":{"my-mcp-plugin":{"command":"node","args":["dist/index.js"]}}}"""
                .replace(" ", ""),
            serversText?.replace(" ", "")
        )
        // 提取产物可再走统一解析
        val config = OperitPluginSource.parseMcpConfigText(serversText!!, "pkg-plugin")
        assertEquals("pkg-plugin", config?.name)
        assertEquals("node", config?.command)
    }

    @Test
    fun `packageJsonMcpServersText returns null when key missing or corrupt`() {
        assertNull(OperitPluginSource.packageJsonMcpServersText("""{"name": "x"}"""))
        assertNull(OperitPluginSource.packageJsonMcpServersText("not json"))
        // mcpServers 不是对象 → 视为无有效配置
        assertNull(OperitPluginSource.packageJsonMcpServersText("""{"mcpServers": [1, 2]}"""))
    }

    // ═══ 双查询合并（mergeDirectory）═══

    private fun plugin(fullName: String, stars: Int = 0) =
        OperitPluginSource.OperitPlugin(
            fullName = fullName,
            description = "",
            htmlUrl = "https://github.com/$fullName",
            stars = stars,
            topics = emptyList(),
            isMcp = false
        )

    @Test
    fun `mergeDirectory dedupes by fullName and sorts by stars descending`() {
        val topicHits = listOf(plugin("a/hot", 50), plugin("b/overlap", 10))
        val keywordHits = listOf(plugin("b/overlap", 10), plugin("c/mid", 30))
        val directory = OperitPluginSource.mergeDirectory(topicHits, keywordHits)
        assertEquals(listOf("a/hot", "c/mid", "b/overlap"), directory.plugins.map { it.fullName })
        // 双路都成功 → 无降级提示
        assertNull(directory.partialError)
    }

    @Test
    fun `mergeDirectory marks partial error when only one query succeeded`() {
        // 匿名限流 10 次/分下真实高频：一路 403、另一路正常
        val directory = OperitPluginSource.mergeDirectory(
            listOf(plugin("a/only", 5)),
            null
        )
        assertEquals(listOf("a/only"), directory.plugins.map { it.fullName })
        // 列表照给，但降级提示必须在（错误横幅可重试，不静默截断）
        assertTrue(directory.partialError != null)
    }

    @Test
    fun `mergeDirectory with both queries failed is not reachable via merge`() {
        // 双失败由 listPlugins 整体 failure（不发 merge）；合并层对双 null
        // 的行为定义为空目录 + 降级提示，防御式兑现
        val directory = OperitPluginSource.mergeDirectory(null, null)
        assertTrue(directory.plugins.isEmpty())
        assertTrue(directory.partialError != null)
    }
}
