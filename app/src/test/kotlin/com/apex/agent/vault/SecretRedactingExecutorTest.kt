package com.apex.agent.vault

import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * SecretRedactingExecutor 单测（纯 JVM）—— 纵深防御层：
 * 无论内层执行器回显什么，密钥到达模型前必须被掩码。
 */
class SecretRedactingExecutorTest {

    /** 固定回显文本的假执行器（模拟意外泄漏密钥的工具）。 */
    private class FakeExecutor(private val output: String) : ToolExecutor {
        override suspend fun execute(toolId: String, arguments: String): String = output
        override fun executeStream(toolId: String, arguments: String) = flowOf(
            ToolStreamEvent.Output(output),
            ToolStreamEvent.Progress(percent = 0.5f, message = "working"),
            ToolStreamEvent.Complete(output),
            ToolStreamEvent.Error(output)
        )
    }

    @Test
    fun `execute 输出被脱敏`() = runTest {
        val redactor = SecretRedactor()
        redactor.register(listOf("leaked-secret-999"))
        val executor = SecretRedactingExecutor(FakeExecutor("got leaked-secret-999 here"), redactor)

        val out = executor.execute("http_request", "{}")
        assertEquals("got ${SecretRedactor.MASK} here", out)
        assertFalse(out.contains("leaked-secret-999"))
    }

    @Test
    fun `executeStream 全部文本事件被脱敏而 Progress 透传`() = runTest {
        val redactor = SecretRedactor()
        redactor.register(listOf("leaked-secret-999"))
        val executor = SecretRedactingExecutor(
            FakeExecutor("chunk leaked-secret-999 end"), redactor
        )

        val events = executor.executeStream("web_fetch", "{}").toList()

        assertEquals(4, events.size)
        events.forEach { ev ->
            when (ev) {
                is ToolStreamEvent.Output ->
                    assertEquals("chunk ${SecretRedactor.MASK} end", ev.chunk)
                is ToolStreamEvent.Complete ->
                    assertEquals("chunk ${SecretRedactor.MASK} end", ev.output)
                is ToolStreamEvent.Error ->
                    assertEquals("chunk ${SecretRedactor.MASK} end", ev.message)
                is ToolStreamEvent.Progress -> {
                    assertEquals(0.5f, ev.percent)
                    assertEquals("working", ev.message)
                }
            }
        }
        // 全事件序列无明文。
        assertFalse(events.joinToString().contains("leaked-secret-999"))
    }

    @Test
    fun `与金库仓库联动 —— 新存密钥即时生效删除即时退出`() = runTest {
        val repo = VaultRepository(InMemoryVaultStore())
        val executor = SecretRedactingExecutor(
            FakeExecutor("echo secret-live-111222"), repo.secretRedactor
        )
        // 入库前：不脱敏（尚未登记）。
        assertEquals("echo secret-live-111222", executor.execute("shell_execute", "{}"))

        val e = repo.save(VaultRepository.newEntry("live", "", "secret-live-111222", VaultOrigin.HUMAN))
        assertEquals("echo ${SecretRedactor.MASK}", executor.execute("shell_execute", "{}"))

        repo.delete(e.id)
        assertEquals("echo secret-live-111222", executor.execute("shell_execute", "{}"))
    }

    @Test
    fun `脱敏幂等 —— 双层装饰器叠加不改变结果`() = runTest {
        val redactor = SecretRedactor()
        redactor.register(listOf("leaked-secret-999"))
        val inner = SecretRedactingExecutor(FakeExecutor("x leaked-secret-999"), redactor)
        val outer = SecretRedactingExecutor(inner, redactor)

        assertEquals("x ${SecretRedactor.MASK}", outer.execute("t", "{}"))
    }
}
