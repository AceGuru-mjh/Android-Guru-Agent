package com.apex.agent.core.code.stream

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * # Code Stream Checkpoint — 胶囊时间轴的扁平持久化（中断恢复）
 *
 * ## 语义（规格书【1】幂等检查点）
 *
 * - **lastEventId**：恢复后继续消费事件的游标（Last-Event-ID 续传语义）；
 * - **committedFiles**：已提交（工具成功落盘）的文件变更清单——恢复后
 *   「哪些文件已经改过」一目了然，重试决策不用瞎猜；
 * - **pendingToolCalls**：未到终态的胶囊清单（中断时 running/waiting 的
 *   调用——恢复后标记为中断态或等待重试指令）；
 * - **entries**：时间轴本体。
 *
 * ## 扁平化取舍
 *
 * 存 **diff 原文**（[StorableStreamEntry.diffText]）而非解析后的 hunk
 * 结构——恢复时经 [UnifiedDiffParser] 现场重解析：持久化格式不与解析器
 * 数据结构耦合，解析器改进（行号语义/截断规则）后旧档案自动受益，
 * 且序列化体积小一个数量级。
 *
 * ## IO 纪律
 *
 * - 原子写（tmp + rename，失败直写兜底）；
 * - 坏档隔离（.corrupt 后缀，不阻断——时间轴是可再生的视图态，宁可
 *   丢轴不可崩会话）；
 * - ignoreUnknownKeys 双向兼容（新增字段对旧档案不崩）。
 */
object CodeStreamCheckpoint {

    // ═══ 可持久化模型（全扁平，kotlinx.serialization）═══

    @Serializable
    data class StorableToolCall(
        val id: String,
        val kindName: String,
        val displayName: String,
        val target: String,
        val argsSummary: String = "",
        val statusName: String,
        val startAt: Long = 0,
        val endAt: Long = 0,
        val durationMs: Long = 0,
        val hunksApplied: Int = 0,
        val hunksTotal: Int = 0,
        val exitCode: Int? = null,
        val logTail: String = "",
        val diffText: String? = null,
        val summary: String? = null
    )

    @Serializable
    data class StorableStreamEntry(
        val type: String,
        val id: String,
        val text: String? = null,
        val isStreaming: Boolean = false,
        val call: StorableToolCall? = null,
        val cycleRound: Int = 0,
        val cycleEditIds: List<String> = emptyList(),
        val cycleVerifyIds: List<String> = emptyList(),
        val cyclePassed: Boolean? = null,
        val files: List<String> = emptyList()
    )

    @Serializable
    data class StorableCheckpoint(
        val workspaceId: String,
        val lastEventId: String = "",
        val committedFiles: List<String> = emptyList(),
        val pendingToolCallIds: List<String> = emptyList(),
        val entries: List<StorableStreamEntry> = emptyList(),
        val updatedAt: Long = 0
    )

    // ═══ 序列化 ═══

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    /** 会话 → 可持久化快照（entries 封顶裁剪：时间轴是视图态，近端优先）。 */
    fun fromSession(session: CodeStreamSession, workspaceId: String, maxEntries: Int = MAX_ENTRIES): StorableCheckpoint {
        val entries = session.entriesSnapshot().takeLast(maxEntries)
        val storable = entries.map { it.toStorable() }
        val pending = storable.mapNotNull { it.call }
            .filter { it.statusName == ToolCallStatus.WAITING.name || it.statusName == ToolCallStatus.RUNNING.name }
            .map { it.id }
        return StorableCheckpoint(
            workspaceId = workspaceId,
            lastEventId = "e-${entries.lastOrNull()?.id ?: ""}",
            committedFiles = emptyList(), // 由 VM 侧从 affectedFiles 注入（见 toCheckpoint 重载）
            pendingToolCallIds = pending,
            entries = storable,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 全量构造（VM 侧把受影响文件清单一并写入）。 */
    fun toCheckpoint(
        session: CodeStreamSession,
        workspaceId: String,
        committedFiles: List<String>,
        maxEntries: Int = MAX_ENTRIES
    ): StorableCheckpoint = fromSession(session, workspaceId, maxEntries).copy(committedFiles = committedFiles)

    /**
     * 恢复：可持久化快照 → 时间轴条目。
     *
     * 未到终态的胶囊恢复为「中断态」（PARTIAL——正在跑的调用没有结局，
     * 恢复后由用户决定单工具重试或整轮重跑）。
     */
    fun toEntries(checkpoint: StorableCheckpoint): List<StreamEntry> =
        checkpoint.entries.mapNotNull { storable ->
            when (storable.type) {
                "user" -> StreamEntry.UserEntry(storable.id, storable.text.orEmpty())
                "assistant" -> StreamEntry.AssistantEntry(storable.id, storable.text.orEmpty(), false)
                "thinking" -> StreamEntry.ThinkingEntry(storable.id, storable.text.orEmpty(), false)
                "tool" -> storable.call?.let { c ->
                    val status = runCatching { ToolCallStatus.valueOf(c.statusName) }
                        .getOrNull()?.let { if (it == ToolCallStatus.RUNNING || it == ToolCallStatus.WAITING) ToolCallStatus.PARTIAL else it }
                        ?: ToolCallStatus.PARTIAL
                    StreamEntry.ToolCapsuleEntry(
                        storable.id,
                        c.toView(status)
                    )
                }
                "cycle" -> StreamEntry.VerifyCycleEntry(
                    id = storable.id,
                    cycle = VerifyCycle(storable.cycleRound, storable.cycleEditIds, storable.cycleVerifyIds, storable.cyclePassed),
                    // 轮内明细恢复为空（可接受降级：DetailSheet 明细不可回放，
                    // 轮次红绿态与轮次号保留）
                    calls = emptyList()
                )
                "status" -> StreamEntry.StatusEntry(storable.id, storable.text.orEmpty())
                "system" -> StreamEntry.SystemEntry(storable.id, storable.text.orEmpty())
                "error" -> StreamEntry.ErrorEntry(storable.id, storable.text.orEmpty(), true)
                "stop" -> StreamEntry.StopEntry(storable.id, storable.text.orEmpty())
                "files" -> StreamEntry.FileChipsEntry(storable.id, storable.files)
                else -> null
            }
        }

    // ═══ 文件 IO ═══

    /** 落盘（原子写；目录 = code_sessions 同级的 stream_checkpoints/）。 */
    fun save(dir: File, checkpoint: StorableCheckpoint) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, fileNameOf(checkpoint.workspaceId))
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(json.encodeToString(checkpoint))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(checkpoint))
                tmp.delete()
            }
        }.onFailure {
            AppLogger.instance.warn(LogCategory.UI, TAG, "时间轴检查点落盘失败（ws=${checkpoint.workspaceId}）：${it.message}")
        }
    }

    /** 读取（坏档隔离 + null 容错）。 */
    fun load(dir: File, workspaceId: String): StorableCheckpoint? {
        val file = File(dir, fileNameOf(workspaceId))
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(StorableCheckpoint.serializer(), file.readText()) }
            .onFailure {
                AppLogger.instance.warn(LogCategory.UI, TAG, "时间轴检查点损坏，隔离（ws=$workspaceId）：${it.message}")
                runCatching {
                    file.renameTo(File(dir, file.name + ".corrupt"))
                }
            }
            .getOrNull()
            ?.takeIf { it.workspaceId == workspaceId }
    }

    fun clear(dir: File, workspaceId: String) {
        runCatching { File(dir, fileNameOf(workspaceId)).delete() }
    }

    private fun fileNameOf(workspaceId: String): String {
        val safe = workspaceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "stream_$safe.json"
    }

    // ═══ 内部：条目 ↔ 扁平模型 ═══

    private fun StreamEntry.toStorable(): StorableStreamEntry = when (this) {
        is StreamEntry.UserEntry -> StorableStreamEntry("user", id, text)
        is StreamEntry.AssistantEntry -> StorableStreamEntry("assistant", id, text, isStreaming)
        is StreamEntry.ThinkingEntry -> StorableStreamEntry("thinking", id, text, isStreaming)
        is StreamEntry.ToolCapsuleEntry -> StorableStreamEntry(
            "tool", id, call = StorableToolCall(
                id = call.id,
                kindName = kindNameOf(call.kind),
                displayName = call.displayName,
                target = call.target,
                argsSummary = call.argsSummary,
                statusName = call.status.name,
                startAt = call.startAt,
                endAt = call.endAt,
                durationMs = call.durationMs,
                hunksApplied = call.hunksApplied,
                hunksTotal = call.hunksTotal,
                exitCode = call.exitCode,
                logTail = call.logTail.take(LOG_TAIL_PERSIST_MAX),
                diffText = call.diffText,
                summary = call.summary
            )
        )
        is StreamEntry.VerifyCycleEntry -> StorableStreamEntry(
            "cycle", id,
            cycleRound = cycle.round,
            cycleEditIds = cycle.editCallIds,
            cycleVerifyIds = cycle.verifyCallIds,
            cyclePassed = cycle.passed
        )
        is StreamEntry.StatusEntry -> StorableStreamEntry("status", id, text)
        is StreamEntry.SystemEntry -> StorableStreamEntry("system", id, text)
        is StreamEntry.ErrorEntry -> StorableStreamEntry("error", id, message)
        is StreamEntry.StopEntry -> StorableStreamEntry("stop", id, reason)
        is StreamEntry.FileChipsEntry -> StorableStreamEntry("files", id, files = files)
    }

    private fun StorableToolCall.toView(restoredStatus: ToolCallStatus): StreamToolCall = StreamToolCall(
        id = id,
        kind = kindOf(kindName),
        displayName = displayName,
        target = target,
        argsSummary = argsSummary,
        status = restoredStatus,
        startAt = startAt,
        endAt = endAt,
        durationMs = durationMs,
        hunksApplied = hunksApplied,
        hunksTotal = hunksTotal,
        exitCode = exitCode,
        logTail = logTail,
        diffText = diffText,
        summary = summary
    )

    private fun kindNameOf(kind: ToolKind): String = when (kind) {
        ToolKind.READ_FILE -> "READ_FILE"
        ToolKind.WRITE_FILE -> "WRITE_FILE"
        ToolKind.EDIT_FILE -> "EDIT_FILE"
        ToolKind.GREP_SEARCH -> "GREP_SEARCH"
        ToolKind.BASH -> "BASH"
        ToolKind.GIT -> "GIT"
        ToolKind.LINT -> "LINT"
        ToolKind.TEST -> "TEST"
        ToolKind.PLAN -> "PLAN"
        ToolKind.MCP_CUSTOM -> "MCP_CUSTOM"
    }

    private fun kindOf(name: String): ToolKind = when (name) {
        "READ_FILE" -> ToolKind.READ_FILE
        "WRITE_FILE" -> ToolKind.WRITE_FILE
        "EDIT_FILE" -> ToolKind.EDIT_FILE
        "GREP_SEARCH" -> ToolKind.GREP_SEARCH
        "BASH" -> ToolKind.BASH
        "GIT" -> ToolKind.GIT
        "LINT" -> ToolKind.LINT
        "TEST" -> ToolKind.TEST
        "PLAN" -> ToolKind.PLAN
        else -> ToolKind.MCP_CUSTOM
    }

    private const val TAG = "CodeStreamCkpt"
    private const val MAX_ENTRIES = 400
    private const val LOG_TAIL_PERSIST_MAX = 2000
}
