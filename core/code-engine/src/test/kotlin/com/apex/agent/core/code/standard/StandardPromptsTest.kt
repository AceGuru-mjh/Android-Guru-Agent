package com.apex.agent.core.code.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardPrompts 提示词层测试：非空 / 关键指令在场 / 结构段完整。
 *
 * 提示词是标准循环的灵魂——这里锁住每段的核心动词，防重构时静默漂移。
 */
class StandardPromptsTest {

    @Test
    fun `all profile prompts are non-blank and carry identity`() {
        val prompts = mapOf(
            "build" to StandardPrompts.buildAgent(),
            "plan" to StandardPrompts.planAgent(),
            "general" to StandardPrompts.generalAgent(),
            "explore" to StandardPrompts.exploreSubAgent(),
            "research" to StandardPrompts.researchSubAgent(),
            "general-sub" to StandardPrompts.generalSubAgent()
        )
        prompts.forEach { (key, prompt) ->
            assertTrue("$key prompt not blank", prompt.isNotBlank())
            assertTrue("$key prompt 有体量", prompt.length > 200)
        }
        assertTrue(StandardPrompts.planAgent().contains("READ-ONLY"))
        assertTrue(StandardPrompts.exploreSubAgent().contains("READ-ONLY"))
        assertTrue(StandardPrompts.buildAgent().contains("BUILD agent"))
    }

    @Test
    fun `plan prompt defines the output contract`() {
        val p = StandardPrompts.planAgent()
        assertTrue(p.contains("### Goal"))
        assertTrue(p.contains("### Steps"))
        assertTrue(p.contains("### Verification"))
        assertTrue(p.contains("### Risks"))
    }

    @Test
    fun `environment context renders the env block with model identity`() {
        val env = StandardPrompts.environmentContext(
            modelId = "glm-4.7",
            todayText = "Sat Oct 4 2026",
            platformText = "android-arm64",
            workdirLabel = "/ws/demo",
            isGitRepo = true
        )
        assertTrue(env.startsWith("You are powered by the model named glm-4.7."))
        assertTrue(env.contains("<env>"))
        assertTrue(env.contains("</env>"))
        assertTrue(env.contains("Working directory: /ws/demo"))
        assertTrue(env.contains("Is directory a git repo: yes"))
        assertTrue(env.contains("Platform: android-arm64"))
        assertTrue(env.contains("Today's date: Sat Oct 4 2026"))
    }

    @Test
    fun `environment context degrades gracefully on unknowns`() {
        val env = StandardPrompts.environmentContext(
            modelId = null,
            todayText = "Sat Oct 4 2026",
            platformText = "android-arm64",
            workdirLabel = null,
            isGitRepo = null
        )
        assertFalse("模型未知时省略首句", env.contains("powered by"))
        assertTrue(env.contains("Is directory a git repo: unknown"))
        assertTrue(env.contains("Working directory: (unknown)"))
        // git 三态的另外两档
        val noRepo = StandardPrompts.environmentContext(
            modelId = null, todayText = "d", platformText = "p",
            workdirLabel = null, isGitRepo = false
        )
        assertTrue(noRepo.contains("Is directory a git repo: no"))
    }

    @Test
    fun `core conduct locks tone objectivity file git and citation rules`() {
        val c = StandardPrompts.coreConduct()
        assertTrue(c.contains("## Core Conduct"))
        // 语气
        assertTrue(c.contains("information-dense"))
        assertTrue(c.contains("under 4 lines"))
        assertTrue(c.contains("emoji if the user explicitly requests"))
        // 专业客观
        assertTrue(c.contains("technical accuracy beats agreement"))
        // 文件纪律（“NEVER proactively” 跨行，断言单行片段）
        assertTrue(c.contains("ALWAYS prefer editing an existing file"))
        assertTrue(c.contains("proactively create documentation files"))
        // git 纪律
        assertTrue(c.contains("NEVER commit unless the user explicitly asks"))
        assertTrue(c.contains("git status / git diff / git log"))
        // 代码引用格式
        assertTrue(c.contains("path/to/file.kt:123"))
        // 通信与运行时提醒机制
        assertTrue(c.contains("<system-reminder>"))
        assertTrue(c.contains("\"SYSTEM:\""))
        assertTrue(c.contains("not user input"))
        // 完成门槛
        assertTrue(c.contains("Never claim completion"))
    }

    @Test
    fun `tool discipline maps file ops to dedicated tools`() {
        val d = StandardPrompts.toolDiscipline(hasShell = true)
        assertTrue(d.contains("## Tool Discipline"))
        assertTrue(d.contains("NOT `find`"))
        assertTrue(d.contains("NOT `grep`"))
        assertTrue(d.contains("NOT `cat`"))
        assertTrue(d.contains("NOT `sed`"))
        assertTrue(d.contains("NOT `echo >`"))
        assertTrue(d.contains("NOT `echo`/`printf`"))
        assertTrue(d.contains("&&"))
        assertTrue(d.contains("Do not separate commands with newlines"))
        assertTrue(d.contains("sub-agent via `task`"))
    }

    @Test
    fun `tool discipline is empty without shell`() {
        assertEquals("", StandardPrompts.toolDiscipline(hasShell = false))
    }

    @Test
    fun `plan mode reminder is a read-only system reminder`() {
        val r = StandardPrompts.planModeReminder()
        assertTrue(r.startsWith("<system-reminder>"))
        assertTrue(r.endsWith("</system-reminder>"))
        assertTrue(r.contains("PLAN mode (read-only)"))
        assertTrue(r.contains("MUST NOT"))
        assertTrue(r.contains("supersedes any other"))
        assertTrue(r.contains("present the plan in the required"))
    }

    @Test
    fun `general subagent persona carries result verification notes contract`() {
        val g = StandardPrompts.generalSubAgent()
        assertTrue(g.contains("GENERAL sub-agent"))
        assertTrue(g.contains("cannot see the parent"))
        assertTrue(g.contains("read before you write"))
        assertTrue(g.contains("minimal"))
        assertTrue(g.contains("cannot dispatch further sub-agents"))
        assertTrue(g.contains("permission gate"))
        assertTrue(g.contains("## Result"))
        assertTrue(g.contains("## Verification"))
        assertTrue(g.contains("## Notes"))
    }

    @Test
    fun `task methodology carries the eight rules`() {
        val m = StandardPrompts.taskMethodology()
        assertTrue(m.contains("Read before you write"))
        assertTrue(m.contains("minimal diff"))
        assertTrue(m.contains("Verify after every change"))
        assertTrue(m.contains("todo list"))
        assertTrue(m.contains("Delegate exploration"))
        assertTrue(m.contains("Report honestly"))
        // 反占位铁律（任务完成逻辑的硬承诺）
        assertTrue(m.contains("TODO: implement later"))
    }

    @Test
    fun `subagent prompt demands evidence format`() {
        val e = StandardPrompts.exploreSubAgent()
        assertTrue(e.contains("## Conclusion"))
        assertTrue(e.contains("## Evidence"))
        assertTrue(e.contains("## Uncertainty"))
        val r = StandardPrompts.researchSubAgent()
        assertTrue(r.contains("## Sources"))
    }

    @Test
    fun `subagent brief embeds description and prompt`() {
        val brief = StandardPrompts.subAgentBrief("找出调用点", "扫描 module A", "/ws/root")
        assertTrue(brief.contains("找出调用点"))
        assertTrue(brief.contains("扫描 module A"))
        assertTrue(brief.contains("/ws/root"))
        assertTrue(brief.contains("no parent history"))
    }

    @Test
    fun `plan execution brief lists confirmed steps`() {
        val brief = StandardPrompts.planExecutionBrief("修登录", listOf("读代码", "改代码", "验证"))
        // 切换声明（PLAN → BUILD，只读约束解除）在前
        assertTrue(brief.contains("switched from PLAN"))
        assertTrue(brief.contains("read-only mode and may now edit files"))
        assertTrue(brief.contains("修登录"))
        assertTrue(brief.contains("1. 读代码"))
        assertTrue(brief.contains("3. 验证"))
        assertTrue(brief.contains("todo list"))
    }

    @Test
    fun `permission denial tool result is model-actionable`() {
        val out = StandardPrompts.permissionDeniedToolResult("shell_execute", "DEFAULT 模式兜底")
        assertTrue(out.contains("shell_execute"))
        assertTrue(out.contains("safer route"))
        assertFalse(out.contains("{"))
    }

    @Test
    fun `budget exhaustion directive forbids more tool calls`() {
        val d = StandardPrompts.turnBudgetExhausted()
        assertTrue(d.contains("turn budget"))
        assertTrue(d.contains("Do not call"))
    }

    @Test
    fun `repetitive call warning includes count and tool`() {
        val w = StandardPrompts.repetitiveCallWarning(3, "code_grep")
        assertTrue(w.contains("3 times"))
        assertTrue(w.contains("code_grep"))
    }

    @Test
    fun `subagent tool result wraps conclusion in xml envelope`() {
        val outcome = StandardSubAgentOutcome(
            kind = StandardAgentKind.EXPLORE,
            output = "结论：X 被 3 处调用",
            turns = 4,
            toolCalls = 6,
            durationMs = 12_500
        )
        val out = StandardPrompts.subAgentToolResult(outcome)
        // 统计行在前，XML 信封在后
        assertTrue(out.contains("Sub-agent [explore] finished: 4 turns / 6 tool calls / 12.5s"))
        assertTrue(out.contains("<task state=\"completed\">"))
        assertTrue(out.contains("<task_result>"))
        assertTrue(out.contains("结论：X 被 3 处调用"))
        assertTrue(out.contains("</task_result>"))
        assertTrue(out.endsWith("</task>"))
    }

    @Test
    fun `subagent tool result flags partial and error states`() {
        val partial = StandardSubAgentOutcome(
            kind = StandardAgentKind.RESEARCH,
            output = "部分结论",
            turns = 3,
            toolCalls = 2,
            durationMs = 1_000,
            truncated = true
        )
        val partialOut = StandardPrompts.subAgentToolResult(partial)
        assertTrue(partialOut.contains("<task state=\"partial\">"))
        assertTrue(partialOut.contains("(partial: timed out)"))
        // 失败折叠：output 前缀判断 → error 态
        val failed = StandardSubAgentOutcome.failure(StandardAgentKind.GENERAL, "预算耗尽")
        val failedOut = StandardPrompts.subAgentToolResult(failed)
        assertTrue(failedOut.contains("<task state=\"error\">"))
        assertTrue(failedOut.contains("子代理执行失败：预算耗尽"))
    }

    @Test
    fun `unknown tool result suggests near misses`() {
        val out = StandardPrompts.unknownToolResult("red", listOf("code_read", "code_edit"))
        assertTrue(out.contains("Unknown tool: 'red'."))
        assertTrue(out.contains("Did you mean: code_read, code_edit?"))
        assertTrue(out.contains("available tools list"))
        assertTrue(out.contains("exact name"))
    }

    @Test
    fun `unknown tool result without suggestions points to the surface`() {
        val out = StandardPrompts.unknownToolResult("frobnicate", emptyList())
        assertTrue(out.contains("Unknown tool: 'frobnicate'."))
        assertFalse("无候选时不出现 Did you mean", out.contains("Did you mean"))
        assertTrue(out.contains("available tools list"))
    }

    @Test
    fun `compaction prompt locks the structured summary template`() {
        val p = StandardPrompts.compactionSummary("压缩以下转录")
        assertTrue(p.contains("压缩以下转录"))
        // 结构化模板五节全在场（无内容写 (none)）
        assertTrue(p.contains("## Objective"))
        assertTrue(p.contains("## Important Details"))
        assertTrue(p.contains("## Work State"))
        assertTrue(p.contains("### Completed"))
        assertTrue(p.contains("### Active"))
        assertTrue(p.contains("### Blocked"))
        assertTrue(p.contains("## Next Move"))
        assertTrue(p.contains("## Relevant Files"))
        assertTrue(p.contains("(none)"))
        // 规则：terse bullets / 保留原文 / 不提压缩过程
        assertTrue(p.contains("terse bullets"))
        assertTrue(p.contains("Preserve exact file paths"))
        assertTrue(p.contains("Do not mention that context was compacted"))
        // 无旧摘要时不出现合并指令
        assertFalse(p.contains("<prior-summary>"))
    }

    @Test
    fun `compaction prompt merges prior summary when present`() {
        val p = StandardPrompts.compactionSummary(
            "压缩以下转录",
            priorSummary = "## Objective\n- 旧目标"
        )
        assertTrue(p.contains("<prior-summary>"))
        assertTrue(p.contains("</prior-summary>"))
        assertTrue(p.contains("## Objective\n- 旧目标"))
        assertTrue(p.contains("discarded after this step"))
        assertTrue(p.contains("in favor of the transcript"))
        assertTrue(p.contains("from Active to"))
        assertTrue(p.contains("refresh Objective and Next Move"))
    }

    @Test
    fun `tool surface section embeds the dynamic guide`() {
        val s = StandardPrompts.toolSurfaceSection("Available: code_read, code_edit")
        assertTrue(s.contains("## Tool Surface"))
        assertTrue(s.contains("code_edit"))
    }

    @Test
    fun `todo guidance locks status semantics`() {
        val g = StandardPrompts.todoGuidance()
        assertTrue(g.contains("## Working With The Todo List"))
        assertTrue(g.contains("in_progress"))
        assertTrue(g.contains("completed"))
        // When to use / NOT use / 实时更新硬规则
        assertTrue(g.contains("3+ steps"))
        assertTrue(g.contains("Do NOT use it"))
        assertTrue(g.contains("never batch updates"))
        assertTrue(g.contains("only AFTER the step is verified"))
        assertTrue(g.contains("blocker item"))
        assertTrue(g.contains("verbatim"))
    }
}
