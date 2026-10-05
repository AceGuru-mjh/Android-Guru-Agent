package com.apex.agent.ui.screen.market

import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpConfigValidator
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// 工位 MCP 服务器管理 —— MarketViewModel 的扩展（God-file 预算拆分，
// 模式同 CodeLongTaskCenterOps.kt：函数体原样迁移，依赖成员已在 VM
// 开放 internal，Compose 调用点与方法引用语义不变）。
//
// 职责：
//  - addMcpServer / updateMcpServer：覆盖写 + 自动连接（真实启动事件弹窗）；
//  - importMcpConfig：社区 JSON 批量导入（逐条报错）；
//  - toggleMcp / connectMcp / disconnectMcp / removeMcp：启停与连接；
//  - updateMcpScope：BUILTIN 配置对话框的作用域更新；
//  - openMcpEditor / closeMcpEditor：编辑器状态（_editingMcp 留在 VM）；
//  - validateMcpConfig：添加/编辑对话框共享预检。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 添加 MCP 工具源。
 *
 * 注意 STDIO 不是"服务器 URL"：它是本地命令（command + args + env），
 * 添加成功后 connect 会真正把这个进程拉起来做 JSON-RPC 握手。
 *
 * #205：添加后的自动连接也接上真实启动事件（进度弹窗 + tracker 留底）
 * —— 旧版只有手动「连接」才弹进度，添加失败时用户只看到一句抽象报错。
 */
fun MarketViewModel.addMcpServer(config: McpServerConfig) {
    // P1 修复（双击锁前置）：入口**同步**置位（launch 之前）—— 旧实现
    // 在协程内部 addServer 成功后才置 mcpConnecting，两个连点协程都能
    // 通过彼此的检查窗口，同名 addServer 互相覆盖（#206 声称修复但
    // 未修住）。同步置位后，重组间隙的第二次点击（无论入口在哪个
    // 调用方）都会看到已置位的标志而被拦截。
    if (_uiState.value.mcpConnecting != null) return
    val name = config.name.trim()
    _uiState.update { it.copy(mcpConnecting = name) }
    viewModelScope.launch {
        try {
            // #197 市场分级：从当前分级带入作用域（Agent 市场添加的归 agent 工位，
            // Coding 市场添加的归 coding 工位；导入/编辑可改）。
            val tierScope = _uiState.value.tier.name.lowercase()
            val scoped = if (config.scope == "all" && config.transport != McpTransport.BUILTIN) {
                config.copy(name = name, scope = tierScope)
            } else {
                config.copy(name = name)
            }
            mcpManager.addServer(scoped).fold(
                onSuccess = {
                    // P2：连接结果不再被吞 —— 添加后立即连接失败（URL 错/命令不存在）时
                    // 用户只看到「已添加」成功提示，错误静默丢失。fold 进同一条 snackbar。
                    // #205：连接过程有真实启动事件弹窗（环境/spawn/握手/stderr）。
                    _uiState.update { it.copy(mcpStartup = McpStartupUi(serverName = name)) }
                    val connectMsg = mcpManager.connect(name, startupListenerFor(name)).fold(
                        onSuccess = { languageManager.getString(R.string.market_mcp_added).format(name) },
                        onFailure = {
                            languageManager.getString(R.string.market_add_failed)
                                .format("${it.message ?: ""}")
                        }
                    )
                    // #206 修复：连接成功也做 tools 发现收尾，弹窗不再无限转圈。
                    if (mcpManager.getConnectedServers().contains(name)) {
                        finishStartupWithTools(name)
                    } else {
                        _uiState.update { s ->
                            s.copy(mcpStartup = s.mcpStartup
                                ?.takeIf { it.serverName == name }?.let { it.copy(running = false) })
                        }
                    }
                    message(connectMsg)
                    refresh()
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
                }
            )
        } finally {
            // P1 修复：无论成功/失败/取消，入口同步置位的锁都在此复位 ——
            // 旧实现的 onFailure 分支不清理（虽未置位，但调用方（目录安装）
            // 的入口锁会泄漏），双击锁一但失效就永久失效。
            _uiState.update { s ->
                if (s.mcpConnecting == name) s.copy(mcpConnecting = null) else s
            }
        }
    }
}
/**
 * 导入社区通用 MCP 配置 JSON（`{"mcpServers": {...}}`）。
 *
 * 支持 `command/args/env`（STDIO 本地命令）与 `type=streamable_http|sse` + `url`
 * 两种形态；解析不通过的条目会**逐条报出原因**，不会静默丢配置。
 */
fun MarketViewModel.importMcpConfig(text: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val parsed = McpConfigImport.parse(text)
        // 分隔符 / “名称：原因”模板按语言取（en 用 "; " 与 ": "）
        val sep = languageManager.getString(R.string.market_list_sep)
        val nameReason = { serverName: String, reason: String ->
            languageManager.getString(R.string.market_name_reason).format(serverName, reason)
        }
        if (parsed.configs.isEmpty()) {
            val detail = parsed.errors.joinToString(sep) { nameReason(it.serverName, it.reason) }
                .ifBlank { languageManager.getString(R.string.market_mcp_import_empty) }
            message(languageManager.getString(R.string.market_mcp_import_none).format(detail))
            return@launch
        }
        val added = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (config in parsed.configs) {
            val exists = mcpManager.getConfigs().any { it.name == config.name }
            val unique = if (exists) config.copy(name = "${config.name}-${System.currentTimeMillis()}") else config
            mcpManager.addServer(unique).fold(
                onSuccess = { added += unique.name },
                onFailure = { failed += nameReason(unique.name, it.message ?: "") }
            )
        }
        val summary = buildString {
            append(languageManager.getString(R.string.market_mcp_imported_count).format(added.size))
            if (failed.isNotEmpty()) {
                append(
                    languageManager.getString(R.string.market_mcp_import_failed_part)
                        .format(failed.size, failed.joinToString(sep))
                )
            }
            if (parsed.errors.isNotEmpty()) {
                append(
                    languageManager.getString(R.string.market_mcp_import_skipped_part).format(
                        parsed.errors.size,
                        parsed.errors.joinToString(sep) { nameReason(it.serverName, it.reason) }
                    )
                )
            }
        }
        message(summary)
        withContext(Dispatchers.Main) { refresh() }
    }
}
fun MarketViewModel.toggleMcp(name: String, enabled: Boolean) {
    viewModelScope.launch {
        mcpManager.setEnabled(name, enabled).fold(
            onSuccess = {
                message(
                    if (enabled) {
                        languageManager.getString(R.string.market_mcp_enabled).format(name)
                    } else {
                        languageManager.getString(R.string.market_mcp_disabled_disconnected).format(name)
                    }
                )
                refresh()
            },
            onFailure = {
                message(languageManager.getString(R.string.market_action_failed).format(it.message ?: ""))
            }
        )
    }
}

fun MarketViewModel.connectMcp(name: String) {
    viewModelScope.launch {
        _uiState.update { it.copy(mcpConnecting = name, mcpStartup = McpStartupUi(serverName = name)) }
        // #197/#205 真实启动事件流：共享监听器（tracker 留底 + 弹窗实时渲染），
        // 每个真实阶段（env/spawn/initialize/initialized/stderr）逐条可见；
        // 连接成功后再做一次真实 tools/list 发现（这也是 McpToolRegistrar
        // 的注册路径）。
        val listener = startupListenerFor(name)
        try {
            mcpManager.connect(name, listener).fold(
                onSuccess = {
                    // 真实工具发现：与 McpToolRegistrar 同一数据源（listServerTools）。
                    // #206：收尾逻辑抽取为 finishStartupWithTools（tracker 去重重记）。
                    finishStartupWithTools(name)
                    message(languageManager.getString(R.string.market_mcp_connected).format(name))
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_connect_failed).format(it.message ?: ""))
                }
            )
        } finally {
            _uiState.update { it.copy(mcpConnecting = null) }
        }
        refresh()
    }
}

/** #197 关闭启动进度弹窗（连接已完成/失败后用户手动关闭）。 */
fun MarketViewModel.dismissMcpStartup() {
    _uiState.update { it.copy(mcpStartup = null) }
}

/**
 * 更新 MCP 工位作用域（BUILTIN 配置对话框的「配置」动作之一）。
 * addServer 覆盖写（不动活跃连接——作用域只影响可见性，不影响协议层）。
 */
fun MarketViewModel.updateMcpScope(name: String, scope: String) {
    viewModelScope.launch {
        val config = mcpManager.getConfigs().firstOrNull { it.name == name }
        if (config == null || config.scope == scope) return@launch
        mcpManager.addServer(config.copy(scope = scope)).fold(
            onSuccess = { refresh() },
            onFailure = {
                message(languageManager.getString(R.string.market_action_failed).format(it.message ?: ""))
            }
        )
    }
}

fun MarketViewModel.disconnectMcp(name: String) {
    viewModelScope.launch {
        mcpManager.disconnect(name)
        message(languageManager.getString(R.string.market_mcp_disconnected).format(name))
        refresh()
    }
}

fun MarketViewModel.removeMcp(name: String) {
    viewModelScope.launch {
        mcpManager.removeServer(name)
        startupTracker.clear(name)
        message(languageManager.getString(R.string.market_mcp_removed).format(name))
        refresh()
    }
}
/** 打开编辑器：按名取配置快照（不存在则忽略 —— 列表与配置极小概率失同步）。 */
fun MarketViewModel.openMcpEditor(name: String) {
    val config = mcpManager.getConfigs().firstOrNull { it.name == name } ?: return
    _editingMcp.value = config
}

fun MarketViewModel.closeMcpEditor() {
    _editingMcp.value = null
}
/**
 * 保存编辑后的配置：断开旧连接（配置已变，旧连接必然失效）→ 覆盖写 →
 * enabled 时自动重连（对齐「添加并连接」的行为闭环）。
 *
 * #205：重连同样接上真实启动事件（进度弹窗 + tracker 留底）。
 */
fun MarketViewModel.updateMcpServer(config: McpServerConfig) {
    viewModelScope.launch {
        val name = config.name.trim()
        // 先断开：addServer 只覆盖配置不触碰活跃连接，旧 client 挂着旧参数
        mcpManager.disconnect(name)
        mcpManager.addServer(config.copy(name = name)).fold(
            onSuccess = {
                // 连接结果折叠（同 addMcpServer）：编辑保存后重连失败不再静默。
                if (config.enabled) {
                    _uiState.update { it.copy(mcpConnecting = name, mcpStartup = McpStartupUi(serverName = name)) }
                    val connectMsg = mcpManager.connect(name, startupListenerFor(name)).fold(
                        onSuccess = {
                            languageManager.getString(R.string.market_mcp_edit_saved).format(name)
                        },
                        onFailure = {
                            languageManager.getString(R.string.market_add_failed)
                                .format("${it.message ?: ""}")
                        }
                    )
                    // #206 修复：与添加路径同构的 tools 发现收尾，弹窗不转圈。
                    if (mcpManager.getConnectedServers().contains(name)) {
                        finishStartupWithTools(name)
                    } else {
                        _uiState.update { s ->
                            s.copy(mcpStartup = s.mcpStartup
                                ?.takeIf { it.serverName == name }?.let { it.copy(running = false) })
                        }
                    }
                    message(connectMsg)
                    _uiState.update { it.copy(mcpConnecting = null) }
                } else {
                    message(languageManager.getString(R.string.market_mcp_edit_saved).format(name))
                }
                refresh()
            },
            onFailure = {
                message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
            }
        )
        _editingMcp.value = null
    }
}
/**
 * #206 添加/编辑对话框的共享预检：全部 [McpConfigValidator] 规则
 * （含重名 —— 旧行为 addServer 直接覆盖同名配置造成无声数据丢失）。
 */
fun MarketViewModel.validateMcpConfig(config: McpServerConfig): List<McpConfigValidator.Finding> {
    val existing = mcpManager.getConfigs().map { it.name }.toSet()
    val sandboxAvailable = java.io.File(appContext.filesDir, "rootfs/ubuntu/current").exists()
    return McpConfigValidator.validate(
        config,
        existingNames = existing,
        builtinNames = mcpManager.getConfigs()
            .filter { it.transport == McpTransport.BUILTIN }.map { it.name }.toSet(),
        sandboxReady = if (config.runInSandbox) sandboxAvailable else null
    )
}
