package com.apex.agent.tools

import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.AgentQuestionOption
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.SessionToolDecision
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolExecutionGate
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolPermissionManager
import com.apex.agent.core.tools.ToolRisk

/**
 * # Tool System v2 — App 端风险门
 *
 * [ToolPermissionManager]（core，纯 JVM 状态机）+ 用户确认对话框的组合，
 * 注入 [DefaultToolExecutor] 后即生效：
 *
 * - HIGH 风险工具首次调用 → 经 [UserQuestionGateway]（复用 ask_user_choice
 *   的既有对话框）向用户弹窗：仅允许一次 / 本会话允许 / 拒绝；
 * - **#230（P1 security）**：MEDIUM 级**文件改写类**工具（write_file /
 *   edit_file 及 Coding 模式的 code_write / code_edit）同样纳入确认链 ——
 *   旧实现 MEDIUM 一律静默放行，Agent 可无声覆写用户文件，与「三级权限链
 *   是核心卖点」的安全叙事直接冲突。弹窗文案区分风险档（「写入确认」vs「高风险
 *   确认」）；会话记忆复用同一状态机（本会话允许后不再骚扰）。
 *   纵深防御说明：业界标准式 PermissionModeGate 在 DEFAULT 模式下已对
 *   非只读工具弹 Ask（PermissionAwareToolGate 的 ExplicitAllow 短路保证
 *   不双弹窗）；本层兜底的是 BYPASS/ACCEPT_EDITS 之外的**其他宿主与直连
 *   风险门的执行器**（测试 / headless / 未来的批量管线）。
 * - "仅允许一次" → 本次放行但不记录（下次再问）；
 * - "本会话允许" → 状态机记 ALLOWED_SESSION，之后静默放行；
 * - 拒绝（用户显式点击，或弹窗被主动取消）→ 状态机记 DENIED_SESSION，
 *   后续调用直接拒绝并给模型可执行指引（换方案，勿重试同一工具）；
 * - 确认超时（#215）→ 超时未决：状态机不动（下次调用重新询问，
 *   本会话不被静默封禁），本次调用明确失败并附「确认超时」语义 ——
 *   绝不把超时静默记成会话级拒绝；
 * - `selfGated` 工具（shell_execute 已预置）跳过本门——它们自带更细粒度
 *   的命令级确认，双重弹窗只会骚扰用户。
 * - **#F-⑯**：每次门决策（弹窗结果 / 会话命中）经 [audit] 输出结构化
 *   JSONL 审计（tool / decision / durationMs）——「agent 在 X 场景走了什么
 *   通道、用户批没批」自此可对外举证。
 *
 * 交互闭环留在 app 层是刻意的：core:tool-registry 不依赖引擎问题桥
 * （依赖方向约束），任何宿主（测试、headless 运行时）注入自己的确认
 * 回调即可复用同一状态机。
 */
class RiskAwareToolGate(
    private val gateway: UserQuestionGateway,
    private val audit: ToolAuditLogger? = null
) : ToolExecutionGate {

    private val manager = ToolPermissionManager(
        confirm = { _, _ -> false } // never used — check() drives the FSM directly
    )

    override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
        if (tool.id in manager.selfGatedToolIds) return GateDecision.Allow

        val metadata = tool.metadata
        // #230：HIGH 必弹；MEDIUM 仅文件改写类弹（沙箱内其他 MEDIUM —— 终端
        // 命令 / UI 查询类 —— 仍是可恢复操作，维持不弹）。
        val requiresPrompt = when {
            metadata.risk == ToolRisk.HIGH -> true
            metadata.risk == ToolRisk.MEDIUM && tool.id in FILE_MUTATING_TOOLS -> true
            else -> false // LOW 及其余 MEDIUM: 沙箱内的可恢复操作，不弹窗
        }
        if (!requiresPrompt) return GateDecision.Allow

        return when (manager.decisionFor(tool.id)) {
            SessionToolDecision.ALLOWED_SESSION -> {
                audit?.log(ToolAuditLogger.Event(tool = tool.id, decision = "allow", detail = "session"))
                GateDecision.Allow
            }
            SessionToolDecision.DENIED_SESSION -> {
                audit?.log(ToolAuditLogger.Event(tool = tool.id, decision = "deny", detail = "session"))
                GateDecision.Deny(
                    "user previously denied '${tool.id}' this session; do not retry it — " +
                        "choose a different approach and tell the user why"
                )
            }
            SessionToolDecision.UNDECIDED -> promptUser(tool, metadata, arguments)
        }
    }

    private suspend fun promptUser(
        tool: AgentTool,
        metadata: ToolMetadata,
        arguments: String
    ): GateDecision {
        val startedAt = System.currentTimeMillis()
        val answer = gateway.ask(buildQuestion(metadata, arguments))
        val durationMs = System.currentTimeMillis() - startedAt
        return when {
            // #215 超时未决：不写 DENIED_SESSION —— 本会话该工具不被静默封禁，
            // 下次调用重新询问；本次调用明确失败并附「确认超时」语义，
            // 模型不会把无响应误读为同意（失败卡片对用户可见，不静默）。
            // 合并注：超时同样进审计（durationMs 记等待全程），决策值 confirm_timeout
            // 与 allow_session / denied_by_user 同为自由字符串，语义互不重叠。
            answer.timedOut -> {
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "confirm_timeout", durationMs = durationMs,
                    command = arguments.take(200), detail = "ask timed out, not denied"
                ))
                GateDecision.Deny(
                    "confirmation for '${tool.id}' timed out " +
                        "(${ASK_TIMEOUT_MS / 1000}s, ${categoryLabel(metadata.category)}/${riskLabel(metadata.risk)}); " +
                        "treat it as unanswered, not denied — the tool will be re-offered on the next call, " +
                        "so ask the user again or pick a safer alternative"
                )
            }
            answer.selectedOptionId == "allow_session" -> {
                manager.allowForSession(tool.id)
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "allow_session", durationMs = durationMs,
                    command = arguments.take(200), detail = "user approved for session"
                ))
                GateDecision.Allow
            }
            answer.selectedOptionId == "allow_once" -> {
                // 不记录 —— 下次调用重新询问（真正的"一次"）。
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "allow_once", durationMs = durationMs,
                    command = arguments.take(200), detail = "user approved once"
                ))
                GateDecision.Allow
            }
            else -> {
                manager.denyForSession(tool.id)
                audit?.log(ToolAuditLogger.Event(
                    tool = tool.id, decision = "denied_by_user", durationMs = durationMs,
                    command = arguments.take(200),
                    detail = if (answer.skipped) "question timeout/skip" else "user denied"
                ))
                GateDecision.Deny(
                    "user denied execution of '${tool.id}' " +
                        "(${categoryLabel(metadata.category)}/${riskLabel(metadata.risk)}); " +
                        "do not retry, propose an alternative and explain to the user"
                )
            }
        }
    }

    private fun buildQuestion(metadata: ToolMetadata, arguments: String): AgentQuestion {
        val riskHint = when (metadata.risk) {
            ToolRisk.HIGH -> "高风险：破坏性或不可逆操作"
            ToolRisk.MEDIUM -> "中风险：会修改数据或状态"
            ToolRisk.LOW -> "低风险"
        }
        // #230：MEDIUM 文件改写类的标题不再谎称「高风险」—— 按实际档位区分，
        // 避免用户对风险标签脱敏。
        val title = if (metadata.risk == ToolRisk.MEDIUM) {
            "文件写入确认：${metadata.id}"
        } else {
            "高风险工具需要确认：${metadata.id}"
        }
        return AgentQuestion(
            title = title,
            description = "$riskHint（${categoryLabel(metadata.category)}）\n" +
                "参数摘要：${arguments.take(200).replace('\n', ' ')}",
            options = listOf(
                AgentQuestionOption(
                    id = "allow_session",
                    label = "本会话允许",
                    description = "本次会话中该工具不再询问"
                ),
                AgentQuestionOption(
                    id = "allow_once",
                    label = "仅允许一次",
                    description = "下次调用将再次询问"
                ),
                AgentQuestionOption(
                    id = "deny",
                    label = "拒绝",
                    description = "不执行，让 Agent 改用其他方案",
                    recommended = true
                )
            ),
            allowCustom = false,
            allowSkip = false,
            timeoutMs = ASK_TIMEOUT_MS
        )
    }

    /** 会话级强制放行（设置界面 / 测试用）。 */
    fun allowForSession(toolId: String) = manager.allowForSession(toolId)

    /** 会话级强制拒绝（设置界面 / 测试用）。 */
    fun denyForSession(toolId: String) = manager.denyForSession(toolId)

    /** 新会话：清除全部会话决定。 */
    fun resetSession() = manager.reset()

    /** 当前会话决定快照（调试 / 设置界面展示）。 */
    fun sessionSnapshot(): Map<String, SessionToolDecision> = manager.snapshot()

    private fun categoryLabel(category: ToolCategory): String = category.label
    private fun riskLabel(risk: ToolRisk): String = risk.label

    private companion object {
        const val ASK_TIMEOUT_MS = 5 * 60 * 1000L

        /** #230：MEDIUM 级也需要确认的**文件改写类**工具 id 集合。
         * （delete_file 已是 HIGH —— inferRisk 黑名单；此处覆盖写/编两类。）
         * Issue #230 收尾：补 coding 模式等价面（code_write/code_edit ——
         * StandardToolSurface 把 write_file/edit_file 映射过去，同一底层行为
         * 必须同一确认语义；file_write/file_edit 是历史别名防漏。）
         * #273：github_write_file 是远程真实 commit（其自身 description 已标
         * ⚠️HIGH-RISK，但 inferRisk 按 github_ 前缀只推到 MEDIUM）—— 远程写
         * 与本地写同一确认语义，且私有仓还有工具内第二道硬门。 */
        val FILE_MUTATING_TOOLS = setOf(
            "write_file", "edit_file", "file_write", "file_edit",
            "code_write", "code_edit", "github_write_file"
        )
    }
}
