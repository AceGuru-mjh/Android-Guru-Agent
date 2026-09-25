package com.apex.agent.permission

import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.AgentQuestionOption
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.ToolExecutionGate
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * 权限快照：settingsProvider 每次检查时现取的（模式 + 规则）只读视图。
 *
 * 门不持有设置引用——宿主（ToolModule DI）注入一个读取当前
 * [AgentSettings][com.apex.agent.ui.screen.settings.AgentSettings] 的 lambda，
 * 用户在设置页改模式 / 增删规则后**下一次工具调用即刻生效**，无需重启。
 *
 * @param mode 当前权限模式。
 * @param rules 当前规则列表（按序首匹配，顺序即优先级）。
 */
data class PermissionSnapshot(
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val rules: List<PermissionRule> = emptyList()
)

/**
 * ═══ opencode 式权限门（Issue #155）═══
 *
 * [PermissionDecider]（纯决策）+ [UserQuestionGateway]（用户询问闭环）
 * + 会话记忆的组合，实现 ToolExecutionGate 契约：
 *
 * - [AllowExplicit][PermissionDecision.AllowExplicit] / 会话记忆命中 /
 *   用户答「本会话允许」或「仅允许一次」→ [GateDecision.Allow]；
 * - [AllowDefault][PermissionDecision.AllowDefault]（模式默认放行）→
 *   同样 Allow——但经 [checkDetailed] 暴露给 [PermissionAwareToolGate]，
 *   由后者转发给风险门继续把关（默认放行 ≠ 用户意志）；
 * - [Deny][PermissionDecision.Deny] → [GateDecision.Deny]（中文原因）；
 * - [Ask][PermissionDecision.Ask] → 先查会话记忆（本会话内该工具 id 已
 *   授权过 → 静默放行，不再骚扰）；未命中则经 gateway 弹授权对话框，
 *   选项与文案对齐 [RiskAwareToolGate][com.apex.agent.tools.RiskAwareToolGate]：
 *   本会话允许（记忆） / 仅允许一次（不记忆） / 拒绝。
 *
 * 超时语义对齐 RiskAwareToolGate：不自行 withTimeout，依赖
 * [UserQuestionBridge][com.apex.agent.core.engine.UserQuestionBridge] 内部的
 * withTimeout(question.timeoutMs)（5 分钟）——超时被桥折叠成 skipped 回答，
 * 本门把 skipped 一律按拒绝处理（挂起等待 5 分钟无响应 ≈ 用户不在场，
 * 放行比拒绝危险得多）。gateway 抛异常（其他实现）同样按拒绝处理，
 * 唯 [CancellationException] 必须向上传播以保真协程取消。
 *
 * 线程安全：会话记忆用并发 Map；decide 纯函数；本门可被任意线程 /
 * 任意协程并发调用（引擎会并行派发工具调用）。
 *
 * @param gateway 用户询问通道（复用 ask_user_choice 的既有对话框）。
 * @param settingsProvider 每次检查现取权限快照（设置热生效的关键）。
 * @param clock 会话授权时间戳来源（默认系统时钟；测试可注入固定值）。
 */
class PermissionModeGate(
    private val gateway: UserQuestionGateway,
    private val settingsProvider: () -> PermissionSnapshot,
    private val clock: () -> Long = System::currentTimeMillis
) : ToolExecutionGate {

    /**
     * 三态详细决策：在 GateDecision 两态之上区分「显式放行」与「默认放行」，
     * 供 [PermissionAwareToolGate] 编排——只有默认放行才转发风险门。
     */
    sealed class DetailedDecision {
        /** 规则 / 模式显式放行，或会话记忆 / 用户刚授权——无需再过风险门。 */
        object ExplicitAllow : DetailedDecision() {
            override fun toString(): String = "ExplicitAllow"
        }

        /** 模式默认放行（无用户意志参与）——仍应交后续门把关。 */
        object DefaultAllow : DetailedDecision() {
            override fun toString(): String = "DefaultAllow"
        }

        /** 拒绝，[reason] 面向模型（中文）。 */
        data class Denied(val reason: String) : DetailedDecision()
    }

    /** 会话记忆：工具 id → 授权时刻（clock 注入，毫秒）。 */
    private val sessionAllowed = ConcurrentHashMap<String, Long>()

    /**
     * ToolExecutionGate 入口：两态折叠（显式 / 默认放行都是 Allow）。
     *
     * 需要区分两态的调用方（组合器）请用 [checkDetailed]；
     * 本方法委托 [checkDetailed]，两入口语义永远一致。
     */
    override suspend fun check(tool: AgentTool, arguments: String): GateDecision =
        when (val decision = checkDetailed(tool, arguments)) {
            is DetailedDecision.ExplicitAllow -> GateDecision.Allow
            is DetailedDecision.DefaultAllow -> GateDecision.Allow
            is DetailedDecision.Denied -> GateDecision.Deny(decision.reason)
        }

    /**
     * 详细入口：决策 → 询问闭环 → 三态结果。
     *
     * 每次调用都现取 [PermissionSnapshot]（设置热生效）；工具上下文从
     * tool.metadata.annotations 读取（readOnlyHint / destructiveHint /
     * sensitiveAction 三个决策注解）。
     */
    suspend fun checkDetailed(tool: AgentTool, arguments: String): DetailedDecision {
        val snapshot = settingsProvider()
        val annotations = tool.metadata.annotations
        val ctx = PermissionContext(
            toolId = tool.id,
            readOnlyHint = annotations.readOnlyHint,
            destructiveHint = annotations.destructiveHint,
            sensitiveAction = annotations.sensitiveAction
        )
        return when (val decision = PermissionDecider.decide(snapshot.mode, snapshot.rules, ctx)) {
            is PermissionDecision.AllowExplicit -> DetailedDecision.ExplicitAllow
            is PermissionDecision.AllowDefault -> DetailedDecision.DefaultAllow
            is PermissionDecision.Deny -> DetailedDecision.Denied(decision.reason)
            is PermissionDecision.Ask -> resolveAsk(tool, arguments, snapshot.mode)
        }
    }

    /**
     * Ask 分支的询问闭环：会话记忆 → 弹窗 → 答案映射。
     */
    private suspend fun resolveAsk(
        tool: AgentTool,
        arguments: String,
        mode: PermissionMode
    ): DetailedDecision {
        // 会话记忆命中：本会话内用户已授权过本工具——静默放行，且视为
        // 显式放行（用户意志已表达，跳过后续风险门，避免权限门刚问完、
        // 风险门再问一次的双弹窗骚扰）
        if (sessionAllowed.containsKey(tool.id)) {
            return DetailedDecision.ExplicitAllow
        }

        val answer = try {
            gateway.ask(buildQuestion(tool, arguments, mode))
        } catch (e: CancellationException) {
            // 协程取消必须传播（引擎中止 / 会话停止），不能吞成"拒绝"
            throw e
        } catch (e: Exception) {
            // 询问通道异常（非预期实现 / UI 崩溃余波）：按拒绝处理并给
            // 出可指引的原因，绝不静默放行
            return DetailedDecision.Denied(
                "权限询问通道异常（${e::class.simpleName ?: "Exception"}），已按拒绝处理；" +
                    "请稍后重试，或在「设置 → 工具权限」中调整规则"
            )
        }

        return when {
            answer.selectedOptionId == OPTION_ALLOW_SESSION -> {
                // 本会话允许：记忆，后续同工具静默放行
                sessionAllowed[tool.id] = clock()
                DetailedDecision.ExplicitAllow
            }

            answer.selectedOptionId == OPTION_ALLOW_ONCE -> {
                // 仅允许一次：本次放行但不记忆（下次再问）
                DetailedDecision.ExplicitAllow
            }

            answer.skipped -> {
                // 超时 / 被取消的询问（UserQuestionBridge 超时折叠形态）
                DetailedDecision.Denied(
                    "对 ${tool.id} 的授权询问超时或未作答，已按拒绝处理；" +
                        "请确认后再试，或调整权限模式"
                )
            }

            else -> {
                // 用户明确点了拒绝（或返回了未知选项 id——一律按拒绝兜底）
                DetailedDecision.Denied(
                    "用户拒绝了本次工具执行（${tool.id}）；" +
                        "请勿重复请求同一工具，改用其他方案或向用户说明"
                )
            }
        }
    }

    /**
     * 授权问题构造（选项与文案对齐 RiskAwareToolGate）。
     *
     * 参数摘要截 300 字符并压平换行，避免对话框被长 JSON 撑爆。
     */
    private fun buildQuestion(
        tool: AgentTool,
        arguments: String,
        mode: PermissionMode
    ): AgentQuestion = AgentQuestion(
        title = "工具执行授权：${tool.id}",
        description = "当前权限模式：${modeLabel(mode)}，该工具需要你确认后才能执行。\n" +
            "参数摘要：${arguments.take(300).replace('\n', ' ')}",
        options = listOf(
            AgentQuestionOption(
                id = OPTION_ALLOW_SESSION,
                label = "本会话允许",
                description = "本次会话中该工具不再询问"
            ),
            AgentQuestionOption(
                id = OPTION_ALLOW_ONCE,
                label = "仅允许一次",
                description = "下次调用将再次询问"
            ),
            AgentQuestionOption(
                id = OPTION_DENY,
                label = "拒绝",
                description = "不执行，让 Agent 改用其他方案",
                recommended = true
            )
        ),
        allowCustom = false,
        allowSkip = false,
        timeoutMs = ASK_TIMEOUT_MS
    )

    /**
     * 清空会话记忆。
     *
     * 接线点（主控）：①新会话开始（与 RiskAwareToolGate.resetSession 同时机
     * 调用）；②权限模式切换时（模式语义变了，旧授权记忆不应延续）。
     */
    fun resetSession() {
        sessionAllowed.clear()
    }

    /** 会话授权记忆快照（调试 / 设置界面展示用）。 */
    fun sessionAllowedSnapshot(): Map<String, Long> = sessionAllowed.toMap()

    /** 模式短名（授权对话框文案用，中文）。 */
    private fun modeLabel(mode: PermissionMode): String = when (mode) {
        PermissionMode.BYPASS -> "全放行（BYPASS）"
        PermissionMode.DEFAULT -> "默认（DEFAULT）"
        PermissionMode.ACCEPT_EDITS -> "接受编辑（ACCEPT_EDITS）"
        PermissionMode.PLAN -> "只读规划（PLAN）"
    }

    private companion object {
        /** 授权询问超时：对齐 RiskAwareToolGate 的 5 分钟。 */
        const val ASK_TIMEOUT_MS = 5 * 60 * 1000L
        const val OPTION_ALLOW_SESSION = "allow_session"
        const val OPTION_ALLOW_ONCE = "allow_once"
        const val OPTION_DENY = "deny"
    }
}

/**
 * ═══ 权限门 × 风险门 协调组合器（Issue #155）═══
 *
 * 把 [PermissionModeGate]（opencode 式权限决策 + 询问闭环）与既有
 * [ToolExecutionGate]（如 RiskAwareToolGate 风险审批门）合成为一门：
 *
 * - 显式放行（规则 / 模式承诺 / 会话记忆 / 用户刚授权）→ 直接 Allow，
 *   **不再**过风险门——用户意志已表达，二次弹窗只是骚扰；
 * - 默认放行（DEFAULT / PLAN 下的只读工具）→ 转发 [fallback]，
 *   风险门继续把关（只读但敏感 / 高风险的工具仍会被问一次）；
 * - 权限拒绝 → 直接 Deny（中文原因），不消耗风险门的会话状态。
 *
 * 主控接线（ToolModule.buildV3Executor，替换原组合行）：
 *
 * 原：CompositeToolGate(ToolEnvironmentGate(environmentState), riskAwareToolGate)
 *
 * 改：CompositeToolGate(
 *         ToolEnvironmentGate(environmentState),
 *         PermissionAwareToolGate(
 *             PermissionModeGate(gateway, settingsProvider),
 *             riskAwareToolGate
 *         )
 *     )
 *
 * 即环境门在外（廉价的前置条件检查先短路），权限 + 风险合成一门：
 * 权限门先表态，只有「默认放行」才落到风险门——顺序与
 * CompositeToolGate 的「廉价检查在前、昂贵询问在后」编排 guidance 一致。
 *
 * @param permissionGate 权限门（决策 + 询问闭环）。
 * @param fallback 后续门（默认放行时继续把关；典型为 RiskAwareToolGate）。
 */
class PermissionAwareToolGate(
    private val permissionGate: PermissionModeGate,
    private val fallback: ToolExecutionGate
) : ToolExecutionGate {

    override suspend fun check(tool: AgentTool, arguments: String): GateDecision =
        when (val decision = permissionGate.checkDetailed(tool, arguments)) {
            is PermissionModeGate.DetailedDecision.ExplicitAllow -> GateDecision.Allow
            is PermissionModeGate.DetailedDecision.DefaultAllow -> fallback.check(tool, arguments)
            is PermissionModeGate.DetailedDecision.Denied -> GateDecision.Deny(decision.reason)
        }
}
