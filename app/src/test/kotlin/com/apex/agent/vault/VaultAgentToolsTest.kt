package com.apex.agent.vault

import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 金库工具集单测（纯 JVM —— 三条投递通道全部用 fake lambda / 本地 HTTP 服务）。
 *
 * 安全红线断言：任何工具返回值都不得包含密钥明文。
 */
class VaultAgentToolsTest {

    private lateinit var repository: VaultRepository
    private lateinit var store: InMemoryVaultStore

    private val pastedToClipboard = mutableListOf<String>()
    private val pastedToTerminal = mutableListOf<Pair<Long, String>>()
    /** 终端写入是否成功（默认 true；失败路径测试时置 false）。 */
    @Volatile
    private var terminalWriteOk = true

    private val httpClient = OkHttpClient()

    private fun buildTools(): List<com.apex.agent.core.tools.AgentTool> = VaultAgentTools.all(
        repository = repository,
        clipboardSetter = { pastedToClipboard += it },
        terminalWriter = { sessionId, text ->
            pastedToTerminal += sessionId to text
            terminalWriteOk
        },
        httpClient = httpClient
    )

    private fun tool(id: String) = buildTools().first { it.id == id }

    @Before
    fun setUp() {
        store = InMemoryVaultStore()
        repository = VaultRepository(store)
        pastedToClipboard.clear()
        pastedToTerminal.clear()
        terminalWriteOk = true
    }

    // ═══════════════ vault_list ═══════════════

    @Test
    fun `vault_list 输出脱敏快照不含 secret`() = runTest {
        repository.save(
            VaultRepository.newEntry("github-token", "CI 发布凭据", "ghp_plaintext_never_show_me", VaultOrigin.HUMAN)
        )
        val out = tool("vault_list").execute("{}")

        assertTrue(out.contains("github-token"))
        assertTrue(out.contains("CI 发布凭据"))
        assertTrue(out.contains("HUMAN"))
        assertTrue(out.contains("used 0x"))
        // 安全红线：明文绝不出现在工具输出。
        assertFalse(out.contains("ghp_plaintext_never_show_me"))
    }

    @Test
    fun `vault_list 空库引导文案`() = runTest {
        val out = tool("vault_list").execute("{}")
        assertTrue(out.contains("Vault is empty"))
    }

    // ═══════════════ vault_save ═══════════════

    @Test
    fun `vault_save 成功返回仅含 label 与 id 绝不含 content`() = runTest {
        val out = tool("vault_save")
            .execute("""{"label":"openai-key","note":"备用模型密钥","content":"sk-plaintext-secret-42"}""")

        assertTrue(out.contains("\"saved\":true"))
        assertTrue(out.contains("\"label\":\"openai-key\""))
        assertFalse(out.contains("sk-plaintext-secret-42"))
        // write-only：存得进，但工具链读不回（快照亦无明文）。
        assertEquals("sk-plaintext-secret-42", repository.resolveSecret("openai-key"))
        assertFalse(tool("vault_list").execute("{}").contains("sk-plaintext-secret-42"))
    }

    @Test
    fun `vault_save label 冲突默认覆写`() = runTest {
        tool("vault_save").execute("""{"label":"k","content":"first-secret-value"}""")
        tool("vault_save").execute("""{"label":"k","content":"second-secret-value"}""")
        assertEquals(1, repository.entryCount())
        assertEquals("second-secret-value", repository.resolveSecret("k"))
    }

    @Test
    fun `vault_save overwrite=false 时冲突报错且不改动原条目`() = runTest {
        tool("vault_save").execute("""{"label":"k","content":"first-secret-value"}""")
        val out = tool("vault_save")
            .execute("""{"label":"k","content":"second-secret-value","overwrite":false}""")

        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("already exists"))
        assertEquals("first-secret-value", repository.resolveSecret("k"))
    }

    @Test
    fun `vault_save 缺参报错`() = runTest {
        assertTrue(tool("vault_save").execute("""{"label":"k"}""").startsWith("Error:"))
        assertTrue(tool("vault_save").execute("""{"content":"x"}""").startsWith("Error:"))
        assertTrue(tool("vault_save").execute("not-json").startsWith("Error:"))
    }

    // ═══════════════ vault_paste ═══════════════

    @Test
    fun `vault_paste clipboard 通道投递并计数且不回显`() = runTest {
        val e = repository.save(
            VaultRepository.newEntry("github-token", "", "ghp_clip_secret_000111", VaultOrigin.HUMAN)
        )
        val out = tool("vault_paste")
            .execute("""{"label":"github-token","to":"clipboard"}""")

        assertTrue(out.contains("\"pasted\":true"))
        assertTrue(out.contains("\"target\":\"clipboard\""))
        assertFalse(out.contains("ghp_clip_secret_000111"))
        // fake 通道收到的是明文（直投语义），且用量 +1。
        assertEquals(listOf("ghp_clip_secret_000111"), pastedToClipboard)
        assertEquals(1, repository.get(e.id)?.usageCount)
    }

    @Test
    fun `vault_paste terminal 通道 RAW 写入并回字节数`() = runTest {
        repository.save(VaultRepository.newEntry("t", "", "ghp_term_secret_222333", VaultOrigin.HUMAN))

        val out = tool("vault_paste")
            .execute("""{"label":"t","to":"terminal","session_id":7}""")

        assertTrue(out.contains("\"target\":\"terminal\""))
        assertTrue(out.contains("\"session\":7"))
        // bytes = UTF-8 字节数（密钥 + 换行），不含内容本身。
        val expected = "ghp_term_secret_222333\n".toByteArray(Charsets.UTF_8).size
        assertTrue(out.contains("\"bytes\":$expected"))
        assertFalse(out.contains("ghp_term_secret_222333"))
        assertEquals(listOf(7L to "ghp_term_secret_222333\n"), pastedToTerminal)
    }

    @Test
    fun `vault_paste terminal newline=false 不补换行`() = runTest {
        repository.save(VaultRepository.newEntry("t", "", "ghp_term_secret_222333", VaultOrigin.HUMAN))
        tool("vault_paste")
            .execute("""{"label":"t","to":"terminal","session_id":3,"newline":false}""")
        assertEquals(listOf(3L to "ghp_term_secret_222333"), pastedToTerminal)
    }

    @Test
    fun `vault_paste terminal 失败返回可自修复错误`() = runTest {
        repository.save(VaultRepository.newEntry("t", "", "ghp_term_secret_222333", VaultOrigin.HUMAN))
        terminalWriteOk = false
        val out = tool("vault_paste")
            .execute("""{"label":"t","to":"terminal","session_id":9}""")
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("session 9"))
    }

    @Test
    fun `vault_paste 缺 session_id 与未知通道报错`() = runTest {
        repository.save(VaultRepository.newEntry("t", "", "ghp_term_secret_222333", VaultOrigin.HUMAN))
        assertTrue(
            tool("vault_paste").execute("""{"label":"t","to":"terminal"}""").contains("session_id")
        )
        assertTrue(
            tool("vault_paste").execute("""{"label":"t","to":"carrier-pigeon"}""").startsWith("Error:")
        )
        // 未知 label 明确报错。
        assertTrue(
            tool("vault_paste").execute("""{"label":"nope","to":"clipboard"}""").contains("no vault entry")
        )
    }

    @Test
    fun `vault_paste http 通道响应体脱敏后返回`() = runTest {
        // 本地 echo 服务：把 Authorization 头原样回进响应体（模拟服务端回显凭据）。
        val server = FakeHttpEndpoint { headers ->
            val auth = headerLine(headers, "Authorization") ?: ""
            """{"echo":"$auth","ok":true}"""
        }
        server.start()
        try {
            val secret = "ghp_http_secret_333444555"
            repository.save(VaultRepository.newEntry("h", "", secret, VaultOrigin.HUMAN))
            val url = "http://127.0.0.1:${server.port}/echo"

            val out = tool("vault_paste")
                .execute("""{"label":"h","to":"http","url":"$url","scheme":"bearer"}""")

            assertTrue(out.contains("\"status\":200"))
            assertTrue(out.contains("\"target\":\"http\""))
            // 服务端回显了 "Bearer <secret>" —— 工具返回必须已脱敏。
            assertTrue("应含掩码：$out", out.contains(SecretRedactor.MASK))
            assertFalse("明文泄漏：$out", out.contains(secret))
            // 用量已记录。
            assertEquals(1, repository.getByLabel("h")?.usageCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `vault_paste http raw scheme 直传与非法 url 报错`() = runTest {
        val server = FakeHttpEndpoint(status = 204, bodyFor = { "" })
        server.start()
        try {
            val secret = "ghp_raw_scheme_secret_666"
            repository.save(VaultRepository.newEntry("r", "", secret, VaultOrigin.HUMAN))
            val url = "http://127.0.0.1:${server.port}/raw"
            val out = tool("vault_paste").execute(
                """{"label":"r","to":"http","url":"$url","header":"X-Api-Key","scheme":"raw"}"""
            )
            assertTrue(out.contains("\"status\":204"))
            // 服务端确实收到裸密钥（请求头里原样可见）。
            assertEquals(secret, server.headerValue("X-Api-Key"))
            assertFalse(out.contains(secret))
        } finally {
            server.close()
        }

        // 非 http(s) url 拒绝。
        val bad = tool("vault_paste").execute("""{"label":"r","to":"http","url":"ftp://x/y"}""")
        assertTrue(bad.startsWith("Error:"))
    }

    // ═══════════════ 测试基建：ServerSocket 版最小 HTTP 端点 ═══════════════

    /**
     * Android 单测编译classpath没有 com.sun.net.httpserver —— 用裸 ServerSocket
     * 实现最小 HTTP/1.1 端点：单线程 accept 循环，读完整请求头后返回固定状态码 +
     * 由 [bodyFor] 生成的响应体（回显场景用）。
     */
    private class FakeHttpEndpoint(
        private val status: Int = 200,
        private val bodyFor: (headers: String) -> String
    ) {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        @Volatile
        private var closed = false
        private val received = mutableListOf<String>()

        fun start() {
            thread(isDaemon = true, name = "fake-http-${server.localPort}") {
                while (!closed && !server.isClosed) {
                    val sock = try {
                        server.accept()
                    } catch (e: Exception) {
                        break // closed
                    }
                    try {
                        sock.use { s ->
                            s.soTimeout = 10_000
                            val reader = BufferedReader(s.getInputStream().reader(Charsets.UTF_8))
                            val sb = StringBuilder()
                            while (true) {
                                val line = reader.readLine() ?: break
                                sb.append(line).append('\n')
                                if (line.isEmpty()) break
                            }
                            synchronized(received) { received += sb.toString() }

                            val body = bodyFor(sb.toString()).toByteArray(Charsets.UTF_8)
                            val head = buildString {
                                append("HTTP/1.1 ").append(status)
                                append(if (status == 204) " No Content" else " OK").append("\r\n")
                                append("Content-Type: application/json\r\n")
                                if (status != 204) {
                                    append("Content-Length: ").append(body.size).append("\r\n")
                                }
                                append("Connection: close\r\n\r\n")
                            }
                            val out = s.getOutputStream()
                            out.write(head.toByteArray(Charsets.UTF_8))
                            if (status != 204) out.write(body)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        // 单个连接异常不拖垮端点（客户端可能提前断开）。
                    }
                }
            }
        }

        /** 取首个收到的请求里指定头的值（大小写不敏感）。 */
        fun headerValue(name: String): String? {
            val regex = Regex("(?i)^$name:\\s*(.*?)\\s*$", RegexOption.MULTILINE)
            return synchronized(received) {
                received.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1) }
            }
        }

        fun close() {
            closed = true
            server.close()
        }
    }

    /** 从原始请求头文本里取指定头（大小写不敏感），供 bodyFor 回显用。 */
    private fun headerLine(headers: String, name: String): String? {
        val regex = Regex("(?i)^$name:\\s*(.*?)\\s*$", RegexOption.MULTILINE)
        return regex.find(headers)?.groupValues?.get(1)
    }

    // ═══════════════ vault_delete ═══════════════

    @Test
    fun `vault_delete 按标签删除且旧密钥退出脱敏登记表`() = runTest {
        repository.save(VaultRepository.newEntry("d", "", "ghp_delete_secret_777888", VaultOrigin.HUMAN))
        val out = tool("vault_delete").execute("""{"label":"d"}""")

        assertTrue(out.contains("Deleted") && out.contains("'d'"))
        assertEquals(0, repository.entryCount())
        assertEquals(0, repository.secretRedactor.registeredCount())
    }

    @Test
    fun `vault_delete 未知标签报错`() = runTest {
        assertTrue(tool("vault_delete").execute("""{"label":"ghost"}""").contains("no vault entry"))
    }

    // ═══════════════ 元数据 ═══════════════

    @Test
    fun `工具 id 唯一且元数据风险分级正确`() {
        val tools = buildTools()
        assertEquals(listOf("vault_list", "vault_save", "vault_paste", "vault_delete"), tools.map { it.id })
        assertEquals(4, tools.map { it.id }.toSet().size)

        val byId = tools.associateBy { it.id }
        assertEquals(ToolRisk.LOW, byId.getValue("vault_list").metadata.risk)
        assertEquals(ToolRisk.MEDIUM, byId.getValue("vault_save").metadata.risk)
        assertEquals(ToolRisk.MEDIUM, byId.getValue("vault_paste").metadata.risk)
        assertEquals(ToolRisk.HIGH, byId.getValue("vault_delete").metadata.risk)
        tools.forEach {
            assertEquals(ToolCategory.SECURITY, it.metadata.category)
            assertTrue("readOnly 标注应只给 vault_list", (it.id == "vault_list") == it.metadata.annotations.readOnlyHint)
        }
    }
}
