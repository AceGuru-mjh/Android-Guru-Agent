package com.apex.agent.ui.screen.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.regex.Pattern

/**
 * HTML 产物检测器：从工具调用的 name + args + 成功 output 中提取 Agent
 * 写出的 HTML 文件路径，供工具卡挂「预览」入口（应用内 WebView 即时渲染，
 * 见 ui/component/HtmlPreviewDialog.kt）。
 *
 * 识别口径（由宽到窄）：
 * 1. **结构化字段**（code_write / code_edit）：JSON args 的 `path` 字段
 *    以 .html / .htm 结尾 —— 最可靠（工具 schema 固定）；
 * 2. **命令行扫描**（terminal.run / shell_execute / exec 等）：命令串里形如
 *    `xxx.html` 的路径词（引号/反引号/空白分隔）—— 兜底覆盖 echo/cat 重定向写法；
 * 3. **输出扫描（#188 短补丁）**：工具成功输出中的**写确认句式**
 *    （wrote / written / saved / created / 写入 / 生成 / 保存 … xxx.html）
 *    优先，其次任意 .html 路径词 —— 覆盖「脚本内部写文件、路径不出现在
 *    args」的场景（python3 gen.py → 输出 /workspace/report.html）。
 *    仅在调用成功时由调用方传入 output（失败写不入账）；预览钮点击时
 *    二次校验存在性（既有行为，文件后续删除不残留死入口）。
 *
 * 提取结果一律为**字符串原样路径**（相对 or 绝对 or guest /workspace 前缀），
 * 宿主路径解析统一交给 [AgentChatViewModel.resolveHtmlPreviewPath]
 * （工作区根 / guest 映射 / 绝对存在性三段式判定）。
 */
internal object HtmlArtifactDetector {

    private val HTML_PATH_PATTERN: Pattern = Pattern.compile(
        """[^\s"'`|;&<>]+\.x?html?\b""",
        Pattern.CASE_INSENSITIVE
    )

    /** #188 输出扫描：写确认句式（捕获组 1 = 文件路径；动词后 0-80 字符内）。 */
    private val HTML_WRITE_CONFIRM_PATTERN: Pattern = Pattern.compile(
        """(?:wrote|written|saved|created|write to|写入|生成|保存)[^\n]{0,80}?([^\s"'`|;&<>]+\.x?html?\b)""",
        Pattern.CASE_INSENSITIVE
    )

    /** 结构化路径字段优先的写类工具（path 字段可信）。 */
    private val PATH_FIELD_TOOLS = setOf("code_write", "code_edit")

    /**
     * 从工具调用中提取 HTML 文件路径（null = 非 HTML 产物）。
     *
     * @param output 工具输出（#188：仅成功调用传入 —— 写确认句式优先，
     *   兜底扫描任意 .html 路径词，脚本内部写文件也能被检出）。
     */
    fun extractHtmlPath(toolName: String, args: String, output: String? = null): String? {
        if (args.isBlank() && output.isNullOrBlank()) return null

        // 1. code_write / code_edit：解析 JSON 的 path 字段（结构化最可靠）。
        if (toolName in PATH_FIELD_TOOLS && args.isNotBlank()) {
            runCatching {
                val path = Json.parseToJsonElement(args).jsonObject["path"]?.jsonPrimitive?.content
                if (path != null && path.isHtmlFileName()) return path
            }
        }

        // 2. 通用命令扫描：任何工具 args（shell 命令 / 输出路径）中出现的 .html 路径词。
        if (args.isNotBlank()) {
            val matcher = HTML_PATH_PATTERN.matcher(args)
            while (matcher.find()) {
                val candidate = matcher.group()
                // 过滤纯 URL（http(s):// 远端页面不属于本地产物预览范畴）与 markdown 链接尾巴。
                if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) {
                    return candidate.removeSuffix(":").trim()
                }
            }
        }

        // 3. 输出扫描（#188）：写确认句式优先，兜底任意 .html 路径词
        //   （读出的文件也可预览 —— 无害增益；存在性由 resolve 阶段把关）。
        if (!output.isNullOrBlank()) {
            val confirm = HTML_WRITE_CONFIRM_PATTERN.matcher(output)
            while (confirm.find()) {
                val confirmed = confirm.group(1) ?: continue
                if (!confirmed.startsWith("http://", true) && !confirmed.startsWith("https://", true)) {
                    return confirmed.removeSuffix(":").trim()
                }
            }
            val outMatcher = HTML_PATH_PATTERN.matcher(output)
            while (outMatcher.find()) {
                val candidate = outMatcher.group()
                if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) {
                    return candidate.removeSuffix(":").trim()
                }
            }
        }
        return null
    }

    private fun String.isHtmlFileName(): Boolean =
        lowercase().endsWith(".html") || lowercase().endsWith(".htm")

    /**
     * 判断解析后的文件是否存在且可读（预览按钮显隐的最终判据）。
     * 由调用方传入已解析的宿主绝对路径。
     */
    fun isPreviewableHostFile(hostPath: String?): Boolean {
        if (hostPath.isNullOrBlank()) return false
        val f = File(hostPath)
        return f.isFile && f.canRead() && runCatching { f.length() < 16 * 1024 * 1024 }.getOrDefault(false)
    }
}
