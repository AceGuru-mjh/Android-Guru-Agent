package com.apex.agent.core.codetools.fs

import com.apex.agent.core.codetools.diff.CodeDiff
import com.apex.agent.core.codetools.diff.DiffResult
import com.apex.agent.core.codetools.diff.EditOperation
import java.io.File
import java.io.IOException

/**
 * Code Mode 专用 Workspace 文件系统。
 *
 * 设计原则（Spec §13/§15）：
 * - **不走 shell**。所有读写用 [java.io.File] / NIO，避免把文件操作退化成 `cat`/`sed`。
 * - **权限边界由 [basePath]（workspaceId 对应的 host 根目录）控制**，所有路径
 *   必须落在 [basePath] 之下；任何 `..` / 符号链接逃逸抛 [SecurityException]。
 * - **结构化返回**。读返回 [ReadResult]，编辑返回 [EditResult]（含 diff），
 *   搜索返回 [List]<[SearchMatch]>，方便 LLM 消费和 UI 渲染。
 * - **大文件友好**。读带窗口（offset/limit），搜索带分页 + 默认跳过
 *   `.git`/`build`/`node_modules` 等噪声目录。
 *
 * 本类是纯 JVM 实现（host 侧 [java.io.File]）。在 Android 上，workspace 根目录
 * 由 [com.apex.agent.platform.code.ws.CodeWorkspaceManager] 通过
 * [com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager] 解析为
 * `<filesDir>/linux/workspaces/<id>/`，bind 到 proot guest 的 `/workspace`。
 * guest 内的程序（git / LSP server / gradlew）看到的同一份文件，与 host 侧
 * 本类的读写保持一致。
 *
 * 路径安全模型与 `FilePathSafety` 一致，独立实现以避免跨模块可见性泄漏。
 */
class CodeWorkspaceFileSystem(
    val basePath: File
) {

    init {
        basePath.apply { mkdirs() }
    }

    // ═══ 路径安全 ═══

    /**
     * 解析 [path]（相对 [basePath] 或绝对），返回 canonical [File]。
     * @throws SecurityException 解析结果逃逸 [basePath]。
     */
    fun resolve(path: String): File = WorkspacePathSafety.safeResolve(basePath, path)

    fun exists(path: String): Boolean = try { resolve(path).exists() } catch (_: SecurityException) { false }

    // ═══ 读 ═══

    data class ReadResult(
        val path: String,
        val exists: Boolean,
        val isBinary: Boolean,
        val totalLines: Int,
        val offsetLine: Int,
        val returnedLines: Int,
        val content: String,
        val sizeBytes: Long,
        val truncated: Boolean
    ) {
        companion object {
            val NOT_FOUND = { path: String -> ReadResult(path, false, false, 0, 0, 0, "", 0, false) }
        }
    }

    /**
     * 窗口化读取文本文件。默认从第 1 行起读 80 行，最大 500 行 / 16MB。
     * 二进制文件（含 NUL 字节）拒绝读取，返回 [ReadResult.isBinary]=true。
     */
    fun read(path: String, offsetLine: Int = 0, limit: Int = 80): ReadResult {
        val file = try { resolve(path) } catch (e: SecurityException) { return ReadResult(path, false, false, 0, offsetLine, 0, "", 0, false) }
        if (!file.exists() || !file.isFile) return ReadResult(path, false, false, 0, offsetLine, 0, "", 0, false)
        if (file.length() > MAX_FILE_BYTES) return ReadResult(path, true, false, 0, offsetLine, 0,
            "Error: file too large (${file.length()} bytes, max $MAX_FILE_BYTES).", file.length(), true)

        val raw = try { file.readText() } catch (e: IOException) { return ReadResult(path, true, false, 0, offsetLine, 0, "Error: ${e.message}", file.length(), false) }
        if (raw.contains('\u0000')) return ReadResult(path, true, true, 0, offsetLine, 0, "", file.length(), false)

        val lines = raw.lines()
        val total = lines.size
        val start = offsetLine.coerceIn(0, total)
        val end = (start + limit.coerceIn(1, MAX_READ_LINES)).coerceAtMost(total)
        val slice = if (start < end) lines.subList(start, end).joinToString("\n") else ""
        return ReadResult(
            path = path, exists = true, isBinary = false, totalLines = total,
            offsetLine = start, returnedLines = end - start, content = slice,
            sizeBytes = file.length(), truncated = end < total
        )
    }

    // ═══ 写 ═══

    data class WriteResult(val path: String, val ok: Boolean, val message: String, val bytesWritten: Long, val diff: DiffResult?)

    /** 全量写入或追加。父目录自动创建。 */
    fun write(path: String, content: String, mode: WriteMode = WriteMode.WRITE): WriteResult {
        val file = try { resolve(path) } catch (e: SecurityException) { return WriteResult(path, false, e.message ?: "path error", 0, null) }
        val before = if (mode == WriteMode.WRITE && file.exists()) file.readText() else null
        try {
            file.parentFile?.mkdirs()
            when (mode) {
                WriteMode.WRITE -> file.writeText(content)
                WriteMode.APPEND -> file.appendText(content)
            }
        } catch (e: IOException) { return WriteResult(path, false, e.message ?: "io error", 0, null) }
        val diff = before?.let { CodeDiff.diff(it, content) }
        return WriteResult(path, true, "${mode.name} ok", content.encodeToByteArray().size.toLong(), diff)
    }

    enum class WriteMode { WRITE, APPEND }

    // ═══ 编辑（搜索-替换，原子） ═══

    data class EditResult(
        val path: String, val ok: Boolean, val message: String,
        val appliedOperations: List<String>, val diff: DiffResult?
    )

    /**
     * 搜索-替换块编辑。复用 [FileEditTool] 的语义（Spec §14）：
     * - 替换：`search` 精确匹配，替换为 `replace`
     * - 插入：`search` 为空 + `insertAfterLine`
     * - 删除：`search` 非空 + `replace` 为空
     * - 任意一个 search 未命中 → 整体回滚（原子）
     * - 返回 [DiffResult] 供 UI 渲染 Changes 面板
     */
    fun edit(path: String, edits: List<EditOperation>, createIfMissing: Boolean = false): EditResult {
        val file = try { resolve(path) } catch (e: SecurityException) { return EditResult(path, false, e.message ?: "path error", emptyList(), null) }
        if (!file.exists()) {
            if (!createIfMissing) return EditResult(path, false, "not found (set create_if_missing=true to create)", emptyList(), null)
            file.parentFile?.mkdirs(); file.createNewFile()
        }
        if (file.length() > MAX_FILE_BYTES) return EditResult(path, false, "file too large", emptyList(), null)

        val original = file.readText()
        var content = original
        val applied = mutableListOf<String>()

        for (edit in edits) {
            when {
                edit.search.isEmpty() && edit.insertAfterLine != null -> {
                    val lines = content.lines().toMutableList()
                    val idx = edit.insertAfterLine.coerceIn(0, lines.size)
                    edit.replace.lines().forEachIndexed { i, line -> lines.add(idx + i, line) }
                    content = lines.joinToString("\n")
                    applied.add("Insert ${edit.replace.lines().size} lines after line ${edit.insertAfterLine}")
                }
                edit.search.isNotEmpty() -> {
                    if (!content.contains(edit.search)) {
                        val trimmed = edit.search.trim()
                        if (content.contains(trimmed)) {
                            content = content.replace(trimmed, edit.replace.trim())
                            applied.add("Replace (trimmed): '${trimmed.take(50)}...'")
                        } else {
                            return EditResult(path, false, "search not found: '${edit.search.take(80)}'", applied,
                                if (applied.isNotEmpty()) CodeDiff.diff(original, content) else null)
                        }
                    } else {
                        val occ = Regex(Regex.escape(edit.search)).findAll(content).count()
                        content = content.replace(edit.search, edit.replace)
                        applied.add("${if (edit.replace.isEmpty()) "Delete" else "Replace"} ($occ): '${edit.search.take(50)}'")
                    }
                }
                else -> return EditResult(path, false, "each edit needs non-empty search or insert_after_line", applied, null)
            }
        }

        file.writeText(content)
        return EditResult(path, true, "edited (${applied.size} ops)", applied, CodeDiff.diff(original, content))
    }

    // ═══ create / delete / move / copy ═══

    data class SimpleResult(val path: String, val ok: Boolean, val message: String)

    fun create(path: String): SimpleResult {
        val file = try { resolve(path) } catch (e: SecurityException) { return SimpleResult(path, false, e.message ?: "path error") }
        return try {
            file.parentFile?.mkdirs()
            if (file.exists()) SimpleResult(path, true, "exists") else { file.createNewFile(); SimpleResult(path, true, "created") }
        } catch (e: IOException) { SimpleResult(path, false, e.message ?: "io error") }
    }

    fun delete(path: String): SimpleResult {
        val file = try { resolve(path) } catch (e: SecurityException) { return SimpleResult(path, false, e.message ?: "path error") }
        return try {
            if (!file.exists()) SimpleResult(path, false, "not found")
            else if (file.isDirectory) { if (file.delete()) SimpleResult(path, true, "deleted dir") else SimpleResult(path, false, "dir not empty") }
            else { if (file.delete()) SimpleResult(path, true, "deleted") else SimpleResult(path, false, "delete failed") }
        } catch (e: SecurityException) { SimpleResult(path, false, e.message ?: "security") }
    }

    fun move(src: String, dst: String): SimpleResult {
        val s = try { resolve(src) } catch (e: SecurityException) { return SimpleResult(src, false, e.message ?: "src path error") }
        val d = try { resolve(dst) } catch (e: SecurityException) { return SimpleResult(dst, false, e.message ?: "dst path error") }
        return try {
            if (!s.exists()) SimpleResult(src, false, "src not found")
            else { d.parentFile?.mkdirs(); if (s.renameTo(d)) SimpleResult(dst, true, "moved") else { copyTree(s, d); s.deleteRecursively(); SimpleResult(dst, true, "moved (copy+delete)") } }
        } catch (e: IOException) { SimpleResult(src, false, e.message ?: "io error") }
    }

    fun copy(src: String, dst: String): SimpleResult {
        val s = try { resolve(src) } catch (e: SecurityException) { return SimpleResult(src, false, e.message ?: "src path error") }
        val d = try { resolve(dst) } catch (e: SecurityException) { return SimpleResult(dst, false, e.message ?: "dst path error") }
        return try {
            if (!s.exists()) SimpleResult(src, false, "src not found")
            else { d.parentFile?.mkdirs(); copyTree(s, d); SimpleResult(dst, true, "copied") }
        } catch (e: IOException) { SimpleResult(src, false, e.message ?: "io error") }
    }

    private fun copyTree(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs(); src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else { src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } } }
    }

    // ═══ glob ═══

    data class GlobResult(val pattern: String, val matches: List<String>, val total: Int, val truncated: Boolean)

    /**
     * 文件名/路径 glob。默认跳过 `.git`/`build`/`node_modules`/`__pycache__`/
     * `.gradle`/`.idea`/`venv`/`.venv`。结果按路径排序，默认上限 200 条。
     */
    fun glob(pattern: String, maxResults: Int = 200): GlobResult {
        val regex = globToRegex(pattern)
        val results = mutableListOf<String>()
        fun walk(dir: File, rel: String) {
            if (results.size >= maxResults) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (results.size >= maxResults) return
                val name = child.name
                if (child.isDirectory && name in SKIP_DIRS) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (regex.matches(name) || regex.matches(childRel)) {
                    results.add(childRel)
                }
                if (child.isDirectory) walk(child, childRel)
            }
        }
        walk(basePath, "")
        val truncated = results.size >= maxResults
        return GlobResult(pattern, results.sorted(), results.size, truncated)
    }

    // ═══ search (grep) ═══

    data class SearchMatch(val file: String, val line: Int, val column: Int, val text: String, val contextBefore: String?, val contextAfter: String?)

    data class SearchResult(
        val pattern: String, val matches: List<SearchMatch>, val total: Int,
        val page: Int, val pageSize: Int, val truncated: Boolean
    )

    /**
     * 正则内容搜索。带上下文行、分页、文件类型过滤。跳过 [SKIP_DIRS]。
     */
    fun search(
        pattern: String,
        fileExtension: String? = null,
        contextLines: Int = 0,
        page: Int = 0,
        pageSize: Int = 50,
        caseSensitive: Boolean = false
    ): SearchResult {
        val opts = setOf(RegexOption.MULTILINE) + if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = try { Regex(pattern, opts) } catch (_: Exception) { return SearchResult(pattern, emptyList(), 0, page, pageSize, false) }
        val all = mutableListOf<SearchMatch>()
        fun walk(dir: File, rel: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) { if (child.name !in SKIP_DIRS) walk(child, if (rel.isEmpty()) child.name else "$rel/${child.name}") ; continue }
                if (fileExtension != null && !child.name.endsWith(fileExtension)) continue
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                try {
                    val lines = child.readText().lines()
                    lines.forEachIndexed { idx, line ->
                        val mr = regex.find(line)
                        if (mr != null) {
                            all.add(SearchMatch(
                                file = childRel, line = idx + 1, column = mr.range.first + 1, text = line.trim().take(200),
                                contextBefore = if (contextLines > 0 && idx > 0) lines.subList((idx - contextLines).coerceAtLeast(0), idx).joinToString("\n") else null,
                                contextAfter = if (contextLines > 0 && idx < lines.size - 1) lines.subList(idx + 1, (idx + contextLines + 1).coerceAtMost(lines.size)).joinToString("\n") else null
                            ))
                        }
                    }
                } catch (_: IOException) { /* skip unreadable */ }
            }
        }
        walk(basePath, "")
        val total = all.size
        val start = page * pageSize
        val end = (start + pageSize).coerceAtMost(total)
        val pageItems = if (start < end) all.subList(start, end) else emptyList()
        return SearchResult(pattern, pageItems, total, page, pageSize, end < total)
    }

    // ═══ helpers ═══

    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (c in pattern) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append(".")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '\\' -> sb.append("\\").append(c)
            else -> sb.append(c)
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    companion object {
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
        const val MAX_READ_LINES = 500
        val SKIP_DIRS = setOf(".git", "build", "node_modules", "__pycache__", ".gradle", ".idea", "venv", ".venv", ".cxx", "target")
    }
}
