package com.apex.agent.permission

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserQuestionBridge
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolExecutionGate
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #155 — opencode 式权限模式单测（纯 JVM，JUnit4 + runTest）。
 *
 * 覆盖（任务书 §测试）：
 *  - 四模式决策矩阵（BYPASS / DEFAULT / ACCEPT_EDITS / PLAN）；
 *  - 规则语义：首匹配优先于模式默认、越级放行 / 收紧、尾缀通配、
 *    单星通配、空模式、大小写敏感；
 *  - 门交互闭环：会话记忆（allow_session 记忆 / allow_once 不记忆）、
 *    deny 映射、gateway 异常、真实 UserQuestionBridge 的超时与取消
 *    （skipped 折叠形态）、resetSession、check 与 checkDetailed 一致性、
 *    授权问题构造（选项 / 截断 / 禁自定义）；
 *  - 组合器三路分派：显式放行不问 fallback、默认放行转发 fallback、
 *    拒绝直接拦截。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PermissionDeciderTest {

    // ══════════════════════ 决策矩阵 ══════════════════════

    @Test
    fun `BYPASS 模式全放行且为显式放行`() {
        val decision = PermissionDecider.decide(
            PermissionMode.BYPASS, emptyList(), writeCtx("shell_execute")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `BYPASS 模式下 DENY 规则不生效（全放行是模式硬承诺）`() {
        val decision = PermissionDecider.decide(
            PermissionMode.BYPASS,
            listOf(PermissionRule("*", PermissionEffect.DENY)),
            writeCtx()
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `DEFAULT 模式只读工具默认放行且仍交后续门`() {
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(), readCtx("code_read")
        )
        assertTrue(decision is PermissionDecision.AllowDefault)
    }

    @Test
    fun `DEFAULT 模式非只读工具一律询问（普通、破坏性、敏感三类同判）`() {
        val plain = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(), writeCtx("code_edit_file")
        )
        val destructive = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(),
            PermissionContext("delete_file", readOnlyHint = false, destructiveHint = true, sensitiveAction = false)
        )
        val sensitiveWrite = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(),
            PermissionContext("sensitive_write", readOnlyHint = false, destructiveHint = false, sensitiveAction = true)
        )
        assertTrue(plain is PermissionDecision.Ask)
        assertTrue(destructive is PermissionDecision.Ask)
        assertTrue(sensitiveWrite is PermissionDecision.Ask)
        // 顺序边界：readOnlyHint 先于 sensitiveAction 判定——只读但敏感的工具
        // （如 notification_read）得到 AllowDefault，仍交后续风险门把关
        val sensitiveRead = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(),
            PermissionContext("notification_read", readOnlyHint = true, destructiveHint = false, sensitiveAction = true)
        )
        assertTrue(sensitiveRead is PermissionDecision.AllowDefault)
    }

    @Test
    fun `ACCEPT_EDITS 模式自动放行编辑写类工具`() {
        val decision = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), writeCtx("code_edit_file")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `ACCEPT_EDITS 模式只读放行而非编辑写类仍询问`() {
        val readOnly = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), readCtx("code_read")
        )
        val notEditLike = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), writeCtx("shell_execute")
        )
        assertTrue(readOnly is PermissionDecision.AllowExplicit)
        assertTrue(notEditLike is PermissionDecision.Ask)
    }

    @Test
    fun `PLAN 模式只读工具默认放行`() {
        val decision = PermissionDecider.decide(
            PermissionMode.PLAN, emptyList(), readCtx("code_read")
        )
        assertTrue(decision is PermissionDecision.AllowDefault)
    }

    @Test
    fun `PLAN 模式拒绝非只读并给出中文原因`() {
        val decision = PermissionDecider.decide(
            PermissionMode.PLAN, emptyList(), writeCtx("code_edit_file")
        )
        val deny = decision as PermissionDecision.Deny
        assertTrue(deny.reason.contains("只读"))
        assertTrue(deny.reason.contains("code_edit_file"))
    }

    @Test
    fun `PLAN 模式下 ALLOW 规则不可越级放行写操作`() {
        val decision = PermissionDecider.decide(
            PermissionMode.PLAN,
            listOf(PermissionRule("*", PermissionEffect.ALLOW)),
            writeCtx()
        )
        assertTrue(decision is PermissionDecision.Deny)
    }

    // ══════════════════════ 规则语义 ══════════════════════

    @Test
    fun `ALLOW 规则越级放行 DEFAULT 模式下的非只读工具`() {
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("shell_execute", PermissionEffect.ALLOW)),
            writeCtx("shell_execute")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `规则按序首条生效（精确 DENY 先于通配 ALLOW）`() {
        val decision = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS,
            listOf(
                PermissionRule("code_edit_file", PermissionEffect.DENY),
                PermissionRule("code_edit_*", PermissionEffect.ALLOW)
            ),
            writeCtx("code_edit_file")
        )
        assertTrue(decision is PermissionDecision.Deny)
    }

    @Test
    fun `尾缀通配匹配 MCP 动态注册工具 id`() {
        assertTrue(PermissionRuleMatcher.matches("mcp__github__*", "mcp__github__create_issue"))
        assertTrue(PermissionRuleMatcher.matches("code_git_*", "code_git_commit"))
        assertFalse(PermissionRuleMatcher.matches("code_git_*", "code_gitx"))
        // 决策层集成：通配规则放行运行期才知道 id 的 MCP 写工具
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("mcp__github__*", PermissionEffect.ALLOW)),
            writeCtx("mcp__github__create_issue")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `单独星号规则匹配一切工具`() {
        assertTrue(PermissionRuleMatcher.matches("*", "shell_execute"))
        assertTrue(PermissionRuleMatcher.matches("*", "mcp__anything__goes"))
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("*", PermissionEffect.ALLOW)),
            writeCtx("shell_execute")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `空模式规则不匹配任何工具`() {
        assertFalse(PermissionRuleMatcher.matches("", "code_edit"))
        assertFalse(PermissionRuleMatcher.matches("   ", "code_edit"))
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("", PermissionEffect.ALLOW)),
            writeCtx()
        )
        assertTrue(decision is PermissionDecision.Ask)
    }

    @Test
    fun `精确匹配大小写敏感`() {
        assertTrue(PermissionRuleMatcher.matches("code_edit", "code_edit"))
        assertFalse(PermissionRuleMatcher.matches("Code_Edit", "code_edit"))
    }

    @Test
    fun `ASK 规则对只读工具同样越级收紧触发询问`() {
        val decision = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS,
            listOf(PermissionRule("code_read", PermissionEffect.ASK)),
            readCtx("code_read")
        )
        assertTrue(decision is PermissionDecision.Ask)
    }

    @Test
    fun `编辑写类前缀判定覆盖正反例`() {
        val editLike = listOf(
            "code_edit_file", "code_write_block", "code_git_commit",
            "code_git_branch_create", "write_file", "edit_file",
            "delete_file", "copy_move_file"
        )
        editLike.forEach { id ->
            assertTrue("应判定为编辑类：$id", PermissionDecider.isEditLike(id))
        }
        val notEditLike = listOf(
            "code_read", "code_search", "code_task",
            "code_git_status", "code_git_diff", "code_git_log",
            "shell_execute", "web_fetch"
        )
        notEditLike.forEach { id ->
            assertFalse("不应判定为编辑类：$id", PermissionDecider.isEditLike(id))
        }
        assertEquals(8, PermissionDecider.EDIT_LIKE_PREFIXES.size)
    }

    // ══════════════════════ 门：询问闭环与会话记忆 ══════════════════════

    @Test
    fun `allow_session 授权被记忆第二次静默放行`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") }
        val gate = PermissionModeGate(
            gateway,
            { PermissionSnapshot(PermissionMode.DEFAULT) },
            clock = { 42L }
        )
        val tool = mutatingTool()

        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)

        assertEquals(1, gateway.questions.size)
        assertEquals(mapOf("code_edit_file" to 42L), gate.sessionAllowedSnapshot())
    }

    @Test
    fun `allow_once 不记忆下次再问`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })
        val tool = mutatingTool()

        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)

        assertEquals(2, gateway.questions.size)
        assertTrue(gate.sessionAllowedSnapshot().isEmpty())
    }

    @Test
    fun `拒绝回答映射为拒绝并带模型指引`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "deny") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })

        val decision = gate.check(mutatingTool(), "{}")

        val deny = decision as GateDecision.Deny
        assertTrue(deny.reason.contains("用户拒绝"))
        assertTrue(deny.reason.contains("code_edit_file"))
    }

    @Test
    fun `询问通道异常按拒绝处理`() = runTest {
        val gateway = FakeGateway { _ -> throw IllegalStateException("boom") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })

        val decision = gate.check(mutatingTool(), "{}")

        val deny = decision as GateDecision.Deny
        assertTrue(deny.reason.contains("异常"))
    }

    @Test
    fun `真实 bridge 超时折叠为 skipped 并按拒绝处理`() = runTest {
        val bridge = UserQuestionBridge()
        val gate = PermissionModeGate(bridge, { PermissionSnapshot(PermissionMode.DEFAULT) })
        var result: GateDecision? = null

        val job = launch { result = gate.check(mutatingTool(), "{}") }
        advanceUntilIdle()                       // ask 挂起等待回答
        advanceTimeBy(5 * 60 * 1000L + 1_000L)   // 虚拟时间越过 5 分钟超时线
        advanceUntilIdle()
        job.join()

        val deny = result as GateDecision.Deny
        assertTrue(deny.reason.contains("超时"))
    }

    @Test
    fun `bridge 主动取消当前问题同样按拒绝处理`() = runTest {
        val bridge = UserQuestionBridge()
        val gate = PermissionModeGate(bridge, { PermissionSnapshot(PermissionMode.DEFAULT) })
        var result: GateDecision? = null

        val job = launch { result = gate.check(mutatingTool(), "{}") }
        advanceUntilIdle()
        bridge.cancelCurrentQuestion()            // skipped 形态回答
        advanceUntilIdle()
        job.join()

        val deny = result as GateDecision.Deny
        assertTrue(deny.reason.contains("超时"))
    }

    @Test
    fun `resetSession 清空会话授权记忆`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })
        val tool = mutatingTool()

        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertEquals(1, gateway.questions.size)

        gate.resetSession()
        assertTrue(gate.sessionAllowedSnapshot().isEmpty())

        // 记忆已清：同一工具再次触发询问
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertEquals(2, gateway.questions.size)
    }

    @Test
    fun `check 与 checkDetailed 两入口语义一致`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "deny") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })
        val tool = mutatingTool()

        val viaCheck = gate.check(tool, "{}")
        val viaDetailed = gate.checkDetailed(tool, "{}")

        val denyA = viaCheck as GateDecision.Deny
        val denyB = viaDetailed as PermissionModeGate.DetailedDecision.Denied
        assertEquals(denyA.reason, denyB.reason)
    }

    @Test
    fun `授权问题构造含工具 id 模式提示与参数摘要截断`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.ACCEPT_EDITS) })

        gate.checkDetailed(
            FakeTool("shell_execute", ToolAnnotations.mutating()),
            "x".repeat(500)
        )

        val question = gateway.questions.single()
        assertTrue(question.title.contains("工具执行授权"))
        assertTrue(question.title.contains("shell_execute"))
        // 模式提示 + 参数摘要截 300 字符
        val description = question.description.orEmpty()
        assertTrue(description.contains("ACCEPT_EDITS"))
        assertTrue(description.contains("x".repeat(300)))
        assertFalse(description.contains("x".repeat(301)))
        // 选项三元组与 RiskAwareToolGate 对齐；禁自定义 / 禁跳过
        assertEquals(listOf("allow_session", "allow_once", "deny"), question.options.map { it.id })
        assertFalse(question.allowCustom)
        assertFalse(question.allowSkip)
    }

    @Test
    fun `PermissionSnapshot 默认值为 DEFAULT 模式空规则`() {
        val snapshot = PermissionSnapshot()
        assertEquals(PermissionMode.DEFAULT, snapshot.mode)
        assertTrue(snapshot.rules.isEmpty())
    }

    // ══════════════════════ 组合器：三路分派 ══════════════════════

    @Test
    fun `显式放行不经过 fallback 风险门`() = runTest {
        val fallback = RecordingFallback()
        val gate = PermissionAwareToolGate(
            PermissionModeGate(
                FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "deny") },
                {
                    PermissionSnapshot(
                        PermissionMode.DEFAULT,
                        listOf(PermissionRule("code_edit_file", PermissionEffect.ALLOW))
                    )
                }
            ),
            fallback
        )

        val decision = gate.check(mutatingTool(), "{}")

        assertTrue(decision is GateDecision.Allow)
        assertTrue(fallback.checkedToolIds.isEmpty())
    }

    @Test
    fun `默认放行转发 fallback 并透传其决定`() = runTest {
        val fallback = RecordingFallback(GateDecision.Deny("风险门拒绝"))
        val gate = PermissionAwareToolGate(
            PermissionModeGate(
                FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") },
                { PermissionSnapshot(PermissionMode.DEFAULT) }
            ),
            fallback
        )

        val decision = gate.check(readonlyTool(), "{}")

        val deny = decision as GateDecision.Deny
        assertEquals("风险门拒绝", deny.reason)
        assertEquals(listOf("code_read"), fallback.checkedToolIds)
    }

    @Test
    fun `权限拒绝直接拦截不消耗 fallback`() = runTest {
        val fallback = RecordingFallback()
        val gate = PermissionAwareToolGate(
            PermissionModeGate(
                FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") },
                { PermissionSnapshot(PermissionMode.PLAN) }
            ),
            fallback
        )

        val decision = gate.check(mutatingTool(), "{}")

        assertTrue(decision is GateDecision.Deny)
        assertTrue(fallback.checkedToolIds.isEmpty())
    }

    // ══════════════════════ 测试基建 ══════════════════════

    /** 只读上下文（readOnlyHint = true）。 */
    private fun readCtx(toolId: String) =
        PermissionContext(toolId, readOnlyHint = true, destructiveHint = false, sensitiveAction = false)

    /** 普通写上下文（非只读、非破坏、非敏感）。 */
    private fun writeCtx(toolId: String = "code_edit_file") =
        PermissionContext(toolId, readOnlyHint = false, destructiveHint = false, sensitiveAction = false)

    /** 元数据完全可控的假工具（annotations 决策三注解的载体）。 */
    private class FakeTool(
        override val id: String,
        annotations: ToolAnnotations
    ) : AgentTool {
        override val name: String = id
        override val description: String = "测试工具"
        override val parametersSchema: String = "{}"
        override val metadata: ToolMetadata = ToolMetadata(
            id = id,
            category = ToolCategory.UTILITY,
            risk = ToolRisk.LOW,
            annotations = annotations
        )

        override suspend fun execute(arguments: String): String = "ok"
    }

    private fun readonlyTool(id: String = "code_read") =
        FakeTool(id, ToolAnnotations.readOnly())

    private fun mutatingTool(id: String = "code_edit_file") =
        FakeTool(id, ToolAnnotations.mutating())

    /** 脚本化问答网关：记录全部问题，按脚本回放答案（可抛异常模拟通道故障）。 */
    private class FakeGateway(
        private val responder: (AgentQuestion) -> AgentAnswer
    ) : UserQuestionGateway {
        val questions = mutableListOf<AgentQuestion>()

        override suspend fun ask(question: AgentQuestion): AgentAnswer {
            questions += question
            return responder(question)
        }
    }

    /** 记录型后续门：验证组合器是否转发、转发给了谁。 */
    private class RecordingFallback(
        private val decision: GateDecision = GateDecision.Allow
    ) : ToolExecutionGate {
        val checkedToolIds = mutableListOf<String>()

        override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
            checkedToolIds += tool.id
            return decision
        }
    }
}
