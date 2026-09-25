package com.apex.agent.core.codetools.diagnostics

import com.apex.agent.core.codetools.diagnostics.providers.BracketBalanceProvider
import com.apex.agent.core.codetools.diagnostics.providers.IndentConsistencyProvider
import com.apex.agent.core.codetools.diagnostics.providers.JsonDiagnosticProvider
import com.apex.agent.core.codetools.diagnostics.providers.MarkdownLinkProvider
import com.apex.agent.core.codetools.diagnostics.providers.XmlDiagnosticProvider
import java.io.File

/**
 * 诊断严重级别。
 *
 * - [ERROR]：几乎确定的语法或结构问题（JSON 解析失败、括号不配对等）；
 * - [WARNING]：可疑但需结合上下文确认（缩进异常、失效链接等）。
 */
enum class Severity { ERROR, WARNING }

/**
 * 单条诊断发现。
 *
 * @param line 1-based 行号；0 表示文件级（无法定位到具体行）
 * @param column 1-based 列号；0 表示未知
 * @param severity 严重级别
 * @param code 提供器内唯一的错误码（如 json.syntax、bracket.unbalanced）
 * @param message 人类可读的中文描述（直接回注给模型）
 */
data class Diagnostic(
    val line: Int,
    val column: Int,
    val severity: Severity,
    val code: String,
    val message: String
)

/**
 * 提供器执行上下文：携带诊断对象在磁盘上的文件本体，供需要解析相对路径的
 * 提供器（如 Markdown 链接检查）以该文件所在目录为基准做存在性判断。
 */
data class DiagnosticContext(val file: File)

/**
 * # 诊断提供器 SPI（Issue #148）
 *
 * 进程内轻量诊断引擎的扩展点。Android 端侧跑真实 LSP 服务器（tsserver、
 * jdtls 等）不现实，本引擎以"编辑后立即回注、即错即修"为目标，约束：
 *
 * - [supports] 只按文件路径（扩展名或文件名）判断，不做任何 IO；
 * - [diagnose] 必须是快速纯扫描（无网络、无子进程）；置信度不足时宁可
 *   漏报也不误报 —— 一条误报会浪费模型一整轮修复；
 * - 单个提供器抛出的异常由 [CodeDiagnostics] 统一兜底，不影响其他提供器。
 */
interface DiagnosticProvider {

    /** 提供器标识（如 json、xml、bracket）。 */
    val id: String

    /** 是否负责该文件（按扩展名或文件名判断，不做 IO）。 */
    fun supports(filePath: String): Boolean

    /**
     * 对文件内容做诊断。
     *
     * @param content 文件全文（引擎保证非空）
     * @param filePath 工作区相对路径（扩展名分派与渲染用）
     * @param context 执行上下文（磁盘文件本体；无磁盘对应物时由引擎合成，
     *   需要磁盘文件的提供器应自行校验后跳过）
     */
    fun diagnose(content: String, filePath: String, context: DiagnosticContext): List<Diagnostic>
}

/**
 * # 进程内诊断引擎（Issue #148 核心）
 *
 * 聚合全部 [DiagnosticProvider]，把"编辑后想知道有没有写错"从"跑一次构建"
 * 降为毫秒级本地扫描：
 *
 * - [diagnose] 收集所有 supports 命中的提供器的发现，按行列排序后截断到
 *   maxFindings（防止病态文件刷屏）；
 * - [render] 渲染为中文回注文本块，每条形如「路径:行:列 [级别 错误码] 消息」；
 * - [renderFindings]（模块内）一步完成诊断、渲染与截断标注，是 code_edit、
 *   code_write 与 code_check 回注的统一入口。
 *
 * 设计铁律：诊断永远不能让编辑失败 —— 引擎对各提供器逐一 runCatching，
 * 外层调用（[appendDiagnostics]）再兜一层。
 */
class CodeDiagnostics(
    private val providers: List<DiagnosticProvider> = defaultProviders()
) {

    /**
     * 诊断并返回（按行列排序，截断到 [maxFindings]）。
     *
     * @param content 文件全文
     * @param filePath 工作区相对路径（扩展名分派与渲染）
     * @param maxFindings 返回条数上限
     * @param context 执行上下文；null 时合成一个仅含路径的上下文（需要磁盘
     *   文件的提供器会因文件不存在而自行跳过）
     */
    fun diagnose(
        content: String,
        filePath: String,
        maxFindings: Int = DEFAULT_MAX_FINDINGS,
        context: DiagnosticContext? = null
    ): List<Diagnostic> =
        collect(content, filePath, context).take(maxFindings.coerceAtLeast(1))

    /**
     * 渲染回注文本块；空列表返回空字符串。
     *
     * @param diags 已收集的发现（通常是 diagnose 的结果）
     * @param filePath 展示用路径
     * @param overflow 因截断未纳入 diags 的数量（追加「另有 N 项未显示」标注）
     */
    fun render(diags: List<Diagnostic>, filePath: String, overflow: Int = 0): String {
        if (diags.isEmpty() && overflow <= 0) return ""
        return buildString {
            append("⚠️ 诊断（").append(diags.size).append(" 项）:")
            for (d in diags) {
                append("\n  ").append(locationOf(filePath, d))
                append(" [").append(d.severity.name).append(' ').append(d.code).append("] ")
                append(d.message)
            }
            if (overflow > 0) append("\n  ……另有 ").append(overflow).append(" 项未显示")
        }
    }

    /**
     * 诊断 + 渲染一步到位（code_edit / code_write / code_check 的统一回注入口）。
     * 无发现返回空字符串；超出上限的发现以「另有 N 项未显示」标注。
     */
    internal fun renderFindings(
        content: String,
        filePath: String,
        file: File? = null,
        maxFindings: Int = DEFAULT_MAX_FINDINGS
    ): String {
        val all = collect(content, filePath, file?.let { DiagnosticContext(it) })
        if (all.isEmpty()) return ""
        val cap = maxFindings.coerceAtLeast(1)
        val shown = all.take(cap)
        return render(shown, filePath, all.size - shown.size)
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private fun collect(content: String, filePath: String, context: DiagnosticContext?): List<Diagnostic> {
        if (content.isEmpty()) return emptyList()
        val ctx = context ?: DiagnosticContext(File(filePath))
        val out = mutableListOf<Diagnostic>()
        for (provider in providers) {
            val supported = runCatching { provider.supports(filePath) }.getOrDefault(false)
            if (!supported) continue
            out += runCatching { provider.diagnose(content, filePath, ctx) }
                .getOrDefault(emptyList())
        }
        // 行列升序；同位置 ERROR 优先（先修阻塞项）
        return out.sortedWith(
            compareBy({ it.line }, { it.column }, { it.severity.ordinal }, { it.code })
        )
    }

    private fun locationOf(filePath: String, d: Diagnostic): String = when {
        d.line > 0 && d.column > 0 -> "$filePath:${d.line}:${d.column}"
        d.line > 0 -> "$filePath:${d.line}"
        else -> filePath
    }

    companion object {

        /** 默认回注条数上限（防病态文件刷屏）。 */
        const val DEFAULT_MAX_FINDINGS = 20

        /** 内置提供器集合（可被 DI 覆盖或扩展）。 */
        fun defaultProviders(): List<DiagnosticProvider> = listOf(
            BracketBalanceProvider(),
            JsonDiagnosticProvider(),
            XmlDiagnosticProvider(),
            IndentConsistencyProvider(),
            MarkdownLinkProvider()
        )
    }
}

/**
 * 模块内共享：取小写扩展名（无扩展名返回空串）。
 *
 * 注意 `substringAfterLast` 的第二参是「分隔符缺失时的返回值」——无 '/'
 * 的裸文件名（如 "Test.kt"，恰是工作区根下文件的常态形态）必须回退为
 * 原串而不是空串，否则扩展名解析永远为空、全部提供器静默失联。
 */
internal fun fileExtensionOf(filePath: String): String =
    filePath.substringAfterLast('/', filePath).substringAfterLast('.', "").lowercase()

/**
 * 模块内共享：编辑/写成功后的诊断回注。
 *
 * 诊断实例为 null（未启用）或内容为空时原样返回；否则对写入后的完整内容跑
 * 诊断，有发现时以空行分隔追加渲染块（含工作区相对路径定位）。全程
 * runCatching —— 诊断失败绝不能让编辑失败。
 */
internal fun appendDiagnostics(
    output: String,
    content: String,
    diagnostics: CodeDiagnostics?,
    root: File,
    file: File
): String {
    if (diagnostics == null || content.isEmpty()) return output
    val relPath = runCatching {
        val rootPath = root.canonicalPath.trimEnd('/')
        val filePath = file.canonicalPath
        if (filePath == rootPath) "/" else filePath.removePrefix("$rootPath/")
    }.getOrDefault(file.name)
    val block = runCatching { diagnostics.renderFindings(content, relPath, file) }.getOrNull()
    if (block.isNullOrEmpty()) return output
    return output + "\n\n" + block
}
