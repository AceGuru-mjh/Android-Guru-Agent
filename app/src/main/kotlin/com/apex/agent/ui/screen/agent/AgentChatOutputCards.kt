package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════
// 流水线智能输出渲染器
//
// 依据"工具类型 + 工具名 + 参数 + 输出内容"自动选择最佳呈现方式：
// - 代码输出（read_file 等）        → 语法高亮代码卡
// - 文件操作（write/edit/delete…）  → 文件操作卡（图标 + 大小/行数变更徽章）
// - Shell 执行（shell_execute）     → 命令 + 输出卡
// - JSON 输出（MCP/设备信息等）     → 可折叠 JSON 树
// - 其余                            → 带复制按钮与统计信息的文本块
// ═══════════════════════════════════════════════════════════════════════

/** CSI ANSI 转义序列（颜色/光标控制等）。工具输出（尤其 shell）常混有这些序列。 */
private val ANSI_REGEX = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

/** 去除文本中的 ANSI 转义序列（供 ViewModel 在事件入口统一清理）。 */
internal fun stripAnsi(text: String): String = ANSI_REGEX.replace(text, "")

/** 字节数 → 人类可读大小。 */
internal fun formatBytes(b: Long): String = when {
    b < 0 -> "0 B"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

/** 毫秒 → 紧凑可读时长（850ms / 1.2s / 2m05s）。 */
internal fun formatDuration(ms: Long): String = when {
    ms < 0 -> "0ms"
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> String.format(
        Locale.US, "%dm%02ds", ms / 60_000, (ms % 60_000) / 1000
    )
}

// ═══════════════════════════════════════════════════════════════════════
// 输出视图路由
// ═══════════════════════════════════════════════════════════════════════

/** 智能输出视图类型。 */
internal enum class ToolOutputView { CODE, FILE_OP, JSON, SHELL, PLAIN }

/** 文件操作类工具集合（输出可解析出结构化文件信息）。 */
internal val FILE_OP_TOOLS = setOf("write_file", "edit_file", "delete_file", "copy_move_file")

/** 根据工具名/参数/输出推断输出视图类型。 */
internal fun detectOutputView(toolName: String, output: String): ToolOutputView {
    val name = toolName.lowercase()
    if (name in FILE_OP_TOOLS) return ToolOutputView.FILE_OP
    if (name == "shell_execute") return ToolOutputView.SHELL
    if (name == "read_file") return ToolOutputView.CODE
    val trimmed = output.trim()
    if (trimmed.length >= 2 &&
        ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]")))
    ) {
        return ToolOutputView.JSON
    }
    return ToolOutputView.PLAIN
}

/**
 * 智能输出渲染入口：按 [detectOutputView] 的结果分发到对应卡片。
 *
 * @param toolName 工具 id（决定解析策略与语言推断）。
 * @param args 原始参数 JSON（提取命令 / 路径 / URL）。
 * @param output 展示用输出（可能是截断后的摘要）。
 * @param fullOutput 完整输出（展开时使用；null 表示与 output 相同）。
 * @param expanded 卡片是否处于展开态。
 * @param isError 工具是否执行失败（失败时红色化）。
 */
@Composable
internal fun SmartToolOutput(
    toolName: String,
    args: String,
    output: String,
    fullOutput: String?,
    expanded: Boolean,
    isError: Boolean = false
) {
    val view = remember(toolName, output.take(64)) { detectOutputView(toolName, output) }
    val body = if (expanded) (fullOutput ?: output) else output

    when (view) {
        ToolOutputView.FILE_OP -> FileOpCard(
            toolName = toolName,
            args = args,
            output = body,
            isError = isError
        )
        ToolOutputView.SHELL -> ShellOutputCard(
            args = args,
            output = body,
            isError = isError
        )
        ToolOutputView.CODE -> CodeOutputCard(
            code = body,
            lang = langFromArgs(toolName, args),
            showHeader = true
        )
        ToolOutputView.JSON -> JsonOutputCard(json = body)
        ToolOutputView.PLAIN -> TextOutputBlock(
            text = body,
            fullText = if (expanded) (fullOutput ?: output) else fullOutput,
            expanded = expanded
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 通用小组件
// ═══════════════════════════════════════════════════════════════════════

/** 复制按钮（点击后短暂变为对勾）。 */
@Composable
internal fun CopyIconButton(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
            android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
        },
        modifier = modifier.size(28.dp)
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = if (copied) "已复制" else "复制",
            modifier = Modifier.size(15.dp),
            tint = tint
                ?: if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 纯文本输出块（含复制按钮 + 行数字符统计 + 截断提示）。
 *
 * @param text 展示文本。
 * @param fullText 完整文本（与 text 不同时，折叠态显示"输出已截断"提示）。
 */
@Composable
internal fun TextOutputBlock(
    text: String,
    fullText: String?,
    expanded: Boolean,
    label: String = if (expanded) "完整输出" else "输出摘要"
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!expanded && fullText != null && fullText.length > text.length) {
                Text(
                    text = "已截断，展开查看全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            CopyIconButton(text = text)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(8.dp)
                        .heightIn(max = if (expanded) 420.dp else 120.dp)
                        .verticalScroll(rememberScrollState()),
                    maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = outputStats(text),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/** 输出统计信息（行数 + 字符数）。 */
internal fun outputStats(text: String): String {
    val lines = text.count { it == '\n' } + 1
    return "$lines 行 · ${text.length} 字符"
}

// ═══════════════════════════════════════════════════════════════════════
// 代码输出卡（语法高亮）
// ═══════════════════════════════════════════════════════════════════════

/** 从参数 JSON 或输出头部推断代码语言标签。 */
internal fun langFromArgs(toolName: String, args: String): String {
    // read_file/write_file 的参数里有 path，按扩展名推断。
    val pathMatch = Regex("\"(?:path|file|source|dest|target)\"\\s*:\\s*\"([^\"]+)\"").find(args)
    val path = pathMatch?.groupValues?.getOrNull(1)
    if (!path.isNullOrBlank()) {
        val lang = langFromPath(path)
        if (lang.isNotBlank()) return lang
    }
    return "code"
}

/** 文件扩展名 → 语言标签。 */
internal fun langFromPath(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "json" -> "json"
        "xml" -> "xml"
        "html", "htm" -> "html"
        "css" -> "css"
        "md" -> "markdown"
        "sh", "bash" -> "bash"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp", "hh" -> "cpp"
        "go" -> "go"
        "rs" -> "rust"
        "swift" -> "swift"
        "rb" -> "ruby"
        "php" -> "php"
        "sql" -> "sql"
        "yml", "yaml" -> "yaml"
        "toml" -> "toml"
        "gradle" -> "gradle"
        "csv" -> "csv"
        "txt" -> "text"
        else -> ""
    }
}

/** 扩展名 → 文件图标（文件操作卡用）。 */
internal fun fileIconFor(path: String): ImageVector {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "c", "h",
        "cpp", "cc", "cxx", "hpp", "go", "rs", "swift", "rb", "php", "gradle" -> Icons.Default.Code
        "json", "xml", "yml", "yaml", "toml" -> Icons.Default.DataObject
        "md", "txt", "log" -> Icons.Default.Description
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> Icons.Default.Image
        "mp4", "mov", "avi", "mkv", "webm" -> Icons.Default.Movie
        "mp3", "wav", "ogg", "flac", "m4a" -> Icons.Default.AudioFile
        "pdf" -> Icons.Default.PictureAsPdf
        "csv", "xls", "xlsx" -> Icons.Default.TableChart
        "zip", "tar", "gz", "7z", "rar", "apk" -> Icons.Default.Archive
        else -> Icons.Default.InsertDriveFile
    }
}

/** 代码高亮配色（明暗主题各一套，均保证与 surfaceContainerHighest 底色对比可读）。 */
internal data class CodeColorScheme(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color
)

@Composable
private fun codeColorScheme(): CodeColorScheme {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        CodeColorScheme(
            keyword = Color(0xFFC792EA),
            string = Color(0xFFA5D6A7),
            number = Color(0xFFFDBA74),
            comment = Color(0xFF94A3B8),
            annotation = Color(0xFFF472B6)
        )
    } else {
        CodeColorScheme(
            keyword = Color(0xFF7C3AED),
            string = Color(0xFF15803D),
            number = Color(0xFFB45309),
            comment = Color(0xFF6B7280),
            annotation = Color(0xFFBE185D)
        )
    }
}

/** 多语言关键字集（C 族 + Kotlin/Java/Python/Go/Rust/Swift 常用关键字并集）。 */
private val CODE_KEYWORDS = setOf(
    // Kotlin / Java
    "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
    "companion", "import", "package", "private", "public", "internal", "protected",
    "override", "open", "abstract", "suspend", "lateinit", "const", "typealias",
    "constructor", "init", "this", "super", "when", "is", "as", "in", "by", "get", "set",
    "public", "static", "void", "final", "extends", "implements", "new", "instanceof",
    "boolean", "int", "long", "double", "float", "char", "byte", "short",
    // C 族 / Go / Rust / Swift
    "func", "struct", "impl", "trait", "enum", "match", "let", "mut", "fn", "pub",
    "crate", "use", "mod", "where", "self", "Self", "nil", "extension", "guard",
    "defer", "echo", "cd", "export", "local", "readonly", "function",
    // 通用控制流
    "if", "else", "for", "while", "do", "return", "break", "continue", "switch",
    "case", "default", "try", "catch", "finally", "throw", "throws", "yield",
    "await", "async", "lambda", "pass", "with", "elif", "except", "raise",
    "from", "global", "nonlocal", "assert", "del", "and", "or", "not", "end",
    // 字面量
    "true", "false", "null", "None", "True", "False", "nil", "undefined"
)

/**
 * 轻量语法高亮：单遍正则扫描（字符串 / 注释 / 注解 / 数字 / 关键字）。
 * 不追求 IDE 级精度，只为输出卡提供可读性层次。
 */
internal fun highlightCode(code: String, lang: String, colors: CodeColorScheme): AnnotatedString {
    val isHashCommentLang = lang in setOf("python", "bash", "yaml", "ruby", "toml", "gradle")
    val commentPattern = if (isHashCommentLang) {
        """#[^\n]*"""
    } else {
        """//[^\n]*|/\*[\s\S]*?\*/"""
    }
    val keywordPattern = CODE_KEYWORDS
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    val master = Regex(
        """("[^"\n]*"|'[^'\n]*'|`[^`\n]*`)|($commentPattern)|(@\w+)|(\b\d+(?:\.\d+)?[fFlLdDuU]?\b)|(\b(?:$keywordPattern)\b)"""
    )

    return buildAnnotatedString {
        var lastEnd = 0
        for (m in master.findAll(code)) {
            if (m.range.first > lastEnd) append(code.substring(lastEnd, m.range.first))
            val styled = when {
                m.groupValues[1].isNotEmpty() -> SpanStyle(color = colors.string)
                m.groupValues[2].isNotEmpty() -> SpanStyle(
                    color = colors.comment, fontStyle = FontStyle.Italic
                )
                m.groupValues[3].isNotEmpty() -> SpanStyle(color = colors.annotation)
                m.groupValues[4].isNotEmpty() -> SpanStyle(color = colors.number)
                m.groupValues[5].isNotEmpty() -> SpanStyle(
                    color = colors.keyword, fontWeight = FontWeight.Medium
                )
                else -> null
            }
            if (styled != null) withStyle(styled) { append(m.value) } else append(m.value)
            lastEnd = m.range.last + 1
        }
        if (lastEnd < code.length) append(code.substring(lastEnd))
    }
}

/**
 * 语法高亮代码输出卡：深色容器 + 语言标签 + 行数统计 + 复制。
 * read_file 等自带行号的输出原样保留行号前缀。
 */
@Composable
internal fun CodeOutputCard(
    code: String,
    lang: String,
    showHeader: Boolean = true
) {
    val colors = codeColorScheme()
    val highlighted = remember(code, lang) { highlightCode(code, lang, colors) }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Column {
            if (showHeader) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = lang.ifBlank { "code" }.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$lineCount 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    CopyIconButton(text = code)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 文件操作卡
// ═══════════════════════════════════════════════════════════════════════

/** 文件操作解析结果（从 write/edit/delete/copy_move 的输出中提取结构化信息）。 */
internal data class FileOpInfo(
    val fileName: String,
    val path: String?,
    val opLabel: String,
    val sizeChange: String?,
    val statLine: String?,
    val extras: List<String>
)

/**
 * 解析文件操作工具输出。兼容以下格式：
 * - `✅ Created: name` / `✅ Overwritten: name` / `✅ Appended to: name (+xKB)`
 * - `  Old: 1KB → New: 2KB`、`  N lines, size`、`  Path: /abs/path`
 * - `✅ Edited name (N operations)` + `  • op` + `  Net change: +X lines`
 * - `OK: Deleted path`、`OK: Moved/Copied src → dest (NKB)`
 */
internal fun parseFileOp(toolName: String, args: String, output: String): FileOpInfo {
    val argPath = Regex("\"(?:path|source|dest|target|file)\"\\s*:\\s*\"([^\"]+)\"")
        .find(args)?.groupValues?.getOrNull(1)

    val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    val opLabel = when {
        firstLine.contains("Created", ignoreCase = true) -> "新建"
        firstLine.contains("Overwritten", ignoreCase = true) -> "覆盖"
        firstLine.contains("Appended", ignoreCase = true) -> "追加"
        firstLine.contains("Edited", ignoreCase = true) -> "编辑"
        firstLine.contains("Deleted", ignoreCase = true) -> "删除"
        firstLine.contains("Moved", ignoreCase = true) -> "移动"
        firstLine.contains("Copied", ignoreCase = true) -> "复制"
        toolName == "write_file" -> "写入"
        toolName == "edit_file" -> "编辑"
        toolName == "delete_file" -> "删除"
        toolName == "copy_move_file" -> "复制/移动"
        else -> "文件操作"
    }

    val sizeChange = Regex("Old:\\s*(.+?)\\s*→\\s*New:\\s*(.+)")
        .find(output)?.let { "${it.groupValues[1]} → ${it.groupValues[2]}" }
        ?: Regex("\\(\\+(?:(\\d+\\.?\\d*[KMG]?B))\\)").find(output)?.groupValues?.getOrNull(1)
            ?.let { "+$it" }

    // 行数统计：优先 "File now: N lines, size"（edit_file），回退到首个 "N lines, size"（write_file）。
    // 注意不要命中 edit 输出中的 "Net change: +X lines"（统计的是变更行而非总行数）。
    val statLine = (Regex("File now:\\s*(\\d+) lines(?:,\\s*(\\S+))?").find(output)
        ?: Regex("(\\d+) lines(?:,\\s*(\\S+))?").find(output))?.let { m ->
            val size = m.groupValues[2].takeIf { it.isNotBlank() }
            "${m.groupValues[1]} 行" + (size?.let { " · $it" } ?: "")
        }

    val extras = output.lineSequence()
        .map { it.trim() }
        .filter {
            (it.startsWith("•") || it.startsWith("Net change:") || it.startsWith("File now:")) &&
                !it.contains("lines,")
        }
        .toList()

    // 文件名优先级：输出第一行的名字 > 参数路径末段
    val nameFromOutput = Regex("(?:Created|Overwritten|Appended to|Edited|Deleted)[: ]\\s*(\\S+?)\\s*(?:\\(|$)")
        .find(firstLine)?.groupValues?.getOrNull(1)
        ?: Regex("(?:Deleted|Moved|Copied)\\s+(\\S+)\\s*(?:→|\\(|$)").find(firstLine)
            ?.groupValues?.getOrNull(1)
    val pathForName = argPath ?: nameFromOutput
    val fileName = pathForName?.substringAfterLast('/')?.substringAfterLast('\\')
        ?: nameFromOutput ?: "文件"

    return FileOpInfo(
        fileName = fileName,
        path = argPath ?: nameFromOutput,
        opLabel = opLabel,
        sizeChange = sizeChange,
        statLine = statLine,
        extras = extras
    )
}

/** 文件操作卡：图标 + 文件名 + 路径 + 操作/变更徽章 + 附加信息。 */
@Composable
internal fun FileOpCard(
    toolName: String,
    args: String,
    output: String,
    isError: Boolean = false
) {
    val info = remember(toolName, output) { parseFileOp(toolName, args, output) }
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawStrokeBorder(borderColor)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = CircleShape,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = fileIconFor(info.path ?: info.fileName),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!info.path.isNullOrBlank() && info.path.contains('/')) {
                        Text(
                            text = info.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = info.opLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }

            if (info.sizeChange != null || info.statLine != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    info.sizeChange?.let {
                        FileOpChip(it, MaterialTheme.colorScheme.secondary)
                    }
                    info.statLine?.let {
                        FileOpChip(it, MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            if (info.extras.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    info.extras.take(6).forEach { extra ->
                        Text(
                            text = extra,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 文件操作小徽章（变更统计）。 */
@Composable
private fun FileOpChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

/** 圆角细边框绘制（与项目内 drawBehind 边框同族视觉）。 */
private fun Modifier.drawStrokeBorder(color: Color, cornerRadius: Int = 10): Modifier =
    this.drawBehind {
        drawRoundRect(
            color = color,
            style = Stroke(width = 1.dp.toPx()),
            cornerRadius = CornerRadius(cornerRadius.dp.toPx())
        )
    }

// ═══════════════════════════════════════════════════════════════════════
// Shell 执行卡
// ═══════════════════════════════════════════════════════════════════════

/** shell 执行输出卡：命令头 + 输出体（等宽、可选中、失败红色化）。 */
@Composable
internal fun ShellOutputCard(
    args: String,
    output: String,
    isError: Boolean = false
) {
    val command: String? = remember(args) {
        runCatching {
            Json.parseToJsonElement(args).jsonObject["command"]?.jsonPrimitive?.content
        }.getOrNull()
    }
    val commandText: String? = command?.takeIf { it.isNotBlank() }
    val hasError = isError || output.startsWith("❌") || output.startsWith("Error")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (commandText != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SelectionContainer {
                        Text(
                            text = "$ $commandText",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                    CopyIconButton(text = commandText)
                }
            }
        }
        if (output.isNotBlank()) {
            TextOutputBlock(
                text = output,
                fullText = null,
                expanded = true,
                label = if (hasError) "执行输出（失败）" else "执行输出"
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// JSON 树输出卡
// ═══════════════════════════════════════════════════════════════════════

/** JSON 每个节点最多渲染的子项数（超出部分显示 +N more）。 */
private const val JSON_MAX_CHILDREN = 30

/** JSON 结构化输出卡：可折叠树形查看器。 */
@Composable
internal fun JsonOutputCard(json: String) {
    val element = remember(json) {
        runCatching { Json.parseToJsonElement(json) }.getOrNull()
    }
    if (element == null) {
        TextOutputBlock(text = json, fullText = null, expanded = true, label = "输出")
        return
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "结构化输出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            CopyIconButton(text = json)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                JsonNodeRow(keyLabel = null, element = element, depth = 0)
            }
        }
    }
}

/** JSON 值的配色。 */
@Composable
private fun jsonValueColor(prim: JsonPrimitive): Color {
    val dark = isSystemInDarkTheme()
    return when {
        prim is JsonNull -> MaterialTheme.colorScheme.onSurfaceVariant
        prim.booleanOrNull != null -> if (dark) Color(0xFFC792EA) else Color(0xFF7C3AED)
        prim.longOrNull != null || prim.doubleOrNull != null ->
            if (dark) Color(0xFFFDBA74) else Color(0xFFB45309)
        else -> if (dark) Color(0xFFA5D6A7) else Color(0xFF15803D)
    }
}

/** JSON 树节点行（对象/数组可折叠，深度 2 以内默认展开）。 */
@Composable
private fun JsonNodeRow(keyLabel: String?, element: JsonElement, depth: Int) {
    when (element) {
        is JsonObject -> JsonBranch(
            keyLabel = keyLabel,
            size = element.size,
            openBrace = "{",
            closeBrace = "}",
            entries = element.entries.map { it.key to it.value },
            depth = depth
        )
        is JsonArray -> JsonBranch(
            keyLabel = keyLabel,
            size = element.size,
            openBrace = "[",
            closeBrace = "]",
            entries = element.indices.map { it.toString() to element[it] },
            depth = depth
        )
        is JsonPrimitive -> JsonLeaf(keyLabel, element, depth)
    }
}

@Composable
private fun JsonBranch(
    keyLabel: String?,
    size: Int,
    openBrace: String,
    closeBrace: String,
    entries: List<Pair<String, JsonElement>>,
    depth: Int
) {
    var expanded by remember { mutableStateOf(depth < 2) }
    Column(modifier = Modifier.padding(start = (depth * 10).dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 1.dp)
        ) {
            Text(
                text = openBrace,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (expanded) "$size 项" else "… $size 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            if (!expanded) {
                Text(
                    text = closeBrace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
        if (expanded) {
            entries.take(JSON_MAX_CHILDREN).forEach { (k, v) ->
                JsonNodeRow(keyLabel = k, element = v, depth = depth + 1)
            }
            if (entries.size > JSON_MAX_CHILDREN) {
                Text(
                    text = "… 还有 ${entries.size - JSON_MAX_CHILDREN} 项",
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = ((depth + 1) * 10).dp, top = 1.dp)
                )
            }
            Text(
                text = closeBrace,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = (depth * 10).dp)
            )
        }
    }
}

@Composable
private fun JsonLeaf(keyLabel: String?, prim: JsonPrimitive, depth: Int) {
    val color = jsonValueColor(prim)
    val text = if (prim.isString) "\"${prim.content}\"" else prim.content
    JsonLeafText(keyLabel, text, depth, color)
}

@Composable
private fun JsonLeafText(keyLabel: String?, value: String, depth: Int, color: Color) {
    Row(modifier = Modifier.padding(start = (depth * 10).dp, top = 1.dp)) {
        if (keyLabel != null) {
            Text(
                text = "$keyLabel: ",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 运行总结卡 & 步骤标记
// ═══════════════════════════════════════════════════════════════════════

/** 运行总结卡：Complete 事件的流水线收尾可视化。 */
@Composable
internal fun RunSummaryCard(summary: AgentUiMessage.RunSummary) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "任务完成",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatDuration(summary.totalDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryStatChip("迭代 ${summary.totalIterations}")
                SummaryStatChip("工具调用 ${summary.totalToolCalls}")
            }

            if (summary.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = summary.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStatChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Plan 模式步骤标记：流水线分隔卡。 */
@Composable
internal fun StepMarkerCard(marker: AgentUiMessage.StepMarker) {
    val accent = MaterialTheme.colorScheme.secondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(accent, CircleShape)
        )
        Icon(
            imageVector = Icons.Default.Flag,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = "步骤 ${marker.stepIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            text = marker.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
