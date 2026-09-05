package com.apex.agent.core.codetools.diff

/**
 * 单条编辑操作（search-replace / insert / delete）。
 * 用于 [com.apex.agent.core.codetools.fs.CodeWorkspaceFileSystem.edit]。
 */
data class EditOperation(
    val search: String,
    val replace: String,
    val insertAfterLine: Int? = null
)

/**
 * 一次 diff 的完整结果（Spec §32）。
 *
 * @param hunks 变更块列表
 * @param addedLines 新增行数
 * @param removedLines 删除行数
 * @param modifiedFiles 涉及文件数（单文件 diff = 1）
 * @param unifiedPatch unified diff 格式文本，供 UI / git 复用
 */
data class DiffResult(
    val hunks: List<DiffHunk>,
    val addedLines: Int,
    val removedLines: Int,
    val modifiedFiles: Int,
    val unifiedPatch: String
) {
    val isClean: Boolean get() = hunks.isEmpty()
    val summary: String get() = "+$addedLines −$removedLines (${modifiedFiles} file${if (modifiedFiles != 1) "s" else ""})"
}

/**
 * 一个变更 hunk：连续的增删行块。
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

/**
 * diff 中的一行。
 */
sealed class DiffLine(val text: String) {
    class Context(text: String) : DiffLine(text)
    class Added(text: String) : DiffLine(text)
    class Removed(text: String) : DiffLine(text)
}
