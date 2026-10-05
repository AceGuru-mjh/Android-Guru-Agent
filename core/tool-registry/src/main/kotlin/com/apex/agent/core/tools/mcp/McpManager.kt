package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * MCP服务器管理器
 * 管理所有已配置的MCP连接
 *
 * ## 并发模型（v2 修复）
 * - 旧实现只有 suspend 写路径互斥（Mutex），而 `getConnectedServers()/getConfigs()/getAllTools()`
 *   是非同步裸读——UI 线程与 IO 线程并发读写 HashMap 会触发 ConcurrentModificationException 闪退。
 *   现统一用 Java 监视器锁（[lock]）保护所有可变集合，读路径返回快照，零 CME 风险。
 * - 旧实 `connect()` 在 Mutex 内执行网络初始化（最长 ~70s），期间 add/remove/disconnect 全部排队。
 *   现在**网络 IO 在锁外**执行，锁只保护集合的瞬时一致性。
 * - 旧实现重复 connect 会直接覆盖旧 client 造成连接泄漏；现在覆盖前先 `shutdown()` 旧实例。
 *
 * ## 持久化（v2 修复）
 * - 旧实现手工字符串拼 JSON，name/url/apiKey 未转义：含 `"` 或 `\` 的配置会把 mcp_servers.json
 *   写坏，下次启动 loadConfigs 解析失败并被 catch-all 静默吞掉 → **全部 MCP 配置无声丢失**。
 *   现改用 kotlinx.serialization 对 `List<McpServerConfig>` 做真正的序列化，天然转义。
 * - 落盘走「临时文件 + 原子重命名」，进程中途被杀不会留下半截文件。
 *
 * ## enabled 语义（v2 新增）
 * - `enabled=false` 的服务器：不出现在 `/` 斜杠菜单、不会被 Agent 工具看到（[getEnabledConfigs]）；
 *   禁用时主动断开其活跃连接。
 * - 修复旧实现 `enabled` 字段"只写不读"的空转状态。
 *
 * ## 连接生命周期代数（v3 修复：connect 与断开意图的竞态）
 * - `connect()` 的握手最长可达 180s（沙箱 npx 冷启动）；期间用户调用
 *   `disconnect` / `setEnabled(false)` / `removeServer` 时，旧实现只能移除
 *   「旧」 client —— 在途连接成功后直接 `put` 回 `clients`，用户刚表达的
 *   断开意图被静默丢弃，且 McpSupervisor 继续看护它。现在每台服务器持有
 *   连接代数：断开/禁用/移除时 `generation++`，connect 在注册前校验代数
 *   与配置状态，过期连接立即关闭并如实报错。
 *
 * ## 持久化线程（v3 修复：主线程磁盘 I/O）
 * - `addServer` 等写路径虽是 suspend，但旧实现内部在调用方线程同步完成
 *   JSON 序列化 + 临时文件写 + rename —— 市场页全部在主线程直调，掉帧/
 *   ANR 风险。现在落盘统一切 `Dispatchers.IO`。
 *
 * ## 内置服务器（BUILTIN 传输）
 * - core 不依赖 app 层实现：宿主（app 的 McpModule）在构造时把
 *   `服务器名 → transport 工厂` 注入 [builtinTransports]，connect 时按名取出传给
 *   [McpClient]。core 内部默认构造（空 map）行为不变，向后兼容。
 * - App 预置的内置服务器条目用 [ensureBuiltinServer] 幂等写入：同名用户自建
 *   条目（HTTP/SSE/STDIO）绝不被动持；用户对 `enabled` 的偏好跨升级保留。
 *
 * ## 沙箱 stdio（Issue #149）
 * - 宿主可注入 [sandboxProcessLauncher]（app 层 PRoot Ubuntu 沙箱实现）；
 *   配置项 `runInSandbox=true` 的 STDIO 服务器在连接时改走沙箱 launcher，
 *   在内嵌 rootfs 里解析执行 npx/python 等命令。未注入或配置未开启时，
 *   行为与旧版完全一致（宿主直接 fork），core 内部默认构造零变化。
 *
 * ## 沙箱预置（Issue #163）
 * - `runInSandbox=true` 打通了三入口启用路径：市场页添加对话框的沙箱开关、
 *   `mcp_connect` 工具的 `run_in_sandbox` 参数、配置导入的 `runInSandbox`
 *   字段 —— 该字段不再处于「存在但无任何启用路径」的空转状态。
 * - App 预置的沙箱 STDIO 条目（官方 reference servers，见
 *   [SANDBOX_PRESET_SERVERS]）用 [ensureSandboxServer] 幂等写入：同名用户
 *   自建的宿主条目（runInSandbox=false）绝不被动持；用户对 `enabled` 的
 *   偏好跨升级保留；默认 enabled=false，只预置不连接。
 * - 沙箱连接的握手/请求超时按 [requestTimeoutFor] 放宽：npx 首次冷启动
 *   要下载包，60s 会在 initialize 就误判超时，放宽到 180s 兑底。
 *
 * ## GitHub 令牌桥（v3 S3 / G8）
 * - 配置的 `headers` / `env` 值里可写占位符 [GITHUB_TOKEN_PLACEHOLDER]
 *   （字面 `${GITHUB_TOKEN}`）——官方 GitHub 远程 MCP 预置（app 层
 *   OfficialGithubMcp）用它引用已连接的 PAT，**真 token 永不进入明文
 *   配置 / mcp_servers.json 落盘**（占位符原样落盘）；
 * - [connect] 时若任一值命中占位符 → 从宿主注入的 [gitHubTokenProvider]
 *   取 PAT 做子串替换（只存在于本次连接的内存 config 副本，每求每取——
 *   token 轮换后重连即生效）；未注入 provider 或未连接 GitHub → 连接
 *   失败并给出可行动文案（「GitHub 未连接，无法注入令牌」）。
 */
class McpManager(
    private val configDir: File,
    /** 内置 MCP 服务器注册表：服务器名 → transport 工厂（每次连接新实例）。 */
    private val builtinTransports: Map<String, () -> McpTransportHandle> = emptyMap(),
    /**
     * PRoot 沙箱进程启动器（Issue #149）：`runInSandbox=true` 的 STDIO
     * 服务器连接时注入 [McpClient]。null = 不支持沙箱（此类配置按旧版
     * 宿主直接 fork 处理，通常随后在握手时报「命令不存在」）。
     */
    private val sandboxProcessLauncher: McpProcessLauncher? = null,
    /**
     * #205 沙箱就绪探针（存在性探测，不是模拟）：ENV_CHECK 阶段把 rootfs
     * 真实状态写进事件 detail —— 「rootfs 未就绪」在拉起进程之前就可见，
     * 而不是等到 launcher 报错才知。null = 宿主未提供（不检查，行为与旧版一致）。
     */
    private val sandboxReadinessProbe: (() -> Boolean)? = null,
    /**
     * 诊断日志出口（HookRegistry 同款模式）：core:tool-registry 无
     * core:logging 依赖（刻意保持纯 JVM），getAllTools 等聚合路径的
     * 异常/失败现场经此回调外送。宿主注入 AppLogger，测试注入捕获列表；
     * 默认 no-op —— 既有构造点（app/di/McpModule）零改动兼容。
     */
    private val errorLog: (String) -> Unit = {},
    /**
     * GitHub PAT 供应器（v3 S3 / G8 令牌桥）：headers/env 值里的
     * [GITHUB_TOKEN_PLACEHOLDER] 占位符在 [connect] 时经此取真值填充。
     * null = 宿主未注入（含占位符的配置连接时报「未注入」——与未连接
     * 同文案）；既有构造点零改动兼容。
     */
    private val gitHubTokenProvider: (() -> String?)? = null
) {
    private val clients = LinkedHashMap<String, McpClient>()
    private val configs = LinkedHashMap<String, McpServerConfig>()

    /**
     * 连接代数（v3 修复）：服务器名 → 当前代。disconnect / setEnabled(false) /
     * removeServer 时代数 +1；connect 注册前校验代数未变，防止在途连接
     * 覆盖用户的断开意图。锁保护，快照读写。
     */
    private val generations = HashMap<String, Long>()

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 配置/连接状态变更通知（市场页、斜杠菜单订阅后自动刷新，无需手动轮询）。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * 会话生命周期监听（v4：MCP 工具一键注册进 ToolRegistry）。
     *
     * 连接成功 → [onServerConnected]；断开/移除/禁用 → [onServerDisconnected]。
     * [McpToolRegistrar] 订阅后，远程工具自动变成 first-class AgentTool。
     */
    interface SessionListener {
        fun onServerConnected(serverName: String)
        fun onServerDisconnected(serverName: String)
    }

    private val sessionListeners = mutableListOf<SessionListener>()

    /** 订阅会话生命周期（幂等；重复注册同一监听器会被忽略）。 */
    fun addSessionListener(listener: SessionListener) {
        synchronized(lock) {
            if (listener !in sessionListeners) sessionListeners += listener
        }
    }

    fun removeSessionListener(listener: SessionListener) {
        synchronized(lock) { sessionListeners -= listener }
    }

    private fun fireConnected(serverName: String) {
        synchronized(lock) { sessionListeners.toList() }.forEach {
            runCatching { it.onServerConnected(serverName) }
        }
    }

    private fun fireDisconnected(serverName: String) {
        synchronized(lock) { sessionListeners.toList() }.forEach {
            runCatching { it.onServerDisconnected(serverName) }
        }
    }

    init {
        runCatching { configDir.mkdirs() }
        loadConfigs()
    }

    /**
     * 代数自增（断开/禁用/移除时调用）—— 在途 connect 检测到代数变化后
     * 不得注册新连接。
     */
    private fun bumpGenerationLocked(name: String) {
        generations[name] = (generations[name] ?: 0L) + 1L
    }

    /**
     * 添加MCP服务器配置
     */
    suspend fun addServer(config: McpServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        // v3 修复：落盘切 IO —— 市场页主线程直调 addServer 时，JSON 序列化 +
        // 临时文件写 + rename 不再钉死主线程。
        val error = synchronized(lock) {
            runCatching {
                configs[config.name] = config
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return@withContext Result.failure(error)
        notifyChanged()
        Result.success(Unit)
    }

    /** 启用/禁用服务器配置（禁用时主动断开活跃连接）。 */
    suspend fun setEnabled(name: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val staleClient: McpClient?
        val error = synchronized(lock) {
            val existing = configs[name]
                ?: return@withContext Result.failure(Exception("Server '$name' not configured"))
            // v3 修复（竞态）：禁用打断在途连接的注册资格（代数 +1）。
            if (!enabled) bumpGenerationLocked(name)
            staleClient = if (enabled) null else clients.remove(name)
            runCatching {
                configs[name] = existing.copy(enabled = enabled)
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        staleClient?.let {
            runCatching { it.shutdown() }
            fireDisconnected(name)
        }
        if (error != null) return@withContext Result.failure(error)
        notifyChanged()
        Result.success(Unit)
    }

    /**
     * 连接MCP服务器（网络 IO 在锁外执行，只对配置做一致性检查）。
     *
     * #197 `startupListener`：可选的真实启动事件监听 —— ENV 检查、spawn
     * （pid/argv）、initialize 握手、initialized 通知各阶段以真实事件回调，
     * 供市场页的启动进度弹窗消费（null = 零开销，既有调用点不变）。
     */
    suspend fun connect(
        name: String,
        startupListener: McpStartupListener? = null
    ): Result<McpCapabilities> {
        val config = synchronized(lock) { configs[name] }
            ?: return Result.failure(Exception("Server '$name' not configured"))

        // v3 S3（G8 令牌桥）：headers/env 值含 ${GITHUB_TOKEN} 占位符时，从
        // 宿主注入的 provider 取 PAT 填充到本次连接的内存副本（占位符原样
        // 落盘，真 token 永不进 mcp_servers.json）。GitHub 未连接 → 诚实
        // 失败并给出可行动指引，不拿占位符串去撞 401。
        val resolvedConfig = resolveGithubTokenPlaceholders(name, config)
            ?: return Result.failure(
                Exception(
                    "GitHub 未连接，无法注入令牌 — 服务器 '$name' 的配置引用了 " +
                        "$GITHUB_TOKEN_PLACEHOLDER 占位符，请先连接 GitHub「Coding 屏 " +
                        "GitHub 图标 / 设置 → GitHub」后再启用"
                )
            )

        // #197 真实事件：环境检查（配置形态 + 沙箱就绪态；rootfs 检查同
        // ProotMcpProcessLauncher 的门径 —— 存在性探测，不是模拟）。
        // #205：沙箱就绪探针注入后就绪态写入 detail（未注入时不猜测）。
        startupListener?.onStartupEvent(
            McpStartupEvent(
                serverName = name,
                stage = McpStartupStage.ENV_CHECK,
                detail = when (config.transport) {
                    McpTransport.BUILTIN -> "内置服务器（进程内）"
                    McpTransport.STDIO -> buildString {
                        append("stdio 本地进程")
                        if (config.runInSandbox) {
                            append(" · PRoot 沙箱")
                            sandboxReadinessProbe?.let { probe ->
                                append(if (probe()) " · rootfs 就绪" else " · rootfs 未就绪（连接将失败，请先安装 Ubuntu 环境）")
                            }
                        }
                    }
                    McpTransport.HTTP, McpTransport.SSE -> "远端 ${config.transport} · ${config.url}"
                }
            )
        )

        // 先关闭旧连接，避免旧实现「直接覆盖导致连接泄漏」的问题
        synchronized(lock) { clients.remove(name) }?.let { runCatching { it.shutdown() } }

        // v3 修复（竞态）：握手前记录当前代数 —— 握手期间用户断开/禁用/
        // 移除该服务器时代数 +1，注册前校验发现代数变化即放弃注册。
        val connectGeneration = synchronized(lock) { generations[name] ?: 0L }

        // BUILTIN 传输按名取注入的工厂；HTTP/SSE/STDIO 传 null（工厂参数不参与）。
        // Issue #149：runInSandbox=true 的 STDIO 配置改走宿主注入的沙箱 launcher
        // （app 层 PRoot Ubuntu 实现）；其余情况传 null → McpClient 回退 JvmProcessLauncher。
        // Issue #163：沙箱连接同时放宽 STDIO 请求超时（npx 冷启动要下载包）。
        // v3 S3：config 用占位符已解析的副本（headers/env 里的 ${GITHUB_TOKEN}
        // → 真 PAT；未含占位符时与原配置同引用，零拷贝零行为变化）。
        val client = McpClient(
            resolvedConfig,
            builtinTransportFactory = builtinTransports[name],
            processLauncher = if (resolvedConfig.runInSandbox) sandboxProcessLauncher else null,
            stdioRequestTimeoutMs = requestTimeoutFor(resolvedConfig),
            startupListener = startupListener
        )
        val initResult = client.initialize()

        if (initResult.isSuccess) {
            // v3 修复（竞态兜底）：并发 connect 同一服务器时，后到者胜出，输家
            // 连接立即关闭；握手期间用户表达断开/禁用/移除意图（代数已变）时，
            // 在途连接同样不注册、立即关闭 —— 旧实现静默把用户刚断开的
            // 服务器拉回连接态并交给 Supervisor 看护。
            val stillCurrent = synchronized(lock) {
                (generations[name] ?: 0L) == connectGeneration &&
                    configs[name]?.enabled == true
            }
            if (stillCurrent) {
                val loser = synchronized(lock) { clients.put(name, client) }
                loser?.let { runCatching { it.shutdown() } }
                notifyChanged()
                fireConnected(name)
            } else {
                runCatching { client.shutdown() }
                return Result.failure(
                    Exception("Server '$name' was disconnected or disabled during connect")
                )
            }
        } else {
            // STDIO 服务器在构造阶段就已 fork 出子进程；握手失败若不收尾就是进程泄漏
            runCatching { client.shutdown() }
        }
        return initResult
    }

    /**
     * 获取所有可用MCP工具（对 clients 快照迭代，锁内零网络调用）。
     *
     * 单台服务器失败不再静默：Result 失败与抛出的异常均经 [errorLog]
     * 外送留痕（旧实现 `catch (_: Exception) {}` 违反仓库「防御式 IO：
     * 异常折叠 + 留痕」纪律）。CancellationException 照常重抛 ——
     * [McpClient.listTools] 现已遵守全仓取消纪律，聚合层不得再把它吞掉。
     */
    suspend fun getAllTools(): List<McpToolDef> {
        val snapshot = synchronized(lock) { clients.entries.map { it.key to it.value } }
        val allTools = mutableListOf<McpToolDef>()
        for ((serverName, client) in snapshot) {
            try {
                client.listTools()
                    .onSuccess { allTools.addAll(it) }
                    .onFailure { e ->
                        errorLog(
                            "McpManager.getAllTools: server '$serverName' listTools failed: " +
                                "${e.message ?: e::class.simpleName}"
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLog(
                    "McpManager.getAllTools: server '$serverName' listTools threw: " +
                        "${e.message ?: e::class.simpleName}"
                )
            }
        }
        return allTools
    }

    /**
     * 列出单台服务器的工具（v4：[McpToolRegistrar] 逐台注册 first-class 工具用）。
     * 服务器未连接时返回空列表——调用方据此跳过注册。
     */
    suspend fun listServerTools(serverName: String): List<McpToolDef> {
        val client = synchronized(lock) { clients[serverName] } ?: return emptyList()
        return runCatching { client.listTools().getOrDefault(emptyList()) }
            .getOrDefault(emptyList())
    }

    /**
     * 调用MCP工具
     */
    suspend fun callTool(serverName: String, toolName: String, arguments: String): Result<McpToolResult> {
        val client = synchronized(lock) { clients[serverName] }
            ?: return Result.failure(Exception("Server '$serverName' not connected"))
        return client.callTool(toolName, arguments)
    }

    /**
     * 断开连接
     */
    suspend fun disconnect(name: String) {
        // v3 修复（竞态）：代数 +1 使在途 connect 的注册前置校验失败。
        synchronized(lock) { bumpGenerationLocked(name) }
        val client = synchronized(lock) { clients.remove(name) }
        client?.let {
            runCatching { it.shutdown() }
            notifyChanged()
            fireDisconnected(name)
        }
    }

    /**
     * 断开所有
     */
    suspend fun disconnectAll() {
        val removed = synchronized(lock) {
            // v3 修复（竞态）：批量断开同样自增代数，拦截全部在途连接。
            clients.keys.forEach { bumpGenerationLocked(it) }
            val all = clients.entries.associate { it.key to it.value }
            clients.clear()
            all
        }
        removed.values.forEach { runCatching { it.shutdown() } }
        notifyChanged()
        removed.keys.forEach { fireDisconnected(it) }
    }

    /**
     * 获取已连接服务器列表（快照读，线程安全）。
     */
    fun getConnectedServers(): List<String> = synchronized(lock) { clients.keys.toList() }

    /**
     * 健康探针（[McpSupervisor] 看门狗用）：已连接且底层传输仍存活。
     *
     * - 未连接 → false；
     * - HTTP/SSE 无状态传输 → 恒 true（探针无分辨力，也无需恢复）；
     * - STDIO → 子进程真实存活（[McpClient.isTransportAlive]）；
     * - BUILTIN → 进程内实现自报。
     */
    fun isClientHealthy(name: String): Boolean {
        val client = synchronized(lock) { clients[name] } ?: return false
        return runCatching { client.isTransportAlive() }.getOrDefault(false)
    }

    /**
     * 获取所有配置（快照读，线程安全）。
     */
    fun getConfigs(): List<McpServerConfig> = synchronized(lock) { configs.values.toList() }

    /**
     * 获取所有**已启用**的配置——斜杠菜单与 Agent 工具的可见性依据。
     */
    fun getEnabledConfigs(): List<McpServerConfig> =
        synchronized(lock) { configs.values.filter { it.enabled } }

    /**
     * 幂等预置一台内置 MCP 服务器（App 启动时调用，如内置 GitHub）。
     *
     * - 无同名条目 → 写入 [config]；
     * - 同名条目已是 BUILTIN → 按传入定义刷新，但**保留用户 enabled 偏好**
     *   （升级后新增字段/默认值得以下发，用户手动禁用的不会被重新打开）；
     * - 同名条目是用户自建（HTTP/SSE/STDIO）→ 不动它（绝不劫持用户配置）。
     */
    suspend fun ensureBuiltinServer(config: McpServerConfig): Result<Unit> {
        val error = synchronized(lock) {
            val existing = configs[config.name]
            if (existing != null && existing.transport != McpTransport.BUILTIN) {
                return Result.failure(
                    Exception("服务器 '${config.name}' 已被用户自建为 ${existing.transport}，不覆盖用户配置")
                )
            }
            val merged = existing?.let { config.copy(enabled = it.enabled) } ?: config
            runCatching {
                configs[config.name] = merged
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /**
     * 删除配置（同时断开活跃连接）。
     */
    suspend fun removeServer(name: String) = withContext(Dispatchers.IO) {
        // v3 修复（竞态）：代数 +1 拦截在途 connect；落盘切 IO 线程。
        synchronized(lock) { bumpGenerationLocked(name) }
        val client = synchronized(lock) { clients.remove(name) }
        client?.let {
            runCatching { it.shutdown() }
            fireDisconnected(name)
        }
        synchronized(lock) {
            configs.remove(name)
            runCatching { saveConfigsLocked() }
        }
        notifyChanged()
    }

    /**
     * 幂等预置一台**沙箱** STDIO MCP 服务器（Issue #163，App 启动时调用，
     * 预置清单见 [SANDBOX_PRESET_SERVERS]）。
     *
     * 语义仿 [ensureBuiltinServer]，但面向 `runInSandbox=true` 的 STDIO 条目
     * （BUILTIN 校验会拒绝沙箱配置，所以另开方法）：
     * - 无同名条目 → 写入 [config]（预置默认 enabled=false，只预置不连接）；
     * - 同名条目已是沙箱条目 → 按传入定义刷新 command/args，但**保留用户
     *   enabled 偏好**（用户手动启用的不会被重新关掉，反之亦然）；
     * - 同名条目是用户自建（runInSandbox=false 的宿主 STDIO/远端条目）→
     *   不动它并返回 failure（防劫持：绝不把用户配置悄悄改写成沙箱形态）。
     */
    suspend fun ensureSandboxServer(config: McpServerConfig): Result<Unit> {
        val error = synchronized(lock) {
            val existing = configs[config.name]
            if (existing != null && !existing.runInSandbox) {
                return Result.failure(
                    Exception("服务器 '${config.name}' 已被用户自建为非沙箱条目（runInSandbox=false），不覆盖用户配置")
                )
            }
            val merged = existing?.let { config.copy(enabled = it.enabled) } ?: config
            runCatching {
                configs[config.name] = merged
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /**
     * GitHub 令牌占位符解析（v3 S3 / G8 令牌桥，纯函数 + provider 读取）：
     *
     * - headers / env 值均不含 [GITHUB_TOKEN_PLACEHOLDER] → 原样返回（零拷贝）；
     * - 含占位符 → 从 [gitHubTokenProvider] 取 PAT（null/空白 = 未连接）做
     *   子串替换（值可以是 `Bearer ${GITHUB_TOKEN}` / 纯占位符 / 嵌在其他
     *   文本里），返回仅存于本次连接的内存副本；
     * - 含占位符但未连接 → 返回 null（调用方转为可行动的连接失败文案）。
     *
     * provider 读取侧防御式：抛异常折叠为未连接（不把宿主异常穿透到连接层）。
     */
    private fun resolveGithubTokenPlaceholders(
        serverName: String,
        config: McpServerConfig
    ): McpServerConfig? {
        val hasPlaceholder = config.headers.values.any { it.contains(GITHUB_TOKEN_PLACEHOLDER) } ||
            config.env.values.any { it.contains(GITHUB_TOKEN_PLACEHOLDER) }
        if (!hasPlaceholder) return config
        val token = runCatching { gitHubTokenProvider?.invoke() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                errorLog(
                    "McpManager.connect('$serverName'): config references " +
                        "$GITHUB_TOKEN_PLACEHOLDER but no GitHub PAT is available"
                )
                return null
            }
        return config.copy(
            headers = config.headers.mapValues { (_, v) ->
                v.replace(GITHUB_TOKEN_PLACEHOLDER, token)
            },
            env = config.env.mapValues { (_, v) ->
                v.replace(GITHUB_TOKEN_PLACEHOLDER, token)
            }
        )
    }

    private fun saveConfigsLocked() {
        val file = File(configDir, "mcp_servers.json")
        val jsonStr = json.encodeToString(configs.values.toList())
        // 原子写：先写临时文件再重命名，进程中途被杀不会损坏配置
        val tmp = File(configDir, "mcp_servers.json.tmp")
        tmp.writeText(jsonStr)
        if (!tmp.renameTo(file)) {
            // 部分文件系统跨 inode rename 失败时退化为直接写
            file.writeText(jsonStr)
            tmp.delete()
        }
    }

    private fun loadConfigs() {
        val file = File(configDir, "mcp_servers.json")
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val loaded = json.decodeFromString<List<McpServerConfig>>(content)
            synchronized(lock) {
                loaded.forEach { configs[it.name] = it }
            }
        } catch (e: Exception) {
            // 配置损坏时保留默认空态并尝试备份，绝不再静默吞掉现场
            runCatching {
                file.copyTo(File(configDir, "mcp_servers.json.corrupt"), overwrite = true)
            }
        }
    }

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    companion object {
        /**
         * GitHub PAT 占位符（v3 S3 / G8 令牌桥）：写在配置 headers/env 值里，
         * [connect] 时经 [McpManager] 的 gitHubTokenProvider 解析为真 PAT。
         * 官方 GitHub 远程 MCP 预置（app 层 OfficialGithubMcp）使用；用户自建
         * 配置同样可用（真 token 不落盘）。注意 Kotlin 源码内写法：
         * "\${GITHUB_TOKEN}"（$ 转义防模板插值）。
         */
        const val GITHUB_TOKEN_PLACEHOLDER = "\${GITHUB_TOKEN}"

        /**
         * 沙箱 STDIO 连接的握手/请求超时（Issue #163）。
         *
         * npx 首次冷启动要下载包（-y 时先拉 tarball 再装依赖再起进程），
         * 移动网络下 60s 走不完 —— 与其让用户在 initialize 阶段就撞超时，
         * 不如给沙箱连接单独放宽到 180s；仅作用于 runInSandbox=true 的
         * STDIO 配置，宿主 fork 与 HTTP/SSE 行为不变。
         */
        const val SANDBOX_REQUEST_TIMEOUT_MS = 180_000L

        /**
         * 按配置选择 STDIO 请求超时（纯函数，Issue #163 抽出便于单测）：
         * 沙箱 STDIO → [SANDBOX_REQUEST_TIMEOUT_MS]；其余（含 HTTP/SSE ——
         * 它们不走 STDIO 超时参数）→ [McpClient.HOST_STDIO_REQUEST_TIMEOUT_MS]。
         */
        fun requestTimeoutFor(config: McpServerConfig): Long =
            if (config.runInSandbox && config.transport == McpTransport.STDIO) {
                SANDBOX_REQUEST_TIMEOUT_MS
            } else {
                McpClient.HOST_STDIO_REQUEST_TIMEOUT_MS
            }

        /**
         * 沙箱预置清单（Issue #163）—— 官方 reference servers，node 纯 JS
         * 实现，arm64 兼容（无原生模块，npx 在 PRoot Ubuntu 内直接可跑）。
         *
         * - `fs-sandbox`：沙箱内文件系统读写（/workspace 作用域）；
         * - `memory-sandbox`：知识图谱记忆（原定 git-sandbox —— 但
         *   `@modelcontextprotocol/server-git` 已从 npm 下架（registry 返回
         *   Not Found），换成同厂官方 server-memory）；
         * - `everything-sandbox`：官方测试服务器（覆盖全部 MCP 能力面：
         *   工具/资源/提示词/采样，验证沙箱链路用）。
         *
         * 全部 enabled=false：只预置不连接 —— rootfs 未就绪也先写入，连接
         * 失败发生在用户主动启用时，ProotMcpProcessLauncher 已有引导性报错。
         */
        val SANDBOX_PRESET_SERVERS: List<McpServerConfig> = listOf(
            McpServerConfig(
                name = "fs-sandbox",
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
                runInSandbox = true,
                enabled = false,
                scope = "coding"
            ),
            McpServerConfig(
                name = "memory-sandbox",
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-memory"),
                runInSandbox = true,
                enabled = false,
                scope = "agent"
            ),
            McpServerConfig(
                name = "everything-sandbox",
                transport = McpTransport.STDIO,
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-everything"),
                runInSandbox = true,
                enabled = false,
                scope = "coding"
            )
        )
    }
}
