package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.fs.GuestFilesystem
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: terminal.fs — T82 structured guest-filesystem API（Termux 基线 §4.8）。
 *
 * 在 Ubuntu/PRoot rootfs 内做结构化文件操作（base64 二进制安全、路径门禁、
 * 结构化错误），取代 `terminal.run "cat > f"` 的引号地狱。
 *
 * JSON Schema (input):
 *   { action: "read"|"write"|"append"|"list"|"stat"|"exists"|"mkdir"|"remove"|"move"|"copy",
 *     path: string, content?: string, target?: string, recursive?: bool }
 * JSON Schema (output): action-specific, e.g.
 *   read   → { ok, text, bytes, base64? }
 *   list   → { ok, entries: [{name,type}] }
 *   stat   → { ok, path, type, sizeBytes, modifiedEpochSec }
 *   写类   → { ok, durationMs }
 * Errors (structured, not message strings):
 *   FsError:ContextUnavailable / FsError:PathDenied / FsError:TooLarge /
 *   FsError:GuestCommandFailed / FsError:Timeout / FsError:InvalidPath
 * 写门禁：/workspace、/root、/tmp、/sdcard（含子路径）。读任意 guest 路径。
 */
class TerminalFsTool(
    private val fs: GuestFilesystem
) : TerminalTool {
    override val id: String = "terminal.fs"
    override val name: String = id
    override val description: String = """
        Structured guest-filesystem operations inside the Ubuntu/PRoot rootfs (binary-safe via
        base64, sandboxed writes). Actions: read/write/append/list/stat/exists/mkdir/remove/
        move/copy. Writes are restricted to /workspace, /root, /tmp, /sdcard; reads are
        unrestricted. Prefer this over terminal.run for file manipulation (no quoting hazards,
        real error codes). The host-side workspace dir is shared with this guest root set.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"action":{"type":"string","enum":["read","write","append","list","stat","exists","mkdir","remove","move","copy"]},"path":{"type":"string"},"content":{"type":"string"},"target":{"type":"string"},"recursive":{"type":"boolean","default":false}},"required":["action","path"]}
""".trim()

    suspend fun execute(input: Input): Output {
        val result: GuestFilesystem.FsResult<*> = when (input.action) {
            "read" -> fs.read(input.path)
            "write" -> fs.write(input.path, input.content ?: "")
            "append" -> fs.append(input.path, input.content ?: "")
            "list" -> fs.list(input.path)
            "stat" -> fs.stat(input.path)
            "exists" -> fs.exists(input.path)
            "mkdir" -> fs.mkdir(input.path)
            "remove" -> fs.remove(input.path, input.recursive)
            "move" -> fs.move(input.path, input.target ?: return err("FsError:InvalidInput", "target required for move"))
            "copy" -> fs.copy(input.path, input.target ?: return err("FsError:InvalidInput", "target required for copy"))
            else -> return err("FsError:InvalidInput", "unknown action '${input.action}'")
        }
        return when (result) {
            is GuestFilesystem.FsResult.Ok -> Output(ok = true, code = "OK", message = null, payload = result.value, durationMs = result.durationMs)
            is GuestFilesystem.FsResult.Err -> Output(ok = false, code = result.code, message = result.message, payload = null, durationMs = 0)
        }
    }

    private fun err(code: String, msg: String): Output = Output(ok = false, code = code, message = msg, payload = null, durationMs = 0)

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val action = json["action"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("action required")
        val path = json["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("path required")
        val content = json["content"]?.jsonPrimitive?.content
        val target = json["target"]?.jsonPrimitive?.content
        val recursive = json["recursive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val out = execute(Input(action, path, content, target, recursive))
        return buildJsonObject {
            put("ok", JsonPrimitive(out.ok))
            put("code", JsonPrimitive(out.code))
            out.message?.let { put("message", JsonPrimitive(it)) }
            when (val p = out.payload) {
                is GuestFilesystem.ReadResult -> {
                    put("text", JsonPrimitive(p.text))
                    put("bytes", JsonPrimitive(p.bytes.size))
                    if (p.bytes.size in 1..32_768) put("base64", JsonPrimitive(p.base64))
                }
                is List<*> -> put("entries", kotlinx.serialization.json.buildJsonArray {
                    p.filterIsInstance<GuestFilesystem.DirEntry>().forEach {
                        add(buildJsonObject { put("name", JsonPrimitive(it.name)); put("type", JsonPrimitive(it.type)) })
                    }
                })
                is GuestFilesystem.FileInfo -> {
                    put("path", JsonPrimitive(p.path))
                    put("type", JsonPrimitive(p.type))
                    put("sizeBytes", JsonPrimitive(p.sizeBytes))
                    put("modifiedEpochSec", JsonPrimitive(p.modifiedEpochSec))
                }
                is Boolean -> put("value", JsonPrimitive(p))
                else -> if (out.ok) put("done", JsonPrimitive(true))
            }
            if (out.durationMs > 0) put("durationMs", JsonPrimitive(out.durationMs))
        }.toString()
    }

    data class Input(
        val action: String,
        val path: String,
        val content: String? = null,
        val target: String? = null,
        val recursive: Boolean = false
    )

    data class Output(
        val ok: Boolean,
        val code: String,
        val message: String?,
        val payload: Any?,
        val durationMs: Long
    )
}
