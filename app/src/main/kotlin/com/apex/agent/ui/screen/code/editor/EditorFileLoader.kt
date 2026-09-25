package com.apex.agent.ui.screen.code.editor

import com.apex.agent.core.tools.builtin.FilePathSafety
import java.io.File
import java.io.IOException

/**
 * 编辑器面板的文件快照（Issue #154 — Code 屏当前文件查看）。
 *
 * [lines] 只承载前 maxLines 行内容（内存有界）；[totalLines] 记录文件真实
 * 总行数（流式数出，不整载内存），供面板展示「仅显示前 N 行，共 M 行」；
 * [truncated] 表示因超出 maxLines 而被裁剪；[binary] 表示嗅探到二进制内容
 * （此时 [lines] 恒为空，面板展示「二进制文件不支持预览」）。
 */
data class EditorFile(
    val path: String,
    val lines: List<String>,
    val totalLines: Int,
    val truncated: Boolean,
    val binary: Boolean
)

/**
 * # 编辑器文件加载器（纯 Kotlin，零 Android / Compose 依赖）
 *
 * 面向 Code 屏编辑器面板的文件读取契约，与 code_read 工具同源的防线与上限：
 *
 * - 路径安全三级防线直接复用 [FilePathSafety.safeResolve]（core/tool-registry
 *   公共助手，app 模块已依赖）：`..` 段拒绝 + 绝对路径须落在根内 +
 *   canonicalPath 前缀校验（符号链接逃逸在规范化后现形）；
 * - 5MB 文件上限（超限直接拒绝预览，避免移动端内存压力）；
 * - 前 8KB 嗅探二进制（NUL 字节命中，或不可打印控制字节占比 > 30%，
 *   与 CodeReadTool.isBinary 同款阈值）→ [EditorFile.binary] = true；
 * - 按换行符切行（兼容 CRLF，行尾回车符被剥离）；流式逐行读取，只保留前
 *   maxLines 行内容，但完整数出真实总行数 —— 大文件不整载内存；
 * - 单行超 2000 字符截断并追加省略号（与 code_read 的单行上限一致）。
 *
 * 异常契约（调用方 = CodeViewModel，catch 后写入 UI 错误态展示）：
 * - [root] 为 null → **不抛**，返回 [EditorFile] 空壳（path=入参、
 *   lines 空、totalLines=0、truncated=false、binary=false）。工作区未就绪
 *   是用户状态而非编程错误，VM 侧应优先检查根目录并展示引导文案；
 * - 路径空白 / 路径越界（穿越、逃逸根目录）/ 文件不存在 / 目标是目录 /
 *   文件超 5MB → [IllegalArgumentException]，message 为可直接展示的中文；
 * - 底层读 IO 失败（磁盘、权限突变等）→ [IOException] 原样上抛。
 */
object EditorFileLoader {

    /** 默认最大保留行数（与 code_read 的 MAX_LIMIT 同值）。 */
    const val DEFAULT_MAX_LINES = 2000

    /** 单行最大字符数，超出截断加省略号。 */
    const val MAX_LINE_CHARS = 2000

    /** 文件大小上限：5MB。 */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    /**
     * 加载 [root] 工作区内 [relPath] 指向的文本文件为编辑器快照。
     *
     * [maxLines] 会被收敛到 1..[DEFAULT_MAX_LINES]；返回的 [EditorFile.lines]
     * 长度不超过该值，[EditorFile.totalLines] 始终是文件真实行数。
     *
     * @throws IllegalArgumentException 见类 KDoc「异常契约」一节
     * @throws IOException 底层读取失败
     */
    fun loadEditorFile(root: File?, relPath: String, maxLines: Int = DEFAULT_MAX_LINES): EditorFile {
        val path = relPath.trim()
        require(path.isNotEmpty()) { "文件路径为空" }

        // 根未就绪：返回空壳由调用方展示（见类 KDoc）
        if (root == null) {
            return EditorFile(path, emptyList(), 0, truncated = false, binary = false)
        }

        // 三级防线：复用 code_read 同款安全解析（.. 段 / 绝对路径逃逸 / canonical 前缀）
        val file = try {
            FilePathSafety.safeResolve(root, path)
        } catch (e: SecurityException) {
            throw IllegalArgumentException("路径越界，已拒绝访问：$path", e)
        }

        if (!file.exists()) throw IllegalArgumentException("文件不存在：$path")
        if (file.isDirectory) throw IllegalArgumentException("目标是目录，不支持预览：$path")
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalArgumentException("文件过大（${file.length()} 字节，上限 5MB），不支持预览")
        }

        // 二进制嗅探：前 8KB 内 NUL 命中即判定；辅以不可打印占比（同 code_read）
        if (isBinary(file)) {
            return EditorFile(path, emptyList(), 0, truncated = false, binary = true)
        }

        // 流式逐行读取：只保留前 limit 行内容，完整数出真实总数
        val limit = maxLines.coerceIn(1, DEFAULT_MAX_LINES)
        val kept = ArrayList<String>(minOf(limit, 512))
        var total = 0
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                total++
                if (kept.size < limit) {
                    kept += if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "…" else line
                }
                // 超出保留窗口的行只计数，不保留内容
            }
        }
        return EditorFile(path, kept, total, truncated = total > kept.size, binary = false)
    }

    // ── 内部辅助 ─────────────────────────────────────────────────────────

    /**
     * 二进制嗅探：读前 8KB，出现 NUL 字节直接判定；否则统计不可打印控制字节
     * 占比（> 30% 判定，与 CodeReadTool.isBinary 同款阈值）。读失败按非二进制
     * 处理，交由后续正式读取路径抛出真实 IO 异常。
     */
    private fun isBinary(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val head = ByteArray(BINARY_SNIFF_BYTES)
                var read = 0
                while (read < head.size) {
                    val n = stream.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) return false
                var unprintable = 0
                for (i in 0 until read) {
                    val b = head[i]
                    if (b == 0.toByte()) return true
                    if (b < 0x09 || (b in 0x0e..0x1f)) unprintable++
                }
                unprintable.toDouble() / read > 0.30
            }
        } catch (e: IOException) {
            false
        }
    }

    private const val BINARY_SNIFF_BYTES = 8192
}
