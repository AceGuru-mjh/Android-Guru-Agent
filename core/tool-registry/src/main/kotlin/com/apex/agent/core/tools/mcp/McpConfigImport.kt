package com.apex.agent.core.tools.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 标准 MCP 配置解析器（零 Android 依赖，可在 JVM 单测里直接跑）。
 *
 * 背景：社区里流转的 MCP 配置几乎都是下面这个形状（Claude Desktop / Cursor /
 * Cline / Operit 通用），而不是我们自己的字段命名。过去用户只能手动把 `command`
 * 拆到 URL 框里 —— 因为本 App 只认"URL + 传输枚举"，等于不支持。
 *
 * ```json
 * {
 *   "mcpServers": {
 *     "filesystem": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/sdcard"],
 *       "env": { "FOO": "bar" },
 *       "disabled": false
 *     },
 *     "remote": {
 *       "type": "streamable_http",
 *       "url": "https://example.com/mcp",
 *       "headers": { "X-Api-Key": "xxx" }
 *     }
 *  }
 * }
 * ```
 *
 * 判定规则（与 Operit `mcp_config_transport_import` 的结论一致）：
 * - 有非空 `command` → STDIO；
 * - `type=streamable_http`（或省略 type 但有 `url`）→ HTTP；
 * - `type=sse` → SSE；
 * - 其他组合（无 command 且无 url、type 未知、字段类型不对）→ 报**逐条输入错误**，
 *   由 UI 明确告知用户哪一项被跳过，绝不静默丢弃整份配置。
 */
object McpConfigImport {

    /** 单条解析错误：[serverName] 是被跳过的服务器名。 */
    data class EntryError(val serverName: String, val reason: String)

    data class Result(
        val configs: List<McpServerConfig>,
        val errors: List<EntryError>
    )

    fun parse(text: String): Result = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val servers = root["mcpServers"]?.jsonObject
            ?: return Result(emptyList(), listOf(EntryError("", "顶层缺少 mcpServers 对象")))
        parseServers(servers)
    }.getOrElse { e ->
        Result(emptyList(), listOf(EntryError("", "配置不是合法 JSON：${e.message}")))
    }

    /** 顶层直接就是 `mcpServers` 的内容（没有外壳）时也接受。 */
    fun parseServers(servers: JsonObject): Result {
        val configs = mutableListOf<McpServerConfig>()
        val errors = mutableListOf<EntryError>()

        servers.forEach { (name, raw) ->
            val entry = runCatching { raw.jsonObject }.getOrNull()
            if (entry == null) {
                errors += EntryError(name, "条目不是 JSON 对象")
                return@forEach
            }
            when (val outcome = parseEntry(name, entry)) {
                is ParseOutcome.Ok -> configs += outcome.config
                is ParseOutcome.Err -> errors += EntryError(name, outcome.reason)
            }
        }
        return Result(configs, errors)
    }

    private sealed interface ParseOutcome {
        data class Ok(val config: McpServerConfig) : ParseOutcome
        data class Err(val reason: String) : ParseOutcome
    }

    private fun parseEntry(name: String, entry: JsonObject): ParseOutcome {
        val command = entry["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val url = entry["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val type = entry["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val args = entry["args"]?.let { el ->
            runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
                ?: return ParseOutcome.Err("args 必须是字符串数组")
        }.orEmpty()

        val env = stringMap(entry, "env")
            ?: return ParseOutcome.Err("env 必须是字符串对象")
        val headers = stringMap(entry, "headers")
            ?: return ParseOutcome.Err("headers 必须是字符串对象")

        val disabled = entry["disabled"]?.jsonPrimitive?.booleanOrNull
            ?: (entry["disabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())

        val transport = when {
            command.isNotBlank() -> {
                if (url.isNotBlank() && type.isBlank()) {
                    // 同时给了 command 与 url 却没说 type：语义歧义，明确报错而不是猜
                    return ParseOutcome.Err("同时存在 command 与 url 但未指定 type，无法判定传输方式")
                }
                McpTransport.STDIO
            }
            type.equals("streamable_http", ignoreCase = true) -> McpTransport.HTTP
            type.equals("http", ignoreCase = true) -> McpTransport.HTTP
            type.equals("sse", ignoreCase = true) -> McpTransport.SSE
            url.isNotBlank() -> McpTransport.HTTP
            else -> return ParseOutcome.Err("既无 command 也无 url，无法判定传输方式")
        }

        if (transport != McpTransport.STDIO && url.isBlank()) {
            return ParseOutcome.Err("${type.ifBlank { transport.name }} 传输缺少 url")
        }

        return ParseOutcome.Ok(
            McpServerConfig(
                name = name.trim().ifBlank { "mcp-${System.currentTimeMillis()}" },
                url = url,
                transport = transport,
                apiKey = null,
                enabled = disabled != true,
                command = command.takeIf { it.isNotBlank() },
                args = args,
                env = env,
                headers = headers
            )
        )
    }

    private fun stringMap(entry: JsonObject, key: String): Map<String, String>? {
        val raw = entry[key] ?: return emptyMap()
        val obj = runCatching { raw.jsonObject }.getOrNull() ?: return null
        return obj.mapValues { (_, v) ->
            runCatching { v.jsonPrimitive.content }.getOrNull() ?: return null
        }
    }
}
