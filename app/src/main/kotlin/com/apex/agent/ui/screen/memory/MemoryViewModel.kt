package com.apex.agent.ui.screen.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryKind
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore as Ledger
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.EpisodeSummary
import com.apex.agent.platform.csmem.store.FSMMacro
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 记忆可视化页 ViewModel —— 提供 Episode / 宏 / 节点检索与管理。
 *
 * 只读为主；删除 Episode 为破坏性操作，由 UI 二次确认后调用 [deleteEpisode]。
 * T76 审计补齐：梦境巩固（DreamRenderer.dreamNow）与免疫系统隔离区
 * （MemoryImmuneSystem）能力此前无 UI 入口 —— 本 VM 接线。
 * #219 隐私合规：新增「聊天记忆」分区 —— v2 起直读 [Ledger]
 * （chat_memory/ledger.json，ChatMemoryPipeline 自动沉淀的画像 / 近况 /
 * 里程碑结构化记录），可查看 / 逐条删 / 一键清空。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val store: MemoryGraphStore,
    private val dreamRenderer: com.apex.agent.platform.csmem.dream.DreamRenderer,
    private val immuneSystem: com.apex.agent.platform.csmem.immune.MemoryImmuneSystem,
    // #219 v2：聊天记忆账本 —— 与 ChatMemoryPipeline 共享同一 Hilt
    // 单例（McpModule.provideMemoryLedgerStore），删除/清空即对下一轮
    // 对话的召回立即生效。
    private val chatLedger: Ledger,
    // 记忆页新文案走资源（en/zh 双语）；VM 层非 Compose 场景按仓库惯例经
    // LanguageManager 取词（SettingsViewModel 同款模式）。
    private val languageManager: LanguageManager
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

    // #219：聊天记忆条目（画像 → 近况 → 里程碑；空观察实体不占位）。
    // 与其余状态流同层声明在 init 之前 —— refresh() 自 init 发起，虽经
    // 挂起点后才写入，仍保持本文件“init 只触达先声明属性”的初始化纪律。
    private val _chatMemory = MutableStateFlow<List<ChatMemoryEntry>>(emptyList())
    val chatMemory: StateFlow<List<ChatMemoryEntry>> = _chatMemory.asStateFlow()

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
            // #219：聊天记忆独立加载 —— cs-mem 轨迹库失败不拖累聊天画像区
            refreshChatMemory()
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

    // ═══ 聊天记忆（#219 v2：MemoryLedgerStore 的画像 / 近况 / 里程碑）═══

    /** 刷新聊天记忆快照（账本内存快照，删除/清空后的回刷也走这里）。 */
    private fun refreshChatMemory() {
        viewModelScope.launch {
            runCatching {
                _chatMemory.value = withContext(Dispatchers.IO) {
                    chatLedger.allRecords().toChatMemoryEntries()
                }
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_chat_msg_failed, e.message ?: ""
                )
            }
        }
    }

    /**
     * 删除单条聊天记忆（#219 逐条删除；UI 二次确认后调用）。
     *
     * v2 起按账本主键删除（比 v1 的内容精确匹配更可靠 —— 同内容不同条
     * 不会误伤）。
     */
    fun deleteChatMemoryRecord(recordId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { chatLedger.removeById(recordId) }
            }.onSuccess { removed ->
                _lastMessage.value = if (removed) {
                    languageManager.getString(R.string.memory_chat_msg_deleted)
                } else {
                    // 幂等兜底：条目已被并发删除（如基调替换竞态）
                    languageManager.getString(R.string.memory_chat_msg_missing)
                }
                refreshChatMemory()
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_chat_msg_failed, e.message ?: ""
                )
            }
        }
    }

    /**
     * 一键清空聊天记忆（#219；UI 二次确认后调用）。
     *
     * 语义边界：只清自动沉淀的三类记忆（画像 / 近况 / 里程碑）；
     * memory MCP 图谱里模型显式建的主题实体不属于自动聊天记忆，
     * 不在此清空范围。
     */
    fun clearChatMemory() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    chatLedger.clearKinds(
                        setOf(MemoryKind.PROFILE, MemoryKind.STATE, MemoryKind.MILESTONE)
                    )
                }
            }.onSuccess { removed ->
                _lastMessage.value = if (removed > 0) {
                    languageManager.getString(R.string.memory_chat_msg_cleared, removed)
                } else {
                    languageManager.getString(R.string.memory_chat_msg_clear_empty)
                }
                refreshChatMemory()
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_chat_msg_failed, e.message ?: ""
                )
            }
        }
    }
}
