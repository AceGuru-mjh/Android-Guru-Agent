package com.apex.agent.platform.code.intel

import com.apex.agent.core.codetools.problems.InMemoryProblemsAggregator
import com.apex.agent.core.codetools.problems.Problem
import com.apex.agent.core.codetools.problems.ProblemsAggregator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 平台 ProblemsAggregator 实现（Spec §23）。
 *
 * v1：委托 [InMemoryProblemsAggregator]（纯 JVM，线程安全）。
 * 数据源：
 * - LSP publishDiagnostics → [setForFile]（CodeViewModel 收到 LSP notification 后调）
 * - build/test runner 输出解析 → [addAll]（CodeBuildTool/CodeTestTool 完成后由 ViewModel 解析 stderr 后调）
 * 持久化（Spec §12 恢复链路）留 v2：加 Room 表 + workspaceId 关联，App 被杀后恢复。
 */
@Singleton
class ProblemsAggregatorImpl @Inject constructor() : ProblemsAggregator {
    private val delegate = InMemoryProblemsAggregator()
    override fun setForFile(file: String, problems: List<Problem>) = delegate.setForFile(file, problems)
    override fun addAll(problems: List<Problem>) = delegate.addAll(problems)
    override fun clear() = delegate.clear()
    override fun clearFile(file: String) = delegate.clearFile(file)
    override fun all(): List<Problem> = delegate.all()
    override fun byFile(): Map<String, List<Problem>> = delegate.byFile()
    override fun summary(): ProblemsAggregator.Summary = delegate.summary()
}

/**
 * 从 build/test 输出解析问题（Spec §23 Build/Test 来源）。
 * v1：简单匹配 `error:` / `Error` / `FAILED` / `: error:` 行 → [Problem]。
 * 复杂的多行编译器输出解析留 v2（按 buildSystem 选 parser）。
 */
object BuildOutputParser {
    fun parse(stdout: String, source: Problem.Source, sourceSpecific: String): List<Problem> {
        val problems = mutableListOf<Problem>()
        val pattern = Regex("""^(.+?):(\d+)(?::(\d+))?:\s*(error|warning):\s*(.+)$""", RegexOption.IGNORE_CASE)
        stdout.lines().forEach { line ->
            val m = pattern.find(line) ?: return@forEach
            val file = m.groupValues[1]
            val ln = m.groupValues[2].toIntOrNull() ?: return@forEach
            val col = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            val severity = when (m.groupValues[4].lowercase()) {
                "error" -> Problem.Severity.ERROR
                "warning" -> Problem.Severity.WARNING
                else -> Problem.Severity.INFO
            }
            problems.add(Problem(file = file, line = ln, column = col, severity = severity, message = m.groupValues[5], source = source, sourceSpecific = sourceSpecific))
        }
        return problems
    }
}
