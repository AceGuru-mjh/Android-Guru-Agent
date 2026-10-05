package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.marketplace.McpSoSource
import com.apex.agent.core.tools.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══ mcp.so 社区目录控制器（市场 · MCP 页签）═══
 *
 * 从 [MarketViewModel] 拆出的独立状态域（与 [MarketHubController] 同构，
 * God-file 预算纪律）：
 * - **目录浏览**：`mcp.so/servers` 分页拉取（60 条/页，默认热门排序），
 *   「加载更多」翻页；返回不足一页即视为末页；
 * - **一键安装**：拉详情页 mcpServers 配置 → [McpSoSource.fetchServerConfig]
 *   统一解析（STDIO 条目自动路由 PRoot 沙箱）→ [McpManager.addServer]
 *   写入注册表（enabled=false —— 安装 ≠ 启动，与官方 Hub 口径一致）。
 *
 * 错误契约：目录/安装失败均折叠进 [McpSoUiState.error] 文案（Result.failure
 * 消息），绝不上抛；安装成功经 [refreshMarket] 通知宿主 VM 刷新快照
 *（已装徽标 / 已配置服务器列表联动）。自持 SupervisorJob scope，
 * 安装失败不殊及宿主。
 */
@Singleton
class MarketMcpSoController @Inject constructor(
    private val mcpSoSource: McpSoSource,
    private val mcpManager: McpManager
) {
    /** mcp.so 目录的 UI 状态。 */
    data class McpSoUiState(
        val servers: List<McpSoSource.McpSoEntry> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** 下一页页码（1 起；0 = 未加载过）。 */
        val nextPage: Int = 0,
        val hasMore: Boolean = true,
        /** 行级 busy：正在拉配置安装的条目 slug。 */
        val installingSlug: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(McpSoUiState())
    val uiState: StateFlow<McpSoUiState> = _uiState.asStateFlow()

    /** 安装完成信号（宿主 VM 收集后刷新已装徽标与配置列表）。 */
    private val _refreshMarket = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val refreshMarket = _refreshMarket.asSharedFlow()

    /** 首屏加载（已有数据或加载中则跳过；force = 下拉刷新语义）。 */
    fun loadFirst(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.loading || current.servers.isNotEmpty())) return
        scope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            mcpSoSource.listServers(page = 1).fold(
                onSuccess = { entries ->
                    _uiState.update {
                        it.copy(
                            servers = entries,
                            // 空目录页（首页即末页）或不足一页（60 条）均末页；
                            // P2-2：空页是合法末页，不再被上游误报为错误
                            hasMore = if (entries.isEmpty()) false
                                else entries.size >= PAGE_SIZE,
                            nextPage = 2
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(servers = emptyList(), error = e.message) }
                }
            )
            _uiState.update { it.copy(loading = false) }
        }
    }

    /** 加载下一页（追加；已是末页或加载中则跳过）。 */
    fun loadMore() {
        val current = _uiState.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        val page = current.nextPage
        if (page < 1) return
        scope.launch {
            _uiState.update { it.copy(loadingMore = true, error = null) }
            mcpSoSource.listServers(page = page).fold(
                onSuccess = { entries ->
                    _uiState.update { state ->
                        val existing = state.servers.map { it.slug }.toSet()
                        val fresh = entries.filter { it.slug !in existing }
                        state.copy(
                            servers = state.servers + fresh,
                            // P2-2：翻到整数倍页边界后下一页是空目录页 ——
                            // 空且 success 即末页（hasMore=false），不再误报错误；
                            // 非空页保持整页（60 条）判定
                            hasMore = if (entries.isEmpty()) false
                                else entries.size >= PAGE_SIZE,
                            nextPage = page + 1
                        )
                    }
                },
                onFailure = { e ->
                    // 翻页失败不清空已加载列表，仅提示
                    _uiState.update { it.copy(error = e.message) }
                }
            )
            _uiState.update { it.copy(loadingMore = false) }
        }
    }

    /**
     * 安装一台 mcp.so 服务器：详情页配置 → 注册表（enabled=false）。
     *
     * - 行级 busy（installingSlug）防并发；
     * - 已有同名配置 → 跳过写入（与官方 Hub 同口径，不覆盖用户自定义）；
     * - 无标准配置块（README 未暴露 mcpServers JSON）→ 错误文案引导去详情页。
     */
    fun installServer(entry: McpSoSource.McpSoEntry) {
        if (_uiState.value.installingSlug != null) return
        scope.launch {
            _uiState.update { it.copy(installingSlug = entry.slug) }
            try {
                val exists = mcpManager.getConfigs().any { it.name == entry.name }
                if (!exists) {
                    mcpSoSource.fetchServerConfig(entry).fold(
                        onSuccess = { config ->
                            mcpManager.addServer(config).fold(
                                onSuccess = { _refreshMarket.tryEmit(Unit) },
                                onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
                            )
                        },
                        onFailure = { e -> _uiState.update { it.copy(error = e.message) } }
                    )
                }
                _refreshMarket.tryEmit(Unit)
            } finally {
                _uiState.update { it.copy(installingSlug = null) }
            }
        }
    }

    private companion object {
        /** mcp.so 每页固定 60 条（不足即末页判定依据）。 */
        const val PAGE_SIZE = 60
    }
}
