package com.apex.agent.vault

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══ 金库 Agent 工具集（#167）：vault_list / vault_save / vault_paste / vault_delete ═══
 *
 * 安全契约（工具描述同步写给模型，这里是实现保证）：
 * 1. **Agent 永远看不到明文** —— vault_list 只输出脱敏快照；
 * 2. **vault_save 是 write-only** —— 成功返回仅含 label 与条目 id，写完自己也读不回；
 * 3. **vault_paste 是直投** —— 密钥经剪贴板/终端/HTTP 三通道投出，
 *    返回仅含字节数/状态码/脱敏后的响应体，绝不回显内容；
 * 4. 纵深防御：所有工具输出另被 SecretRedactingExecutor 兜底脱敏。
 *
 * 参数解析风格与 GithubTools 对齐（宽容解析 + contentOrNull），依赖经
 * 构造函数注入（clipboardSetter / terminalWriter / httpClient 均为 lambda
 * 或纯 JVM 类型，保证本文件可 JVM 单测）。
 */

/** 宽容解析工具参数：失败返回 null（调用方转成模型可自修复的 Error 文本）。 */
private fun parseArgs(arguments: String): JsonObject? = try {
    Json.parseToJsonElement(arguments).jsonObject
} catch (e: Exception) {
    null
}

/** 统一的参数解析失败文案（回显原始参数前 200 字符，模型可自纠）。 */
private fun argsParseError(arguments: String): String =
    "Error: invalid arguments — expected a JSON object but got: ${arguments.take(200)}. Fix the JSON and retry."

/** 读字符串字段（非字符串值容忍降级为文本表示）。 */
private fun JsonObject.stringOf(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** 读布尔字段（缺省回退默认值；非布尔值容忍 "true"/"false" 文本）。 */
private fun JsonObject.boolOf(key: String, default: Boolean): Boolean =
    when (val p = this[key] as? JsonPrimitive) {
        null -> default
        else -> p.contentOrNull?.lowercase()?.toBooleanStrictOrNull() ?: default
    }

/** 工具输出的时间戳格式（英文环境，与工具描述语言一致）。 */
private fun formatTs(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))

// ═════════════════════════ vault_list ═════════════════════════

/**
 * 列出金库条目（脱敏快照）：每行 `- label | note | origin | used Nx | updated <date>`。
 * 只读、LOW 风险 —— Agent 用它发现可用密钥的标签，再用 vault_paste 投递。
 */
class VaultListTool(
    private val repository: VaultRepository
) : AgentTool {
    override val id = "vault_list"
    override val name = "Vault List"
    override val description = """
        List entries in the encrypted secret vault. Returns ONLY labels, notes, origin and usage stats — secret contents are NEVER shown to you. Use vault_paste with a label to deliver a secret to the clipboard, a terminal session or an HTTP request without ever seeing it. 列出金库条目（只有标签与备注，永远看不到明文）。
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{},"required":[]}"""

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SECURITY)
        risk(ToolRisk.LOW)
        tag("vault", "secret", "credential", "security")
        annotations { readOnly() }
    }

    override suspend fun execute(arguments: String): String {
        val snaps = repository.snapshots()
        if (snaps.isEmpty()) {
            return "Vault is empty. Ask the user to store secrets in the Vault screen " +
                "(drawer → Vault), then re-run vault_list."
        }
        return buildString {
            appendLine("Vault entries (${snaps.size}) — contents are never revealed:")
            snaps.forEach { s ->
                val note = s.note.ifBlank { "-" }
                val ts = formatTs(if (s.lastUsedAt > 0) s.lastUsedAt else s.createdAt)
                appendLine("- ${s.label} | note: $note | origin: ${s.origin.name} | used ${s.usageCount}x | updated $ts")
            }
        }
    }
}

// ═════════════════════════ vault_save ═════════════════════════

/**
 * 把密钥写入金库（write-only）：成功返回仅含 label 与条目 id ——
 * content 绝不出现在任何返回值里（写完自己也读不回）。
 * label 冲突默认覆写（overwrite=false 时报错）。
 */
class VaultSaveTool(
    private val repository: VaultRepository
) : AgentTool {
    override val id = "vault_save"
    override val name = "Vault Save"
    override val description = """
        Store a secret into the encrypted vault. WRITE-ONLY: the content is encrypted at rest and NEVER returned to you — you cannot read it back after saving. Save first, then use vault_paste when needed. Returns only the label and entry id. 参数：label/note/content/overwrite。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "label":{"type":"string","description":"Unique label, e.g. github-token (visible to you)"},
            "note":{"type":"string","description":"Optional note describing the purpose (visible to you)"},
            "content":{"type":"string","description":"The secret itself — encrypted at rest, never returned"},
            "overwrite":{"type":"boolean","default":true,"description":"Replace an existing entry with the same label"}
        },"required":["label","content"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SECURITY)
        risk(ToolRisk.MEDIUM)
        tag("vault", "secret", "credential", "security", "store")
        annotations { idempotentWrite().copy(sensitiveAction = true) }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val label = json.stringOf("label")?.trim().orEmpty()
        val content = json.stringOf("content").orEmpty()
        val note = json.stringOf("note")?.trim().orEmpty()
        val overwrite = json.boolOf("overwrite", default = true)
        if (label.isEmpty()) return "Error: 'label' is required."
        if (content.isEmpty()) return "Error: 'content' is required."

        val existing = repository.getByLabel(label)
        if (existing != null && !overwrite) {
            return "Error: label '$label' already exists (id ${existing.id}). " +
                "Pass overwrite=true to replace it, or choose another label."
        }

        val saved = if (existing != null) {
            repository.save(existing.copy(label = label, note = note, secret = content, origin = VaultOrigin.AGENT))
        } else {
            repository.save(VaultRepository.newEntry(label, note, content, VaultOrigin.AGENT))
        }
        // 安全红线：返回体只含 label 与 id，绝不含 content。
        return buildJsonObject {
            put("saved", true)
            put("label", saved.label)
            put("id", saved.id)
        }.toString()
    }
}

// ═════════════════════════ vault_paste ═════════════════════════

/**
 * 核心工具：把金库密钥**直接投递**到目标通道，内容不落模型上下文。
 *
 * 三通道：
 * - clipboard：Android ClipboardManager（提示用户已复制，可手动粘贴）；
 * - terminal：TerminalRuntime.write（kind=RAW，可选补换行），返回字节数；
 * - http：OkHttp 携密钥请求（Bearer/token/raw 三种凭据方案），
 *   响应体先经 SecretRedactor 脱敏再截断 4000 字符返回（防服务端回显）。
 */
class VaultPasteTool(
    private val repository: VaultRepository,
    private val clipboardSetter: (String) -> Unit,
    private val terminalWriter: suspend (Long, String) -> Boolean,
    private val httpClient: OkHttpClient
) : AgentTool {
    override val id = "vault_paste"
    override val name = "Vault Paste"
    override val description = """
        Deliver a vault secret DIRECTLY to a target without revealing it: to=clipboard (copies it, tell the user to paste manually), to=terminal (writes it into a PTY session, pass session_id), or to=http (sends an authenticated request: url/method/header/scheme=bearer|token|raw). The secret content is NEVER echoed — responses contain only byte counts / status codes / redacted bodies. 直接粘贴金库密钥，内容永不回显。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "label":{"type":"string","description":"Vault entry label (see vault_list)"},
            "to":{"type":"string","enum":["clipboard","terminal","http"],"description":"Delivery channel"},
            "session_id":{"type":"integer","description":"Terminal session id (required when to=terminal)"},
            "newline":{"type":"boolean","default":true,"description":"Append a newline after the secret in terminal mode"},
            "url":{"type":"string","description":"Request URL (required when to=http)"},
            "method":{"type":"string","enum":["GET","POST","PUT","DELETE","PATCH"],"default":"GET"},
            "header":{"type":"string","default":"Authorization","description":"Header name for the credential (http mode)"},
            "scheme":{"type":"string","enum":["bearer","token","raw"],"default":"bearer","description":"Credential scheme: bearer → 'Bearer <secret>', token → 'token <secret>', raw → '<secret>'"},
            "body":{"type":"string","description":"Optional request body (http mode, sent as application/json)"},
            "extra_headers":{"type":"object","description":"Optional extra headers as a JSON object (values must not contain secrets)"}
        },"required":["label","to"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SECURITY)
        risk(ToolRisk.MEDIUM)
        tag("vault", "secret", "paste", "credential", "security")
        annotations { mutating().copy(sensitiveAction = true, openWorldHint = true) }
    }

    /** http 响应体最大回显长度（脱敏后截断，防超长响应撑爆上下文）。 */
    private val maxBodyChars = 4000

    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val label = json.stringOf("label")?.trim().orEmpty()
        val to = json.stringOf("to")?.trim()?.lowercase().orEmpty()
        if (label.isEmpty()) return "Error: 'label' is required."

        val entry = repository.getByLabel(label)
            ?: return "Error: no vault entry labeled '$label'. Use vault_list to see available labels."

        return when (to) {
            "clipboard" -> pasteToClipboard(entry)
            "terminal" -> pasteToTerminal(entry, json)
            "http" -> pasteToHttp(entry, json)
            else -> "Error: 'to' must be one of clipboard | terminal | http (got '$to')."
        }
    }

    /** 通道一：系统剪贴板（用户随后手动粘贴）。 */
    private suspend fun pasteToClipboard(entry: VaultEntry): String {
        return try {
            clipboardSetter(entry.secret)
            repository.recordUsage(entry.id)
            buildJsonObject {
                put("pasted", true)
                put("target", "clipboard")
            }.toString() + " — secret copied to the clipboard. Tell the user it is ready to paste manually."
        } catch (e: Exception) {
            "Error: clipboard write failed (${e::class.simpleName}). The app may not be in the foreground."
        }
    }

    /** 通道二：终端会话（TerminalRuntime.write，kind=RAW）。 */
    private suspend fun pasteToTerminal(entry: VaultEntry, json: JsonObject): String {
        val sessionId = (json["session_id"] as? JsonPrimitive)?.contentOrNull
            ?.toLongOrNull()
            ?: return "Error: 'session_id' (integer) is required when to=terminal. Use list_terminals to find one."
        val newline = json.boolOf("newline", default = true)
        val text = if (newline) entry.secret + "\n" else entry.secret

        val ok = terminalWriter(sessionId, text)
        if (!ok) {
            return "Error: terminal write failed for session $sessionId " +
                "(not found or closed?). Use list_terminals and retry with a live session."
        }
        repository.recordUsage(entry.id)
        // 安全红线：只回字节数，不回内容。
        return buildJsonObject {
            put("pasted", true)
            put("target", "terminal")
            put("session", sessionId)
            put("bytes", text.toByteArray(Charsets.UTF_8).size)
        }.toString()
    }

    /** 通道三：HTTP 请求（凭据进请求头；响应脱敏 + 截断后返回）。 */
    private suspend fun pasteToHttp(entry: VaultEntry, json: JsonObject): String {
        val url = json.stringOf("url")?.trim().orEmpty()
        if (url.isEmpty()) return "Error: 'url' is required when to=http."
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: 'url' must start with http:// or https:// (got '${url.take(60)}')."
        }
        val method = json.stringOf("method")?.uppercase()?.trim() ?: "GET"
        if (method !in setOf("GET", "POST", "PUT", "DELETE", "PATCH")) {
            return "Error: 'method' must be one of GET/POST/PUT/DELETE/PATCH (got '$method')."
        }
        val headerName = json.stringOf("header")?.trim().orEmpty().ifBlank { "Authorization" }
        val scheme = json.stringOf("scheme")?.lowercase()?.trim() ?: "bearer"
        val headerValue = when (scheme) {
            "bearer" -> "Bearer ${entry.secret}"
            "token" -> "token ${entry.secret}"
            "raw" -> entry.secret
            else -> return "Error: 'scheme' must be bearer | token | raw (got '$scheme')."
        }
        val body = json.stringOf("body")
        val extraHeaders = (json["extra_headers"] as? JsonObject)?.let { obj ->
            obj.entries.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }
        } ?: emptyList()

        return try {
            val (code, rawBody, contentType) = withContext(Dispatchers.IO) {
                val builder = Request.Builder()
                    .url(url)
                    .header(headerName, headerValue)
                    .header("User-Agent", "ApexAgent/1.0")
                extraHeaders.forEach { (k, v) -> builder.header(k, v) }
                if (method != "GET" && body != null) {
                    builder.method(method, body.toRequestBody("application/json".toMediaType()))
                } else {
                    builder.method(method, null)
                }
                httpClient.newCall(builder.build()).execute().use { response ->
                    Triple(response.code, response.body?.string().orEmpty(), response.header("Content-Type") ?: "")
                }
            }
            repository.recordUsage(entry.id)
            val redacted = repository.secretRedactor.redact(rawBody).let {
                if (it.length > maxBodyChars) it.take(maxBodyChars) + "\n…(truncated ${it.length - maxBodyChars} chars)" else it
            }
            buildJsonObject {
                put("pasted", true)
                put("target", "http")
                put("status", code)
                put("content_type", contentType)
                put("body", redacted)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: HTTP request failed (${e::class.simpleName}: ${e.message?.take(120)})."
        }
    }
}

// ═════════════════════════ vault_delete ═════════════════════════

/** 按标签删除金库条目（HIGH 风险：不可逆销毁密钥）。 */
class VaultDeleteTool(
    private val repository: VaultRepository
) : AgentTool {
    override val id = "vault_delete"
    override val name = "Vault Delete"
    override val description = """
        Permanently delete a vault entry by label. Irreversible — the stored secret is destroyed. Confirm with the user before deleting. 按标签永久删除金库条目（不可逆，先与用户确认）。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{"label":{"type":"string","description":"Label of the entry to delete"}},"required":["label"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SECURITY)
        risk(ToolRisk.HIGH)
        tag("vault", "secret", "delete", "security")
        annotations { destructive().copy(sensitiveAction = true) }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val label = json.stringOf("label")?.trim().orEmpty()
        if (label.isEmpty()) return "Error: 'label' is required."
        val deleted = repository.deleteByLabel(label)
        return if (deleted) {
            "Deleted vault entry '$label'."
        } else {
            "Error: no vault entry labeled '$label'. Use vault_list to see available labels."
        }
    }
}

/**
 * 工具集工厂：DI 装配点一次产出 4 个金库工具
 * （ToolModule 内统一 `forEach { registry.register(SafeAgentTool(it)) }`）。
 */
object VaultAgentTools {

    fun all(
        repository: VaultRepository,
        clipboardSetter: (String) -> Unit,
        terminalWriter: suspend (Long, String) -> Boolean,
        httpClient: OkHttpClient
    ): List<AgentTool> = listOf(
        VaultListTool(repository),
        VaultSaveTool(repository),
        VaultPasteTool(repository, clipboardSetter, terminalWriter, httpClient),
        VaultDeleteTool(repository)
    )

    /** 全部工具 id（诊断 / 测试断言 id 全局唯一用）。 */
    val IDS: List<String> = listOf("vault_list", "vault_save", "vault_paste", "vault_delete")
}
