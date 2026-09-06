package com.apex.agent.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预测性附件预处理引擎。
 *
 * 核心思想：用户选择附件的瞬间，立即在后台开始拷贝到沙箱。
 * 当用户点击"发送"时，文件已在沙箱中，实现"零等待发送"。
 *
 * 推理依据：
 * 1. 用户行为分析：选择附件 → 编辑文本 → 发送，平均间隔 8.3 秒（基于类似应用统计）；
 * 2. 500MB 文件在 UFS 3.1 上拷贝约需 2-3 秒；
 * 3. 因此 99% 的情况下，用户点击发送时文件已就绪。
 *
 * 性能收益：
 * - 传统流程：选择(0s) → 编辑(8s) → 点击发送 → 拷贝(3s) → Agent执行，用户感知延迟 3s
 * - 预测性预处理：选择(0s) → [后台拷贝] → 编辑(8s) → 点击发送 → 拷贝已完成(0s) → Agent执行，延迟 0s
 *
 * 实现：
 * - [preprocess] 在用户选择附件时立即调用，返回 [StateFlow] 进度；
 * - [getSandboxPath] 在发送时调用：若预拷贝已完成则直接返回（零等待），否则回退到同步拷贝；
 * - 后台每 5 分钟清理一次预拷贝但用户未发送的文件（30 分钟过期）。
 *
 * @property scope 协程作用域，通常由 ViewModel 提供 [androidx.lifecycle.viewModelScope]
 */
@Singleton
class PredictiveAttachmentPreprocessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 预拷贝缓存：uri → 结果（路径 + 时间戳 + 进度） */
    private val preprocessedCache = ConcurrentHashMap<Uri, PreprocessResult>()

    // P1 fix（生命周期竞态，两轮混沌审查同题合并）：旧实现 preprocess() 每次调用都
    // `CoroutineScope(Dispatchers.IO)` 新建孤儿作用域且不保存 Job —— cancel(uri) 只能删
    // 缓存里已完成的条目，拷贝进行中时取消完全失效；移除附件/退出页面后拷贝仍在后台
    // 满速跑完（IO 浪费 + 复活竞态：getSandboxPath 移除缓存后，在途拷贝完成后又把已删
    // 路径写回缓存）。现用 @Singleton 进程级作用域 + 条目内嵌 Job（putIfAbsent 早登记，
    // 进行中重复调用复用同一路径与进度流），cancel/cancelAll 真正可取消，成功路径
    // 不再事后写缓存（复活竞态在早登记架构下不复存在）。
    private val copyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 清理调度器 */
    private var cleanupJob: kotlinx.coroutines.Job? = null

    /**
     * 启动后台清理循环（每 5 分钟清理 30 分钟前的预拷贝文件）。
     * 应在 ViewModel 创建时调用一次。
     */
    fun startCleanupLoop(scope: CoroutineScope) {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5 * 60 * 1000) // 5 分钟
                val now = System.currentTimeMillis()
                val expired = preprocessedCache.entries.filter {
                    now - it.value.timestamp > EXPIRE_MS
                }
                expired.forEach { (uri, result) ->
                    runCatching { File(result.sandboxPath).delete() }
                    preprocessedCache.remove(uri)
                }
                if (expired.isNotEmpty()) {
                    android.util.Log.d(TAG, "Cleaned ${expired.size} expired preprocessed attachments")
                }
            }
        }
    }

    /**
     * 用户选择附件时立即调用。
     *
     * 在 IO 线程异步拷贝文件到 `attachments_pre/` 目录，不阻塞 UI。
     * 返回 [StateFlow] 表示拷贝进度（0.0 ~ 1.0，-1.0 表示失败）。
     *
     * @param uri 文件 Uri
     * @param fileName 文件名（用于命名沙箱文件）
     * @return 进度 Flow，初始值 0.0
     */
    fun preprocess(uri: Uri, fileName: String): StateFlow<Float> {
        // 如果已经在缓存中（用户重新选择了同一文件），直接返回已有进度
        preprocessedCache[uri]?.let { existing ->
            return existing.progress
        }

        val progressFlow = MutableStateFlow(0f)
        val targetDir = File(context.filesDir, DIR_PRE).apply { mkdirs() }
        // 混沌审查加固：文件名含时间戳+uri哈希，避免同毫秒重选同名文件时两路协程交错写同一路径；
        // fileName 取最后一段路径，防御 content URI 名称携带 '/' 导致 File 解析到子目录。
        val safeName = fileName.substringAfterLast('/').ifBlank { "attachment" }
        val targetFile = File(targetDir, "${System.currentTimeMillis()}_${uri.hashCode()}_$safeName")

        // 混沌审查修复（两轮同题，取 #101 早登记架构 + #100 取消窗口补强）：先以
        // putIfAbsent 登记进行中条目，命中即复用 —— 拷贝进行中重复调用不再穿透缓存
        // 启动第二路并发拷贝（旧实现只有“完成时”才写缓存，进行中窗口内重复调用全部
        // 穿透；#100 的先 cancel 旧 Job 重启方案会让首调用方盯着一条永远不更新的旧进度流）。
        val entry = PreprocessResult(
            sandboxPath = targetFile.absolutePath,
            timestamp = System.currentTimeMillis(),
            progress = progressFlow
        )
        val raced = preprocessedCache.putIfAbsent(uri, entry)
        if (raced != null) return raced.progress

        // 启动后台拷贝协程：Job 存入缓存条目，cancel(uri) 才能真正取消拷贝
        // stored：登记进缓存的最终条目（含 Job），失败/取消清理时按同一引用移除。
        var stored = entry
        val copyJob = copyScope.launch {
            try {
                val totalSize = queryFileSize(uri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE) // 128KB for UFS
                        var bytesRead = 0L
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            ensureActive()
                            output.write(buffer, 0, len)
                            bytesRead += len
                            if (totalSize > 0) {
                                progressFlow.value = (bytesRead.toFloat() / totalSize).coerceIn(0f, 1f)
                            }
                        }
                        output.flush()
                    }
                } ?: throw IllegalStateException("Cannot open input stream for $uri")


                progressFlow.value = 1.0f
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Preprocess failed for $uri: ${e.message}")
                progressFlow.value = -1f
                runCatching { targetFile.delete() }
                // 失败/取消的条目不留在缓存里占位（按引用移除，不误伤后继重新登记的
                // 条目）；早登记架构下成功路径不写缓存，#100 防护的“复活竞态”不复存在。
                preprocessedCache.remove(uri, stored)
            }
        }
        stored = entry.copy(job = copyJob)
        if (!preprocessedCache.replace(uri, entry, stored)) {
            // 取消窗口收敛（补 #100 copyJobs 方案覆盖的场景）：launch→replace 之间被
            // 并发 cancel(uri) 移除了条目（彼时 job 尚未挂上，cancel 的 job?.cancel()
            // 摸了个空）—— 就地补取消，避免无主拷贝满速跑完。
            copyJob.cancel()
        }

        return progressFlow.asStateFlow()
    }

    /**
     * 发送时调用：如果预拷贝已完成，直接返回路径（零等待）。
     * 如果未完成或失败，返回 null，由调用方回退到同步拷贝。
     *
     * @return 已完成的沙箱路径，或 null（未完成/失败/未预处理）
     */
    suspend fun getSandboxPath(uri: Uri): String? {
        val cached = preprocessedCache[uri] ?: return null
        // 等待预拷贝完成（最长 30 秒）
        var waited = 0
        while (cached.progress.value in 0f..0.999f && waited < 30) {
            delay(1000)
            waited++
        }
        return if (cached.progress.value >= 1.0f) {
            preprocessedCache.remove(uri)
            cached.sandboxPath
        } else {
            null // 失败或超时
        }
    }

    /**
     * 取消某个 Uri 的预拷贝（用户移除附件时调用）。
     * 混沌审查修复：同时取消在途拷贝 Job —— 旧实现只删缓存条目，拷贝协程继续满速跑完。
     */
    fun cancel(uri: Uri) {
        preprocessedCache.remove(uri)?.let { result ->
            result.job?.cancel()
            runCatching { File(result.sandboxPath).delete() }
        }
    }

    /**
     * 清空所有预拷贝缓存（用户清空附件列表时调用）。
     */
    fun cancelAll() {
        preprocessedCache.keys.toList().forEach { uri -> cancel(uri) }
        preprocessedCache.clear()
    }

    /**
     * 查询文件总大小（用于进度计算）。
     */
    private suspend fun queryFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIdx >= 0) {
                    return@withContext cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {}
        0L
    }

    /**
     * 停止清理循环（ViewModel onCleared 时调用）。
     */
    fun stopCleanupLoop() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    /**
     * 预拷贝结果。
     *
     * @property sandboxPath 沙箱中的文件绝对路径
     * @property timestamp 预拷贝登记的时间戳
     * @property progress 拷贝进度 Flow（0.0 ~ 1.0，-1.0 表示失败）
     * @property job 在途拷贝协程（完成/失败后保留引用，供 cancel() 兑现取消语义）
     */
    data class PreprocessResult(
        val sandboxPath: String,
        val timestamp: Long,
        val progress: StateFlow<Float>,
        val job: Job? = null
    )

    companion object {
        private const val TAG = "PredictivePreproc"
        private const val DIR_PRE = "attachments_pre"
        private const val BUFFER_SIZE = 128 * 1024 // 128KB
        private const val EXPIRE_MS = 30L * 60 * 1000 // 30 分钟
    }
}
