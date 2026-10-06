package com.apex.agent.ui.screen.code.longtask

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.apex.agent.core.code.longtask.LongTaskRecord
import com.apex.agent.core.code.longtask.LongTaskStatus
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═════════════════════════════════════════════════════════════════════════════
 *  #184：长任务记录导出/分享 —— JSON 原样导出 + Markdown 运行报告
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * LongTaskRecord 结构完整（目标/统计/文件集/检查点/todo 快照/模板来源），
 * 双格式导出：
 *  - **JSON**：记录原样序列化（备份/跨设备导入/程序化分析）；
 *  - **Markdown**：给人看的运行报告（目标 → 进度时间线 → 改动文件 →
 *    工具用量 → 结论）—— 复盘文档直接可贴。
 *
 * 落点：`filesDir/exports/`（FileProvider files-path 已覆盖）→ 系统分享
 * 面板（ACTION_SEND）。文件名带任务 id 前缀（多次导出不互相覆盖，幂等）。
 * 防御式 IO：全部折叠 null，失败只留痕不抛（导出是增益路径）。
 */
object LongTaskExporter {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 时间戳格式（文件名安全：无冒号）。 */
    private val fileTimeFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 可读时间格式（报告正文）。 */
    private val reportTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 导出产物（两格式同批产出，一次分享动作）。 */
    data class ExportResult(val jsonFile: File, val markdownFile: File) {
        val sizeBytes: Long get() = jsonFile.length() + markdownFile.length()
    }

    /**
     * 导出单条记录（JSON + Markdown 双格式）。
     * 返回 null = 落盘失败（磁盘满/IO 异常 —— 已留痕，调用方给 Toast 兜底）。
     */
    fun export(context: Context, record: LongTaskRecord): ExportResult? = runCatching {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val stamp = fileTimeFmt.format(Date(record.createdAt))
        val safeId = record.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

        val jsonFile = File(dir, "longtask_${safeId}_$stamp.json")
        jsonFile.writeText(json.encodeToString(LongTaskRecord.serializer(), record))

        val mdFile = File(dir, "longtask_${safeId}_$stamp.md")
        mdFile.writeText(buildMarkdown(record))

        ExportResult(jsonFile, mdFile)
    }.onFailure { e ->
        AppLogger.instance.warn(
            LogCategory.SYSTEM, "LongTaskExporter", "导出失败（task=${record.id}）：${e.message}"
        )
    }.getOrNull()

    /**
     * 拉起系统分享面板（两格式一次分享 —— ACTION_SEND_MULTIPLE）。
     * 单格式兜底：多文件分享被拒（极旧 ROM）时退回首个 JSON。
     */
    fun share(context: Context, result: ExportResult): Boolean = runCatching {
        val uris = listOf(result.jsonFile, result.markdownFile)
            .map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        val intent = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, result.markdownFile.name))
        true
    }.onFailure { e ->
        AppLogger.instance.warn(
            LogCategory.SYSTEM, "LongTaskExporter", "分享拉起失败：${e.message}"
        )
    }.getOrDefault(false)

    // ── Markdown 运行报告 ─────────────────────────────────────────────────

    /** 报告结构：元信息 → 目标 → 结论/错误 → 时间线 → todo 终态 → 改动文件 → 工具用量。 */
    internal fun buildMarkdown(record: LongTaskRecord): String = buildString {
        appendLine("# 长任务运行报告：${record.title}")
        appendLine()
        appendLine("> 由 Apex Agent 导出 · ${reportTimeFmt.format(Date(record.updatedAt))}")
        appendLine()
        appendLine("## 元信息")
        appendLine()
        appendLine("| 项 | 值 |")
        appendLine("| --- | --- |")
        appendLine("| 任务 ID | `${record.id}` |")
        appendLine("| 工作区 | ${record.workspaceName}（${record.workspaceId}） |")
        appendLine("| 状态 | ${statusLabel(record.status)} |")
        appendLine("| 思考档位 | ${record.thinkingLevel} |")
        appendLine("| 模式 | ${record.agentMode} |")
        record.parentTaskId?.let { appendLine("| 复制自 | `$it`（第 ${record.copyCount} 次复制） |") }
        appendLine("| 迭代轮数 | ${record.iterations} |")
        appendLine("| 工具调用 | ${record.toolCalls} |")
        appendLine("| 总时长 | ${formatDuration(record.durationMs)} |")
        appendLine()
        appendLine("## 目标")
        appendLine()
        appendLine("```")
        appendLine(record.goal)
        appendLine("```")
        appendLine()
        record.summary?.let { s ->
            appendLine("## 结论")
            appendLine()
            appendLine(s)
            appendLine()
        }
        record.errorMessage?.let { e ->
            appendLine("## 错误")
            appendLine()
            appendLine("```")
            appendLine(e)
            appendLine("```")
            appendLine()
        }
        if (record.todoSnapshot.isNotEmpty()) {
            appendLine("## Todo 终态快照")
            appendLine()
            record.todoSnapshot.forEach { appendLine("- $it") }
            appendLine()
        }
        if (record.filesTouched.isNotEmpty()) {
            appendLine("## 改动文件（${record.filesTouched.size}）")
            appendLine()
            record.filesTouched.forEach { appendLine("- `$it`") }
            appendLine()
        }
        if (record.toolsUsed.isNotEmpty()) {
            appendLine("## 工具用量")
            appendLine()
            appendLine("| 工具 | 调用次数 |")
            appendLine("| --- | --- |")
            record.toolsUsed.entries
                .sortedByDescending { it.value }
                .forEach { (tool, n) -> appendLine("| `$tool` | $n |") }
            appendLine()
        }
        if (record.checkpoints.isNotEmpty()) {
            appendLine("## 检查点（${record.checkpoints.size}）")
            appendLine()
            record.checkpoints.forEach { cp ->
                appendLine("- `${cp.id}` @ 迭代 ${cp.atIteration} · ${reportTimeFmt.format(Date(cp.timestamp))}" +
                    "（工具 ${cp.toolCallCount} 次 / 文件 ${cp.filesTouchedCount} 个）")
            }
            appendLine()
        }
    }

    private fun statusLabel(status: LongTaskStatus): String = when (status) {
        LongTaskStatus.RUNNING -> "运行中"
        LongTaskStatus.COMPLETED -> "已完成"
        LongTaskStatus.ABORTED -> "已中止"
        LongTaskStatus.FAILED -> "失败"
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m${s % 60}s"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }
}
