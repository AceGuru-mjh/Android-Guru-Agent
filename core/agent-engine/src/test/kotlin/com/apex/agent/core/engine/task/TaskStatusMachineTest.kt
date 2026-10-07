package com.apex.agent.core.engine.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T76 — 持久层状态机迁移矩阵全量测试（任务书 §18A）。
 *
 * 覆盖：
 * - 全部 11 态 × 11 态的 121 组合逐一断言（合法表 vs 实际判定一致）；
 * - 非法迁移抛 [TaskStatusMachine.IllegalTaskTransitionException]（含 from/to 信息）；
 * - 自环一律非法；
 * - 终态无出边（COMPLETED/CANCELLED 绝对终态；FAILED 仅两条受控出边）；
 * - `transition()` 的时间戳维护（startedAt 固化 / updatedAt 刷新 / completedAt 终态固化）；
 * - `crashRecoveryEntry()` 降级规则（PAUSED 保持、活跃态 → RECOVERING、终态 null）。
 */
class TaskStatusMachineTest {

    private val ALL = TaskStatus.entries
    private val T0 = 1_000L

    private fun newTask(status: TaskStatus): AgentTask = AgentTask(
        taskId = "task-test",
        title = "t",
        userInput = "input",
        mode = "BUILD",
        status = status,
        createdAt = T0,
        updatedAt = T0
    )

    // ═══ 1. 全量矩阵：显式表 == isLegal 判定 ═══

    @Test
    fun `full matrix - isLegal matches the documented transition table`() {
        val legal = mapOf(
            TaskStatus.PENDING to setOf(
                TaskStatus.PLANNING, TaskStatus.RUNNING, TaskStatus.RECOVERING,
                TaskStatus.FAILED, TaskStatus.CANCELLED
            ),
            TaskStatus.PLANNING to setOf(
                TaskStatus.RUNNING, TaskStatus.WAITING_USER, TaskStatus.PAUSED,
                TaskStatus.RECOVERING, TaskStatus.FAILED, TaskStatus.CANCELLED
            ),
            TaskStatus.RUNNING to setOf(
                TaskStatus.PLANNING, TaskStatus.WAITING_USER, TaskStatus.PAUSED,
                TaskStatus.CANCELLING, TaskStatus.RECOVERING,
                TaskStatus.COMPLETED, TaskStatus.FAILED
            ),
            TaskStatus.WAITING_USER to setOf(
                TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.CANCELLING,
                TaskStatus.RECOVERING, TaskStatus.FAILED
            ),
            TaskStatus.PAUSED to setOf(
                TaskStatus.RUNNING, TaskStatus.CANCELLING, TaskStatus.RECOVERING, TaskStatus.FAILED
            ),
            TaskStatus.CANCELLING to setOf(TaskStatus.CANCELLED, TaskStatus.FAILED),
            TaskStatus.RECOVERING to setOf(
                TaskStatus.RUNNING, TaskStatus.CANCELLING, TaskStatus.FAILED
            ),
            TaskStatus.RETRYING to setOf(
                TaskStatus.RUNNING, TaskStatus.CANCELLING, TaskStatus.FAILED
            ),
            TaskStatus.FAILED to setOf(TaskStatus.RETRYING, TaskStatus.CANCELLED),
            TaskStatus.COMPLETED to emptySet(),
            TaskStatus.CANCELLED to emptySet()
        )

        // 表完整性：每个状态都有声明（含空集）
        assertEquals(ALL.size, TaskStatusMachine.transitions.size)
        legal.forEach { (from, targets) ->
            assertEquals("declared table mismatch at $from", targets, TaskStatusMachine.transitions[from])
            ALL.forEach { to ->
                val expected = to in targets
                assertEquals("$from -> $to should be ${if (expected) "legal" else "illegal"}",
                    expected, TaskStatusMachine.isLegal(from, to))
            }
        }
    }

    // ═══ 2. 关键语义：Pause/Resume vs Cancel ═══

    @Test
    fun `pause-resume cycle is legal and cancel is absolute terminal`() {
        // RUNNING -> PAUSED -> RUNNING（resume 从断点继续）
        assertTrue(TaskStatusMachine.isLegal(TaskStatus.RUNNING, TaskStatus.PAUSED))
        assertTrue(TaskStatusMachine.isLegal(TaskStatus.PAUSED, TaskStatus.RUNNING))

        // RUNNING -> CANCELLING -> CANCELLED
        assertTrue(TaskStatusMachine.isLegal(TaskStatus.RUNNING, TaskStatus.CANCELLING))
        assertTrue(TaskStatusMachine.isLegal(TaskStatus.CANCELLING, TaskStatus.CANCELLED))

        // CANCELLED 无任何出边：重启后不自动继续（与 PAUSED 的核心区别）
        ALL.forEach { to ->
            assertFalse("CANCELLED -> $to must be illegal", TaskStatusMachine.isLegal(TaskStatus.CANCELLED, to))
        }
        // COMPLETED 同为绝对终态
        ALL.forEach { to ->
            assertFalse("COMPLETED -> $to must be illegal", TaskStatusMachine.isLegal(TaskStatus.COMPLETED, to))
        }
    }

    // ═══ 3. 非法迁移异常 ═══

    @Test
    fun `illegal transition throws with from-to details`() {
        val ex = assertThrows(TaskStatusMachine.IllegalTaskTransitionException::class.java) {
            TaskStatusMachine.requireLegal(TaskStatus.COMPLETED, TaskStatus.RUNNING)
        }
        assertEquals(TaskStatus.COMPLETED, ex.from)
        assertEquals(TaskStatus.RUNNING, ex.to)
        assertTrue(ex.message!!.contains("COMPLETED"))
        assertTrue(ex.message!!.contains("RUNNING"))
    }

    @Test
    fun `self loops are always illegal`() {
        ALL.forEach { s ->
            assertFalse("$s -> $s self loop must be illegal", TaskStatusMachine.isLegal(s, s))
        }
    }

    @Test
    fun `cancel shortcut from RUNNING to CANCELLED is illegal - must pass CANCELLING`() {
        // 取消必须经过 CANCELLING 瞬态（协作式收尾），不允许直接跳终态
        assertFalse(TaskStatusMachine.isLegal(TaskStatus.RUNNING, TaskStatus.CANCELLED))
    }

    // ═══ 4. transition() 状态对象演进 ═══

    @Test
    fun `transition stamps startedAt on first activation and completedAt on terminal`() {
        val pending = newTask(TaskStatus.PENDING)

        val running = TaskStatusMachine.transition(pending, TaskStatus.RUNNING, T0 + 100)
        assertEquals(TaskStatus.RUNNING, running.status)
        assertEquals(T0 + 100, running.startedAt)
        assertEquals(T0 + 100, running.updatedAt)
        assertEquals(0L, running.completedAt)

        // startedAt 只固定一次
        val waiting = TaskStatusMachine.transition(running, TaskStatus.WAITING_USER, T0 + 200)
        assertEquals(T0 + 100, waiting.startedAt)
        assertEquals(T0 + 200, waiting.updatedAt)

        val completed = TaskStatusMachine.transition(waiting, TaskStatus.RUNNING, T0 + 300)
            .let { TaskStatusMachine.transition(it, TaskStatus.COMPLETED, T0 + 400) }
        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertEquals(T0 + 400, completed.completedAt)
    }

    @Test
    fun `transition on illegal path throws and does not mutate input`() {
        val task = newTask(TaskStatus.CANCELLED)
        assertThrows(TaskStatusMachine.IllegalTaskTransitionException::class.java) {
            TaskStatusMachine.transition(task, TaskStatus.RUNNING, T0)
        }
        assertEquals(TaskStatus.CANCELLED, task.status) // data class copy 语义：原对象不变
    }

    @Test
    fun `failed task retry path - FAILED to RETRYING to RUNNING`() {
        val failed = newTask(TaskStatus.FAILED)
        val retrying = TaskStatusMachine.transition(failed, TaskStatus.RETRYING, T0 + 50)
        assertEquals(TaskStatus.RETRYING, retrying.status)
        val running = TaskStatusMachine.transition(retrying, TaskStatus.RUNNING, T0 + 60)
        assertEquals(TaskStatus.RUNNING, running.status)
        // FAILED 之外仅两条受控出边
        assertEquals(setOf(TaskStatus.RETRYING, TaskStatus.CANCELLED),
            TaskStatusMachine.transitions[TaskStatus.FAILED])
    }

    // ═══ 5. 崩溃恢复入口降级 ═══

    @Test
    fun `crashRecoveryEntry maps active states to RECOVERING and keeps PAUSED`() {
        assertEquals(TaskStatus.RECOVERING, TaskStatusMachine.crashRecoveryEntry(TaskStatus.RUNNING))
        assertEquals(TaskStatus.RECOVERING, TaskStatusMachine.crashRecoveryEntry(TaskStatus.WAITING_USER))
        assertEquals(TaskStatus.RECOVERING, TaskStatusMachine.crashRecoveryEntry(TaskStatus.PLANNING))
        assertEquals(TaskStatus.RECOVERING, TaskStatusMachine.crashRecoveryEntry(TaskStatus.PENDING))
        assertEquals(TaskStatus.RECOVERING, TaskStatusMachine.crashRecoveryEntry(TaskStatus.RECOVERING))
        // PAUSED 保持：用户明确暂停过，恢复发现后等用户决定，不自动续跑
        assertEquals(TaskStatus.PAUSED, TaskStatusMachine.crashRecoveryEntry(TaskStatus.PAUSED))
        // 终态/瞬态不参与恢复
        assertNull(TaskStatusMachine.crashRecoveryEntry(TaskStatus.COMPLETED))
        assertNull(TaskStatusMachine.crashRecoveryEntry(TaskStatus.CANCELLED))
        assertNull(TaskStatusMachine.crashRecoveryEntry(TaskStatus.FAILED))
    }

    // ═══ 6. AgentTask 派生属性 ═══

    @Test
    fun `task derived progress fields track step lifecycle`() {
        val task = newTask(TaskStatus.RUNNING).copy(
            steps = listOf(
                TaskStepModel("s0", 0, "a", status = StepStatus.DONE),
                TaskStepModel("s1", 1, "b", status = StepStatus.RUNNING),
                TaskStepModel("s2", 2, "c", status = StepStatus.PENDING)
            )
        )
        assertEquals(1, task.currentStepIndex)
        assertEquals(1, task.completedSteps)
        assertTrue(task.isActive)

        val allDone = task.copy(
            steps = task.steps.map { it.copy(status = StepStatus.DONE) },
            status = TaskStatus.COMPLETED
        )
        assertEquals(3, allDone.completedSteps)
        assertEquals(3, allDone.currentStepIndex) // 最后完成步 + 1
        assertTrue(allDone.isTerminal)

        val empty = newTask(TaskStatus.PENDING)
        assertEquals(-1, empty.currentStepIndex)
        assertTrue(empty.isActive)
    }

    // ═══ 7. v2 回归：恢复降级迁移必须合法（秒闪退根因） ═══

    /**
     * 回归测试（v1.4.9 秒闪退修复）：crashRecoveryEntry 对**每个活跃态**
     * 返回的降级目标，都必须是 from 的合法出边 —— 否则启动恢复扫描
     * discoverRecoverableTasks() 的 transition() 必抛 IllegalTaskTransitionException，
     * 经 VM init 无保护协程直接杀死进程（磁盘上存在 PENDING/PLANNING 态任务
     * 时 100% 复现，每次冷启动重复 = 秒闪退循环）。
     */
    @Test
    fun `every crashRecoveryEntry mapping is a legal transition - startup scan must not throw`() {
        ALL.filter { it != TaskStatus.COMPLETED && it != TaskStatus.CANCELLED }
            .forEach { from ->
                val entry = TaskStatusMachine.crashRecoveryEntry(from)
                if (entry != null && entry != from) {
                    assertTrue(
                        "crashRecoveryEntry($from) = $entry must be a legal transition, " +
                            "otherwise startup recovery scan kills the process",
                        TaskStatusMachine.isLegal(from, entry)
                    )
                }
            }
    }

    /** PENDING/PLANNING 崩溃现场 → 恢复扫描迁移为 RECOVERING（v2 补出边）。 */
    @Test
    fun `PENDING and PLANNING crash leftovers migrate to RECOVERING without throwing`() {
        val pending = newTask(TaskStatus.PENDING)
        val planning = newTask(TaskStatus.PLANNING)

        val r1 = TaskStatusMachine.transition(pending, TaskStatusMachine.crashRecoveryEntry(TaskStatus.PENDING)!!, T0 + 1)
        assertEquals(TaskStatus.RECOVERING, r1.status)

        val r2 = TaskStatusMachine.transition(planning, TaskStatusMachine.crashRecoveryEntry(TaskStatus.PLANNING)!!, T0 + 1)
        assertEquals(TaskStatus.RECOVERING, r2.status)
    }
}
