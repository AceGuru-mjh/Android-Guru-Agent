package com.apex.agent.ui.screen.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.EpisodeSummary
import com.apex.agent.platform.csmem.store.FSMMacro
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆可视化页 ViewModel —— 提供 Episode / 宏 / 节点检索与管理。
 *
 * 只读为主；删除 Episode 为破坏性操作，由 UI 二次确认后调用 [deleteEpisode]。
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val store: MemoryGraphStore
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

    init { refresh() }

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
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            runCatching {
                store.searchNodesByText(query, limit = 50)
            }.getOrDefault(emptyList()).let { _searchResults.value = it }
        }
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
}
