package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.marketplace.OperitPluginSource
import com.apex.agent.core.tools.mcp.McpManager
import kotlinx.coroutines.CancellationException
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
 * ═══ Operit 社区插件控制器（市场 · MCP 页签）═══
 *
 * GitHub 聚合的 Operit 社区插件目录（`topic:operit-plugin` + `operit mcp`
 * 双查询合并，按 star 排序）：
 * - **目录浏览**：单次加载全量（上限 40 条），下拉刷新语义 force 重拉；
 * - **一键安装**：仅 MCP 插件形态可装 —— 安装时拉仓库 `mcp.json` /
 *   `package.json` 的 mcpServers 键 → [OperitPluginSource.fetchMcpConfig]
 *   统一解析（STDIO 自动路由 PRoot 沙箱）→ [McpManager.addServer]
 *   （enabled=false —— 安装 ≠ 启动）；ToolPkg / 脚本形态明确报错 +
 *   「浏览器打开仓库」指引，绝不静默装空配置。
 *
 * 错误契约：目录/安装失败均折叠进 [OperitUiState.error]，绝不上抛；
 * 安装成功经 [refreshMarket] 通知宿主 VM 刷新快照。自持 SupervisorJob
 * scope，安装失败不殃及宿主。
 */
@Singleton
class MarketOperitController @Inject constructor(
    private val operitSource: OperitPluginSource,
    private val mcpManager: McpManager
) {
    /** Operit 社区插件目录的 UI 状态。 */
    data class OperitUiState(
        val plugins: List<OperitPluginSource.OperitPlugin> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        /** 行级 busy：正在安装的条目 key。 */
        val installingKey: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(OperitUiState())
    val uiState: StateFlow<OperitUiState> = _uiState.asStateFlow()

    /** 安装完成信号（宿主 VM 收集后刷新已装徽标与配置列表）。 */
    private val _refreshMarket = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val refreshMarket = _refreshMarket.asSharedFlow()

    /** 首屏加载（已有数据或加载中则跳过；force = 刷新语义）。 */
    fun loadFirst(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.loading || current.plugins.isNotEmpty())) return
        scope.launch {
            try {
                _uiState.update { it.copy(loading = true, error = null) }
                operitSource.listPlugins().fold(
                    onSuccess = { directory ->
                        // 单查询降级：列表照给，partialError 进错误横幅（可重试）
                        _uiState.update {
                            it.copy(plugins = directory.plugins, error = directory.partialError)
                        }
                    },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(
                                plugins = emptyList(),
                                error = e.message ?: e.javaClass.simpleName
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 错误契约：逃逸异常折叠进 error，绝不上抛
                _uiState.update {
                    it.copy(plugins = emptyList(), error = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    /**
     * 安装一个 Operit 社区插件（仅 MCP 形态可装）：
     * 仓库 mcp_config.json / mcp.json / package.json 的 mcpServers →
     * 统一解析 → 注册表（enabled=false）。行级 busy 防并发；非 MCP
     * 形态得到明确报错；同名已配置给出与 Registry 安装同口径的提示。
     */
    fun installPlugin(plugin: OperitPluginSource.OperitPlugin) {
        if (_uiState.value.installingKey != null) return
        scope.launch {
            _uiState.update { it.copy(installingKey = plugin.key) }
            var installed = false
            try {
                val exists = mcpManager.getConfigs().any { it.name == plugin.configName }
                if (exists) {
                    _uiState.update {
                        it.copy(
                            error = "同名服务器「${plugin.configName}」已配置——" +
                                "如需重装请先在「已安装管理」中删除"
                        )
                    }
                } else {
                    operitSource.fetchMcpConfig(plugin).fold(
                        onSuccess = { config ->
                            mcpManager.addServer(config).fold(
                                onSuccess = { installed = true },
                                onFailure = { e ->
                                    _uiState.update {
                                        it.copy(error = e.message ?: e.javaClass.simpleName)
                                    }
                                }
                            )
                        },
                        onFailure = { e ->
                            _uiState.update {
                                it.copy(error = e.message ?: e.javaClass.simpleName)
                            }
                        }
                    )
                }
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
}
