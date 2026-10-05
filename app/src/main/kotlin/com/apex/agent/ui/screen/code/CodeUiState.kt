package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.longtask.LongTaskRecord
import com.apex.agent.core.code.standard.StandardLogicMode
import com.apex.agent.core.code.stream.CodeStreamSnapshot
import com.apex.agent.core.code.thinking.CodeThinkingEvolutionTracker
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.engine.goal.GoalRuntimeState
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.ui.screen.code.editor.EditorFile

/**
 * Code 屏 UI 状态（单数据源，ViewModel 独占更新）。
 */
data class CodeUiState(
    // ── 工作区 ──
    val workspaces: List<CodeWorkspace> = emptyList(),
    val activeWorkspace: CodeWorkspace? = null,

    // ── 对话 ──
    val messages: List<CodeChatMessage> = emptyList(),
    val isRunning: Boolean = false,
    val currentIteration: Int = 0,
    val error: String? = null,
    // #209：当前 error 是否提供「重试」入口 —— 仅引擎运行失败类错误为 true；
    // 参数校验/状态冲突类（任务运行中、工作区冲突等）保持 false，避免误导重放。
    val errorRetriable: Boolean = false,
    val pendingQuestion: String? = null,

    // ── v1.5 思考逻辑（双引擎：深潜 = 自研七档 / 标准 = 标准任务循环）──
    // 右上角选择器直改 + AgentSettings.codeThinkingLogic 持久化；
    // DualLogicCodeEngine 按此路由 execute/abort/问答。
    val logicMode: StandardLogicMode = StandardLogicMode.DEEP_DIVE,

    // ── #197 执行模式（Coding 屏 Build/Plan 双档）──
    val mode: com.apex.agent.core.engine.AgentMode = com.apex.agent.core.engine.AgentMode.BUILD,

    // ── #197 PLAN 模式计划确认（人控门）──
    val plan: com.apex.agent.core.engine.ExecutionPlan? = null,
    val awaitingPlanConfirmation: Boolean = false,

    // ── Todo（code_todo 快照）──
    val todos: List<CodeTodoTool.Todo> = emptyList(),

    // ── 上下文仪表 ──
    val contextUsedTokens: Int = 0,
    val contextMaxTokens: Int = 0,

    // ── 思考档位（coding 专属七档）──
    // 选择器直改 + AgentSettings.codeThinkingLevel 持久化，引擎侧三通道
    // （档位映射 + 旋钮补偿 + 编码特化指令）同步见
    // CodeAgentEngine.updateThinkingLevel。
    val thinkingLevel: CodeThinkingLevel = CodeThinkingLevel.STANDARD,

    /**
     * AUTO 档最近一次预检决策（"因子→评分→档位"）；非 AUTO 档或尚无
     * 预检 → null。发送前由 CodeViewModel.resolveRuntimeThinkingLevel
     * 产生，选择器旁回显（深水区升级另有系统消息通道）。
     */
    val adaptiveDecision: String? = null,

    // ── 长任务中心（v1.2）──
    // 长任务面板可见性 + 当前工作区的长任务记录（updatedAt 降序）；
    // 记录由 LongTaskTracker 在运行收尾时自动入库，UI 只读。
    val longTaskSheetVisible: Boolean = false,
    val longTasks: List<LongTaskRecord> = emptyList(),
    val longTaskLoading: Boolean = false,

    /** 档位效能统计（当前工作区；null = 未加载/无工作区）。 */
    val thinkingStats: CodeThinkingEvolutionTracker.WorkspaceThinkingStats? = null,

    // ── 胶囊时间轴（渲染主通道快照）──
    // 25ms ≤40Hz 攒批推送；entries/终端尾窗/派生统计一体的不可变值。
    val stream: CodeStreamSnapshot = CodeStreamSnapshot(),

    // ── 编辑器面板（v1.0 #154）──
    // editorFilePath 非空即挂载面板；editorFile 为加载结果（null = 加载中/失败态）。
    val editorFilePath: String? = null,
    val editorFile: EditorFile? = null,
    val editorLoading: Boolean = false,
    val editorError: String? = null,

    // ── 输入栏草稿（v1.0 #154）──
    // 提升到 VM 层：编辑器面板行点击 / @file:line 插入需要程序化写入输入框。
    val inputDraft: String = "",

    // ── v3 GOAL 目标模式（状态卡 + 设定弹层）──
    // goalState：协调器全局快照（目标存在即显示状态卡——无论 ACTIVE/
    // ACHIEVED/STOPPED、切走模式也显示，用户可随时停止/重启）；
    // showGoalSetup/goalSetupDraft：设定弹层开关 + 打开时的输入框草稿
    //（预填目标陈述，并作为目标的首条提示重放）。
    val goalState: GoalRuntimeState? = null,
    val showGoalSetup: Boolean = false,
    val goalSetupDraft: String = ""
)

/**
 * 聊天条目（用户 / 助手 / 工具卡片 / 系统提示）。
 */
data class CodeChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    // 工具卡片段
    val toolName: String? = null,
    val toolSuccess: Boolean = true,
    val durationMs: Long = 0,
    val isStreaming: Boolean = false
) {
    enum class Role { USER, ASSISTANT, TOOL, SYSTEM }
}
