package com.apex.agent.ui.screen.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.apex.agent.attachment.PredictiveAttachmentPreprocessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 附件管理器：从 [AgentChatViewModel] 抽出的单一职责协作类。
 *
 * 负责附件相关的全部状态与逻辑（缺陷 1 修复：全部异步化）：
 * - [attachments] 状态流（UPLOADING 占位 → IO 线程回填真实元数据）；
 * - [attachFile] / [attachImage] / [removeAttachment]；
 * - 沙箱拷贝 [copyToSandboxSafe]（供发送消息时回退使用）；
 * - 预测性预处理生命周期（startCleanupLoop / stopCleanupLoop）。
 *
 * 依赖：
 * - [context]：应用 Context（ContentResolver / filesDir）；
 * - [preprocessor]：预测性附件预处理器（后台预拷贝，发送时零等待）；
 * - [scope]：协程作用域（调用方传入 viewModelScope，随 ViewModel 生命周期清理）。
 *
 * 逻辑与原 ViewModel 内联实现逐行等价，仅重新安家。
 */
internal class AttachmentManager(
    private val context: Context,
    private val preprocessor: PredictiveAttachmentPreprocessor,
    private val scope: CoroutineScope
) {

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    /**
     * 附件处理 Job 追踪，支持取消（缺陷 1 修复）。
     * key = [attachmentIdCounter] 分配的唯一 id，与占位项 [Attachment.attachmentId] 一一对应。
     */
    private val attachmentJobs = mutableMapOf<Int, Job>()
    private var attachmentIdCounter = 0

    /**
     * 附件大小上限：50 MB。超过即拒绝拷贝，避免大文件静默耗尽内部存储
     * （取 50 MB 而非 Runtime.maxMemory()/4，以获得稳定可预期的大小阈值）。
     */
    private val maxAttachmentBytes: Long = 50L * 1024L * 1024L

    init {
        // 启动预测性附件预处理清理循环（每 5 分钟清理 30 分钟前的预拷贝文件）
        preprocessor.startCleanupLoop(scope)
    }

    /**
     * 处理文件附件。立即添加 UPLOADING 占位项，IO 线程读取真实元数据后回填。
     * 同时触发预测性预处理（后台拷贝到沙箱），发送时零等待。
     */
    fun attachFile(uri: Uri) {
        val id = attachmentIdCounter++
        // 先添加一个 UPLOADING 状态的占位项，UI 立即响应；
        // 用 id 标记占位项，异步回填时按 id 精确匹配（缺陷 2 修复：避免 lastIndex 竞态）。
        _attachments.update {
            it + Attachment(
                uri = uri,
                name = "读取中...",
                mimeType = "application/octet-stream",
                sizeBytes = 0,
                type = AttachmentType.FILE,
                uploadProgress = 0f,
                status = UploadStatus.UPLOADING,
                attachmentId = id
            )
        }

        attachmentJobs[id] = scope.launch(Dispatchers.IO) {
            try {
                val meta = getFileMetadataSafe(uri)
                // 缺陷 3 修复：超限文件直接置 ERROR，不启动后续拷贝/预处理。
                if (meta.sizeBytes > maxAttachmentBytes) {
                    _attachments.update { list ->
                        list.map { att ->
                            if (att.attachmentId == id && att.status == UploadStatus.UPLOADING) {
                                att.copy(
                                    status = UploadStatus.ERROR,
                                    name = "文件过大（${meta.sizeBytes} bytes，上限 ${maxAttachmentBytes} bytes）"
                                )
                            } else att
                        }
                    }
                    return@launch
                }
                val info = meta.copy(
                    uploadProgress = 1.0f,
                    status = UploadStatus.SUCCESS,
                    attachmentId = id
                )
                _attachments.update { list ->
                    list.map { att ->
                        if (att.attachmentId == id && att.status == UploadStatus.UPLOADING) {
                            info
                        } else att
                    }
                }
                // ★ 触发预测性预处理：后台拷贝到沙箱，用户编辑文本时同步进行
                preprocessor.preprocess(uri, info.name)
            } catch (e: Exception) {
                _attachments.update { list ->
                    list.map { att ->
                        if (att.attachmentId == id && att.status == UploadStatus.UPLOADING) {
                            att.copy(status = UploadStatus.ERROR, name = "读取失败")
                        } else att
                    }
                }
            }
        }
    }

    /**
     * 处理图片附件。立即添加 UPLOADING 占位项，IO 线程读取真实元数据后回填。
     * 同时触发预测性预处理（后台拷贝到沙箱），发送时零等待。
     */
    fun attachImage(uri: Uri) {
        val id = attachmentIdCounter++
        _attachments.update {
            it + Attachment(
                uri = uri,
                name = "读取中...",
                mimeType = "image/*",
                sizeBytes = 0,
                type = AttachmentType.IMAGE,
                uploadProgress = 0f,
                status = UploadStatus.UPLOADING,
                attachmentId = id
            )
        }

        attachmentJobs[id] = scope.launch(Dispatchers.IO) {
            try {
                val meta = getFileMetadataSafe(uri)
                // 缺陷 3 修复：超限图片直接置 ERROR，不启动后续拷贝/预处理。
                if (meta.sizeBytes > maxAttachmentBytes) {
                    _attachments.update { list ->
                        list.map { att ->
                            if (att.attachmentId == id && att.status == UploadStatus.UPLOADING) {
                                att.copy(
                                    status = UploadStatus.ERROR,
                                    name = "文件过大（${meta.sizeBytes} bytes，上限 ${maxAttachmentBytes} bytes）"
                                )
                            } else att
                        }
                    }
                    return@launch
                }
                val info = meta.copy(
                    type = AttachmentType.IMAGE,
                    uploadProgress = 1.0f,
                    status = UploadStatus.SUCCESS,
                    attachmentId = id
                )
                _attachments.update { list ->
                    list.map { att ->
                        if (att.attachmentId == id && att.status == UploadStatus.UPLOADING) {
                            info
                        } else att
                    }
                }
                // ★ 触发预测性预处理
                preprocessor.preprocess(uri, info.name)
            } catch (e: Exception) {
                _attachments.update { list ->
                    list.map { att ->
                        if (att.attachmentId == id && att.status == UploadStatus.UPLOADING) {
                            att.copy(status = UploadStatus.ERROR, name = "读取失败")
                        } else att
                    }
                }
            }
        }
    }

    /**
     * 移除附件。同时取消对应的元数据读取 Job 和预测性预拷贝。
     */
    fun removeAttachment(index: Int) {
        val removed = _attachments.value.getOrNull(index)
        // 缺陷 1 修复：只取消被移除附件对应的 Job，不影响其它进行中的元数据读取。
        // 旧实现 attachmentJobs.values.forEach { it.cancel() } 会取消全部附件的 Job。
        removed?.attachmentId?.let { id ->
            attachmentJobs.remove(id)?.cancel()
        }
        // 取消被移除附件的预测性预拷贝
        removed?.uri?.let { preprocessor.cancel(it) }
        _attachments.update { list ->
            list.filterIndexed { i, _ -> i != index }
        }
    }

    /**
     * 收集并清空当前附件列表（发送消息时调用）。
     *
     * ★ 缺陷 2 修复：无条件收集并清空附件，避免斜杠指令分支 return 后附件永久残留。
     */
    fun drainAttachments(): List<Attachment> {
        val snapshot = _attachments.value.toList()
        _attachments.value = emptyList()
        return snapshot
    }

    /**
     * 取消全部附件处理 Job 并停止预测性预处理清理循环
     * （ViewModel onCleared 时调用）。
     */
    fun dispose() {
        attachmentJobs.values.forEach { it.cancel() }
        preprocessor.stopCleanupLoop()
    }

    // ═══════════════════════════════════════════════════════════
    // 异步 I/O 工具方法（缺陷 1 修复）
    // ═══════════════════════════════════════════════════════════

    /**
     * 异步读取附件元数据。必须在 IO 调度器中调用。
     *
     * ContentResolver.query() 走 Binder IPC 到 MediaProvider，可能阻塞 2-5 秒。
     */
    private suspend fun getFileMetadataSafe(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "unknown_file"
        var mimeType = "application/octet-stream"
        var size = 0L

        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            // ContentProvider 可能已失效（如临时权限过期）
            name = "file_${System.currentTimeMillis()}"
        }

        mimeType = try {
            resolver.getType(uri) ?: mimeType
        } catch (e: Exception) {
            mimeType
        }

        val type = when {
            mimeType.startsWith("image/") -> AttachmentType.IMAGE
            mimeType.startsWith("audio/") -> AttachmentType.AUDIO
            mimeType.startsWith("video/") -> AttachmentType.VIDEO
            mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("rar") -> AttachmentType.ARCHIVE
            else -> AttachmentType.FILE
        }

        Attachment(uri, name, mimeType, size, type)
    }

    /**
     * 异步拷贝附件到应用沙箱。
     *
     * 修复点：
     * - 64KB buffer（比默认 8KB 快 8 倍，匹配 UFS/eMMC optimal I/O block）；
     * - [ensureActive] 协程取消检查点，用户移除附件时立即停止拷贝；
     * - 落盘失败抛出异常，由调用方处理。
     */
    suspend fun copyToSandboxSafe(
        uri: Uri,
        fileName: String
    ): String = withContext(Dispatchers.IO) {
        // 缺陷 3 修复：拷贝前再做一次大小校验（防御性）。attachFile/attachImage 已在
        // 读取元数据时拒绝超限文件，但发送时若附件仍处于 ERROR 状态或预拷贝缺失，
        // 会走到这里；超限则抛异常，由 executeNormalMessage 的 try/catch 转 Error 提示，
        // 避免大文件被 64KB 流式拷入 filesDir 静默耗尽内部存储。
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && idx >= 0) c.getLong(idx) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        // size<0 表示查不到 SIZE 列（如某些 content:// 协议），放行由后续拷贝自然失败。
        if (size >= 0 && size > maxAttachmentBytes) {
            throw IllegalStateException("文件过大（$size bytes，上限 $maxAttachmentBytes bytes）")
        }

        val targetDir = java.io.File(context.filesDir, "attachments")
        targetDir.mkdirs()
        val targetFile = java.io.File(targetDir, "${System.currentTimeMillis()}_$fileName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    // 协程取消检查点
                    ensureActive()
                    output.write(buffer, 0, len)
                }
                output.flush()
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")

        targetFile.absolutePath
    }
}
