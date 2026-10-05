package com.apex.agent.core.code

import com.apex.agent.core.code.standard.StandardLogicMode
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentMode
import java.io.File

/**
 * # Code Engine Facade — Coding 屏引擎对外契约（双思考逻辑共用）
 *
 * Coding 屏的「思考逻辑」有两条引擎线（见 [StandardLogicMode]）：
 * - **深潜**（[CodeAgentEngine]，自研七档思考）；
 * - **标准**（`StandardModeEngine`，业界标准 Agent 任务循环）。
 *
 * 两条线都实现本接口，[CodeViewModel] 只面向本接口编程，
 * [com.apex.agent.core.code.standard.DualLogicCodeEngine] 按
 * [StandardLogicMode] 把调用路由到当前激活引擎——VM 永远不知道
 * 「背后换引擎了」，胶囊时间轴 / 长任务追踪 / 权限问答闭环
 * （全部消费 AgentEvent）自然两线通用。
 *
 * 方法清单 = `CodeViewModel` 实际使用的 `codeEngineImpl.*` 调用面
 * （签名与 [CodeAgentEngine] 逐一对齐，深潜线零改动即实现）。
 */
interface CodeEngineFacade : AgentEngine {

    /**
     * 切换激活的编码工作区：绑定 per-workspace 记忆 + 重放该工作区
     * 历史 + 刷新 JIT 上下文。双引擎门面会把本调用同步到**两条**引擎线
     * （各自独立记忆通道，切回时现场不丢）。
     */
    fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String? = null
    )

    /** 用户在编辑器里切换文件/选区时刷新上下文（不切工作区）。 */
    fun setActiveFile(activeFile: String?)

    /** 每轮发送前刷新 JIT 上下文（VM 在 execute 前调用）。 */
    fun prepareForTask()

    /** 全局规则（设置层 AgentSettings.globalRules 的引擎侧缓存同步）。 */
    fun updateGlobalRules(rules: String)

    /**
     * 执行模式（BUILD/PLAN 双档）：标准引擎把 PLAN 映射为「规划师画像 +
     * 计划确认人控门」，BUILD 映射为「构建者画像」。
     */
    fun updateMode(mode: AgentMode)

    /** PLAN 模式计划确认/驳回（勾选与重排按原 index 口径）。 */
    fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>? = null,
        order: List<Int>? = null
    )

    /** 「小圆环」会话上下文附加段（coding 工位独占）。 */
    fun updateSessionExtras(extras: String?)

    /**
     * v6 Coding 专家模板人设通道：roleDefinition（专家定义段）+ rolePrompt
     * （用户自定义提示词，可空）。深潜线经 delegate.patchConfig 落到
     * AgentConfig 人设字段；标准线注入系统提示词专家段。空串 = 清除
     * （回落引擎默认画像）。
     */
    fun updateRolePersona(roleDefinition: String, rolePrompt: String?)

    /** v4 强制函数调用（forcedToolIds 非空 = 只暴露选中工具且 required）。 */
    fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean)

    /**
     * 思考档位（七档）：标准引擎把它映射为**回合预算倍率**与采样温度取向
     * （NONE/LIGHT 收紧、MAXIMUM 以上放开），语义见
     * `StandardModeEngine.updateThinkingLevel`。
     */
    fun updateThinkingLevel(level: CodeThinkingLevel)

    /** 当前思考档位（UI 回显用）。 */
    fun thinkingLevel(): CodeThinkingLevel

    /** 最近一次自适应决策说明（标准引擎恒 null——它没有 AUTO 预检通道）。 */
    fun currentThinkingDecision(): String?

    /** 清空当前工作区的对话历史（新会话）。 */
    fun clearConversation()

    /** 当前会话历史条数（UI 徽标用）。 */
    fun historyCount(): Int

    /** 当前工作区快照（UI 用）。 */
    fun currentWorkspace(): CodeAgentEngine.WorkspaceInfo?

    /** 当前上下文 token 估算（上下文仪表盘）。 */
    fun currentTokenCount(): Int

    /** 上下文窗口预算（上下文仪表盘）。 */
    fun maxContextTokens(): Int
}
