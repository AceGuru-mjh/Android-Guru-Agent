package com.apex.agent.ui.screen.storage

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.attachment.AttachmentCleanupManager
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 存储与数据管理页 ViewModel。
 *
 * T76 审计 §7 缺口补齐：AttachmentCleanupManager 的存储用量/清理 API 此前只有
 * WorkManager 周期自清在调用，无用户入口；Ubuntu rootfs（数百 MB）无删除入口；
 * 全局会话历史（apex_memory: conversation_history）无导出/管理页 —— 本页统一承接。
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentCleanup: AttachmentCleanupManager,
    private val conversationMemory: ConversationMemory,
    private val ubuntuLifecycle: UbuntuLifecycleCoordinator
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val attachmentsSize: Long = 0L,
        val attachmentsCount: Int = 0,
        val conversationCount: Int = 0,
        val rootfsSize: Long? = null,
        val rootfsPhase: UbuntuLifecycleCoordinator.Phase = UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED,
        val logCount: Int = 0,
        val logBytes: Long = 0L,
        /** 破坏性操作进行中（清附件/清会话/删 rootfs）。 */
        val busy: Boolean = false,
        /** 结果反馈（UI 消费后调 consumeMessage）。 */
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val attSize = attachmentCleanup.getTotalSize()
            val attCount = attachmentCleanup.getFileCount()
            val convCount = withContext(Dispatchers.Default) {
                runCatching { conversationMemory.count() }.getOrDefault(0)
            }
            val rootfsSize = ubuntuLifecycle.rootfsSizeBytes()
            val rootfsPhase = ubuntuLifecycle.stateFlow.value.phase
            val logStats = AppLogger.instance.stats.value
            _uiState.update {
                it.copy(
                    loading = false,
                    attachmentsSize = attSize,
                    attachmentsCount = attCount,
                    conversationCount = convCount,
                    rootfsSize = rootfsSize,
                    rootfsPhase = rootfsPhase,
                    logCount = logStats.total,
                    logBytes = logStats.totalBytes
                )
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /** 清空附件沙箱（破坏性 —— UI 二次确认后调用）。 */
    fun clearAttachments() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            attachmentCleanup.clearAll()
            _uiState.update {
                it.copy(
                    busy = false,
                    message = "附件已清空",
                    attachmentsSize = 0L,
                    attachmentsCount = 0
                )
            }
        }
    }

    /** 清空全局会话历史（破坏性 —— UI 二次确认后调用）。 */
    fun clearConversation() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            withContext(Dispatchers.Default) {
                runCatching { conversationMemory.clear() }
                    .onFailure { Log.w("Storage", "clear conversation failed: ${it.message}") }
            }
            _uiState.update {
                it.copy(busy = false, message = "会话历史已清空", conversationCount = 0)
            }
        }
    }

    /** 导出会话历史为文本并分享（cacheDir 临时文件 + FileProvider）。 */
    fun exportConversation() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    val messages = conversationMemory.load()
                    if (messages.isEmpty()) return@runCatching null
                    val text = buildString {
                        appendLine("Apex Agent 会话历史导出")
                        appendLine("导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                        appendLine("消息数：${messages.size}")
                        appendLine("────────────────")
                        messages.forEach { m ->
                            val (role, content) = when (m) {
                                is com.apex.agent.core.llm.LlmMessage.System -> "System" to m.content
                                is com.apex.agent.core.llm.LlmMessage.User -> "User" to m.content
                                is com.apex.agent.core.llm.LlmMessage.Assistant -> "Assistant" to m.content
                                is com.apex.agent.core.llm.LlmMessage.ToolResult -> "ToolResult" to m.content
                            }
                            appendLine("[$role]")
                            appendLine(content)
                            appendLine()
                        }
                    }
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "apex-conversation-${System.currentTimeMillis()}.txt")
                    file.writeText(text)
                    file
                }.getOrNull()
            }
            _uiState.update { it.copy(busy = false) }
            if (exported == null) {
                _uiState.update { it.copy(message = "会话历史为空，无可导出内容") }
                return@launch
            }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", exported
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(
                        Intent.createChooser(intent, "导出会话历史")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "导出失败：${e.message?.take(80)}") }
            }
        }
    }

    /** 删除 Ubuntu rootfs（破坏性 —— UI 二次确认后调用；用户数据保留）。 */
    fun removeRootfs() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val r = ubuntuLifecycle.removeRootfs()
            val rootfsSize = ubuntuLifecycle.rootfsSizeBytes()
            val phase = ubuntuLifecycle.stateFlow.value.phase
            _uiState.update {
                it.copy(
                    busy = false,
                    message = r.message,
                    rootfsSize = rootfsSize,
                    rootfsPhase = phase
                )
            }
        }
    }
}
