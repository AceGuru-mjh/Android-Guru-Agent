package com.apex.agent.tools

import com.apex.agent.R
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.permission.PermissionMode
import com.apex.agent.ui.language.LanguageManager

/**
 * ═══ 权限/风险弹窗文案源（#208）═══
 *
 * [PermissionModeGate] / [RiskAwareToolGate] 构造 [AgentQuestion][com.apex.agent.core.engine.AgentQuestion]
 * 时曾硬编码中文——英文系统用户在权限引导弹窗看到整段中文（#208 的用户可见
 * 活体路径）。core-engine 的 AgentQuestion 模型不能依赖 Android 资源，故经
 * 本 provider 取词：
 *
 * - **生产**：DI 注入 [Res]（[LanguageManager.getString] 按当前语言解析
 *   values/ 默认英文 / values-zh 中文）；
 * - **兜底**：[DefaultCn] 返回与旧实现一致的中文——JVM 单测（无 Android
 *   资源）与未接线的宿主走此路径，既有测试断言不变。
 *
 * 纪律：新增弹窗文案必须双侧（values + values-zh）同步写入并保持占位符
 * 一致（scripts/check_string_mirror.py 门禁）。
 */
interface GateDialogStrings {

    // ── PermissionModeGate ──────────────────────────────────────────

    /** 授权弹窗标题。 */
    fun permTitle(toolId: String): String

    /** 授权弹窗首行：当前权限模式 + 确认要求。 */
    fun permModeLine(mode: PermissionMode): String

    /** 授权弹窗次行：参数摘要。 */
    fun argsLine(arguments: String): String

    // ── RiskAwareToolGate ───────────────────────────────────────────

    /** 高风险工具确认标题。 */
    fun riskTitle(toolId: String): String

    /** #230：MEDIUM 文件改写类确认标题（不谎称高风险）。 */
    fun fileWriteTitle(toolId: String): String

    /** 风险提示行（风险档 + 类别括注）。 */
    fun riskLine(riskHintIsHigh: Boolean, category: ToolCategory): String

    // ── 共用三选项 ──────────────────────────────────────────────────

    fun allowSessionLabel(): String
    fun allowSessionDesc(): String
    fun allowOnceLabel(): String
    fun allowOnceDesc(): String
    fun denyLabel(): String
    fun denyDesc(): String

    /** 中文兜底（JVM 单测 / 未注入 DI 的宿主）—— 与旧实现逐字一致。 */
    object DefaultCn : GateDialogStrings {
        override fun permTitle(toolId: String) = "工具执行授权：$toolId"
        override fun permModeLine(mode: PermissionMode): String {
            val label = when (mode) {
                PermissionMode.BYPASS -> "全放行（BYPASS）"
                PermissionMode.DEFAULT -> "默认（DEFAULT）"
                PermissionMode.ACCEPT_EDITS -> "接受编辑（ACCEPT_EDITS）"
                PermissionMode.PLAN -> "只读规划（PLAN）"
            }
            return "当前权限模式：$label，该工具需要你确认后才能执行。"
        }

        override fun argsLine(arguments: String) =
            "参数摘要：${arguments.take(300).replace('\n', ' ')}"

        override fun riskTitle(toolId: String) = "高风险工具需要确认：$toolId"
        override fun fileWriteTitle(toolId: String) = "文件写入确认：$toolId"
        override fun riskLine(riskHintIsHigh: Boolean, category: ToolCategory): String {
            val hint = if (riskHintIsHigh) "高风险：破坏性或不可逆操作" else "中风险：会修改数据或状态"
            return "$hint（${category.label}）"
        }

        override fun allowSessionLabel() = "本会话允许"
        override fun allowSessionDesc() = "本次会话中该工具不再询问"
        override fun allowOnceLabel() = "仅允许一次"
        override fun allowOnceDesc() = "下次调用将再次询问"
        override fun denyLabel() = "拒绝"
        override fun denyDesc() = "不执行，让 Agent 改用其他方案"
    }

    /** 资源版实现（生产接线）：经 [LanguageManager] 按当前语言取词。 */
    class Res(private val language: LanguageManager) : GateDialogStrings {

        override fun permTitle(toolId: String): String =
            language.getString(R.string.gate_perm_title_fmt, toolId)

        override fun permModeLine(mode: PermissionMode): String {
            val label = when (mode) {
                PermissionMode.BYPASS -> language.getString(R.string.gate_mode_bypass)
                PermissionMode.DEFAULT -> language.getString(R.string.gate_mode_default)
                PermissionMode.ACCEPT_EDITS -> language.getString(R.string.gate_mode_accept_edits)
                PermissionMode.PLAN -> language.getString(R.string.gate_mode_plan)
            }
            return language.getString(R.string.gate_perm_mode_fmt, label)
        }

        override fun argsLine(arguments: String): String =
            language.getString(
                R.string.gate_args_fmt,
                arguments.take(300).replace('\n', ' ')
            )

        override fun riskTitle(toolId: String): String =
            language.getString(R.string.gate_risk_title_fmt, toolId)

        override fun fileWriteTitle(toolId: String): String =
            language.getString(R.string.gate_file_write_title_fmt, toolId)

        override fun riskLine(riskHintIsHigh: Boolean, category: ToolCategory): String {
            val hint = if (riskHintIsHigh) {
                language.getString(R.string.gate_risk_hint_high)
            } else {
                language.getString(R.string.gate_risk_hint_medium)
            }
            return language.getString(R.string.gate_risk_line_fmt, hint, categoryLabel(category))
        }

        override fun allowSessionLabel() = language.getString(R.string.gate_opt_allow_session)
        override fun allowSessionDesc() = language.getString(R.string.gate_opt_allow_session_desc)
        override fun allowOnceLabel() = language.getString(R.string.gate_opt_allow_once)
        override fun allowOnceDesc() = language.getString(R.string.gate_opt_allow_once_desc)
        override fun denyLabel() = language.getString(R.string.gate_opt_deny)
        override fun denyDesc() = language.getString(R.string.gate_opt_deny_desc)

        /** 工具类别的本地化标签（core 的 ToolCategory.label 是中文，弹窗侧替换）。 */
        private fun categoryLabel(category: ToolCategory): String = when (category) {
            ToolCategory.SHELL -> language.getString(R.string.tool_cat_shell)
            ToolCategory.FILE -> language.getString(R.string.tool_cat_file)
            ToolCategory.TERMINAL -> language.getString(R.string.tool_cat_terminal)
            ToolCategory.WEB -> language.getString(R.string.tool_cat_web)
            ToolCategory.BROWSER -> language.getString(R.string.tool_cat_browser)
            ToolCategory.MEMORY -> language.getString(R.string.tool_cat_memory)
            ToolCategory.CONTEXT -> language.getString(R.string.tool_cat_context)
            ToolCategory.APP -> language.getString(R.string.tool_cat_app)
            ToolCategory.SYSTEM -> language.getString(R.string.tool_cat_system)
            ToolCategory.SECURITY -> language.getString(R.string.tool_cat_security)
            ToolCategory.UI -> language.getString(R.string.tool_cat_ui)
            ToolCategory.SENSOR -> language.getString(R.string.tool_cat_sensor)
            ToolCategory.AGENT -> language.getString(R.string.tool_cat_agent)
            ToolCategory.SKILL -> language.getString(R.string.tool_cat_skill)
            ToolCategory.MCP -> language.getString(R.string.tool_cat_mcp)
            ToolCategory.GITHUB -> language.getString(R.string.tool_cat_github)
            ToolCategory.PLUGIN -> language.getString(R.string.tool_cat_plugin)
            ToolCategory.UTILITY -> language.getString(R.string.tool_cat_utility)
        }
    }
}
