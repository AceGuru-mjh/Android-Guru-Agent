package com.apex.agent.tools

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * ═══ #F-⑯ 工具调用审计日志（结构化 JSONL）═══
 *
 * 问题背景：Agent 的 shell 命令在 Root / Shizuku / 沙箱三级降级链上执行时，
 * 每一步降级决策此前只有 Logcat 一行 D 级日志 —— 无法对外证明「agent 在 X
 * 场景下确实走了沙箱」，也无法事后归因权限决策。
 *
 * 设计：
 *  - 每次工具门决策 / shell 通道执行输出**一行 JSON**（JSONL，追加写），
 *    key 至少包含文档要求的 `tool / command / tier / decision / durationMs`；
 *  - 双写：本地文件（256KB 滚动，保留后半段）+ Logcat（I 级，tag `ToolAudit`）；
 *  - 文件位置：`<filesDir>/logs/tool-audit.jsonl`（与 hooks.log 同目录族）；
 *  - 线程安全：单 write 方法 + synchronized（审计写入本就不在热路径 ——
 *    每工具调用一次，远低于 hooks.log 的每次 POST_TOOL_USE 频率）；
 *  - 写失败静默（磁盘满/IO 异常不允许炸工具执行链路），仅 Logcat 告警。
 *
 * 与 HookRegistry 内置 `builtin-tool-usage-log` 的分工：hooks.log 记录
 * 工具调用的参数/结果预览（POST_TOOL_USE 事件）；本类记录**权限决策链**
 * （门放行/拒绝/用户选择 + 实际执行通道 tier + 时长）—— 两者互补不重叠。
 */
@Singleton
class ToolAuditLogger @Inject constructor(
    context: Context
) {
    private val logFile: File = File(File(context.filesDir, "logs"), "tool-audit.jsonl")
    private val lock = Any()

    /** 一次审计事件（字段对齐审计文档的最小 key 集 + 若干扩展）。 */
    data class Event(
        /** 工具 id（如 shell_execute / terminal.exec / write_file / <gate>）。 */
        val tool: String,
        /** 用户可见的决策结果（allow / deny / allow_session / allow_once / executed / failed / denied_by_user …）。 */
        val decision: String,
        /** 权限通道（root / shizuku / shell / proot-ubuntu / none）。 */
        val tier: String? = null,
        /** 命令原文（长命令截断到 512 字符 —— 审计需要可读性而非全文）。 */
        val command: String? = null,
        val durationMs: Long? = null,
        val success: Boolean? = null,
        val exitCode: Int? = null,
        val detail: String? = null
    )

    /** 追加一条审计记录（文件 + Logcat 双写；失败静默降级为 Logcat-only）。 */
    fun log(event: Event) {
        val json = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("tool", event.tool)
            put("decision", event.decision)
            event.tier?.let { put("tier", it) }
            event.command?.let { put("command", it.take(COMMAND_TRUNCATE)) }
            event.durationMs?.let { put("durationMs", it) }
            event.success?.let { put("success", it) }
            event.exitCode?.let { put("exitCode", it) }
            event.detail?.let { put("detail", it.take(DETAIL_TRUNCATE)) }
        }
        val line = json.toString()
        android.util.Log.i(TAG, line)
        synchronized(lock) {
            runCatching {
                logFile.parentFile?.mkdirs()
                rotateIfNeeded()
                FileOutputStream(logFile, true).use { it.write((line + "\n").toByteArray()) }
            }.onFailure {
                android.util.Log.w(TAG, "audit write failed: ${it.message}")
            }
        }
    }

    /** 超过上限保留后半段（审计通常更关心最近事件；与 hooks.log 的滚动策略同型）。 */
    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() <= MAX_BYTES) return
        val lines = runCatching { logFile.readLines() }.getOrNull() ?: return
        val kept = mutableListOf<String>()
        var size = 0L
        for (i in lines.indices.reversed()) {
            val l = lines[i]
            size += l.length + 1
            if (size > KEEP_BYTES) break
            kept.add(0, l)
        }
        logFile.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val TAG = "ToolAudit"
        const val MAX_BYTES = 256L * 1024
        const val KEEP_BYTES = 128L * 1024
        const val COMMAND_TRUNCATE = 512
        const val DETAIL_TRUNCATE = 200
    }
}
