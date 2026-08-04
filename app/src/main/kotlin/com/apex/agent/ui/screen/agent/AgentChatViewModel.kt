package com.apex.agent.ui.screen.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.*
import com.apex.agent.core.llm.ReasoningEffort
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Agent 对话界面状态
 *
 * 保留与旧 ChatUiState 相同的字段名（currentResponse / currentThinking / currentToolCall），
 * ApexDrawerContent 已依赖这些字段显示模式/思考深度/记忆深度。
 */
data class AgentChatUiState(
    val messages: List<AgentUiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val currentThinking: String = "",       // 当前思考内容（流式）
    val currentResponse: String = "",       // 当前回复内容（流式）
    val currentToolCall: AgentToolCallUi? = null, // 当前执行的工具
    val mode: AgentMode = AgentMode.BUILD,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.STANDARD,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val plan: ExecutionPlan? = null,
    val awaitingPlanConfirmation: Boolean = false,
    val historyDepth: Int = 0
)

sealed interface AgentUiMessage {
    data class User(
        val text: String,
        val attachments: List<MessageAttachment> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    ) : AgentUiMessage
    data class Agent(val text: String, val timestamp: Long = System.currentTimeMillis()) : AgentUiMessage
    data class ToolCall(
        val toolName: String,
        val args: String,
        val output: String? = null,
        val success: Boolean? = null,
        val durationMs: Long = 0
    ) : AgentUiMessage
    data class System(val text: String) : AgentUiMessage
    data class PlanMessage(val plan: ExecutionPlan) : AgentUiMessage
    data class ThinkingMessage(val thought: String) : AgentUiMessage
}

data class AgentToolCallUi(
    val toolName: String,
    val args: String,
    val isRunning: Boolean = true
)

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine,
    private val memory: ConversationMemory,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentChatUiState(historyDepth = memory.count()))
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private var currentJob: Job? = null

    /**
     * 发送消息
     */
    fun sendMessage(text: String) {
        if (text.isBlank() && _attachments.value.isEmpty()) return
        if (_uiState.value.isLoading) return

        // 检查是否是斜杠指令
        if (text.startsWith("/")) {
            handleSlashCommand(text)
            return
        }

        // 收集当前附件并清空
        val currentAttachments = _attachments.value.toList()
        _attachments.value = emptyList()

        // 将附件复制到应用沙箱（持久化）
        val persistedAttachments = currentAttachments.map { att ->
            val localPath = copyToSandbox(att.uri, att.name)
            MessageAttachment(
                name = att.name,
                mimeType = att.mimeType,
                sizeBytes = att.sizeBytes,
                type = att.type,
                localPath = localPath,
                thumbnailUri = if (att.type == AttachmentType.IMAGE) att.uri else null
            )
        }

        _uiState.update { state ->
            state.copy(
                messages = state.messages + AgentUiMessage.User(
                    text = text,
                    attachments = persistedAttachments
                ),
                isLoading = true,
                currentThinking = "",
                currentResponse = ""
            )
        }

        // 将附件路径告知 Agent
        val attachmentContext = if (persistedAttachments.isNotEmpty()) {
            val fileList = persistedAttachments.joinToString("\n") { "  - ${it.localPath} (${it.name})" }
            "[用户附加了 ${persistedAttachments.size} 个文件]\n$fileList\n\n用户消息: $text"
        } else text

        currentJob = viewModelScope.launch {
            agentEngine.execute(attachmentContext).collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            // ═══ 思考 ═══
            is AgentEvent.ThinkingStart -> {
                _uiState.update { it.copy(currentThinking = "") }
            }
            is AgentEvent.ThinkingChunk -> {
                _uiState.update {
                    it.copy(currentThinking = it.currentThinking + event.text)
                }
            }
            is AgentEvent.ThinkingComplete -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.ThinkingMessage(event.fullThought),
                        currentThinking = ""
                    )
                }
            }

            // ═══ Plan模式 ═══
            is AgentEvent.PlanGenerated -> {
                _uiState.update { it.copy(plan = event.plan) }
            }
            is AgentEvent.PlanAwaitingConfirmation -> {
                _uiState.update { it.copy(awaitingPlanConfirmation = true) }
            }
            is AgentEvent.PlanConfirmed -> {
                _uiState.update { state ->
                    state.copy(
                        awaitingPlanConfirmation = false,
                        messages = state.messages + AgentUiMessage.PlanMessage(event.plan)
                    )
                }
            }

            // ═══ 工具调用 ═══
            is AgentEvent.ToolCallStart -> {
                _uiState.update { state ->
                    state.copy(
                        currentToolCall = AgentToolCallUi(
                            toolName = event.toolName,
                            args = event.arguments,
                            isRunning = true
                        )
                    )
                }
            }
            is AgentEvent.ToolCallComplete -> {
                _uiState.update { state ->
                    state.copy(
                        currentToolCall = null,
                        messages = state.messages + AgentUiMessage.ToolCall(
                            toolName = event.toolName,
                            args = "",
                            output = event.output.take(500),
                            success = event.success,
                            durationMs = event.durationMs
                        )
                    )
                }
            }

            // ═══ 流式回复 ═══
            is AgentEvent.ResponseChunk -> {
                _uiState.update {
                    it.copy(currentResponse = it.currentResponse + event.text)
                }
            }
            is AgentEvent.ResponseComplete -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.Agent(event.fullText),
                        currentResponse = "",
                        isLoading = false
                    )
                }
            }

            // ═══ 压缩 ═══
            is AgentEvent.ContextCompressed -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.System(
                            "📦 Context compressed: ${event.beforeTokens}→${event.afterTokens} tokens " +
                            "(${event.strategy}, removed ${event.messagesRemoved} msgs" +
                            (if (event.messagesTruncated > 0) ", truncated ${event.messagesTruncated}" else "") +
                            ")"
                        )
                    )
                }
            }

            // ═══ 错误/完成 ═══
            is AgentEvent.Error -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.System("❌ ${event.message}"),
                        isLoading = false
                    )
                }
            }
            is AgentEvent.Complete -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        historyDepth = (agentEngine as? ApexAgentEngine)?.historyCount() ?: it.historyDepth
                    )
                }
            }
            is AgentEvent.Aborted -> {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + AgentUiMessage.System("⏹ 已中止"),
                        isLoading = false
                    )
                }
            }

            else -> {}
        }
    }

    fun setMode(mode: AgentMode) {
        _uiState.update { it.copy(mode = mode) }
        (agentEngine as? ApexAgentEngine)?.updateConfig(
            AgentConfig(mode = mode, thinkingLevel = _uiState.value.thinkingLevel)
        )
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        _uiState.update { it.copy(thinkingLevel = level) }
        (agentEngine as? ApexAgentEngine)?.updateConfig(
            AgentConfig(mode = _uiState.value.mode, thinkingLevel = level)
        )
    }

    fun confirmPlan(confirmed: Boolean) {
        _uiState.update { it.copy(awaitingPlanConfirmation = false) }
        (agentEngine as? ApexAgentEngine)?.submitPlanConfirmation(confirmed)
    }

    fun abort() {
        currentJob?.cancel()
        viewModelScope.launch { agentEngine.abort() }
    }

    fun newChat() {
        currentJob?.cancel()
        viewModelScope.launch {
            (agentEngine as? ApexAgentEngine)?.clearHistory()
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    currentThinking = "",
                    currentResponse = "",
                    currentToolCall = null,
                    plan = null,
                    awaitingPlanConfirmation = false,
                    isLoading = false,
                    historyDepth = 0
                )
            }
        }
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        context.getSharedPreferences("apex_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("llm_reasoning_effort", effort.name)
            .apply()
        _uiState.update { it.copy(reasoningEffort = effort) }
    }

    // ═══ 附件处理 ═══

    /**
     * 处理文件附件
     */
    fun attachFile(uri: Uri) {
        val info = getFileMetadata(uri)
        _attachments.update { it + info }
    }

    /**
     * 处理图片附件
     */
    fun attachImage(uri: Uri) {
        val info = getFileMetadata(uri).copy(type = AttachmentType.IMAGE)
        _attachments.update { it + info }
    }

    /**
     * 移除附件
     */
    fun removeAttachment(index: Int) {
        _attachments.update { list ->
            list.filterIndexed { i, _ -> i != index }
        }
    }

    // ═══ 斜杠指令处理 ═══

    /**
     * 处理斜杠指令
     */
    private fun handleSlashCommand(command: String) {
        val parts = command.split(":", limit = 2)
        val type = parts[0].removePrefix("/")
        val name = parts.getOrNull(1)?.trim() ?: ""

        val message = when (type) {
            "skill" -> "🧩 激活 Skill: $name"
            "mcp" -> "🔌 连接 MCP: $name"
            "connector" -> "🔗 使用连接器: $name"
            "plugin" -> "📦 调用插件: $name"
            else -> "未知指令: $command"
        }

        _uiState.update { s ->
            s.copy(messages = s.messages + AgentUiMessage.System(message))
        }
    }

    private fun getFileMetadata(uri: Uri): Attachment {
        val resolver = context.contentResolver
        var name = "unknown_file"
        var mimeType = "application/octet-stream"
        var size = 0L

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        mimeType = resolver.getType(uri) ?: mimeType

        val type = when {
            mimeType.startsWith("image/") -> AttachmentType.IMAGE
            mimeType.startsWith("audio/") -> AttachmentType.AUDIO
            mimeType.startsWith("video/") -> AttachmentType.VIDEO
            mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("rar") -> AttachmentType.ARCHIVE
            else -> AttachmentType.FILE
        }

        return Attachment(uri, name, mimeType, size, type)
    }

    private fun copyToSandbox(uri: Uri, fileName: String): String {
        val targetDir = java.io.File(context.filesDir, "attachments")
        targetDir.mkdirs()
        val targetFile = java.io.File(targetDir, "${System.currentTimeMillis()}_$fileName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return targetFile.absolutePath
    }
}
