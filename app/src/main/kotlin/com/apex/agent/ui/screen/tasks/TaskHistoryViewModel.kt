package com.apex.agent.ui.screen.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.task.AgentTask
import com.apex.agent.core.engine.task.TaskStatus
import com.apex.agent.ui.screen.agent.AgentTaskStatusController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 任务历史页 ViewModel —— T76 审计 §7 缺口补齐。
 *
 * TaskRuntime.loadTaskHistory()（FileTaskStore 全量任务：步骤/journal/checkpoint）
 * 此前注释明写「UI 历史列表数据源」但全仓库零 UI 调用 —— 本页接线该数据源，
 * 提供任务列表、统计与步骤回放（只读；控制操作仍在聊天页任务状态卡）。
 */
@HiltViewModel
class TaskHistoryViewModel @Inject constructor(
    private val taskController: AgentTaskStatusController
) : ViewModel() {

    data class TaskStats(
        val total: Int = 0,
        val active: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        val cancelled: Int = 0
    ) {
        val finished: Int get() = completed + failed + cancelled
    }

    data class UiState(
        val loading: Boolean = true,
        val tasks: List<AgentTask> = emptyList(),
        val stats: TaskStats = TaskStats(),
        /** 最近 7 天每日新建任务数（旧→新；epochDay → count）。 */
        val dailyCreated: List<Pair<Long, Int>> = emptyList(),
        /** 展开详情的任务 ID（null = 全收起）。 */
        val expandedTaskId: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val tasks = withContext(Dispatchers.IO) {
                runCatching { taskController.loadTaskHistory() }.getOrDefault(emptyList())
            }
            // 新的在前
            val sorted = tasks.sortedByDescending { it.createdAt }
            val stats = TaskStats(
                total = sorted.size,
                active = sorted.count { it.isActive },
                completed = sorted.count { it.status == TaskStatus.COMPLETED },
                failed = sorted.count { it.status == TaskStatus.FAILED },
                cancelled = sorted.count { it.status == TaskStatus.CANCELLED }
            )
            val daily = buildDailyCreated(sorted)
            _uiState.update {
                it.copy(loading = false, tasks = sorted, stats = stats, dailyCreated = daily)
            }
        }
    }

    fun toggleExpanded(taskId: String) {
        _uiState.update {
            it.copy(expandedTaskId = if (it.expandedTaskId == taskId) null else taskId)
        }
    }

    /** 最近 7 天（含今天）每日创建任务数（旧→新），epochDay 与计数对。 */
    private fun buildDailyCreated(tasks: List<AgentTask>): List<Pair<Long, Int>> {
        val today = System.currentTimeMillis() / 86_400_000L
        val counts = HashMap<Long, Int>()
        tasks.forEach { t ->
            val day = t.createdAt / 86_400_000L
            if (day >= today - 6) counts[day] = (counts[day] ?: 0) + 1
        }
        return (today - 6..today).map { day -> day to (counts[day] ?: 0) }
    }
}
