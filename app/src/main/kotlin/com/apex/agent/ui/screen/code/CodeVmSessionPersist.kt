package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.stream.CodeStreamCheckpoint
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import com.apex.agent.ui.screen.code.session.toCodeTodos
import com.apex.agent.ui.screen.code.session.toStorable
import com.apex.agent.ui.screen.code.session.toStreamEntries
import com.apex.agent.ui.screen.code.session.withFreshIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════
// 会话持久化三件套（自 CodeViewModel 迁出，God-file 预算腾挪）
// ═══════════════════════════════════════════════════════════════
// 模式同 AgentChatHistoryController：同包 internal 扩展 + 成员直调，
// 调用点（bindWorkspace / reduce / onCleared）零改动。
//  - restoreSessionSnapshot：工作区切换时恢复 UI 快照（消息/todos/时间轴）
//  - buildSessionSnapshot：当前 UI 态 → 快照（含 stream 检查点）
//  - scheduleSessionPersist：800ms 防抖落盘

/** 恢复当前工作区的 UI 会话快照；无快照 = 全新会话（欢迎提示）。 */
internal suspend fun CodeViewModel.restoreSessionSnapshot(ws: CodeWorkspace) {
    val snapshot = withContext(Dispatchers.IO) {
        runCatching { codeSessionStore.load(ws.workspaceId) }.getOrNull()
    }
    if (snapshot != null && (snapshot.messages.isNotEmpty() || snapshot.stream != null)) {
        val restored = snapshot.messages.withFreshIds(1L)
        idGen.set(restored.lastOrNull()?.id ?: 0L)
        codeTodoTool.restore(snapshot.todos.toCodeTodos())
        // 时间轴恢复：stream 检查点优先（含 diff 原文/轮次红绿态）；
        // 旧档（null）走 messages → 条目的兼容映射（降级：无 diff 细节）
        val timeline = snapshot.stream
            ?.let { cp -> CodeStreamCheckpoint.toEntries(cp) }
            ?: snapshot.messages.toStreamEntries()
        streamSession.replaceAll(timeline)
        _uiState.update {
            it.copy(
                messages = restored,
                todos = snapshot.todos.toCodeTodos(),
                stream = streamSession.snapshot()
            )
        }
        snapshot.lastActiveFile?.let { lastFile -> openEditorFile(lastFile) }
    } else {
        // 全新会话：清 UI 态（引擎侧 setActiveWorkspace 已重置上下文）+ 欢迎提示
        codeTodoTool.clear()
        // P1 回归：时间轴与 streamSession 必须一并清空——否则工作区 A 的
        // 胶囊时间轴泄漏进新工作区 B，并经 buildSessionSnapshot 污染 B 的
        // 落盘检查点（跨工作区数据污染被持久化）
        streamSession.clear()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                todos = emptyList(),
                stream = CodeStreamSnapshot()
            )
        }
        val env = ws.detectedEnvironment ?: "空工作区"
        _uiState.update { state ->
            state.copy(
                messages = state.messages + CodeChatMessage(
                    id = idGen.incrementAndGet(),
                    role = CodeChatMessage.Role.SYSTEM,
                    text = "已切换到工作区「${ws.name}」（$env）。描述你的编码任务开始吧。"
                )
            )
        }
    }
}

/** 从当前 UI 态构造会话快照（消息 + todos + 当前文件 + 时间轴检查点）。 */
internal fun CodeViewModel.buildSessionSnapshot(workspaceId: String): CodeSessionSnapshot {
    val state = _uiState.value
    return CodeSessionSnapshot(
        workspaceId = workspaceId,
        messages = state.messages.toStorable(),
        todos = codeTodoTool.snapshot().toStorable(),
        lastActiveFile = state.editorFilePath,
        stream = CodeStreamCheckpoint.toCheckpoint(
            session = streamSession,
            workspaceId = workspaceId,
            committedFiles = state.stream.affectedFiles
        ),
        updatedAt = System.currentTimeMillis()
    )
}

/** 会话快照防抖落盘（800ms；对齐 Agent 模式 ChatHistoryManager 惯例）。 */
internal fun CodeViewModel.scheduleSessionPersist() {
    val wsId = boundWorkspaceId ?: return
    sessionPersistJob?.cancel()
    sessionPersistJob = viewModelScope.launch {
        delay(800)
        // 守卫：防抖期间工作区已切换 → 旧快照已由 bindWorkspace 冲刷，跳过
        if (boundWorkspaceId != wsId) return@launch
        val snapshot = buildSessionSnapshot(wsId)
        withContext(Dispatchers.IO) { runCatching { codeSessionStore.save(snapshot) } }
            .onFailure { AppLogger.instance.warn(LogCategory.UI, "CodeSession", "会话快照落盘失败：${it.message}") }
    }
}
