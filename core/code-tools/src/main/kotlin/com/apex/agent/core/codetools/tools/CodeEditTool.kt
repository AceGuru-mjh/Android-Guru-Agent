package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.diagnostics.CodeDiagnostics
import com.apex.agent.core.codetools.diagnostics.appendDiagnostics
import com.apex.agent.core.codetools.edit.FuzzyReplacer
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.FilePathSafety
import com.apex.agent.core.tools.toolSchema
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * # code_edit — 精确替换式编辑（opencode edit 契约）
 *
 * 语义：`old_string` 在文件中**唯一匹配**（或多处匹配 + `replace_all`），替换为
 * `new_string`。这是端侧编码 agent 最重要的工具 —— 它的成功率直接决定编码循环
 * 的轮次成本。
 *
 * 核心机制（[FuzzyReplacer]）：
 * - 精确匹配失败后依次尝试 6 级模糊回退（行 trim / 块锚点+Levenshtein / 空白
 *   归一 / 缩进平移 / 边界 trim / replaceAll），修复模型"记个大概"的偏差；
 * - disproportionate 护栏：模糊命中 span 与 old_string 尺寸失衡（行 ≥ max(原+3, 2×)、
 *   字符 ≥ 4×）→ 拒绝并要求重读文件 —— 宁可多一轮，不可错改；
 * - `old_string` 为空且文件不存在 → 允许创建新文件（write 语义兜底）。
 *
 * 工程细节：CRLF/LF 检测与还原、BOM 保留、按文件路径互斥锁（防并发编辑竞态）、
 * 统一 diff 摘要回显（模型自我验证改对了没有）、编辑成功后的即时诊断回注
 * （注入 [diagnostics] 时，对编辑后的完整内容追加「⚠️ 诊断」块）。
 */
class CodeEditTool(
    private val roots: CodeWorkspaceRoots,
    private val diagnostics: CodeDiagnostics? = null
) : BaseTool(
    id = "code_edit",
    name = "Code Edit",
    description = """
        Edit a file by replacing old_string with new_string (exact-match with smart fallbacks).

        Rules:
        - old_string must appear in the file; include 2-3 surrounding lines when the
          target text appears multiple times, or set replace_all=true.
        - old_string must match the raw file content (code_read shows "N: " line
          prefixes — strip them before writing old_string).
        - Leave old_string empty AND point to a non-existing file to create it.
        - After editing, verify with code_read or run the project build/tests.

        Output includes a unified diff of the change — self-check that the edit
        landed where you intended before moving on.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true, description = "File path relative to the workspace root")
        string("old_string", required = true, description = "Exact text to replace (empty + new file = create file)")
        string("new_string", required = true, description = "Replacement text (must differ from old_string)")
        boolean("replace_all", description = "Replace every occurrence (default false)")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("code")
        tag("edit")
        annotations(com.apex.agent.core.tools.ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false
        ))
    }

    /** 按路径的互斥锁：同文件并发编辑防撕裂（跨线程安全的 ReentrantLock 池）。 */
    private val locks = ConcurrentHashMap<String, Any>()

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val path = args.requireString("path")
        val oldString = args.requireString("old_string")
        val newString = args.requireString("new_string")
        val replaceAll = args.booleanWithDefault("replace_all", false)

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

        val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
        synchronized(lock) {
            return executeLocked(root, file, path, oldString, newString, replaceAll)
        }
    }

    private fun executeLocked(
        root: File,
        file: File,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean
    ): ToolResult {
        // 空 old_string = 创建新文件（文件必须不存在 —— 已存在请用正常替换或 code_write）
        if (oldString.isEmpty()) {
            if (file.exists()) {
                return ToolResult.fail(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "file already exists: $path — provide a non-empty old_string to edit it (or use code_write to overwrite)"
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(newString, Charsets.UTF_8)
            return ToolResult.ok(
                appendDiagnostics(
                    "✅ created $path (${newString.count { it == '\n' } + 1} lines, ${newString.length} chars)",
                    newString,
                    diagnostics,
                    root,
                    file
                )
            )
        }

        if (!file.exists()) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "file not found: $path — create it first (empty old_string) or check the path"
            )
        }
        if (!file.canRead() || !file.canWrite()) {
            return ToolResult.fail(ToolErrorCode.PERMISSION_DENIED, "cannot read/write: $path")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "file too large (${file.length()} bytes, max $MAX_FILE_BYTES)"
            )
        }

        // 读原文：保留 BOM / 探测 CRLF
        val raw = file.readText(Charsets.UTF_8)
        val bom = raw.startsWith(BOM)
        val body = if (bom) raw.substring(BOM.length) else raw
        val crlf = body.contains("\r\n")

        // 归一到 LF 做匹配，写出时按原行尾风格还原
        val normalized = if (crlf) body.replace("\r\n", "\n") else body
        val normalizedOld = if (crlf) oldString.replace("\r\n", "\n") else oldString
        val normalizedNew = if (crlf) newString.replace("\r\n", "\n") else newString

        val outcome = FuzzyReplacer.replace(normalized, normalizedOld, normalizedNew, replaceAll)
        return when (outcome) {
            is FuzzyReplacer.Outcome.NotFound -> ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT, outcome.reason
            )
            is FuzzyReplacer.Outcome.Ambiguous -> ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "old_string matches ${outcome.occurrences} locations in $path. " +
                    "Add surrounding lines to make it unique, or set replace_all=true."
            )
            is FuzzyReplacer.Outcome.Replaced -> {
                val newBody = if (crlf) outcome.newContent.replace("\n", "\r\n") else outcome.newContent
                val output = (if (bom) BOM else "") + newBody
                file.writeText(output, Charsets.UTF_8)
                val diff = UnifiedDiff.mini(body, outcome.newContent, path, 6)
                ToolResult.ok(
                    appendDiagnostics(
                        "✅ edited $path (lines ${outcome.startLine}-${outcome.endLine}, strategy=${outcome.strategy})\n$diff",
                        output,
                        diagnostics,
                        root,
                        file
                    )
                )
            }
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        const val BOM = "﻿"
    }
}

/**
 * 极简统一 diff（行级 LCS）—— 供编辑结果回显。上下文行数受限，总行数钳制，
 * 大改动手动折叠为统计行。
 */
internal object UnifiedDiff {

    fun mini(before: String, after: String, path: String, contextLines: Int, maxDiffLines: Int = 60): String {
        val a = before.split('\n')
        val b = after.split('\n')
        val lcs = lcsTable(a, b)

        // 回溯产生编辑脚本（保持顺序）
        val ops = mutableListOf<Op>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> {
                    ops += Op.Equal(a[i - 1]); i--; j--
                }
                j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
                    ops += Op.Add(b[j - 1]); j--
                }
                else -> {
                    ops += Op.Remove(a[i - 1]); i--
                }
            }
        }
        ops.reverse()
        if (ops.all { it is Op.Equal }) return "(no textual change)"

        // 上下文折叠：仅保留变更块 ±context
        val changed = ops.withIndex().filter { it.value !is Op.Equal }.map { it.index }
        if (changed.isEmpty()) return "(no textual change)"
        val keep = HashSet<Int>()
        for (idx in changed) {
            for (c in (idx - contextLines)..(idx + contextLines)) {
                if (c in ops.indices) keep += c
            }
        }

        val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
        var shown = 0
        var idx = 0
        var skipped = 0
        while (idx < ops.size) {
            if (idx !in keep) {
                skipped++; idx++
                continue
            }
            if (skipped > 0) {
                sb.appendLine("@@ ... ($skipped unchanged lines) ...")
                skipped = 0
            }
            if (shown >= maxDiffLines) {
                sb.appendLine("@@ (diff truncated after $maxDiffLines lines)")
                break
            }
            when (val op = ops[idx]) {
                is Op.Equal -> sb.appendLine(" ${op.line}")
                is Op.Add -> sb.appendLine("+${op.line}")
                is Op.Remove -> sb.appendLine("-${op.line}")
            }
            shown++
            idx++
        }
        val adds = ops.count { it is Op.Add }
        val removes = ops.count { it is Op.Remove }
        sb.appendLine("(${adds} added, ${removes} removed)")
        return sb.toString().trimEnd()
    }

    private sealed interface Op {
        data class Equal(val line: String) : Op
        data class Add(val line: String) : Op
        data class Remove(val line: String) : Op
    }

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp
    }
}
