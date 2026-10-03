package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.marketplace.OfficialRegistrySource
import com.apex.agent.core.tools.marketplace.PulseMcpSource
import com.apex.agent.core.tools.marketplace.RegistryServer
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.marketplace.PulseMcpCredentialsStore
import com.apex.agent.marketplace.RegistryMcpInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * ═══ MCP Registry 目录控制器（市场 · MCP 页签）═══
 *
 * 从 [MarketViewModel] 拆出的独立状态域（与 [MarketMcpSoController]
 * 同构，God-file 预算纪律）：
 * - **源切换**：官方 Registry（registry.modelcontextprotocol.io，9000+
 *   服务器，无认证）⇄ PulseMCP Sub-Registry（api.pulsemcp.com，同规格
 *   但需 X-API-Key / X-Tenant-ID 合作凭据）；
 * - **目录浏览**：cursor 分页（默认 30 条/页）+ 服务端 search 搜索；
 * - **一键安装**：[RegistryMcpInstaller] 决策树 —— 远端直装（无沙箱）/
 *   npm 沙箱真实预装（npm install -g + 失败回滚）/ 不支持形态明确报错。
 *
 * 错误契约：目录/安装失败均折叠进 [RegistryUiState.error] 文案，
 * 绝不上抛；安装成功经 [refreshMarket] 通知宿主 VM 刷新快照（已装
 * 徽标 / 已配置服务器列表联动）。自持 SupervisorJob scope。
 *
 * **在途请求管理**：目录请求有代际（generation）+ Job 取消——源切换 /
 * 搜索 / 重试 / 回目录都会取消在途旧请求并作废其响应，迟到响应
 * 不会落进新源/新模式的 UI 状态；追加加载不抢占在途请求。
 */
@Singleton
class MarketRegistryController @Inject constructor(
    private val officialSource: OfficialRegistrySource,
    private val pulseSource: PulseMcpSource,
    private val installer: RegistryMcpInstaller,
    private val mcpManager: McpManager,
    private val pulseCredentials: PulseMcpCredentialsStore
) {
    /** Registry 目录的源切换（官方 / PulseMCP）。 */
    enum class RepoSource { OFFICIAL, PULSE }

    /** Registry 目录的 UI 状态。 */
    data class RegistryUiState(
        val source: RepoSource = RepoSource.OFFICIAL,
        val servers: List<RegistryServer> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** 下一页游标（null = 末页 / 未加载）。 */
        val nextCursor: String? = null,
        /** 搜索输入框当前值。 */
        val query: String = "",
        /** 列表当前是否为搜索结果（控制「返回目录」按钮）。 */
        val searchActive: Boolean = false,
        /** 行级 busy：正在安装的条目 key。 */
        val installingKey: String? = null,
        /** PulseMCP 凭据是否已配置完整（源可用性判定）。 */
        val pulseCredentialsSet: Boolean = false,
        /** 凭据编辑态：两个输入框草稿（空 = 对话框关闭由 UI 自行管理）。 */
        val pulseApiKeyDraft: String = "",
        val pulseTenantIdDraft: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(
        RegistryUiState(pulseCredentialsSet = pulseCredentials.isConfigured())
    )
    val uiState: StateFlow<RegistryUiState> = _uiState.asStateFlow()

    /** 安装完成信号（宿主 VM 收集后刷新已装徽标与配置列表）。 */
    private val _refreshMarket = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val refreshMarket = _refreshMarket.asSharedFlow()

    /** 搜索结果模式下的当前查询词（翻页/重试用——不用输入框实时值，
     * 用户可能改了未提交）。 */
    private var activeSearchQuery: String = ""

    /** 在途目录请求（新意图先取消旧请求，见 loadPage 代际说明）。 */
    private var loadJob: Job? = null

    /** 目录请求代际号：非追加请求自增，旧协程据此丢弃过期响应。 */
    private var loadGeneration = 0

    /** 切换源（官方 ⇄ PulseMCP）；切到未配置凭据的 Pulse 时给明确错误。 */
    fun selectSource(source: RepoSource) {
        if (_uiState.value.source == source) return
        _uiState.update {
            it.copy(
                source = source,
                servers = emptyList(),
                error = null,
                nextCursor = null,
                searchActive = false,
                query = "",
                pulseCredentialsSet = pulseCredentials.isConfigured()
            )
        }
        // force：切源前在途请求刚被取消但 loading 旗标还压在新请求身上，
        // 不 force 会被早退拦下永不加载
        loadFirst(force = true)
    }

    /** 更新搜索输入框（不触发请求）。 */
    fun updateQuery(query: String) =
        _uiState.update { it.copy(query = query) }

    /** 首屏加载（已有数据或加载中则跳过；force = 刷新语义，搜索模式下
     * 用已提交查询词重搜——不用输入框实时值）。 */
    fun loadFirst(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.loading || current.servers.isNotEmpty())) return
        if (current.searchActive && activeSearchQuery.isNotBlank()) {
            loadPage(cursor = null, search = activeSearchQuery, appending = false)
        } else {
            loadPage(cursor = null, search = null, appending = false)
        }
    }

    /** 关键词搜索（空关键词退回目录首页）。 */
    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            backToBrowse()
            return
        }
        activeSearchQuery = query
        loadPage(cursor = null, search = query, appending = false, markSearch = true)
    }

    /** 退出搜索结果模式，回目录首页。 */
    fun backToBrowse() {
        activeSearchQuery = ""
        _uiState.update { it.copy(searchActive = false, query = "") }
        loadPage(cursor = null, search = null, appending = false)
    }

    /** 加载下一页（追加；末页或加载中跳过；搜索模式下沿用查询词分页）。 */
    fun loadMore() {
        val current = _uiState.value
        if (current.loading || current.loadingMore || current.nextCursor == null) return
        val search = if (current.searchActive) activeSearchQuery else null
        loadPage(cursor = current.nextCursor, search = search, appending = true)
    }

    /** 失败重试（搜索模式用已提交查询词重搜，不吃输入框实时值）。 */
    fun retry() {
        if (_uiState.value.searchActive && activeSearchQuery.isNotBlank()) {
            loadPage(cursor = null, search = activeSearchQuery, appending = false)
        } else {
            loadFirst(force = true)
        }
    }

    // ── PulseMCP 凭据编辑 ──

    fun updatePulseApiKeyDraft(value: String) =
        _uiState.update { it.copy(pulseApiKeyDraft = value) }

    fun updatePulseTenantIdDraft(value: String) =
        _uiState.update { it.copy(pulseTenantIdDraft = value) }

    /** 保存凭据并立即以 Pulse 源加载（凭据不全给明确错误）。 */
    fun savePulseCredentials() {
        val state = _uiState.value
        if (state.pulseApiKeyDraft.isBlank() || state.pulseTenantIdDraft.isBlank()) {
            _uiState.update {
                it.copy(
                    error = "X-API-Key 与 X-Tenant-ID 均不能为空（向 pulsemcp.com 申请）"
                )
            }
            return
        }
        pulseCredentials.save(state.pulseApiKeyDraft, state.pulseTenantIdDraft)
        _uiState.update {
            it.copy(
                pulseCredentialsSet = true,
                pulseApiKeyDraft = "",
                pulseTenantIdDraft = "",
                // 切到 Pulse 源并清空旧目录（凭据可能刚从别的租户换过来）
                source = RepoSource.PULSE,
                servers = emptyList(),
                nextCursor = null,
                searchActive = false,
                query = "",
                error = null
            )
        }
        // force：旧源在途请求刚被取消，loading 旗标尚未归零（属新请求），
        // 不 force 会被早退拦下 Pulse 永不加载
        loadFirst(force = true)
    }

    /** 清除凭据并回官方源。 */
    fun clearPulseCredentials() {
        pulseCredentials.clear()
        _uiState.update {
            it.copy(
                pulseCredentialsSet = false,
                pulseApiKeyDraft = "",
                pulseTenantIdDraft = ""
            )
        }
        selectSource(RepoSource.OFFICIAL)
    }

    // ── 安装 ──

    /**
     * 安装一台 Registry 服务器：决策树（远端直装 / npm 沙箱预装+回滚 /
     * 不支持报错）。行级 busy 防并发；npm 形态最长 5 分钟（沙箱真实
     * 下载），安装期间行卡显示进行中提示。
     */
    fun installServer(server: RegistryServer) {
        if (_uiState.value.installingKey != null) return
        scope.launch {
            _uiState.update { it.copy(installingKey = server.key) }
            var installed = false
            try {
                installer.install(server).fold(
                    onSuccess = { installed = true },
                    onFailure = { e ->
                        _uiState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 错误契约：逃逸异常折叠进 error，绝不上抛
                _uiState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _uiState.update { it.copy(installingKey = null) }
            }
            if (installed) _refreshMarket.tryEmit(Unit)
        }
    }

    // ── 内部实现 ──

    private fun loadPage(
        cursor: String?,
        search: String?,
        appending: Boolean,
        markSearch: Boolean = false
    ) {
        // 代际 + Job 取消（见类 KDoc「在途请求管理」）：非追加请求是新的
        // 用户意图（切源/搜索/重试/回目录）——取消在途旧请求，其迟到响应
        // 一律丢弃；追加请求不抢占（有在途加载就放弃本次，按钮再触发）。
        if (!appending) {
            loadJob?.cancel()
            loadGeneration++
        } else if (loadJob?.isActive == true) {
            return
        }
        val generation = loadGeneration
        loadJob = scope.launch {
            try {
                if (appending) {
                    _uiState.update { it.copy(loadingMore = true, error = null) }
                } else {
                    _uiState.update { it.copy(loading = true, error = null) }
                }
                val result = when (_uiState.value.source) {
                    RepoSource.OFFICIAL -> officialSource.listServers(
                        cursor = cursor,
                        search = search
                    )
                    RepoSource.PULSE -> pulseSource.listServers(
                        cursor = cursor,
                        search = search
                    )
                }
                // 被更新的意图取代的响应直接丢弃（取消可能发生在挂起点
                // 之外的窄窗口：响应已返回、还没落到状态）
                if (generation != loadGeneration) return@launch
                result.fold(
                    onSuccess = { page ->
                        _uiState.update { state ->
                            val servers = if (appending) {
                                val seen = state.servers.map { it.key }.toHashSet()
                                state.servers + page.servers.filter { it.key !in seen }
                            } else {
                                page.servers
                            }
                            state.copy(
                                servers = servers,
                                nextCursor = page.nextCursor,
                                // 翻页不清搜索态：search 非空时沿用当前模式
                                // （loadMore 只会在 searchActive 下带 search 来）
                                searchActive = markSearch || (search != null && state.searchActive)
                            )
                        }
                    },
                    onFailure = { e ->
                        if (!appending) {
                            _uiState.update {
                                it.copy(
                                    servers = emptyList(),
                                    error = e.message ?: e.javaClass.simpleName
                                )
                            }
                        } else {
                            // 翻页失败不清空已加载列表，仅提示
                            _uiState.update {
                                it.copy(error = e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 错误契约：逃逸异常折叠进 error，绝不上抛
                if (generation == loadGeneration) {
                    _uiState.update { state ->
                        val message = e.message ?: e.javaClass.simpleName
                        if (appending) state.copy(error = message)
                        else state.copy(servers = emptyList(), error = message)
                    }
                }
            } finally {
                // 只有仍是当前代际才收尾清旗标——被取代的旧协程不清
                // （loading 语义属于新请求，旧协程无权关它）
                if (generation == loadGeneration) {
                    _uiState.update { it.copy(loading = false, loadingMore = false) }
                }
            }
        }
    }
}
