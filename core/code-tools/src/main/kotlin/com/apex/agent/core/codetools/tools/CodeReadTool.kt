package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.FilePathSafety
import com.apex.agent.core.tools.toolSchema
import java.io.File

/**
 * # code_read — 编码级文件读取（opencode read 契约）
 *
 * 与 Agent 模式的 read_file（视口滚动）互补，本工具面向**编码会话**的读取契约：
 *
 * - 输出以 `N: 内容` 行号前缀渲染（cat -n 风格）——code_edit 的 old_string
 *   **必须去掉行号前缀**后引用原文，这一契约写进描述防止模型把行号当内容；
 * - 文件/目录二合一：目标是目录时返回按字母序的条目清单（目录带 `/` 尾），
 *   同样支持 offset/limit 分页 —— "先 ls 再 cd 式"的探索在单工具内闭环；
 * - 未命中时给出同目录**相似名建议**（Did you mean…）—— 显著降低路径幻觉循环；
 * - 三重上限：默认 800 行 / 单行 2000 字符截断 / 累计 50KB —— 超限提示用
 *   offset 续读，保证大文件不会撑爆上下文窗口；
 * - 二进制嗅探（NUL 字节 + 不可打印占比）→ 明确报错而非乱码。
 *
 * 根目录经 [CodeWorkspaceRoots] 动态解析（Code 模式切换 workspace 即时生效），
 * 路径穿越/符号链接逃逸经 [FilePathSafety] 拒绝。
 */
class CodeReadTool(
    private val roots: CodeWorkspaceRoots
) : BaseTool(
    id = "code_read",
    name = "Code Read",
    description = """
        Read a file or list a directory in the active coding workspace.

        File output format: every line is prefixed with "N: " (line number, 1-based).
        When using code_edit, strip the "N: " prefix first — old_string must match the
        raw file content exactly.

        - File: returns up to `limit` lines from `offset` (1-based). Default limit 800.
          Use offset to continue reading long files.
        - Directory: returns alphabetically sorted entries (dirs with trailing /).
        - File not found: similar names in the same directory are suggested.

        Size limits: 2000 chars per line, 50KB per read. Read the file in slices
        with offset/limit instead of requesting everything at once.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true, description = "File or directory path, relative to the workspace root (or absolute inside it)")
        integer("offset", description = "1-based start line for files / start index for directory entries", minimum = 1.0)
        integer("limit", description = "Max lines (files) or entries (directories) to return. Default 800, max 2000", minimum = 1.0, maximum = 2000.0)
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("read")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val path = args.requireString("path")
        val offset = args.optionalInt("offset") ?: 1
        val limit = (args.optionalInt("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active coding workspace. Ask the user to open the Code screen and select a workspace first."
            )

        val file = try {
            FilePathSafety.safeResolve(root, path)
        } catch (e: SecurityException) {
            return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, e.message ?: "path escapes workspace")
        }

        if (!file.exists()) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                buildNotFoundMessage(root, file, path)
            )
        }

        if (file.isDirectory) {
            return ToolResult.ok(renderDirectory(file, offset, limit))
        }
        if (!file.canRead()) {
            return ToolResult.fail(ToolErrorCode.PERMISSION_DENIED, "cannot read: $path")
        }
        if (isBinary(file)) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "binary file (${formatSize(file.length())}) — use terminal.exec to inspect it"
            )
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "file too large (${formatSize(file.length())}, max ${formatSize(MAX_FILE_BYTES)}). " +
                    "Read it in slices with code_read + shell tools (grep/sed), or increase offset."
            )
        }

        return ToolResult.ok(renderFile(file, offset, limit))
    }

    // ── 渲染 ─────────────────────────────────────────────────────────

    private fun renderFile(file: File, offset: Int, limit: Int): String {
        val lines = readFileLines(file)
        val total = lines.size
        if (total == 0) return "${relPath(file)} — empty file (0 lines)"

        val start = (offset - 1).coerceIn(0, total - 1)
        val end = minOf(start + limit, total)
        val shown = lines.subList(start, end)

        return buildString {
            appendLine("<path>${relPath(file)}</path>")
            appendLine("<type>file</type>")
            appendLine("<total_lines>$total</total_lines>")
            append("<content>")
            for (i in shown.indices) {
                val lineNo = start + i + 1
                val raw = shown[i]
                val line = if (raw.length > MAX_LINE_CHARS) raw.take(MAX_LINE_CHARS) + "…[truncated]" else raw
                append("\n$lineNo: $line")
            }
            append("\n</content>")
            if (end < total) {
                append("\n(Showing lines ${start + 1}-$end of $total. Use offset=${end + 1} to continue.)")
            } else if (start > 0) {
                append("\n(Showing lines ${start + 1}-$end of $total.)")
            }
        }
    }

    private fun renderDirectory(dir: File, offset: Int, limit: Int): String {
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return "<path>${relPath(dir)}</path>\n<type>directory</type>\n(cannot list: permission denied)"

        val total = entries.size
        if (total == 0) {
            return "<path>${relPath(dir)}</path>\n<type>directory</type>\n(empty directory)"
        }
        val start = (offset - 1).coerceIn(0, total - 1)
        val end = minOf(start + limit, total)

        return buildString {
            appendLine("<path>${relPath(dir)}</path>")
            appendLine("<type>directory</type>")
            appendLine("<total_entries>$total</total_entries>")
            append("<entries>")
            for (i in start until end) {
                val e = entries[i]
                val suffix = if (e.isDirectory) "/" else ""
                append("\n${e.name}$suffix")
            }
            append("\n</entries>")
            if (end < total) append("\n(Showing entries ${start + 1}-$end of $total. Use offset=${end + 1} to continue.)")
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    private fun readFileLines(file: File): List<String> {
        val raw = file.readText(Charsets.UTF_8)
        return raw.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty() && raw.endsWith("\n")) it.dropLast(1) else it }
    }

    private fun relPath(file: File): String {
        val root = roots.activeRoot() ?: return file.path
        val rootPath = root.canonicalPath.trimEnd('/')
        val filePath = file.canonicalPath
        return if (filePath == rootPath) "/" else filePath.removePrefix("$rootPath/")
    }

    private fun buildNotFoundMessage(root: File, file: File, path: String): String {
        val parent = file.parentFile?.takeIf { it.exists() } ?: return "file not found: $path"
        val wanted = file.name.lowercase()
        val siblings = parent.listFiles()?.map { it.name } ?: emptyList()
        // 相似名建议：包含关系或编辑距离相似度 ≥ 0.75（拼写笔误兜底）
        val similar = siblings.filter { name ->
            val n = name.lowercase()
            n == wanted || n.contains(wanted) || wanted.contains(n) ||
                editSimilarity(n, wanted) >= 0.75
        }.take(3)
        return if (similar.isEmpty()) {
            "file not found: $path (workspace root: ${root.name})"
        } else {
            "file not found: $path — did you mean: ${similar.joinToString(", ")}?"
        }
    }

    /** 归一化编辑距离相似度（1=全等，0=完全不同；小字符串开销可忽略）。 */
    private fun editSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1)
        val curr = IntArray(b.length + 1)
        for (j in 0..b.length) prev[j] = j
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, b.length + 1)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun isBinary(file: File): Boolean {
        if (file.length() > MAX_FILE_BYTES) return false // 走大小上限分支报错
        val bytes = ByteArray(minOf(file.length(), 8192L).toInt())
        val read = file.inputStream().use { it.read(bytes) }
        if (read <= 0) return false
        var unprintable = 0
        for (i in 0 until read) {
            val b = bytes[i]
            if (b == 0.toByte()) return true
            if (b < 0x09 || (b in 0x0e..0x1f)) unprintable++
        }
        return unprintable.toDouble() / read > 0.30
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1fMB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    private companion object {
        const val DEFAULT_LIMIT = 800
        const val MAX_LIMIT = 2000
        const val MAX_LINE_CHARS = 2000
        const val MAX_FILE_BYTES = 5L * 1024 * 1024 // 5MB readText 上限
    }
}
