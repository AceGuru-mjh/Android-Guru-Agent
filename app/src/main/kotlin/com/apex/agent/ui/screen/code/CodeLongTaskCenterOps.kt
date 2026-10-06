package com.apex.agent.ui.screen.code

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.code.longtask.LongTaskCopyOptions
import com.apex.agent.core.code.longtask.LongTaskDiff
import com.apex.agent.core.code.longtask.LongTaskTemplates
import com.apex.agent.ui.screen.code.longtask.LongTaskExporter
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// 长任务中心（v1.2）—— CodeViewModel 的 internal 扩展（God-file 预算拆分，
// 模式同 AgentChatHistoryController.kt：调用点无感知，依赖的成员已开放
// internal；CodeScreen 同包直取扩展成员，方法引用语义不变）。
//
// 职责（全部原样迁移自 CodeViewModel v1.2 段落，零行为变化）：
//  - 面板开关与数据刷新（当前工作区长任务记录 + 档位效能统计）；
//  - 复制链：copyTask（parentTaskId 链）→ 可选立即重跑 / relaunchTask /
//    resumeTask（检查点续跑，无检查点回退重跑）；
//  - deleteLongTask / compareWithParent（复制链差异对比 → 系统消息）；
//  - startFromTemplate（内置模板：推荐档位 + todo 骨架 + goal 发送）。
//
// 运行态归属对照：
//  - run 生命周期钩子（beginRun/onEvent/endRun/ingest）仍在 VM 的
//    runEngine 内（见 CodeViewModel「事件归约」半边——与渲染通道耦合）；
//  - 本文件只管「面板打开后的用户操作流」，错误经 uiState.error 通道。
// ─────────────────────────────────────────────────────────────────────────────

/** 打开长任务面板（异步加载当前工作区的长任务记录 + 档位效能统计）。 */
fun CodeViewModel.openLongTaskCenter() {
    _uiState.update { it.copy(longTaskSheetVisible = true, longTaskLoading = true) }
    refreshLongTasks()
    refreshThinkingStats()
}

fun CodeViewModel.closeLongTaskCenter() {
    _uiState.update { it.copy(longTaskSheetVisible = false) }
}

/** 刷新长任务列表（IO 读存储；面板可见或收尾入库后调用）。 */
internal fun CodeViewModel.refreshLongTasks() {
    val wsId = boundWorkspaceId
    if (wsId == null) {
        _uiState.update { it.copy(longTasks = emptyList(), longTaskLoading = false) }
        return
    }
    viewModelScope.launch {
        val records = withContext(Dispatchers.IO) {
            runCatching { longTaskStore.list(wsId) }.getOrDefault(emptyList())
        }
        _uiState.update { it.copy(longTasks = records, longTaskLoading = false) }
    }
}

/** 刷新档位效能统计（IO：首次访问会同步扫一次统计文件）。 */
private fun CodeViewModel.refreshThinkingStats() {
    val wsId = boundWorkspaceId
    if (wsId == null) {
        _uiState.update { it.copy(thinkingStats = null) }
        return
    }
    viewModelScope.launch {
        val stats = withContext(Dispatchers.IO) {
            runCatching { thinkingEvolutionTracker.statsFor(wsId) }.getOrNull()
        }
        _uiState.update { it.copy(thinkingStats = stats) }
    }
}

/**
 * 复制任务（顶级优化核心）：源记录 → 新副本（parentTaskId 链）→
 * 可选立即重跑（buildRelaunchPrompt 组装上下文后 sendMessage）。
 */
fun CodeViewModel.copyTask(id: String, options: LongTaskCopyOptions, relaunch: Boolean) {
    if (_uiState.value.isRunning) {
        showError("任务运行中，不能复制重跑")
        return
    }
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { taskCopyEngine.copy(id, options) }
        result.onSuccess { copy ->
            refreshLongTasks()
            if (relaunch) {
                val prompt = taskCopyEngine.buildRelaunchPrompt(copy, options)
                closeLongTaskCenter()
                sendMessage(prompt)
            } else {
                appendSystemMessage("已创建任务副本「${copy.title}」（可从长任务面板重跑）")
            }
        }.onFailure { e ->
            showError("复制任务失败：${e.message ?: "未知错误"}")
        }
    }
}

/** 直接重跑一条历史记录（默认携带上下文/todos/文件清单）。 */
fun CodeViewModel.relaunchTask(id: String) {
    if (_uiState.value.isRunning) {
        showError("任务运行中，不能重跑")
        return
    }
    viewModelScope.launch {
        val record = withContext(Dispatchers.IO) { longTaskStore.get(id) }
        if (record == null) {
            showError("任务记录不存在")
            return@launch
        }
        val prompt = taskCopyEngine.buildRelaunchPrompt(
            record,
            LongTaskCopyOptions(includeConversation = true, includeTodos = true, includeFilesList = true)
        )
        closeLongTaskCenter()
        sendMessage(prompt)
    }
}

/**
 * 从检查点**续跑**（顶级优化：不从头重跑，接着干）。
 *
 * @param checkpointId 指定检查点；null = 最后一个检查点。记录无检查点
 *   时自动回退到 relaunchTask 语义（无进度可续，只能重跑）。
 */
fun CodeViewModel.resumeTask(id: String, checkpointId: String? = null) {
    if (_uiState.value.isRunning) {
        showError("任务运行中，不能续跑")
        return
    }
    viewModelScope.launch {
        val record = withContext(Dispatchers.IO) { longTaskStore.get(id) }
        if (record == null) {
            showError("任务记录不存在")
            return@launch
        }
        val resumePrompt = taskCopyEngine.buildResumePrompt(record, checkpointId)
        closeLongTaskCenter()
        if (resumePrompt.isNotEmpty()) {
            sendMessage(resumePrompt)
        } else {
            // 无检查点可续 → 回退重跑（并在对话里说明）
            appendSystemMessage("该任务无检查点可续跑，已改为带上下文重跑")
            relaunchTask(id)
        }
    }
}

/** 删除一条长任务记录。 */
fun CodeViewModel.deleteLongTask(id: String) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { longTaskStore.delete(id) } }
        refreshLongTasks()
    }
}

/** 与父任务对比运行差异（复制链对比，结果以系统消息形式进对话）。 */
fun CodeViewModel.compareWithParent(id: String) {
    viewModelScope.launch {
        val record = withContext(Dispatchers.IO) { longTaskStore.get(id) }
        val parentId = record?.parentTaskId
        if (record == null || parentId == null) {
            showError("无父任务可对比（非复制运行）")
            return@launch
        }
        val parent = withContext(Dispatchers.IO) { longTaskStore.get(parentId) }
        if (parent == null) {
            showError("父任务记录已删除")
            return@launch
        }
        val diff = LongTaskDiff.compare(parent, record)
        appendSystemMessage(LongTaskDiff.renderText(diff, parent.title, record.title))
    }
}

/** 从内置模板启动任务：应用推荐档位 + todo 骨架 + goal 模板发送。 */
fun CodeViewModel.startFromTemplate(key: String) {
    val template = LongTaskTemplates.byKey(key) ?: return
    val ws = _uiState.value.activeWorkspace
    val record = LongTaskTemplates.instantiate(
        template,
        ws?.workspaceId ?: "",
        ws?.name ?: ""
    )
    // 推荐档位落地（持久化 + 引擎三通道 + 原生 effort 同步）
    CodeThinkingLevel.fromName(template.recommendedThinkingLevel)
        ?.let { setThinkingLevel(it) }
    // todo 骨架预置（pending 状态，模型后续可改写）
    runCatching {
        codeTodoTool.restore(
            template.todoSkeleton.map { CodeTodoTool.Todo(content = it, status = "pending", priority = "medium") }
        )
    }.onFailure { AppLogger.instance.warn(LogCategory.UI, "LongTask", "模板 todo 预置失败：${it.message}") }
    _uiState.update { it.copy(todos = codeTodoTool.snapshot()) }
    closeLongTaskCenter()
    sendMessage(record.goal)
}

/**
 * 导出长任务记录（#184：JSON + Markdown 双格式落盘 → 系统分享面板）。
 *
 * CodeScreen 的 onExport 回调消费；导出/落盘 IO 在 Dispatchers.IO，
 * 分享面板与失败 Toast 回主线程（viewModelScope 默认 Main）。
 * 落盘失败返回 null → Toast 兜底（LongTaskExporter 内部已留痕）。
 */
fun CodeViewModel.exportLongTask(context: Context, id: String) {
    viewModelScope.launch {
        val record = withContext(Dispatchers.IO) { longTaskStore.get(id) }
        if (record == null) {
            showError("任务记录不存在")
            return@launch
        }
        val result = withContext(Dispatchers.IO) { LongTaskExporter.export(context, record) }
        when {
            result == null ->
                Toast.makeText(context, "导出失败，请重试", Toast.LENGTH_SHORT).show()
            !LongTaskExporter.share(context, result) ->
                Toast.makeText(context, "分享面板拉起失败", Toast.LENGTH_SHORT).show()
        }
    }
}
