package com.apex.agent.ui.screen.agent

import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.update

// ═══════════════════════════════════════════════════════════════
// UX-1：消息操作（长按菜单 / overflow 菜单）
// ═══════════════════════════════════════════════════════════════
// 独立文件原因：AgentChatViewModel.kt 已贴 1200 行 God-file 门禁
// （scripts/check_file_size.sh），消息删除/重生成属于"列表编辑"职责，
// 以 internal 扩展函数形式挂靠 VM（与 AgentChatQuestionHandler.kt 同款拆分模式）。

/**
 * 删除单条消息（UI 内存列表过滤；不触碰引擎对话历史）。
 *
 * - 门禁：生成中（isLoading）拒绝执行并给出反馈，避免与流式追加交错；
 * - 列表删空时同步复位流式暂态（currentToolCall / currentResponse /
 *   currentThinking），防止悬挂的"运行中工具卡"残留（与 newChat 的复位面一致，
 *   但不清引擎历史、不清 plan/spec 等会话级状态）。
 */
fun AgentChatViewModel.deleteMessage(id: String) {
    if (_uiState.value.isLoading) {
        _uiFeedback.tryEmit("正在生成回复，稍后再删除")
        return
    }
    _uiState.update { s ->
        val next = s.messages.filterNot { it.id == id }
        if (next.size == s.messages.size) return@update s // id 不存在：no-op
        if (next.isEmpty()) {
            s.copy(
                messages = next,
                currentToolCall = null,
                currentResponse = "",
                currentThinking = ""
            )
        } else {
            s.copy(messages = next)
        }
    }
}

/**
 * 截断式删除：移除 [id] 这条消息及其之后的全部消息（"从此处重来"）。
 *
 * - 门禁与暂态复位策略同 [deleteMessage]；
 * - 引擎侧 conversationHistory 不同步截断（引擎未暴露历史编辑 API，
 *   见 ApexAgentEngine —— 仅 clearHistory() 全清）。UI 截断只影响展示，
 *   后续新消息仍带完整引擎上下文，属已知边界（诚实取舍，不静默伪造一致性）。
 */
fun AgentChatViewModel.deleteMessagesFrom(id: String) {
    if (_uiState.value.isLoading) {
        _uiFeedback.tryEmit("正在生成回复，稍后再删除")
        return
    }
    _uiState.update { s ->
        val idx = s.messages.indexOfFirst { it.id == id }
        if (idx < 0) return@update s
        val next = s.messages.take(idx)
        if (next.isEmpty()) {
            s.copy(
                messages = next,
                currentToolCall = null,
                currentResponse = "",
                currentThinking = ""
            )
        } else {
            s.copy(messages = next)
        }
    }
}

/**
 * 重生成此回复（仅 AI 消息菜单项）。
 *
 * 语义：截断到最后一条触发它的 User 消息（含该 User 气泡一并移除），
 * 然后复用 [AgentChatViewModel.retry] 重发同文本 + 原附件（localPath 已落盘，
 * 直接透传不重新拷贝）。最终 UI 呈现 = [..., User(原文), 新生成回复...]。
 *
 * - isLoading 门禁：生成中拒绝（retry 会取消在途收集器，交错态不可预期）；
 * - 该 AI 消息之前无 User 气泡（如斜杠流水线产物）→ 提示不可重生成；
 * - 仅附件（空文本）User 消息 → retry 的空文本门禁同样生效，提示后放弃
 *   （截断发生在校验之后，列表不被破坏）。
 */
fun AgentChatViewModel.regenerateResponse(id: String) {
    val state = _uiState.value
    if (state.isLoading) {
        _uiFeedback.tryEmit("正在生成回复，稍后再重生成")
        return
    }
    val idx = state.messages.indexOfFirst { it.id == id }
    if (idx < 0) return
    // 找到该 AI 消息之前最近的一条 User 消息（触发它的那条输入）
    val userIdx = (idx - 1 downTo 0).firstOrNull { i -> state.messages[i] is AgentUiMessage.User }
    if (userIdx == null) {
        _uiFeedback.tryEmit("此回复前没有可重发的用户消息")
        return
    }
    val user = state.messages[userIdx] as AgentUiMessage.User
    if (user.text.isBlank()) {
        _uiFeedback.tryEmit("仅附件消息暂不支持重生成")
        return
    }
    // 截断：移除该 User 气泡及其后全部（retry→runEngine 会重新追加 User 气泡）
    _uiState.update { s -> s.copy(messages = s.messages.take(userIdx)) }
    retry(user.text, user.attachments)
}

/**
 * 消息气泡操作菜单（M3 DropdownMenu，条目 ≥44dp 触达高度，图标 + 文字）。
 *
 * 入口有两处（见 UserBubble / AgentBubble）：
 * 1. 气泡头部（头像 / 角色标 / 时间戳区域，即"非文本区域"）长按 —— 文本区
 *    的长按仍归 SelectionContainer 文本选择，互不冲突；
 * 2. 头部右侧常驻 overflow 图标按钮（MoreVert）。
 *
 * [actionsEnabled]：流式生成期间禁用"重生成 / 删除"（破坏性操作与在途收集器
 * 交错不可预期）；"复制全文"始终可用。
 */
@Composable
internal fun MessageActionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    copyText: String,
    showRegenerate: Boolean,
    actionsEnabled: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDeleteFrom: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        DropdownMenuItem(
            text = { Text("复制全文") },
            leadingIcon = {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier.heightIn(min = 44.dp),
            onClick = {
                clipboard.setText(AnnotatedString(copyText))
                Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
                onDismissRequest()
            }
        )
        if (showRegenerate) {
            DropdownMenuItem(
                text = { Text("重生成此回复") },
                leadingIcon = {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.heightIn(min = 44.dp),
                enabled = actionsEnabled,
                onClick = {
                    onDismissRequest()
                    onRegenerate()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("删除此消息") },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier.heightIn(min = 44.dp),
            enabled = actionsEnabled,
            onClick = {
                onDismissRequest()
                onDelete()
            }
        )
        DropdownMenuItem(
            text = { Text("删除此消息及之后") },
            leadingIcon = {
                Icon(
                    Icons.Default.DeleteSweep, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier.heightIn(min = 44.dp),
            enabled = actionsEnabled,
            onClick = {
                onDismissRequest()
                onDeleteFrom()
            }
        )
    }
}
