package com.apex.agent.ui.screen.code.session

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

// ─────────────────────────────────────────────────────────────────────────────
// 编码会话 UI 快照仓库（Issue #152）
//
// 存储：baseDir 下「一工作区一文件」（ws_<workspaceId>.json），命名习惯与
// CodeConversationMemory 对齐但目录必须不同 —— 引擎记忆在 <filesDir>/code_memory，
// 本仓库建议主控注入 <filesDir>/code_sessions（同名文件、不同目录，切不可共用
// baseDir，否则两侧互相覆盖）。
//
// IO 约定（对齐 ChatHistoryManager 惯例）：全部方法为同步阻塞 IO，调用方
// （CodeViewModel）必须自行切换 Dispatchers.IO；快照访问是低频路径
// （防抖落盘 / 进程恢复 / 清空），无需常驻缓存，直接读盘最简单也最不易
// 出现缓存与磁盘不一致。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 按 workspaceId 持久化与恢复 [CodeSessionSnapshot]。
 *
 * 损坏隔离：JSON 解析失败或读取 IO 异常时，把坏文件改名「.corrupt」留档
 * 并返回 null —— 下次成功 save 会写入全新文件，坏档留作排查线索
 * （与 CodeConversationMemory / McpManager 同款策略）。
 *
 * 写入安全：tmp + rename 原子写；rename 失败退化直接复制覆盖，异常时
 * 先清理半截 tmp 再做最后一次直写兜底，仍失败则 IOException 向上抛
 * （持久化失败应让调用方感知，静默吞会无声丢快照）。
 *
 * @param baseDir 快照目录（构造时自动创建）；须与引擎记忆目录隔离
 */
class CodeSessionStore(private val baseDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        baseDir.mkdirs()
    }

    /**
     * 读取指定工作区的快照。
     *
     * @return 文件不存在 → null；文件损坏或读取失败 → 隔离坏档后 null；
     *         正常 → 快照（其 workspaceId 字段以文件内容为准，一般与入参一致）
     */
    fun load(workspaceId: String): CodeSessionSnapshot? {
        val file = fileFor(workspaceId)
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            quarantine(file)
            return null
        }
        return try {
            json.decodeFromString(CodeSessionSnapshot.serializer(), raw)
        } catch (e: SerializationException) {
            quarantine(file)
            null
        }
    }

    /**
     * 全量覆写快照（按 snapshot.workspaceId 派生目标文件）。
     *
     * 建议调用方在构造快照时盖时间戳：snapshot.copy(updatedAt = 当前毫秒)。
     *
     * @throws IOException 三重写入策略全部失败时向上抛
     */
    fun save(snapshot: CodeSessionSnapshot) {
        val file = fileFor(snapshot.workspaceId)
        writeAtomic(file, json.encodeToString(CodeSessionSnapshot.serializer(), snapshot))
    }

    /**
     * 删除指定工作区的快照（新会话开始时调用）。
     * 文件不存在时静默返回，不视为错误。
     */
    fun clear(workspaceId: String) {
        fileFor(workspaceId).delete()
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /** 目标文件：ws_<清洗后 id>.json，恒定落在 baseDir 内。 */
    private fun fileFor(workspaceId: String): File =
        File(baseDir, "ws_" + sanitizeName(workspaceId) + ".json")

    /**
     * 文件名安全清洗：只保留 [A-Za-z0-9_-]，其余字符（含路径分隔符与点）
     * 一律替换为下划线，封死路径穿越；清洗结果为空时回退「default」。
     * 超长 id 截断到上限并追加稳定散列后缀，防文件名超长写入失败，
     * 且共享前缀的不同长 id 仍可区分。
     */
    private fun sanitizeName(workspaceId: String): String {
        val cleaned = workspaceId.replace(UNSAFE_CHARS, "_")
        val base = cleaned.ifEmpty { "default" }
        if (base.length <= MAX_FILE_STEM) return base
        val hash = Integer.toHexString(base.hashCode()).padStart(8, '0').takeLast(8)
        return base.take(MAX_FILE_STEM - 8) + hash
    }

    /**
     * 坏档隔离（尽力而为）：优先 rename 为「原名.corrupt」；rename 失败退化
     * 复制 + 删原文件；两者都失败则保留原坏文件 —— 下次 load 会幂等地再次
     * 尝试隔离，行为不变（返回 null）。
     *
     * @return 是否成功隔离（原文件已从原位消失）
     */
    private fun quarantine(file: File): Boolean {
        val corrupt = File(file.parentFile, file.name + CORRUPT_SUFFIX)
        if (file.renameTo(corrupt)) return true
        return runCatching {
            file.copyTo(corrupt, overwrite = true)
            file.delete()
        }.isSuccess
    }

    /**
     * 原子写：先写 tmp 再 rename；rename 失败（跨卷 / 目标被占用）退化
     * 复制覆盖；任一环节 IOException 时清理半截 tmp 后做最后一次直写
     * 兜底，仍失败则异常向上抛。
     */
    private fun writeAtomic(file: File, raw: String) {
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            tmp.writeText(raw, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            // 最终兜底直写（对齐 CodeConversationMemory 的容错链）；若磁盘
            // 真的不可写，这里抛出的 IOException 就是调用方拿到的失败信号
            file.writeText(raw, Charsets.UTF_8)
        }
    }

    private companion object {
        /** 文件名危险字符：安全集合之外的任意字符。 */
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9_-]")
        /** 清洗后文件名主干上限（含散列后缀）。 */
        const val MAX_FILE_STEM = 64
        const val CORRUPT_SUFFIX = ".corrupt"
        const val TMP_SUFFIX = ".tmp"
    }
}
