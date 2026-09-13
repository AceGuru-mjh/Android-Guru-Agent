package com.apex.agent.ui.screen.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.EpisodeSummary
import com.apex.agent.platform.csmem.store.FSMMacro
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆可视化页 ViewModel —— 提供 Episode / 宏 / 节点检索与管理。
 *
 * 只读为主；删除 Episode 为破坏性操作，由 UI 二次确认后调用 [deleteEpisode]。
 * T76 审计补齐：梦境巩固（DreamRenderer.dreamNow）与免疫系统隔离区
 * （MemoryImmuneSystem）能力此前无 UI 入口 —— 本 VM 接线。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val store: MemoryGraphStore,
    private val dreamRenderer: com.apex.agent.platform.csmem.dream.DreamRenderer,
    private val immuneSystem: com.apex.agent.platform.csmem.immune.MemoryImmuneSystem
) : ViewModel() {

    data class MemoryStats(
        val episodeCount: Int = 0,
        val nodeCount: Int = 0,
        val macroCount: Int = 0
    )

    private val _episodes = MutableStateFlow<List<EpisodeSummary>>(emptyList())
    val episodes: StateFlow<List<EpisodeSummary>> = _episodes.asStateFlow()

    private val _macros = MutableStateFlow<List<FSMMacro>>(emptyList())
    val macros: StateFlow<List<FSMMacro>> = _macros.asStateFlow()

    private val _stats = MutableStateFlow(MemoryStats())
    val stats: StateFlow<MemoryStats> = _stats.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SemanticNode>>(emptyList())
    val searchResults: StateFlow<List<SemanticNode>> = _searchResults.asStateFlow()

    /** 删除结果提示（如 "已删除 Episode xxx"），UI 消费后清空 */
    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    init {
        refresh()
        // P2-12（6-c）：搜索防抖——原 onSearch 每次击键即查 Room；改为 _searchQuery
        // 经 debounce(250) + distinctUntilChanged 统一触发检索（UI 调用点不变）。
        viewModelScope.launch {
            _searchQuery
                .debounce(250L)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                    } else {
                        _searchResults.value = runCatching {
                            store.searchNodesByText(query, limit = 50)
                        }.getOrDefault(emptyList())
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val eps = store.getRecentEpisodes(limit = 50)
                val macs = store.getTopMacros(limit = 20)
                val nodeCount = store.countNodes()
                val macroCount = store.countMacros()
                _episodes.value = eps
                _macros.value = macs
                _stats.value = MemoryStats(
                    episodeCount = eps.size,
                    nodeCount = nodeCount,
                    macroCount = macroCount
                )
            }.onFailure { e ->
                _lastMessage.value = "加载记忆失败：${e.message}"
            }
        }
    }

    fun onSearch(query: String) {
        // P2-12（6-c）：只更新查询流；实际检索由 init 中的 debounced collector 触发。
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun deleteEpisode(episodeId: String) {
        viewModelScope.launch {
            runCatching {
                val deleted = store.deleteEpisode(episodeId)
                if (deleted > 0) {
                    _lastMessage.value = "已删除 Episode $episodeId"
                    refresh()
                } else {
                    _lastMessage.value = "删除失败：$episodeId 不存在"
                }
            }.onFailure { e ->
                _lastMessage.value = "删除失败：${e.message}"
            }
        }
    }

    fun clearMessage() { _lastMessage.value = null }

    // ═══ 记忆健康：梦境巩固 + 免疫隔离区（T76 补齐入口）═══

    /** 隔离区规模（被隔离的可疑 UI 指纹条数；0 = 正常态）。 */
    private val _quarantinedCount = MutableStateFlow(0)
    val quarantinedCount: StateFlow<Int> = _quarantinedCount.asStateFlow()

    /** 梦境整理进行中。 */
    private val _dreamRunning = MutableStateFlow(false)
    val dreamRunning: StateFlow<Boolean> = _dreamRunning.asStateFlow()

    init { refreshQuarantineCount() }

    fun refreshQuarantineCount() {
        _quarantinedCount.value = immuneSystem.quarantinedCount()
    }

    /** 立即梦境整理：能量衰减 + 修剪 + 宏优化（后台执行，完成后刷新全部数据）。 */
    fun dreamNow() {
        if (_dreamRunning.value) return
        _dreamRunning.value = true
        dreamRenderer.dreamNow { result ->
            _dreamRunning.value = false
            _lastMessage.value = if (result.errors.isNotEmpty()) {
                "梦境整理完成（${result.errors.size} 项异常）：${result.errors.first().take(80)}"
            } else {
                "梦境整理完成：能量已衰减、修剪 ${result.prunedCount} 条、陈旧宏 ${result.staleMacroCount} 条"
            }
            refresh()
        }
    }

    /** 清除免疫隔离名单（App 更新后可重新评估）。 */
    fun clearQuarantine() {
        runCatching { immuneSystem.clearQuarantine() }
            .onSuccess {
                _quarantinedCount.value = 0
                _lastMessage.value = "隔离区已清空"
            }
            .onFailure { _lastMessage.value = "清除隔离区失败：${it.message}" }
    }
}
