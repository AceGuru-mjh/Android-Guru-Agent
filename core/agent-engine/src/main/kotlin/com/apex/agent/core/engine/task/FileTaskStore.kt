package com.apex.agent.core.engine.task

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
/**
 * T76 — 文件式 TaskStore 实现（D-1）。
 *
 * 布局：
 * ```
 * <root>/                       DI 注入（App: filesDir/taskstore；测试: 临时目录）
 *   ├── task-1735689600000-a3f2.json    一个任务一个文件（envelope v1）
 *   ├── corrupt/                        解析失败隔离区（不删除）
 *   └── task-....json.tmp              写入中 temp（崩溃残留，扫描时清理）
 * ```
 *
 * **原子写**：`*.tmp` 写入 → flush → FileDescriptor.sync()（fsync，防
 * OS 页缓存丢数据）→ 同目录 rename（同分区原子）。读侧永远只看到完整文件。
 *
 * **宽容解析**：`ignoreUnknownKeys` + `isLenient`——旧版本文件读入后
 * 按当前 schema 重写即完成升级；版本号差异只记录日志不拒绝（v1 内）。
 *
 * 零 Android 依赖（纯 java.io），core:agent-engine（纯 JVM）内实现，
 * app DI 只需提供目录：`File(context.filesDir, "taskstore")`。
 */
class FileTaskStore(
    private val rootDir: File
) : TaskStore {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val corruptDir: File get() = File(rootDir, CORRUPT_DIR)

    init {
        rootDir.mkdirs()
    }

    // ═══ 写 ═══

    override fun save(task: AgentTask) {
        val target = fileFor(task.taskId)
        val tmp = File(rootDir, task.taskId + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(TaskStoreEnvelope.serializer(), TaskStoreEnvelope(task = task)).toByteArray())
                out.flush()
                // fsync：保证 rename 前数据已落盘（进程死亡 → rename 要么发生要么没发生，
                // 不会出现"rename 成功但内容丢失"）。API 21+ 可用。
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // rename 失败（跨设备/目标被锁）→ 回退复制 + 删除，最后报告
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete() // 半写 temp 清理，不留垃圾
            throw TaskStoreIOException("save(${task.taskId}) failed: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // kotlinx SerializationException 是 IllegalArgumentException 子类
            tmp.delete()
            throw TaskStoreIOException("save(${task.taskId}) serialize failed: ${e.message}", e)
        }
    }

    // ═══ 读 ═══

    override fun load(taskId: String): AgentTask? {
        val file = fileFor(taskId)
        if (!file.exists()) {
            // 兼容半写残留：主文件不存在但 temp 存在 → 视为任务不存在（temp 将被扫描清理）
            return null
        }
        return decodeOrQuarantine(file) { it.task }
    }

    override fun loadActiveTasks(): List<AgentTask> {
        cleanupTempFiles()
        return loadAllTasks().filter { it.isActive }
    }

    override fun loadAllTasks(): List<AgentTask> {
        val files = rootDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return emptyList()
        val results = mutableListOf<AgentTask>()
        for (file in files.sortedByDescending { it.lastModified() }) {
            decodeOrQuarantine(file) { it.task }?.let { results.add(it) }
        }
        return results.sortedByDescending { it.createdAt }
    }

    // ═══ 删 ═══

    override fun delete(taskId: String) {
        fileFor(taskId).delete()
        File(rootDir, taskId + TMP_SUFFIX).delete()
    }

    // ═══ 内部 ═══

    private fun fileFor(taskId: String): File {
        // 防路径穿越：taskId 只允许 [a-z0-9-]（TaskIds 生成器保证）
        require(taskId.matches(Regex("[a-zA-Z0-9_-]+"))) { "illegal taskId: $taskId" }
        return File(rootDir, "$taskId$SUFFIX")
    }

    /**
     * 解析单个文件；失败 → 隔离到 corrupt/（带时间戳后缀防覆盖）并返回 null。
     * 隔离动作自身失败只记日志（不影响其余任务加载）。
     */
    private fun <T> decodeOrQuarantine(file: File, extract: (TaskStoreEnvelope) -> T): T? {
        return try {
            extract(json.decodeFromString(TaskStoreEnvelope.serializer(), file.readText()))
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "task file corrupt, quarantining: ${file.name} (${e::class.simpleName}: ${e.message})"
            )
            runCatching {
                corruptDir.mkdirs()
                file.renameTo(File(corruptDir, "${file.name}.${System.currentTimeMillis()}"))
            }.onFailure {
                AppLogger.instance.warn(LogCategory.ENGINE, TAG, "quarantine move failed: ${file.name}")
            }
            null
        }
    }

    /** 扫描并删除半写 temp 残留（loadActiveTasks 时顺带执行）。 */
    private fun cleanupTempFiles() {
        val temps = rootDir.listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) } ?: return
        for (tmp in temps) {
            val ok = tmp.delete()
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "cleaned half-written temp checkpoint: ${tmp.name} (deleted=$ok)"
            )
        }
    }

    private companion object {
        const val TAG = "FileTaskStore"
        const val SUFFIX = ".json"
        const val TMP_SUFFIX = ".json.tmp"
        const val CORRUPT_DIR = "corrupt"
    }
}
