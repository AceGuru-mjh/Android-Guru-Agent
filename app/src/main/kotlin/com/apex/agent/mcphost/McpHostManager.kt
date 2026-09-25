package com.apex.agent.mcphost

import android.content.Context
import android.content.SharedPreferences
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.platform.mcphost.McpHostBridge
import com.apex.agent.platform.mcphost.McpHostConfig
import com.apex.agent.platform.mcphost.McpHostConfigStore
import com.apex.agent.platform.mcphost.McpHostServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Provider

/**
 * # 逆向 MCP Host 管理器（app 层接线）
 *
 * 持有 [McpHostServer] 生命周期与配置持久化：
 * - 配置经 SharedPreferences + kotlinx.serialization JSON 持久化
 *   （token 本身 32 位高熵随机串，无需 EncryptedSharedPreferences 加密层）；
 * - 首启自动生成 token；
 * - `enabled=true` 时自动 start；端口变更自动重启；App 启动时若 enabled 延迟自启
 *   （本单例经 [com.apex.agent.di.McpHostModule] 提供、被 ApexApp @Inject 触发创建）；
 * - 不触碰 McpManager/McpModule（内置 MCP transport 由并行工作流维护）。
 *
 * 绑定见 McpHostModule（@Provides @Singleton，无 @Inject 构造绑定避免重复）。
 */
class McpHostManager(
    @ApplicationContext context: Context,
    registry: ToolRegistry,
    // Provider 注入：Hilt 惰性解析执行器（v3 管线 + SecretRedactingExecutor 包装，
    // 注入 ToolExecutor 类型即可拿到完整门控链）
    private val executorProvider: Provider<ToolExecutor>
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_host_prefs", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** 配置持久化（JSON 单键）。 */
    private val store: McpHostConfigStore = object : McpHostConfigStore {
        override fun load(): McpHostConfig {
            val raw = prefs.getString(KEY_CONFIG, null) ?: return McpHostConfig()
            return runCatching { json.decodeFromString(McpHostConfig.serializer(), raw) }
                .getOrElse {
                    AppLogger.instance.warn(
                        LogCategory.TOOL, "McpHostManager",
                        "config decode failed, fallback to defaults: ${it.message}"
                    )
                    McpHostConfig()
                }
        }

        override fun save(config: McpHostConfig) {
            prefs.edit()
                .putString(KEY_CONFIG, json.encodeToString(McpHostConfig.serializer(), config))
                .apply()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow(loadAndSeedToken())
    val config: StateFlow<McpHostConfig> = _config.asStateFlow()

    /** 服务运行态 / 会话 / 审计（未启动时为空值流）。 */
    val isRunning: StateFlow<Boolean> get() = server.isRunning
    val sessions: StateFlow<List<com.apex.agent.platform.mcphost.HostSessionInfo>> get() = server.sessions
    val auditLog: StateFlow<List<com.apex.agent.platform.mcphost.HostAuditEntry>> get() = server.auditLog

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val bridge = McpHostBridge(
        registry = registry,
        executor = executorProvider.get(),
        configProvider = { _config.value }
    )

    private val server = McpHostServer(
        configProvider = { _config.value },
        bridge = bridge,
        scope = scope
    )

    init {
        // App 启动时若 enabled：延迟自启（IO scope，不阻塞构造/主线程）
        if (_config.value.enabled) {
            scope.launch { start() }
        }
    }

    /** 载入配置；token 为空则首启生成并落盘（fail-closed：空 token 拒绝所有请求）。 */
    private fun loadAndSeedToken(): McpHostConfig {
        val loaded = store.load()
        if (loaded.token.isEmpty()) {
            val seeded = loaded.copy(token = McpHostConfig.generateToken())
            store.save(seeded)
            return seeded
        }
        return loaded
    }

    /** 启动服务（幂等；端口绑定失败记录 lastError 不抛出）。 */
    fun start() {
        try {
            server.start()
            _lastError.value = null
            AppLogger.instance.info(
                LogCategory.TOOL, "McpHostManager",
                "reverse MCP host listening on port ${_config.value.effectivePort}"
            )
        } catch (e: Exception) {
            _lastError.value = e.message ?: e::class.simpleName
            AppLogger.instance.error(
                LogCategory.TOOL, "McpHostManager",
                "start failed on port ${_config.value.effectivePort}: ${e.message}"
            )
        }
    }

    /** 停止服务（幂等）。 */
    fun stop() {
        server.stop()
        AppLogger.instance.info(LogCategory.TOOL, "McpHostManager", "reverse MCP host stopped")
    }

    /** 重新生成 token（UI 确认对话框后调用；已连接会话下次请求即失效）。 */
    fun regenerateToken(): String {
        val newToken = McpHostConfig.generateToken()
        updateConfig(_config.value.copy(token = newToken))
        return newToken
    }

    /**
     * 更新配置并同步生命周期：
     * - enabled off → stop；on → start；
     * - 端口变化且运行中 → 新端口重启；
     * - token/白名单/黑名单/限速 → 即时生效（configProvider 每请求读取）。
     */
    fun updateConfig(newConfig: McpHostConfig) {
        val old = _config.value
        val normalized = newConfig.copy(
            port = McpHostConfig.sanitizePort(newConfig.port),
            rateLimitPerMinute = McpHostConfig.sanitizeRateLimit(newConfig.rateLimitPerMinute)
        )
        store.save(normalized)
        _config.value = normalized

        val portChanged = old.effectivePort != normalized.effectivePort
        val wasRunning = server.isRunning.value
        when {
            !normalized.enabled && wasRunning -> stop()
            normalized.enabled && !wasRunning -> start()
            normalized.enabled && wasRunning && portChanged -> {
                stop()
                start()
            }
            else -> Unit
        }
    }

    /** 白名单分类切换（UI FilterChip 点击）。 */
    fun toggleCategory(categoryName: String) {
        val current = _config.value
        val next = if (current.allowedCategories.any { it.equals(categoryName, true) }) {
            current.copy(allowedCategories = current.allowedCategories.filterNot {
                it.equals(categoryName, true)
            })
        } else {
            current.copy(allowedCategories = current.allowedCategories + categoryName)
        }
        updateConfig(next)
    }

    companion object {
        private const val KEY_CONFIG = "mcp_host_config_json"
    }
}
