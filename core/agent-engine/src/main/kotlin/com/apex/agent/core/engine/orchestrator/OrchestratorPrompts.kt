package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.tools.catalog.PrivilegeLadder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure prompt/payload construction for the BUILD-mode orchestrator loop.
 *
 * Extracted from [DefaultTaskOrchestrator] — the orchestrator's job is driving
 * the ReAct loop; composing system-prompt text and extracting the question
 * from an `ask_user` JSON payload are leaf concerns with no state, so they
 * live here as testable pure functions.
 */
internal object OrchestratorPrompts {

    /**
     * Build the BUILD-mode system prompt from the agent configuration:
     * identity, mode, thinking-level instruction, optional custom
     * instruction, current privilege level and the capability-expansion
     * hint.
     *
     * 能力自省修复：旧版只有一行 `Privilege level: $level`——编排线比引擎
     * 线更"失明"（无 CAN/CANNOT、无扩展通道）。现在注入：
     * - [PrivilegeLadder.briefLine] + 升级指引（与引擎线 / capability_report
     *   工具输出同源的权限知识，单一真值源）；
     * - 能力扩展梯度提示（tool_search / skill / mcp / 终端工具链），
     *   编排器在 BUILD 线同样「先找方法，再谈不可能」。
     */
    fun buildSystemPrompt(
        config: AgentConfig,
        privilegeInfoProvider: PrivilegeInfoProvider?
    ): String {
        val sb = StringBuilder()
        sb.append("You are ApexAgent, a capable AI assistant running on Android.")
        sb.append("\n\nMode: ${config.mode.displayName} — ${config.mode.description}")
        // #168：AUTO/NONE 的 toPromptInstruction 为空串——不再注入空段落
        //（编排器路径暂不接 ThinkingModeController，AUTO 由引擎主循环路径承载）。
        config.thinkingLevel.toPromptInstruction().takeIf { it.isNotBlank() }?.let {
            sb.append("\n\n$it")
        }
        if (config.mode == AgentMode.CUSTOM && config.customInstruction != null) {
            sb.append("\n\n## Custom Instructions\n${config.customInstruction}")
        }
        privilegeInfoProvider?.currentLevel()?.let { level ->
            sb.append("\n\n").append(PrivilegeLadder.briefLine(level))
            sb.append("\n").append(PrivilegeLadder.infoFor(level).upgradeHint)
        }
        sb.append(
            "\n\nOn-demand capability expansion: tool_search/tool_open load any registered " +
                "tool; market_search/skill_search + skill_install add skills; mcp_connect " +
                "wires external MCP servers; the PRoot Ubuntu terminal (terminal.ubuntu.ensure, " +
                "then apt/pip/npm) installs full Linux toolchains. capability_report() gives a " +
                "one-call self-check. Never declare a task impossible before trying these routes."
        )
        return sb.toString()
    }

    /**
     * Extract the human-readable question from an `ask_user` /
     * `ask_user_choice` arguments JSON payload.
     *
     * Minimal JSON parsing — falls back to the raw payload when the JSON is
     * malformed or carries no `question`/`prompt` key.
     */
    fun parseAskUserPrompt(arguments: String): String {
        return try {
            val obj: JsonObject = Json.parseToJsonElement(arguments).jsonObject
            obj["question"]?.jsonPrimitive?.contentOrNull
                ?: obj["prompt"]?.jsonPrimitive?.contentOrNull
                ?: arguments
        } catch (e: Throwable) {
            arguments
        }
    }
}
