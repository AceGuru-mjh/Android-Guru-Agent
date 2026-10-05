package com.apex.agent.core.code

import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.code.thinking.CodeThinkingProfile
import com.apex.agent.core.code.thinking.CodeThinkingPrompts
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * # Code Agent Engine — Coding 模式引擎（与 Agent 模式同级别的对等入口）
 *
 * **不重写 Agent Loop**。本类是对 [ApexAgentEngine] 的薄包装：
 * - DI 用 `@Named("code")` 提供一个**独立**的 ApexAgentEngine 实例（与 Agent
 *   模式的默认实例隔离，各自维护 conversationHistory 与配置），但两个实例
 *   共享同一批单例依赖：ToolRegistry / ToolExecutor(v3 门控+熔断) /
 *   SkillRegistry（prompt 注入互用）/ ModelRuntime（多模型路由）—— 这就是
 *   "skills / MCP / 插件 / 工具规则互用"的落点；
 * - 编码行为通过 [AgentConfig.additionalSystemContext] 通道注入（BUILD 循环 +
 *   coding 提示词段），不改动 EnginePrompts 本体，agent 模式零影响；
 * - 工作区切换 = 绑定 per-workspace 记忆（[codeMemory.bindWorkspace]）+ 重放
 *   该工作区历史 + JIT 上下文刷新（[CodeContextProvider]）；
 * - 行为规则（#164）：[rulesProvider] 在每次 [refreshContext] 时从工作区
 *   发现 AGENTS.md / CLAUDE.md / .cursorrules 项目规则，连同 [globalRules]
 *   一起注入 Session Context 段（此时 EnginePrompts 的 globalRules 参数应
 *   保持为空——防双注，接线约定见 [RulesProvider] KDoc）。
 *
 * # 双思考逻辑（v1.5）：本类是「深潜」线——自研七档思考逻辑；对外的
 * Coding 屏契约统一为 [CodeEngineFacade]，与「标准」线
 * （com.apex.agent.core.code.standard.StandardModeEngine）同构并联，
 * 由 com.apex.agent.core.code.standard.DualLogicCodeEngine 按用户选择路由。
 * 本类行为零变化，只是显式落实接口（方法签名本就是这份契约的出处）。
 */
class CodeAgentEngine(
    private val delegate: ApexAgentEngine,
    private val codeMemory: CodeConversationMemory,
    private val contextProvider: CodeContextProvider?,
    /**
     * 行为规则提供者（#164；null = 无规则系统，行为与 v1.0 完全一致）。
     *
     * 带默认值保持既有构造兼容：主控接线时在 CodeModule 的
     * provideCodeAgentEngine 里追加 `rulesProvider = RulesProvider()`，
     * 并由 VM 在设置变更/每轮发送前调 [updateGlobalRules]。
     */
    private val rulesProvider: RulesProvider? = null
) : AgentEngine, CodeEngineFacade {

    private var currentWorkspaceId: String? = null
    private var currentRoot: File? = null
    private var currentWorkspaceName: String? = null
    private var currentActiveFile: String? = null

    /** 全局规则文本（设置层 AgentSettings.globalRules 的引擎侧缓存）。 */
    private var globalRules: String = ""

    /** #197 「小圆环」会话上下文附加段（coding 工位独占，见 [updateSessionExtras]）。 */
    private var sessionExtras: String? = null

    /**
     * 当前思考档位（coding 专属七档枚举）：引擎侧缓存，refreshContext 时取
     * 对应编码特化指令。引擎配置层的通用画像由 delegate.patchConfig 的
     * thinkingLevel 字段承载（[updateThinkingLevel] 三通道统一同步）。
     */
    private var currentCodeThinkingLevel: CodeThinkingLevel = CodeThinkingLevel.STANDARD

    /**
     * 引擎旋钮基数快照（构造时拍下 CodeModule 配置的原始值）。
     *
     * 深水两档（ULTRACODE/APEXCODE）映射 MAXIMUM 打底后，超出 MAXIMUM 的
     * 增量靠「改基数再让引擎自乘」反补：迭代基数 ×(2.0/1.5 或 3.0/1.5)、
     * 输出预算抬到档位预算、APEX 压缩阈值 /0.9。若不快照基数而是叠加当前
     * 配置反复换档，多次切换后基数会被指数污染——快照保证任意次换档都从
     * 同一起点计算（幂等）。
     */
    private val engineKnobBase: KnobBase = KnobBase(
        maxIterations = delegate.currentConfig().maxIterations,
        maxToolOutputLength = delegate.currentConfig().maxToolOutputLength,
        compressionThreshold = delegate.currentConfig().compressionThreshold
    )

    /** 旋钮基数（见 [engineKnobBase] KDoc）。 */
    private data class KnobBase(
        val maxIterations: Int,
        val maxToolOutputLength: Int,
        val compressionThreshold: Float
    )

    // ═══ AgentEngine 委托 ═══

    override fun execute(input: String): Flow<AgentEvent> = delegate.execute(input)
    override fun execute(input: UserInput): Flow<AgentEvent> = delegate.execute(input)
    override suspend fun abort() = delegate.abort()
    override fun submitUserInput(answer: String) = delegate.submitUserInput(answer)
    override fun cancelUserInput() = delegate.cancelUserInput()

    // ═══ Code 专属 API ═══

    /**
     * 切换激活的编码工作区。
     *
     * 1. 绑定 per-workspace 记忆；
     * 2. 引擎历史清空后重放该工作区保存的对话（恢复会话现场）；
     * 3. JIT 上下文刷新（新工作区的环境/统计注入系统提示词）。
     */
    override fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String?
    ) {
        currentWorkspaceId = workspaceId
        currentWorkspaceName = name
        currentRoot = root
        currentActiveFile = activeFile

        codeMemory.bindWorkspace(workspaceId)
        val history = codeMemory.load()
        delegate.clearHistory()
        if (history.isNotEmpty()) {
            delegate.restoreHistory(history)
        }
        refreshContext()
        AppLogger.instance.info(
            LogCategory.SYSTEM, TAG,
            "Code workspace activated: $workspaceId (history=${history.size}, root=${root.path})"
        )
    }

    /** 用户在编辑器里切换文件/选区时刷新上下文（不切工作区）。 */
    override fun setActiveFile(activeFile: String?) {
        currentActiveFile = activeFile
        refreshContext()
    }

    /**
     * 每轮发送前刷新 JIT 上下文 —— CodeViewModel 在 execute 前调用，
     * 保证系统提示词里的工作区状态是最新的。
     */
    override fun prepareForTask() = refreshContext()

    /**
     * 更新全局规则（#164）：VM 在设置变更或每轮发送前调用，把
     * AgentSettings.globalRules 同步进引擎（存字段，不立即刷上下文——
     * 上下文本来就是每轮 JIT 重算的，下次 [prepareForTask] 自然生效）。
     */
    override fun updateGlobalRules(rules: String) {
        globalRules = rules
    }

    /**
     * #197 切换执行模式（Coding 屏的 Build/Plan 双档）：
     * PLAN = 先出完整计划、用户确认后逐步执行；BUILD = 边想边做。
     * patchConfig 即时生效（下一轮请求携带新模式）。
     */
    override fun updateMode(mode: com.apex.agent.core.engine.AgentMode) {
        delegate.patchConfig { cfg -> cfg.copy(mode = mode) }
    }

    /**
     * #197 计划确认/驳回（PLAN 模式人控门）：透传给 delegate。
     * confirmed=true 时可携带步骤勾选与重排（原 index 口径）。
     */
    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ) {
        delegate.submitPlanConfirmation(confirmed, enabledSteps, order)
    }

    /**
     * #197 「小圆环」会话上下文附加段（coding 工位独占）：
     * 网络搜索/时间感知/结构化输出/用户规则的组装文本，refreshContext
     * 时追加在编码身份段之后（JIT 语义：存字段，下轮生效）。
     */
    override fun updateSessionExtras(extras: String?) {
        sessionExtras = extras?.takeIf { it.isNotBlank() }
    }

    /**
     * v6 专家模板人设：与 Agent 屏 applyRoleToEngine 同通道（AgentConfig
     * 人设字段），即时生效——下一轮请求携带。空定义 = 清除回落默认。
     */
    override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        delegate.patchConfig { cfg ->
            cfg.copy(
                roleDefinition = roleDefinition.trim(),
                rolePrompt = rolePrompt?.trim().orEmpty()
            )
        }
    }

    /**
     * #197 强制函数调用（v4 语义）：forcedToolIds 非空 = 本轮只暴露
     * 选中工具且 tool_choice=required；exposeAllTools = 全量暴露。
     */
    override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        delegate.patchConfig { cfg ->
            cfg.copy(forcedToolIds = forcedToolIds, exposeAllTools = exposeAll)
        }
    }

    /**
     * 更新思考档位（coding 七档）：三通道同步——
     * 1. 档位映射：delegate.patchConfig(thinkingLevel = level.toAgentLevel())
     *    → 通用思考画像（Thinking Instructions 段 + 迭代/压缩/输出预算
     *    倍率）随轮次生效；深水两档映射 MAXIMUM 打底；
     * 2. 旋钮补偿：以 [engineKnobBase] 为基数反补深水两档的增量
     *    （[CodeThinkingProfile] compensated\* 纯函数——ULTRACODE 迭代
     *    ×2.0、APEX ×3.0 / 输出预算抬到档位预算 / APEX 压缩更晚）；
     *    其余档位基数复位，由引擎自己的倍率体系接管；
     * 3. 编码特化层：存字段，refreshContext 时把
     *    [CodeThinkingPrompts.thinkingDirective] 拼进 additionalSystemContext。
     *
     * 与 updateGlobalRules 同款 JIT 语义：不立即刷上下文，下轮生效。
     */
    override fun updateThinkingLevel(level: CodeThinkingLevel) {
        currentCodeThinkingLevel = level
        delegate.patchConfig { cfg ->
            cfg.copy(
                thinkingLevel = level.toAgentLevel(),
                maxIterations = CodeThinkingProfile.compensatedMaxIterations(
                    engineKnobBase.maxIterations, level
                ),
                maxToolOutputLength = CodeThinkingProfile.compensatedToolOutputBudget(
                    engineKnobBase.maxToolOutputLength, level
                ),
                compressionThreshold = CodeThinkingProfile.compensatedCompressionThreshold(
                    engineKnobBase.compressionThreshold, level
                )
            )
        }
    }

    /** 当前思考档位（UI 回显用，coding 七档枚举）。 */
    override fun thinkingLevel(): CodeThinkingLevel = currentCodeThinkingLevel

    /**
     * 当前档位最近一次自适应决策说明（发送前预检/运行中升级由 VM 侧
     * CodeAdaptiveThinkingSelector 产生并自行展示——引擎侧仅透传通用
     * 引擎的 AUTO 可解释性通道，coding 正常路径不产生非空值）。
     */
    override fun currentThinkingDecision(): String? = delegate.currentThinkingDecision()

    /** 清当前工作区的对话历史（新会话）。 */
    override fun clearConversation() {
        delegate.clearHistory()
    }

    override fun historyCount(): Int = codeMemory.count()

    override fun currentWorkspace(): WorkspaceInfo? {
        val id = currentWorkspaceId ?: return null
        return WorkspaceInfo(id, currentWorkspaceName ?: id, currentRoot)
    }

    /** 工作区信息快照（UI 用）。 */
    data class WorkspaceInfo(val id: String, val name: String, val root: File?)

    override fun currentTokenCount(): Int = delegate.currentTokenCount()
    override fun maxContextTokens(): Int = delegate.maxContextTokens()

    // ═══ 内部 ═══

    private fun refreshContext() {
        val root = currentRoot
        val name = currentWorkspaceName ?: return
        val segments = mutableListOf(CodePrompts.codingIdentity())

        // ═══ coding 七档思考系统：编码特化思考指令（NONE 档返回空串自动跳过）═══
        // 通用思考画像（推理框架 + 预算倍率）由引擎配置层的 thinkingLevel
        // 承载（updateThinkingLevel 三通道同步），此处只补编码方法论。
        CodeThinkingPrompts.thinkingDirective(currentCodeThinkingLevel)
            .takeIf { it.isNotEmpty() }
            ?.let { segments += it }

        if (root != null) {
            segments += CodePrompts.workspaceContext(
                workspaceName = name,
                rootLabel = root.path,
                guestPath = GUEST_PATH,
                environmentSummary = contextProvider?.provide(root, currentActiveFile),
                projectStats = null,
                activeFile = currentActiveFile
            )
        }

        // ═══ 行为规则（#164）：只追加，不动既有段落 ═══
        // 全局规则（设置层持久化）+ 项目规则（工作区规则文件即时发现）。
        // RulesProvider 的 IO 是同步的，与上面 contextProvider.provide 同一
        // 线程约定（调用方 = VM 的 prepareForTask 链路）。
        if (rulesProvider != null) {
            rulesProvider.formatGlobalRules(globalRules)?.let { segments += it }
            rulesProvider
                .loadProjectRules(root, currentActiveFile)
                ?.let { rulesProvider.formatProjectRules(it) }
                ?.let { segments += it }
        }

        // #197 小圆环会话上下文（coding 工位独占）：附加在末尾
        sessionExtras?.let { segments += it }

        val context = segments.joinToString("\n\n")
        delegate.patchConfig { cfg ->
            cfg.copy(additionalSystemContext = context)
        }
    }

    private companion object {
        const val TAG = "CodeAgentEngine"

        /** PRoot Ubuntu 会话中工作区的统一挂载点（与 LinuxWorkspaceManager 一致）。 */
        const val GUEST_PATH = "/workspace"
    }
}
