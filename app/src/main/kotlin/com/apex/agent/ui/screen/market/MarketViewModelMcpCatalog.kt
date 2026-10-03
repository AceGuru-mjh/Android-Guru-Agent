package com.apex.agent.ui.screen.market

import androidx.lifecycle.viewModelScope
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpConfigValidator
import com.apex.agent.core.tools.mcp.McpServerCatalog
import com.apex.agent.core.tools.mcp.McpStartupEvent
import com.apex.agent.core.tools.mcp.McpStartupListener
import com.apex.agent.core.tools.mcp.McpStartupStage
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.update.HotContentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MCP 精选目录 + 启动时间线（#205/#206）—— MarketViewModel 的扩展
// （God-file 预算拆分，模式同 CodeLongTaskCenterOps.kt：函数体原样迁移，
// 依赖成员已在 VM 开放 internal，Compose 调用点与方法引用语义不变）。
//
// 职责：
//  - 启动事件双写（startupListenerFor / finishStartupWithTools）：
//    tracker 留底（时间线回放）+ uiState.mcpStartup（弹窗实时渲染）；
//  - assets/mcp_catalog 精选目录：加载/分级过滤/分类切换/一键安装
//    （必填环境变量引导表单 + McpConfigValidator 预检 + 重名递增序号）；
//  - 启动时间线回放（showMcpTimeline / hasMcpTimeline）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * #205 共享启动监听器：真实事件双写 —— tracker 留底（事后时间线可回放）
 * + uiState.mcpStartup（弹窗实时渲染）。所有连接路径统一走它。
 *
 * #206 修复：仅当弹窗属于同一服务器且未被用户关闭时追加 —— 旧行为里
 * 用户点「隐藏」后，后续 stderr 事件（每连接最多 30 行）会把弹窗反复拉起。
 */
internal fun MarketViewModel.startupListenerFor(name: String): McpStartupListener =
    McpStartupListener { event ->
        startupTracker.record(event)
        _uiState.update { s ->
            s.copy(
                mcpStartup = s.mcpStartup
                    ?.takeIf { it.serverName == name && it.running }
                    ?.let { it.copy(events = it.events + event, running = event.stage != McpStartupStage.FAILED) }
            )
        }
    }

/**
 * #206 连接成功的收尾：真实 tools/list 发现 + TOOLS_DISCOVERED 事件
 * （[McpStartupTracker.recordToolsIfChanged] 去重重记）+ 弹窗停止转圈。
 * connect/add/update 三条路径共用。
 */
internal suspend fun MarketViewModel.finishStartupWithTools(name: String) {
    val tools = mcpManager.listServerTools(name)
    val toolNames = tools.map { it.name }
    // tracker 侧：同清单不重记（重连场景不刷重复行）。
    startupTracker.recordToolsIfChanged(name, toolNames)
    val detail = "发现 ${tools.size} 个工具：" + toolNames.take(6).joinToString("、") +
        if (tools.size > 6) " …" else ""
    _uiState.update { s ->
        s.copy(
            mcpStartup = s.mcpStartup
                ?.takeIf { it.serverName == name }
                ?.let { it.copy(events = it.events + McpStartupEvent(name, McpStartupStage.TOOLS_DISCOVERED, detail), running = false) }
        )
    }
}
// ═══ MCP · 精选目录（#205：assets/mcp_catalog 离线随包，一键安装）═══

/**
 * 加载精选目录：遍历 assets/mcp_catalog 下的分类 JSON → [McpServerCatalog.parseCategoryFile]。
 *
 * 单文件损坏只降级跳过（错误计入 [MarketUiState.mcpCatalogError]），
 * 不拖垮其余分类。只加载一次（[init]），refresh 不重置。
 *
 * v1.4.7 热更 overlay（docs/hot-update-pipeline.md）：存在生效中的热更
 * 目录快照时，目录层**整体替换**为 overlay（`filesDir/hot/active/mcp_catalog`）
 * —— 支持上游增删分类文件；overlay 全军覆没（零条目且报错）时回退
 * APK assets。无热更内容时与旧行为逐字节一致。
 *
 * #206 加固：跨文件重复 id 与非法条目不进入 [MarketUiState.mcpCatalog]
 * （LazyColumn 的 item key 与安装装配都假定 id 唯一合法——有问题条目
 * 只留在错误提示里，不进渲染列表）。
 *
 * Hub v2 迁移（docs/hub-ecosystem.md）：随包精选目录已整体迁往官方
 * MCP 仓库（apex-mcp-hub），APK 不再打包 assets/mcp_catalog —— 本方法
 * 允许 assets 目录缺失/为空（零条目零报错，市场精选目录段自动隐藏）；
 * 热更 overlay 与未来重新随包分发两条连路均不受影响。
 */
internal fun MarketViewModel.loadMcpCatalog() {
    viewModelScope.launch(Dispatchers.IO) {
        val entries = mutableListOf<McpServerCatalog.McpCatalogEntry>()
        val errors = mutableListOf<String>()
        // 热更 overlay 读取（store 读取入口自带 APK 追平退役）
        val overlayFiles = HotContentStore(
            appContext.filesDir,
            HotContentStore.SharedPrefs(appContext),
            BuildConfig.VERSION_CODE
        ).activeCatalogFiles()
        runCatching {
            if (overlayFiles.isNotEmpty()) {
                for (file in overlayFiles) {
                    runCatching { file.readText() }.fold(
                        onSuccess = { text ->
                            McpServerCatalog.parseCategoryFile(text).fold(
                                onSuccess = { entries += it.entries },
                                onFailure = { errors += "${file.name}: ${it.message}" }
                            )
                        },
                        onFailure = { errors += "${file.name}: ${it.message}" }
                    )
                }
            }
            // 回退：无 overlay，或 overlay 颗粒无收（快照损坏）→ APK assets。
            // Hub v2：随包目录已退役（迁官方 MCP 仓库），assets 缺失/为空
            // 不再视为错误 —— 零条目即零渲染，市场目录段自动隐藏。
            if (entries.isEmpty()) {
                val names = appContext.assets.list("mcp_catalog").orEmpty()
                    .filter { it.endsWith(".json") }.sorted()
                for (file in names) {
                    val text = appContext.assets.open("mcp_catalog/$file")
                        .bufferedReader().use { it.readText() }
                    McpServerCatalog.parseCategoryFile(text).fold(
                        onSuccess = { entries += it.entries },
                        onFailure = { errors += "${file}: ${it.message}" }
                    )
                }
            }
        }.onFailure { errors += it.message.orEmpty() }
        val issues = McpServerCatalog.validateEntries(entries)
        // 跨文件去重（首个胜出，后到的重复 id 只报错不渲染）。
        val seen = HashSet<String>()
        val deduped = entries.filter { seen.add(it.id) }
        val dedupDrops = entries.size - deduped.size
        // P2（#206 加固收尾）：非法条目过滤 —— 注释承诺“非法条目不进入列表”
        // 但旧实现只去重未滤非法（LazyColumn 的 item key 与安装装配都假定
        // id 合法）。“重复”类 issue 的 entryId 与首个同 id，不计入非法集
        //（去重逻辑已另行处理，避免误杀合法首个）。
        val invalidIds = issues
            .filterNot { it.reason.startsWith("id 重复") }
            .map { it.entryId }
            .toSet()
        val legal = deduped.filterNot { it.id in invalidIds }
        val illegalDrops = deduped.size - legal.size
        _uiState.update {
            it.copy(
                mcpCatalog = legal,
                mcpCatalogLoaded = true,
                mcpCatalogError = buildList {
                    errors.forEach { e -> add(e) }
                    issues.forEach { i -> add("${i.entryId}: ${i.reason}") }
                    if (dedupDrops > 0) add("跨文件重复 id：已丢弃 $dedupDrops 条")
                    if (illegalDrops > 0) add("非法条目：已拦截 $illegalDrops 条（不进列表）")
                }.takeIf { l -> l.isNotEmpty() }?.joinToString("；")
            )
        }
    }
}

/** 当前分级下可见的目录条目（#197 tier 过滤 + 分类过滤）。 */
fun MarketViewModel.visibleCatalog(state: MarketUiState): List<McpServerCatalog.McpCatalogEntry> {
    val tier = state.tier.name.lowercase()
    return state.mcpCatalog
        .filter { it.visibleToTier(tier) }
        .filter { state.mcpCatalogCategory == null || it.category == state.mcpCatalogCategory }
}

/** 目录分类切换（null = 全部）。 */
fun MarketViewModel.selectCatalogCategory(category: String?) {
    _uiState.update { it.copy(mcpCatalogCategory = category) }
}
/**
 * 目录条目是否已安装：精确名匹配 + 安装时生成的序号后缀形态
 * （id-2 / id-3…）；不再用宽泛前缀（`github-backup` 不再误判成 `github`）。
 */
fun MarketViewModel.isCatalogEntryInstalled(entry: McpServerCatalog.McpCatalogEntry): Boolean {
    val suffixPattern = Regex("^${Regex.escape(entry.id)}-\\d+$")
    return mcpManager.getConfigs().any { it.name == entry.id || suffixPattern.matches(it.name) }
}

/** 打开目录条目的环境变量弹窗（无必填变量时 UI 直接走安装）。 */
fun MarketViewModel.openCatalogEnvDialog(entry: McpServerCatalog.McpCatalogEntry) {
    _uiState.update { it.copy(catalogEnvEntry = entry) }
}

fun MarketViewModel.closeCatalogEnvDialog() {
    _uiState.update { it.copy(catalogEnvEntry = null) }
}

/**
 * 安装目录条目：必填环境变量预检 → 预检（[McpConfigValidator]）→
 * 重名自动加序号 → addServer + connect（真实启动事件弹窗 + tracker 留底）。
 *
 * #206 加固：busy 锁防双击并发重入（旧行为两连点会各自 addServer 互相
 * 覆盖）；序号后缀改为递增扫描（时间戳取模可能撞车）；装配包裹
 * runCatching（非法目录条目不再炸协程）。
 */
fun MarketViewModel.installCatalogEntry(entry: McpServerCatalog.McpCatalogEntry, envValues: Map<String, String>) {
    viewModelScope.launch {
        _uiState.update { it.copy(catalogEnvEntry = null) }
        val missing = McpServerCatalog.missingRequiredEnv(entry, envValues)
        if (missing.isNotEmpty()) {
            message(languageManager.getString(R.string.market_mcp_catalog_missing_env).format(missing.joinToString("、")))
            return@launch
        }
        if (_uiState.value.mcpConnecting != null) return@launch // 已有连接在进行：防双击
        // 重名递增：filesystem 已存在 → filesystem-2 → filesystem-3…
        val existing = mcpManager.getConfigs().map { it.name }.toSet()
        val name = if (entry.id !in existing) entry.id
        else {
            var n = 2
            while ("${entry.id}-$n" in existing) n++
            "${entry.id}-$n"
        }
        val sandboxAvailable = java.io.File(appContext.filesDir, "rootfs/ubuntu/current").exists()
        val config = runCatching {
            McpServerCatalog.toServerConfig(
                entry, envValues, name,
                runInSandbox = sandboxAvailable && entry.transport == McpTransport.STDIO
            )
        }.getOrElse {
            message(languageManager.getString(R.string.market_mcp_catalog_preflight_failed).format(it.message ?: ""))
            return@launch
        }
        // #205 预检：静态可判定的问题当场报出（重名/沙箱未装/URL 非法/注入字符）。
        val findings = McpConfigValidator.validate(
            config,
            existingNames = emptySet(), // 重名已自行处理为唯一名
            builtinNames = emptySet(),
            sandboxReady = if (config.runInSandbox) sandboxAvailable else null
        )
        McpConfigValidator.errorsOf(findings).let { errors ->
            if (errors.isNotEmpty()) {
                message(
                    languageManager.getString(R.string.market_mcp_catalog_preflight_failed)
                        .format(errors.joinToString("；") { it.message })
                )
                return@launch
            }
        }
        addMcpServer(config)
    }
}
/** #205 回放某台服务器的启动时间线（tracker 留底，弹窗只读渲染）。 */
fun MarketViewModel.showMcpTimeline(name: String) {
    // #206 修复：不覆盖进行中的实时弹窗（连接事件会继续追加进历史快照造成混排）。
    if (_uiState.value.mcpConnecting != null) return
    _uiState.update {
        it.copy(mcpStartup = McpStartupUi(
            serverName = name,
            events = startupTracker.snapshot(name),
            running = false
        ))
    }
}

/** #205 该服务器是否有时间线可看（installed 行按钮的可见性）。 */
fun MarketViewModel.hasMcpTimeline(name: String): Boolean = startupTracker.hasEvents(name)
