package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.bridge.GuestBridgeService
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: terminal.bridge — T82 apexctl bridge 状态/直连调用（基线 §11.1）。
 *
 * guest 内脚本用 `apexctl <action> [argsJson]`（文件队列 → host handler）；
 * Agent 用本工具 `call` 直连**同一组 handler**（无文件往返、零延迟）。`status`
 * 列出已注册动作 —— Agent 可据此告诉用户/脚本可调用什么。
 *
 * JSON Schema (input):
 *   { action: "status"|"call", bridgeAction?: string, args?: string }
 *   （args 为 JSON 文本 —— handler 自行解析）
 * JSON Schema (output):
 *   status → { ok, running, scriptInstalled, actions: [{name,description}] }
 *   call   → { ok, data? , error? }
 */
class TerminalBridgeTool(
    private val bridge: GuestBridgeService
) : TerminalTool {
    override val id: String = "terminal.bridge"
    override val name: String = "terminal.bridge"
    override val description: String = """
        Android capability bridge (apexctl). Guest scripts call `apexctl <action> [argsJson]`
        (e.g. apexctl clipboard get) — the same handlers are callable here via action=call
        with zero file round-trip. action=status lists registered bridge actions and whether
        the bridge poller is running.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"action":{"type":"string","enum":["status","call"]},"bridgeAction":{"type":"string"},"args":{"type":"string"}},"required":["action"]}
""".trim()

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val action = json["action"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("action required")
        return when (action) {
            "status" -> {
                val actions = bridge.supportedActions()
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("running", JsonPrimitive(bridge.isRunning))
                    put("scriptInstalled", JsonPrimitive(true))
                    put("actions", buildJsonArray {
                        actions.forEach { (name, desc) ->
                            add(buildJsonObject { put("name", JsonPrimitive(name)); put("description", JsonPrimitive(desc)) })
                        }
                    })
                }.toString()
            }
            "call" -> {
                val bridgeAction = json["bridgeAction"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("bridgeAction required for call")
                val args = json["args"]?.jsonPrimitive?.content ?: "null"
                val resp = bridge.dispatch(bridgeAction, args)
                buildJsonObject {
                    put("ok", JsonPrimitive(resp.ok))
                    resp.data?.let { put("data", JsonPrimitive(it)) }
                    resp.error?.let { put("error", JsonPrimitive(it)) }
                }.toString()
            }
            else -> throw IllegalArgumentException("unknown action '$action'")
        }
    }
}
