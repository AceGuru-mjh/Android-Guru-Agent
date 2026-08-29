package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.engine.ThinkingLevel
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
     * instruction and current privilege level.
     */
    fun buildSystemPrompt(
        config: AgentConfig,
        privilegeInfoProvider: PrivilegeInfoProvider?
    ): String {
        val sb = StringBuilder()
        sb.append("You are ApexAgent, a capable AI assistant running on Android.")
        sb.append("\n\nMode: ${config.mode.displayName} — ${config.mode.description}")
        if (config.thinkingLevel != ThinkingLevel.NONE) {
            sb.append("\n\n${config.thinkingLevel.toPromptInstruction()}")
        }
        if (config.mode == AgentMode.CUSTOM && config.customInstruction != null) {
            sb.append("\n\n## Custom Instructions\n${config.customInstruction}")
        }
        privilegeInfoProvider?.currentLevel()?.let { level ->
            sb.append("\n\nPrivilege level: $level")
        }
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
