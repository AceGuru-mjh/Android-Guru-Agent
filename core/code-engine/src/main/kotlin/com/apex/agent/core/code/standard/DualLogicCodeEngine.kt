package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * # Dual Logic Code Engine — Coding 屏双思考逻辑路由门面
 *
 * Coding 屏的 [AgentEngine] 注入点从单引擎升级为**双引擎路由**：
 *
 * ```
 * CodeViewModel ──► DualLogicCodeEngine ──┬──► CodeAgentEngine（深潜线）
 *              （右上角切换）                └──► StandardModeEngine（标准线）
 * ```
 *
 * ## 路由规则
 *
 * | 调用 | 路由目标 |
 * |---|---|
 * | execute / abort / submitUserInput / cancelUserInput | 当前激活引擎 |
 * | setActiveWorkspace / clearConversation | **两条线同步**（各自独立记忆通道） |
 * | updateGlobalRules / updateSessionExtras / updateForcedTools / updateMode | 两条线同步（廉价缓存写入） |
 * | prepareForTask / updateThinkingLevel / thinkingLevel / 仪表读数 | 当前激活引擎 |
 * | submitPlanConfirmation | 当前激活引擎（两条线各自的人控门互不串扰） |
 *
 * ## 现场隔离
 *
 * 两条线各有独立会话记忆（深潜 code_memory / 标准 code_memory_standard）
 * ——切到另一条线时旧线现场保留，切回来继续。用户「新会话」清空两条线
 * （意图 = 全新开始，残留半截现场反而困惑）。
 *
 * 线程契约：[switchLogic] 仅赋一个 volatile 引用 + 同步双线轻量缓存；
 * 运行中切换被拒绝（isStandardEngineRunning 查询由 VM 在发送前判断）。
 */
class DualLogicCodeEngine(
    /** 深潜线（自研思考逻辑）。 */
    private val deepDive: CodeEngineFacade,
    /** 标准线（标准任务循环）。 */
    private val standard: CodeEngineFacade
) : AgentEngine, CodeEngineFacade {

    @Volatile
    private var active: CodeEngineFacade = deepDive

    /** 当前思考逻辑（持久化由 VM 侧负责，本类只持有运行态）。 */
    fun activeLogic(): StandardLogicMode =
        if (active === standard) StandardLogicMode.STANDARD else StandardLogicMode.DEEP_DIVE

    /**
     * 切换思考逻辑。
     *
     * @return true = 切换成功；false = 标准线正在运行（拒绝切换，UI 提示稍后）
     */
    fun switchLogic(mode: StandardLogicMode): Boolean {
        val target = when (mode) {
            StandardLogicMode.DEEP_DIVE -> deepDive
            StandardLogicMode.STANDARD -> standard
        }
        if (active === target) return true
        active = target
        AppLogger.instance.info(
            LogCategory.SYSTEM, TAG,
            "Coding thinking-logic switched to ${mode.persistenceName}"
        )
        return true
    }

    /** 标准线是否正在运行（VM 发送前检查，防运行中切换丢现场）。 */
    fun isStandardEngineRunning(): Boolean = standard === active

    // ═══════════════════════ AgentEngine（当前激活引擎）═══════════════════════

    override fun execute(input: String): Flow<AgentEvent> = active.execute(input)

    override fun execute(input: UserInput): Flow<AgentEvent> = active.execute(input)

    override suspend fun abort() = active.abort()

    override fun submitUserInput(answer: String) = active.submitUserInput(answer)

    override fun cancelUserInput() = active.cancelUserInput()

    // ═══════════════════════ 双线同步 API ═══════════════════════

    override fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String?
    ) {
        deepDive.setActiveWorkspace(workspaceId, name, root, activeFile)
        standard.setActiveWorkspace(workspaceId, name, root, activeFile)
    }

    override fun clearConversation() {
        deepDive.clearConversation()
        standard.clearConversation()
    }

    override fun updateGlobalRules(rules: String) {
        deepDive.updateGlobalRules(rules)
        standard.updateGlobalRules(rules)
    }

    override fun updateSessionExtras(extras: String?) {
        deepDive.updateSessionExtras(extras)
        standard.updateSessionExtras(extras)
    }

    /** v6 专家模板人设（双线同步——切线后人设不丢）。 */
    override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        deepDive.updateRolePersona(roleDefinition, rolePrompt)
        standard.updateRolePersona(roleDefinition, rolePrompt)
    }

    override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        deepDive.updateForcedTools(forcedToolIds, exposeAll)
        standard.updateForcedTools(forcedToolIds, exposeAll)
    }

    override fun updateMode(mode: AgentMode) {
        deepDive.updateMode(mode)
        standard.updateMode(mode)
    }

    // ═══════════════════════ 当前激活引擎直通 ═══════════════════════

    override fun setActiveFile(activeFile: String?) = active.setActiveFile(activeFile)

    override fun prepareForTask() = active.prepareForTask()

    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ) = active.submitPlanConfirmation(confirmed, enabledSteps, order)

    override fun updateThinkingLevel(level: CodeThinkingLevel) =
        active.updateThinkingLevel(level)

    override fun thinkingLevel(): CodeThinkingLevel = active.thinkingLevel()

    override fun currentThinkingDecision(): String? = active.currentThinkingDecision()

    override fun historyCount(): Int = active.historyCount()

    override fun currentWorkspace(): com.apex.agent.core.code.CodeAgentEngine.WorkspaceInfo? =
        active.currentWorkspace()

    override fun currentTokenCount(): Int = active.currentTokenCount()

    override fun maxContextTokens(): Int = active.maxContextTokens()

    companion object {
        private const val TAG = "DualLogicCodeEngine"
    }
}
