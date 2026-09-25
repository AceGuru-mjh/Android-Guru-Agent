package com.apex.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk

/**
 * 系统意图工具（#172 高级设备工具包）：分享与深链。
 *
 * 两个工具都走 lambda 注入模式（工具只做参数解析，JVM 可测；Android
 * Intent 侧见文件底部的 [AndroidIntents] 生产接线）。
 */
// ═══════════════════════════ share_content ═══════════════════════════

/**
 * 分享文本到其他应用（Android 分享面板）。参数：text（必填）、title
 * （可选，仅作为 chooser 标题展示）。ACTION_SEND + text/plain +
 * FLAG_ACTIVITY_NEW_TASK。
 */
class ShareContentTool(
    /** (text, title?) → 结果文本。 */
    private val share: (String, String?) -> String
) : AgentTool {
    override val id = "share_content"
    override val name = "Share Content"
    override val description = """
        Share text via the Android share sheet (user picks the target app).
        Input: {"text": "...", "title": "Pick an app"} — title is optional
        (share sheet heading only, not sent). 分享文本（text 必填，title 可选）。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "text":{"type":"string","description":"The text to share (required)"},
            "title":{"type":"string","description":"Optional chooser title shown in the share sheet"}
        },"required":["text"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("share", "intent", "send", "system")
        annotations { idempotentWrite() }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseToolArgs(arguments) ?: return argsParseErrorMessage(arguments)
        val text = json.stringOf("text")
            ?: return "Error: 'text' is required and must be non-empty."
        return share(text, json.stringOf("title"))
    }
}

// ═══════════════════════════ deep_link ═══════════════════════════

/**
 * 打开 URI（深链）。http(s) 链接自然落到浏览器，其他 scheme（app://、
 * intent 内部页、market:// 等）直接 ACTION_VIEW 尝试；无法解析时 try/catch
 * 返回明确错误（哪个 scheme、建议安装对应应用），绝不静默失败。
 */
class DeepLinkTool(
    /** uri → 结果文本。 */
    private val open: (String) -> String
) : AgentTool {
    override val id = "deep_link"
    override val name = "Deep Link / Open URI"
    override val description = """
        Open a URI on the device: http(s) links go to the browser, other
        schemes (app deep links, market://, settings pages) are tried directly
        via ACTION_VIEW. Input: {"uri": "https://example.com"}.
        Errors say which app/scheme could not be resolved. 打开 URI/深链。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "uri":{"type":"string","description":"The URI to open (https://, app://, market://, ...)"}
        },"required":["uri"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.APP)
        risk(ToolRisk.MEDIUM)
        tag("deep-link", "uri", "intent", "open", "browser")
        annotations { idempotentWrite() }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseToolArgs(arguments) ?: return argsParseErrorMessage(arguments)
        val uri = json.stringOf("uri")
            ?: return "Error: 'uri' is required (e.g. {\"uri\": \"https://example.com\"})."
        if (!isPlausibleUri(uri)) {
            return "Error: 'uri' does not look like a valid URI (needs a scheme like https:// or app://, no spaces): '$uri'."
        }
        return open(uri)
    }

    companion object {
        /** URI 预检（JVM 可测）：必须有合法 scheme（字母开头，字母/数字/+-.）且无空白。 */
        fun isPlausibleUri(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
            val schemeEnd = trimmed.indexOf("://").takeIf { it > 0 }
                ?: trimmed.indexOf(':').takeIf { it > 0 }
                ?: return false
            val scheme = trimmed.substring(0, schemeEnd)
            return scheme.isNotEmpty() &&
                scheme.first().isLetter() &&
                scheme.all { it.isLetterOrDigit() || it in "+-." }
        }
    }
}

/** 生产接线：真实 Intent 发射（FLAG_ACTIVITY_NEW_TASK，工具进程无 Activity）。 */
object AndroidIntents {

    fun share(context: Context, text: String, title: String?): String {
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(send, title ?: "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            "Share sheet opened (${text.length} chars). The user picks the target app."
        } catch (e: Exception) {
            "Error: cannot open the share sheet: ${e.message ?: e::class.simpleName}"
        }
    }

    fun openUri(context: Context, uriText: String): String {
        return try {
            val uri = Uri.parse(uriText.trim())
            if (uri.scheme.isNullOrBlank()) {
                return "Error: '$uriText' has no scheme — use a full URI like https://example.com or the app's deep-link scheme."
            }
            val view = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(view)
            "Opened '$uriText' (scheme ${uri.scheme})."
        } catch (e: android.content.ActivityNotFoundException) {
            "Error: no app can handle '${uriText.take(80)}' (${e.message?.take(80) ?: "activity not found"}). " +
                "The target app may not be installed — suggest installing it or opening the https:// fallback."
        } catch (e: Exception) {
            "Error: cannot open '$uriText': ${e.message ?: e::class.simpleName}"
        }
    }
}
