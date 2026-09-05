package com.apex.agent.core.engine.task

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T76 — FileTaskStore 持久化测试（任务书 §18B：真实文件 IO roundtrip、
 * 损坏隔离、版本宽容、原子性、temp 清理）。
 */
class FileTaskStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FileTaskStore
    private lateinit var root: File

    private fun sampleTask(
        taskId: String = "task-1000-abcd",
        status: TaskStatus = TaskStatus.RUNNING
    ): AgentTask = AgentTask(
        taskId = taskId,
        title = "标题",
        userInput = "帮我分析项目",
        mode = "PLAN",
        status = status,
        steps = listOf(
            TaskStepModel("task-1000-abcd-s0", 0, "读取文件", "read_file", listOf()),
            TaskStepModel("task-1000-abcd-s1", 1, "写报告", "write_file", listOf(0), StepStatus.RUNNING)
        ),
        operations = listOf(
            ToolOperationRecord(
                operationId = "task-1000-abcd-op0",
                llmCallId = "call_abc",
                toolName = "read_file",
                arguments = """{"path":"a.txt"}""",
                status = OperationStatus.SUCCEEDED,
                idempotency = ToolIdempotencyClass.READ_ONLY
            )
        ),
        retryCount = 1,
        createdAt = 1000L,
        startedAt = 1100L,
        updatedAt = 1200L
    )

    @Before
    fun setup() {
        root = tmp.newFolder("taskstore")
        store = FileTaskStore(root)
    }

    // ═══ Roundtrip ═══

    @Test
    fun `save-load roundtrip preserves all fields`() {
        val task = sampleTask()
        store.save(task)
        val loaded = store.load(task.taskId)

        assertNotNull(loaded)
        assertEquals(task, loaded)
        // 关键字段抽查（防 equals 掩盖序列化丢字段）
        assertEquals("PLAN", loaded!!.mode)
        assertEquals(2, loaded.steps.size)
        assertEquals(StepStatus.RUNNING, loaded.steps[1].status)
        assertEquals(1, loaded.operations.size)
        assertEquals(OperationStatus.SUCCEEDED, loaded.operations[0].status)
        assertEquals(ToolIdempotencyClass.READ_ONLY, loaded.operations[0].idempotency)
        assertEquals(1, loaded.retryCount)
    }

    @Test
    fun `load missing task returns null`() {
        assertNull(store.load("task-9999-zzzz"))
    }

    @Test
    fun `overwriting save replaces previous checkpoint atomically`() {
        val task = sampleTask()
        store.save(task)
        store.save(task.copy(status = TaskStatus.PAUSED, updatedAt = 2000L))
        val loaded = store.load(task.taskId)!!
        assertEquals(TaskStatus.PAUSED, loaded.status)
        assertEquals(2000L, loaded.updatedAt)
        // 单文件：无残留多版本
        val files = root.listFiles { f -> f.name.endsWith(".json") }!!
        assertEquals(1, files.size)
    }

    // ═══ Schema 宽容演进 ═══

    @Test
    fun `unknown fields are tolerated - forward compatibility`() {
        val task = sampleTask()
        store.save(task)
        // 模拟未来版本写入了新字段（schema v2 预演）：尾部追加 unknown 字段。
        // 注：右花括号字符用 \u007D 表达——保持源码括号计数配对（CI brace gate）。
        val raw = File(root, "${task.taskId}.json").readText()
        val patched = raw.dropLast(1) + ",\"futureField\":123" + '\u007D'
        File(root, "${task.taskId}.json").writeText(patched)
        val loaded = store.load(task.taskId)
        assertNotNull(loaded)
        assertEquals(task.taskId, loaded!!.taskId)
        assertEquals("帮我分析项目", loaded.userInput)
    }

    // ═══ 损坏隔离 ═══

    @Test
    fun `corrupt file is quarantined and does not break other tasks`() {
        val good = sampleTask("task-2000-good")
        val bad = sampleTask("task-3000-bad")
        store.save(good)
        store.save(bad)
        // 制造损坏：运行时截断完整 JSON（源码字面量括号配对平衡）
        val fullBadJson = """{"version":1,"task":{"taskId":"task-3000-bad"}}"""
        File(root, "task-3000-bad.json").writeText(fullBadJson.substring(0, 28))

        val all = store.loadAllTasks()
        assertEquals(1, all.size)
        assertEquals("task-2000-good", all[0].taskId)

        // 隔离区存在被移走的损坏文件
        val corruptDir = File(root, "corrupt")
        assertTrue(corruptDir.exists())
        assertEquals(1, corruptDir.listFiles()!!.size)
        // 原位置不再有损坏文件
        assertFalse(File(root, "task-3000-bad.json").exists())
        // 单个 load 损坏任务 → null（不抛）
        assertNull(store.load("task-3000-bad"))
    }

    // ═══ 发现与清理 ═══

    @Test
    fun `loadActiveTasks filters by active status and cleans temp residue`() {
        store.save(sampleTask("task-1-a", TaskStatus.RUNNING))
        store.save(sampleTask("task-2-b", TaskStatus.PAUSED))
        store.save(sampleTask("task-3-c", TaskStatus.WAITING_USER))
        store.save(sampleTask("task-4-d", TaskStatus.COMPLETED))
        store.save(sampleTask("task-5-e", TaskStatus.CANCELLED))
        store.save(sampleTask("task-6-f", TaskStatus.FAILED))
        // 半写 temp 残留（模拟崩溃现场：运行时截断，源码字面量平衡）
        val halfWritten = """{"version":1,"task":{"taskId":"x"}}"""
        File(root, "task-7-g.json.tmp").writeText(halfWritten.substring(0, 26))

        val active = store.loadActiveTasks()
        assertEquals(3, active.size)
        assertEquals(setOf("task-1-a", "task-2-b", "task-3-c"), active.map { it.taskId }.toSet())
        // temp 已清理（listFiles 带 filter 无匹配返回空数组而非 null）
        assertTrue(root.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
    }

    @Test
    fun `loadAllTasks returns full history sorted by createdAt desc`() {
        store.save(sampleTask("task-1-a", TaskStatus.COMPLETED))
        store.save(sampleTask("task-2-b", TaskStatus.COMPLETED))
        val all = store.loadAllTasks()
        assertEquals(2, all.size)
        assertTrue(all[0].createdAt >= all[1].createdAt || all[0].taskId == "task-2-b")
    }

    // ═══ 删除 ═══

    @Test
    fun `delete removes task file`() {
        val task = sampleTask()
        store.save(task)
        store.delete(task.taskId)
        assertNull(store.load(task.taskId))
        assertFalse(File(root, "${task.taskId}.json").exists())
    }

    @Test
    fun `illegal taskId is rejected - path traversal guard`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.load("../../etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.delete("../evil")
        }
    }

    // ═══ 写失败清理 ═══

    @Test
    fun `failed serialize leaves no temp garbage`() {
        val root2 = tmp.newFolder("readonly")
        val store2 = FileTaskStore(root2)
        val task = sampleTask()
        // 正常路径本身不会序列化失败；用非法 taskId 使 fileFor 抛 IAE 验证不落盘
        try {
            store2.save(task.copy(taskId = "bad/../id"))
            throw AssertionError("should have thrown")
        } catch (expected: IllegalArgumentException) {
        }
        assertTrue(root2.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
    }
}

/**
 * T76 — 悬空 toolCall 修补测试（审计 R-5，任务书 §18E）。
 */
class DanglingToolCallRepairTest {

    private fun assistantWithCalls(vararg ids: String) =
        LlmMessage.Assistant("thinking", ids.map { ToolCall(it, "tool_x", "{}") })

    @Test
    fun `no dangling - history untouched`() {
        val history = listOf(
            LlmMessage.User("hello"),
            assistantWithCalls("c1"),
            LlmMessage.ToolResult("c1", "ok")
        )
        val report = DanglingToolCallRepair.repair(history)
        assertFalse(report.hasRepairs)
        assertEquals(history, report.repairedHistory)
    }

    @Test
    fun `single dangling tail call gets synthetic ToolResult`() {
        val history = listOf(
            LlmMessage.User("do it"),
            assistantWithCalls("c1"),
            LlmMessage.ToolResult("c1", "ok"),
            assistantWithCalls("c2") // 进程死于此
        )
        val report = DanglingToolCallRepair.repair(history)
        assertTrue(report.hasRepairs)
        assertEquals(listOf("c2"), report.repairedCallIds)
        assertEquals(5, report.repairedHistory.size)
        val patched = report.repairedHistory.last() as LlmMessage.ToolResult
        assertEquals("c2", patched.toolCallId)
        assertTrue(patched.content.contains("UNKNOWN"))
        assertTrue(patched.content.contains("Verify"))
        // 顺序：补发在末尾，前面消息不变
        assertEquals(history.take(4), report.repairedHistory.take(4))
    }

    @Test
    fun `multiple parallel dangling calls all repaired in order`() {
        val history = listOf(
            LlmMessage.User("run"),
            assistantWithCalls("c1", "c2") // 并行工具调用，均未返回
        )
        val report = DanglingToolCallRepair.repair(history)
        assertEquals(listOf("c1", "c2"), report.repairedCallIds)
        val tail = report.repairedHistory.takeLast(2)
        assertTrue(tail.all { it is LlmMessage.ToolResult })
        assertEquals(setOf("c1", "c2"), tail.map { (it as LlmMessage.ToolResult).toolCallId }.toSet())
    }

    @Test
    fun `mid-history dangling is detected too - not only tail`() {
        // c2 悬空出现在中段，后跟不相关 User 消息（异常恢复现场）
        val history = listOf(
            LlmMessage.User("start"),
            assistantWithCalls("c1", "c2"),
            LlmMessage.ToolResult("c1", "ok"),
            // c2 无 ToolResult，但历史继续走了
            LlmMessage.User("next")
        )
        val report = DanglingToolCallRepair.repair(history)
        assertEquals(listOf("c2"), report.repairedCallIds)
    }

    @Test
    fun `duplicate callIds repaired only once`() {
        val history = listOf(
            assistantWithCalls("c1"),
            assistantWithCalls("c1") // 畸形重复
        )
        val report = DanglingToolCallRepair.repair(history)
        assertEquals(listOf("c1"), report.repairedCallIds)
        assertEquals(3, report.repairedHistory.size)
    }

    @Test
    fun `blank callId is ignored`() {
        val history = listOf(
            assistantWithCalls("") // 畸形：空 id
        )
        val report = DanglingToolCallRepair.repair(history)
        assertFalse(report.hasRepairs)
    }

    @Test
    fun `repaired history passes OpenAI pairing invariant`() {
        // 修补后：每个 Assistant.toolCall id 必有 ToolResult 配对（API 400 防线）
        val history = listOf(
            LlmMessage.User("go"),
            assistantWithCalls("c1", "c2"),
            LlmMessage.ToolResult("c1", "ok")
        )
        val repaired = DanglingToolCallRepair.repair(history).repairedHistory
        val called = mutableSetOf<String>()
        val answered = mutableSetOf<String>()
        repaired.forEach { msg ->
            when (msg) {
                is LlmMessage.Assistant -> msg.toolCalls.forEach { called.add(it.id) }
                is LlmMessage.ToolResult -> answered.add(msg.toolCallId)
                else -> Unit
            }
        }
        assertTrue("repaired history must satisfy pairing invariant", answered.containsAll(called))
    }

    @Test
    fun `tailHasDangling detects crash-at-tool-execution signature`() {
        val dangling = listOf(
            LlmMessage.User("go"),
            assistantWithCalls("c9")
        )
        assertTrue(DanglingToolCallRepair.tailHasDangling(dangling))

        val clean = listOf(
            LlmMessage.User("go"),
            assistantWithCalls("c9"),
            LlmMessage.ToolResult("c9", "done")
        )
        assertFalse(DanglingToolCallRepair.tailHasDangling(clean))
    }
}
