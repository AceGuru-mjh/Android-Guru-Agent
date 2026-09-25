package com.apex.agent.core.codetools.diagnostics.providers

import com.apex.agent.core.codetools.diagnostics.Diagnostic
import com.apex.agent.core.codetools.diagnostics.DiagnosticContext
import com.apex.agent.core.codetools.diagnostics.DiagnosticProvider
import com.apex.agent.core.codetools.diagnostics.Severity
import com.apex.agent.core.codetools.diagnostics.fileExtensionOf
import java.io.File

/**
 * # Markdown 链接提供器
 *
 * 检查行内链接「文本 → (目标)」中相对目标的存在性：外链（http、https、ftp、
 * mailto 等带协议前缀的目标）与页内锚点（# 开头）不做网络与文件 IO，直接
 * 跳过；相对路径以 [context.file] 所在目录为基准解析，不存在则报
 * md.dead-link（WARNING 级）。目标上的锚点与查询串（#…、?…）在解析前剥除。
 *
 * 误报防护：跳过围栏代码块（``` 或 ~~~ 行首围栏切换）与行内代码区段（反引号
 * 配对区间）里的链接语法 —— 文档中展示 Markdown 语法的示例不是真实链接。
 *
 * 前置条件：[DiagnosticContext] 的磁盘文件必须存在且可读（引擎在无磁盘对应
 * 物时会合成仅含路径的上下文，此时无法判定相对基准，整体跳过）。最多检查
 * 500 个链接。
 */
class MarkdownLinkProvider : DiagnosticProvider {

    override val id: String = "md-links"

    override fun supports(filePath: String): Boolean = fileExtensionOf(filePath) in SUPPORTED

    override fun diagnose(content: String, filePath: String, context: DiagnosticContext): List<Diagnostic> {
        if (content.isEmpty()) return emptyList()
        // 磁盘锚点校验：无磁盘对应物（或不可读、非文件）时整体跳过
        val anchor = context.file
        if (!anchor.isFile || !anchor.canRead()) return emptyList()
        val base = anchor.parentFile ?: return emptyList()

        val findings = mutableListOf<Diagnostic>()
        var scanned = 0
        var inFence = false

        for ((idx, line) in content.lineSequence().withIndex()) {
            val lineNo = idx + 1
            // 围栏代码块切换（行首 ``` 或 ~~~，容忍缩进）：块内是展示文本
            val trimmed = line.trimStart(' ', '\t')
            if (trimmed.startsWith(FENCE_BACKTICK) || trimmed.startsWith(FENCE_TILDE)) {
                inFence = !inFence
                continue
            }
            if (inFence) continue

            val codeSpans = backtickSpans(line)
            for (m in LINK_PATTERN.findAll(line)) {
                if (scanned >= MAX_LINKS) return findings
                if (codeSpans.any { m.range.first in it }) continue
                scanned++
                val target = m.groupValues[2]
                if (isSkippedTarget(target)) continue
                // 剥除锚点与查询串后再做文件存在性判断
                val relative = target.substringBefore('#').substringBefore('?')
                if (relative.isEmpty()) continue
                if (!File(base, relative).exists()) {
                    findings += Diagnostic(
                        lineNo, m.range.first + 1, Severity.WARNING, CODE,
                        "相对链接失效：$target（在 ${base.name} 目录下不存在）"
                    )
                }
            }
        }
        return findings
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    /** 行内代码区段（反引号配对区间）—— 区间内的链接语法是展示文本。 */
    private fun backtickSpans(line: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var start = -1
        for (i in line.indices) {
            if (line[i] == '`') {
                if (start < 0) {
                    start = i
                } else {
                    spans += start..i
                    start = -1
                }
            }
        }
        return spans
    }

    /** 外链与锚点不检查：协议前缀（http、https、ftp、mailto、tel 等）或 # 开头。 */
    private fun isSkippedTarget(target: String): Boolean =
        target.startsWith("#") || SCHEME_PATTERN.containsMatchIn(target)

    private companion object {
        const val CODE = "md.dead-link"
        const val MAX_LINKS = 500
        const val FENCE_BACKTICK = "```"
        const val FENCE_TILDE = "~~~"
        val SUPPORTED = setOf("md", "markdown")

        /** 行内链接：[文本](目标)，目标不含空白与右括号（够用即可）。 */
        val LINK_PATTERN = Regex("""\[([^\]\[]*)\]\(([^)\s]+)[^)]*\)""")

        /** 行首协议前缀（scheme:）—— 有协议的目标按外链处理，不做文件 IO。 */
        val SCHEME_PATTERN = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    }
}
