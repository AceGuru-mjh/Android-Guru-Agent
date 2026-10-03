package com.apex.agent.core.tools.marketplace

import com.apex.agent.core.tools.mcp.McpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GenericRegistryApi] 纯解析单测：目录响应 / 安装决策树 / 配置产出。
 * JSON 夹具取自 registry.modelcontextprotocol.io 真实响应的最小化切片
 * （字段 camelCase、server 元数据嵌套在 servers[].server —— 服务端改版
 * 时按新结构更新夹具即可，断言口径不变）。
 */
class GenericRegistryApiTest {

    // ═══ 目录列表解析 ═══

    @Test
    fun `parseServerList extracts remotes with http preference and nextCursor`() {
        val body = """
            {
              "servers": [
                {
                  "server": {
                    "name": "ac.inference.sh/mcp",
                    "description": "Run 150+ AI apps",
                    "title": "inference.sh",
                    "version": "2.0.1",
                    "remotes": [
                        {"type": "streamable-http", "url": "https://api.inference.sh/mcp"}
                    ]
                  }
                }
              ],
              "metadata": {"nextCursor": "ac.tandem/docs-mcp:0.3.2", "count": 1}
            }
        """.trimIndent()
        val page = GenericRegistryApi.parseServerList(body).getOrThrow()
        assertEquals(1, page.servers.size)
        val server = page.servers[0]
        assertEquals("ac.inference.sh/mcp", server.name)
        assertEquals("inference.sh", server.displayName)
        assertEquals("Run 150+ AI apps", server.description)
        assertEquals("2.0.1", server.version)
        assertEquals("registry/ac.inference.sh/mcp", server.key)
        assertEquals(RegistryServer.InstallKind.REMOTE, server.installKind)
        assertEquals("https://api.inference.sh/mcp", server.bestRemote?.url)
        assertEquals("ac.tandem/docs-mcp:0.3.2", page.nextCursor)
    }

    @Test
    fun `parseServerList prefers streamable-http over sse for bestRemote`() {
        val body = """
            {
              "servers": [
                {"server": {"name": "x/a", "remotes": [
                    {"type": "sse", "url": "https://a/sse"},
                    {"type": "streamable-http", "url": "https://a/mcp"}
                ]}}
              ]
            }
        """.trimIndent()
        val page = GenericRegistryApi.parseServerList(body).getOrThrow()
        assertEquals("https://a/mcp", page.servers[0].bestRemote?.url)
        // metadata 缺失 → nextCursor null（末页语义）
        assertNull(page.nextCursor)
    }

    @Test
    fun `parseServerList dedupes same-name servers and skips broken entries`() {
        val body = """
            {
              "servers": [
                {"server": {"name": "x/a", "remotes": [{"type": "streamable-http", "url": "https://a"}]}},
                {"server": {"name": "x/a", "remotes": [{"type": "streamable-http", "url": "https://a2"}]}},
                {"not-a-server": true},
                {"server": {"description": "name 缺失的坏条目"}}
              ]
            }
        """.trimIndent()
        val page = GenericRegistryApi.parseServerList(body).getOrThrow()
        assertEquals(1, page.servers.size)
    }

    @Test
    fun `parseServerList with empty servers array is a valid empty page`() {
        val body = """{"servers": [], "metadata": {"count": 0}}"""
        val page = GenericRegistryApi.parseServerList(body).getOrThrow()
        assertTrue(page.servers.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `parseServerList with corrupt json returns failure`() {
        val result = GenericRegistryApi.parseServerList("""{"servers": [not-json""")
        assertTrue(result.isFailure)
    }

    // ═══ packages 解析（npm 决策 + 环境变量）═══

    @Test
    fun `npm stdio package yields NPM install kind and sandboxed npx config`() {
        val body = """
            {
              "servers": [
                {"server": {"name": "io.github.BAM-DevCrew/MAXential-Thinking-MCP",
                  "description": "11 focused tools",
                  "version": "2.0.1",
                  "packages": [
                    {"registryType": "npm", "identifier": "@bam-devcrew/maxential-thinking-mcp",
                     "version": "2.0.1", "transport": {"type": "stdio"}}
                  ]}}
              ]
            }
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        assertEquals(RegistryServer.InstallKind.NPM, server.installKind)
        val config = server.toMcpServerConfig()
        assertNotNull(config)
        config!!
        assertEquals("MAXential-Thinking-MCP", config.name)
        assertEquals(McpTransport.STDIO, config.transport)
        assertEquals("npx", config.command)
        assertEquals(listOf("-y", "@bam-devcrew/maxential-thinking-mcp"), config.args)
        assertTrue(config.runInSandbox)
        // 安装 ≠ 启动（与官方 Hub / mcp.so 口径一致）
        assertTrue(!config.enabled)
    }

    @Test
    fun `remote takes precedence over npm package when both exist`() {
        val body = """
            {
              "servers": [
                {"server": {"name": "io.github.RiskThinking/cdt-express-mcp",
                  "title": "CDT Express",
                  "packages": [
                    {"registryType": "mcpb", "identifier": "https://example.com/x.mcpb",
                     "version": "0.6.0", "fileSha256": "6798fc0f", "transport": {"type": "stdio"}}
                  ],
                  "remotes": [{"type": "streamable-http", "url": "https://mcp.riskthinking.ai/mcp"}]}}
              ]
            }
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        assertEquals(RegistryServer.InstallKind.REMOTE, server.installKind)
        assertEquals("CDT Express", server.configName)
        val config = server.toMcpServerConfig()!!
        assertEquals(McpTransport.HTTP, config.transport)
        assertEquals("https://mcp.riskthinking.ai/mcp", config.url)
    }

    @Test
    fun `unsupported registry types yield UNSUPPORTED and null config`() {
        val body = """
            {"servers": [
                {"server": {"name": "io.github.j0hanz/filesystem-mcp",
                  "packages": [
                    {"registryType": "oci", "identifier": "ghcr.io/j0hanz/filesystem-mcp:2.7.1",
                     "runtimeHint": "docker", "transport": {"type": "stdio"}}
                  ]}}
            ]}
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        assertEquals(RegistryServer.InstallKind.UNSUPPORTED, server.installKind)
        assertNull(server.toMcpServerConfig())
        assertEquals("oci", server.unsupportedPackage?.registryType)
    }

    @Test
    fun `non-stdio npm transport does not qualify for sandbox install`() {
        val body = """
            {"servers": [
                {"server": {"name": "x/port-mcp",
                  "packages": [
                    {"registryType": "npm", "identifier": "@x/port-mcp",
                     "runtimeHint": "npx",
                     "transport": {"type": "streamable-http", "url": "http://127.0.0.1:{port}/mcp"}}
                  ]}}
            ]}
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        assertNull(server.npmPackage)
        assertEquals(RegistryServer.InstallKind.UNSUPPORTED, server.installKind)
    }

    @Test
    fun `required environment variables are surfaced for install message`() {
        val body = """
            {"servers": [
                {"server": {"name": "com.pulsemcp/remote-filesystem",
                  "packages": [
                    {"registryType": "npm", "identifier": "remote-filesystem-mcp-server",
                     "version": "0.1.5", "runtimeHint": "npx", "transport": {"type": "stdio"},
                     "environmentVariables": [
                        {"description": "bucket", "isRequired": true, "name": "GCS_BUCKET"},
                        {"description": "project", "name": "GCS_PROJECT_ID"}
                     ]}
                  ]}}
            ]}
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        val pkg = server.npmPackage!!
        assertEquals(listOf("GCS_BUCKET", "GCS_PROJECT_ID"), pkg.envVarNames)
        assertEquals(listOf("GCS_BUCKET"), pkg.requiredEnvVarNames)
    }

    // ═══ remotes 细节（headers 模板 / sse 映射）═══

    @Test
    fun `remote headers template is preserved for later editing`() {
        val body = """
            {"servers": [
                {"server": {"name": "ai.smithery/Leghis-smart-thinking",
                  "remotes": [
                    {"type": "streamable-http", "url": "https://server.smithery.ai/x/mcp",
                     "headers": [
                        {"description": "Bearer token", "value": "Bearer {smithery_api_key}",
                         "name": "Authorization"}
                     ]}
                  ]}}
            ]}
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        val remote = server.bestRemote!!
        assertEquals(mapOf("Authorization" to "Bearer {smithery_api_key}"), remote.headers)
        val config = server.toMcpServerConfig()!!
        assertEquals(mapOf("Authorization" to "Bearer {smithery_api_key}"), config.headers)
    }

    @Test
    fun `sse remote maps to SSE transport in config`() {
        val body = """
            {"servers": [
                {"server": {"name": "x/sse-only",
                  "remotes": [{"type": "sse", "url": "https://x/sse"}]}}
            ]}
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        assertTrue(server.bestRemote!!.isSse)
        val config = server.toMcpServerConfig()!!
        assertEquals(McpTransport.SSE, config.transport)
    }

    @Test
    fun `displayName falls back to last path segment when title missing`() {
        val body = """
            {"servers": [
                {"server": {"name": "io.github.bytedance/mcp-server-filesystem",
                  "description": "filesystem server"}}
            ]}
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        assertEquals("mcp-server-filesystem", server.displayName)
        assertEquals("mcp-server-filesystem", server.configName)
    }

    @Test
    fun `repository url is extracted from nested repository object`() {
        val body = """
            {"servers": [
                {"server": {"name": "x/repo-server",
                  "repository": {"url": "https://github.com/x/repo-server", "source": "github"}}}
            ]}
        """.trimIndent()
        val server = GenericRegistryApi.parseServerList(body).getOrThrow().servers[0]
        assertEquals("https://github.com/x/repo-server", server.repositoryUrl)
    }
}
