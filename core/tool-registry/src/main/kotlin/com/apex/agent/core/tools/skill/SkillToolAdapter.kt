package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 将 Skill 定义的工具适配为 AgentTool 接口。
 *
 * 执行时按步骤调用底层 Tool：
 * - "composite" — 按顺序执行 steps，每步调用一个 Tool，{{var}} 从 arguments 解析，
 *   {{prev_output}} 替换为上一步输出（截断到 2000 字符防止爆炸）
 * - "script"    — 通过 shell_execute 运行脚本
 */
class SkillToolAdapter(
    private val skillTool: SkillToolDef,
    private val toolExecutor: ToolExecutor
) : AgentTool {

    override val id = skillTool.id
    override val name = skillTool.name
    override val description = skillTool.description
    override val parametersSchema = skillTool.parameters

    override suspend fun execute(arguments: String): String {
        val impl = skillTool.implementation

        return when (impl.type) {
            "composite" -> executeComposite(impl.steps, arguments)
            "script" -> executeScript(impl.script ?: "", impl.scriptLang ?: "shell")
            "prompt" -> "Skill '${skillTool.id}' is a prompt-injection skill; no direct execution."
            "connector" -> "Skill '${skillTool.id}' is a connector skill; connect via ${impl.connectorUrl ?: "unknown URL"}"
            else -> "Error: Unknown implementation type '${impl.type}'"
        }
    }

    private suspend fun executeComposite(steps: List<SkillStep>, arguments: String): String {
        if (steps.isEmpty()) return "Error: Composite skill '${skillTool.id}' has no steps"

        // 解析调用方传入的参数（JSON 字符串）
        val argsMap: Map<String, String> = try {
            val parsed = Json.parseToJsonElement(arguments).jsonObject
            parsed.entries.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: v.toString())
            }
        } catch (_: Exception) {
            emptyMap()
        }

        var lastOutput = ""

        for (step in steps) {
            // 模板替换：{{var}} 来自 argsMap，{{prev_output}} 来自上一步
            val resolvedArgs = resolveTemplate(step, argsMap, lastOutput)

            lastOutput = try {
                toolExecutor.execute(step.tool, resolvedArgs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrow: toolExecutor.execute is a suspend call; CancellationException
                // must propagate so abort() works through composite skill execution.
                throw e
            } catch (e: Exception) {
                return "Error in step '${step.tool}': ${e.message}"
            }

            if (lastOutput.startsWith("Error")) {
                return lastOutput
            }
        }

        return lastOutput
    }

    private fun resolveTemplate(step: SkillStep, args: Map<String, String>, prevOutput: String): String {
        if (step.args.isEmpty()) {
            // 没有定义 args 模板，直接把上一步输出当作参数
            return prevOutput
        }

        // 用 JsonObject 构造 args
        val resolved = JsonObject(
            step.args.mapValues { (_, template) ->
                val value = template
                    .replace("{{prev_output}}", prevOutput.take(2000))
                // 替换 {{varName}}
                var result = value
                args.forEach { (k, v) ->
                    result = result.replace("{{$k}}", v)
                }
                JsonPrimitive(result)
            }
        )
        return resolved.toString()
    }

    private suspend fun executeScript(script: String, lang: String): String {
        // Build the shell command, then JSON-encode the argument via kotlinx.serialization
        // so any '"', '\\', newline, or tab in the script is escaped correctly. The previous
        // string-interpolation form `"""{"command": "$cmd"}""" produced invalid JSON for any
        // non-trivial script and could even inject a second tool argument.
        val cmd = when (lang) {
            "python" -> "python3 -c '${script.replace("'", "'\\''")}'"
            "shell" -> script
            else -> script
        }
        val args = JsonObject(mapOf("command" to JsonPrimitive(cmd)))
        return toolExecutor.execute("shell_execute", args.toString())
    }
}
