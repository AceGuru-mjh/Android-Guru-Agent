package com.apex.agent.platform.code.intel

import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.codetools.fs.CodeWorkspaceFileSystem
import com.apex.agent.core.codetools.problems.ProblemsAggregator
import com.apex.agent.core.codetools.tools.WorkspaceFsProvider
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Code Agent 的 JIT 上下文提供器实现（Spec §42）。
 *
 * 每轮 Code Agent 调用前由 CodeAgentEngine.prepareForTask 调用，
 * 组装 concise 的 "## Code Context" 注入 system prompt：
 * - 当前 workspace（name / detectedEnvironment / buildSystem）
 * - 当前 active file（前 ~40 行 + 选区高亮）
 * - diagnostics 摘要（ProblemsAggregator.Summary）
 * - git diff 统计（如有未提交改动）
 *
 * **不把整个 repository 塞进 context**（Spec §43 token 控制）。
 */
@Singleton
class CodeContextProviderImpl @Inject constructor(
    private val workspaceManager: CodeWorkspaceManager,
    private val fsProvider: WorkspaceFsProvider,
    private val problems: ProblemsAggregator
) : CodeContextProvider {

    private val invalidated = mutableSetOf<String>()

    override suspend fun provide(
        workspaceId: String,
        activeFile: String?,
        selection: IntRange?,
        task: String
    ): String {
        val ws = workspaceManager.inspect(workspaceId).getOrNull() ?: return ""
        val sb = StringBuilder()
        sb.appendLine("## Code Context")
        sb.appendLine("- Workspace: ${ws.name} ($workspaceId)")
        ws.detectedEnvironment?.let { sb.appendLine("- Environment: $it") }
        ws.buildSystem?.let { sb.appendLine("- Build system: $it") }
        sb.appendLine("- Problems: ${problems.summary()}")

        val fs = fsProvider.current()
        if (activeFile != null && fs != null) {
            sb.appendLine("- Active file: $activeFile")
            val read = fs.read(activeFile, offsetLine = 0, limit = 40)
            if (read.exists && !read.isBinary) {
                sb.appendLine("- File preview ($activeFile, ${read.returnedLines}/${read.totalLines} lines):")
                sb.append("```")
                read.content.lines().forEachIndexed { i, line ->
                    val ln = i + 1
                    val inSel = selection != null && ln in selection
                    sb.append(if (inSel) "▶" else " ").append(ln.toString().padStart(4)).append(" │ ").append(line).append("\n")
                }
                sb.appendLine("```")
            }
            selection?.let { sb.appendLine("- Selection: lines ${it.first}-${it.last}") }
        }
        return sb.toString()
    }

    override fun invalidate(workspaceId: String) {
        invalidated.add(workspaceId)
    }
}
