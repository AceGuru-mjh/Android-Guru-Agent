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
 * - "仅允许一次" → 本次放行但不记录（下次再问）；
 * - "本会话允许" → 状态机记 ALLOWED_SESSION，之后静默放行；
 * - 拒绝 / 超时 → 状态机记 DENIED_SESSION，后续调用直接拒绝并给模型
 *   可执行指引（换方案，勿重试同一工具）；
 * - `selfGated` 工具（shell_execute 已预置）跳过本门——它们自带更细粒度
 *   的命令级确认，双重弹窗只会骚扰用户。
 *
 * 交互闭环留在 app 层是刻意的：core:tool-registry 不依赖引擎问题桥
 * （依赖方向约束），任何宿主（测试、headless 运行时）注入自己的确认
 * 回调即可复用同一状态机。
 */
class RiskAwareToolGate(
    private val gateway: UserQuestionGateway
) : ToolExecutionGate {

    private val manager = ToolPermissionManager(
        confirm = { _, _ -> false } // never used — check() drives the FSM directly
    )

    override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
        if (tool.id in manager.selfGatedToolIds) return GateDecision.Allow

        val metadata = tool.metadata
        val requiresPrompt = when (metadata.risk) {
            ToolRisk.HIGH -> true
            else -> false // MEDIUM/LOW: 沙箱内的可恢复操作，不弹窗
        }
        if (!requiresPrompt) return GateDecision.Allow

        return when (manager.decisionFor(tool.id)) {
            SessionToolDecision.ALLOWED_SESSION -> GateDecision.Allow
            SessionToolDecision.DENIED_SESSION -> GateDecision.Deny(
                "user previously denied '${tool.id}' this session; do not retry it — " +
                    "choose a different approach and tell the user why"
            )
            SessionToolDecision.UNDECIDED -> promptUser(tool, metadata, arguments)
        }
    }

    private suspend fun promptUser(
        tool: AgentTool,
        metadata: ToolMetadata,
        arguments: String
    ): GateDecision {
        val answer = gateway.ask(buildQuestion(metadata, arguments))
        return when (answer.selectedOptionId) {
            "allow_session" -> {
                manager.allowForSession(tool.id)
                GateDecision.Allow
            }
            "allow_once" -> {
                // 不记录 —— 下次调用重新询问（真正的"一次"）。
                GateDecision.Allow
            }
            else -> {
                manager.denyForSession(tool.id)
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
        return AgentQuestion(
            title = "高风险工具需要确认：${metadata.id}",
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
    }
}
