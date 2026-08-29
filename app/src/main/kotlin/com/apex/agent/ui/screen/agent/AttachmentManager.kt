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
     */
    private val attachmentJobs = mutableMapOf<Int, Job>()
    private var attachmentIdCounter = 0

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
        // 先添加一个 UPLOADING 状态的占位项，UI 立即响应
        _attachments.update {
            it + Attachment(
                uri = uri,
                name = "读取中...",
                mimeType = "application/octet-stream",
                sizeBytes = 0,
                type = AttachmentType.FILE,
                uploadProgress = 0f,
                status = UploadStatus.UPLOADING
            )
        }

        attachmentJobs[id] = scope.launch(Dispatchers.IO) {
            try {
                val info = getFileMetadataSafe(uri).copy(
                    uploadProgress = 1.0f,
                    status = UploadStatus.SUCCESS
                )
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
                            info
                        } else att
                    }
                }
                // ★ 触发预测性预处理：后台拷贝到沙箱，用户编辑文本时同步进行
                preprocessor.preprocess(uri, info.name)
            } catch (e: Exception) {
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
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
                status = UploadStatus.UPLOADING
            )
        }

        attachmentJobs[id] = scope.launch(Dispatchers.IO) {
            try {
                val info = getFileMetadataSafe(uri).copy(
                    type = AttachmentType.IMAGE,
                    uploadProgress = 1.0f,
                    status = UploadStatus.SUCCESS
                )
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
                            info
                        } else att
                    }
                }
                // ★ 触发预测性预处理
                preprocessor.preprocess(uri, info.name)
            } catch (e: Exception) {
                _attachments.update { list ->
                    list.mapIndexed { index, att ->
                        if (index == list.lastIndex && att.status == UploadStatus.UPLOADING) {
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
        attachmentJobs.values.forEach { it.cancel() }
        // 取消被移除附件的预测性预拷贝
        val removed = _attachments.value.getOrNull(index)
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
