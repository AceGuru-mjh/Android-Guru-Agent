package com.apex.agent.core.codetools.problems

/**
 * 统一的问题模型（Spec §23）。
 *
 * 聚合 LSP Diagnostics、Build 输出、Test 输出三路来源，统一成 [Problem]。
 * 由 [ProblemsAggregator] 合并去重。
 */
data class Problem(
    val file: String,
    val line: Int,          // 1-based; 0 if unknown
    val column: Int,        // 1-based; 0 if unknown
    val endLine: Int = line,
    val endColumn: Int = column,
    val severity: Severity,
    val message: String,
    val source: Source,
    val code: String? = null,       // LSP code / compiler error code
    val sourceSpecific: String? = null // e.g. "clangd", "gradle", "pytest"
) {
    enum class Severity { ERROR, WARNING, INFO, HINT }
    enum class Source { LSP, BUILD, TEST }

    val locationString: String get() = "$file:$line" + (if (column > 0) ":$column" else "")
}

/**
 * Diagnostics 聚合器接口。实现见 :platform:code-intelligence 的
 * [com.apex.agent.platform.code.intel.ProblemsAggregatorImpl]，合并：
 * - LSP `textDocument/publishDiagnostics` 推送
 * - build/test runner 解析的 stderr
 */
interface ProblemsAggregator {
    /** 全量替换某文件的问题集（LSP 推送语义）。 */
    fun setForFile(file: String, problems: List<Problem>)
    /** 增量追加（build/test 语义）。 */
    fun addAll(problems: List<Problem>)
    /** 清空全部（切换 workspace / 关闭项目）。 */
    fun clear()
    /** 清空某文件（编辑触发重发 diagnostics）。 */
    fun clearFile(file: String)
    /** 当前全部问题。 */
    fun all(): List<Problem>
    /** 按文件分组。 */
    fun byFile(): Map<String, List<Problem>>
    /** 按严重度统计。 */
    fun summary(): Summary

    data class Summary(val errors: Int, val warnings: Int, val infos: Int, val total: Int) {
        val isClean: Boolean get() = total == 0
        override fun toString(): String = "❌$errors ⚠$warnings ℹ$infos / $total"
    }
}

/**
 * 纯 JVM 默认实现（线程安全），用于单测和作为 Android 实现的 fallback。
 * Android 实现可在此基础上加 Room 持久化（Spec §12 恢复链路）。
 */
class InMemoryProblemsAggregator : ProblemsAggregator {
    private val store = LinkedHashMap<String, MutableList<Problem>>()
    @Synchronized override fun setForFile(file: String, problems: List<Problem>) { store[file] = problems.toMutableList() }
    @Synchronized override fun addAll(problems: List<Problem>) { problems.forEach { store.getOrPut(it.file) { mutableListOf() }.add(it) } }
    @Synchronized override fun clear() { store.clear() }
    @Synchronized override fun clearFile(file: String) { store.remove(file) }
    @Synchronized override fun all(): List<Problem> = store.values.flatten()
    @Synchronized override fun byFile(): Map<String, List<Problem>> = store.mapValues { it.value.toList() }
    @Synchronized override fun summary(): ProblemsAggregator.Summary {
        val all = all()
        return ProblemsAggregator.Summary(
            errors = all.count { it.severity == Problem.Severity.ERROR },
            warnings = all.count { it.severity == Problem.Severity.WARNING },
            infos = all.count { it.severity == Problem.Severity.INFO || it.severity == Problem.Severity.HINT },
            total = all.size
        )
    }
}
