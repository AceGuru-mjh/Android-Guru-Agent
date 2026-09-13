package com.apex.agent.browser.chrome.bridge

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.apex.agent.browser.BrowserEngine
import com.apex.agent.browser.chrome.BrowserEngineGateway
import com.apex.agent.browser.chrome.HistoryEntry
import com.apex.agent.browser.chrome.JsDialogChoice
import com.apex.agent.browser.chrome.JsDialogRequest
import com.apex.agent.browser.chrome.PermissionDecision
import com.apex.agent.browser.chrome.TabIdentity
import com.apex.agent.browser.chrome.TabSet
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ApexChromeWiring —— 把通用 [NeonChromeWiring] 接到本项目 [BrowserEngine] 的真实接线器。
 *
 * 职责（全部收敛在这里，引擎与 chrome UI 保持互不感知）：
 * 1. **TabOperations 六行直通**：chrome 标签命令 → 引擎 newTab/switchTab/closeTab；
 * 2. **链式接管**：活动 WebView 的 client 链式包一层——引擎自动化回调零破坏，
 *    JS 弹窗/网页权限/下载/进度/标题/查找全部导入 chrome 层；
 * 3. **人机双通道弹窗**：WAITING_HUMAN（浮窗开着）→ 人裁决；
 *    无人值守（AGENT_DRIVING/HIDDEN）→ [AgentDialogPolicy] 安全默认
 *    （ALERT 自动确认 / CONFIRM 默认拒绝 / PROMPT 不接管按取消回放）；
 * 4. **无人值守权限**：非 WAITING_HUMAN 时网页权限一律安全拒绝
 *    （对齐引擎旧 onPermissionRequest 的 deny 语义，但不打断状态机）；
 * 5. **上下文回写**：弹窗/下载事件回写 engine.lastDialog / lastDownload，
 *    Agent 侧 snapshot 注入与 browser_download_list 工具语义保持不变；
 * 6. **状态联动**：订阅引擎 BrowserUiCallback，会话状态变化即 resync（浮窗展开、
 *    Agent 开新标签、渲染进程重建后，chrome 数据源自动对齐引擎实况）。
 *
 * 用法（BrowserOverlay 内）：
 * ```
 * val gateway = wiring.gateway      // 交给 BrowserChrome(gateway = ...)
 * wiring.onActiveWebViewChanged = { view -> rebindWebView(view) }
 * ```
 */
@Singleton
class ApexChromeWiring @Inject constructor(
    @ApplicationContext appContext: Context,
    private val engine: BrowserEngine,
) : BrowserEngine.BrowserUiCallback {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.w("ApexChromeWiring", "chrome wiring task failed", e)
        }
    )

    /** 引擎会话状态镜像（弹窗/权限「等人 vs 自动」的分岔依据）。 */
    private val sessionState = MutableStateFlow(BrowserEngine.BrowserSessionState.HIDDEN)

    /** chrome 层可见的引擎会话状态（浮窗状态徽章等）。 */
    val engineSessionState: StateFlow<BrowserEngine.BrowserSessionState> = sessionState.asStateFlow()

    /** 活动标签 WebView 更换（tab 切换/新建/崩溃重建）：浮窗据此换挂容器。 */
    var onActiveWebViewChanged: ((WebView) -> Unit)? = null

    /** 通用接线器（链式 client + 快照合成 + 弹窗/权限/下载路由）。 */
    val wiring: NeonChromeWiring = NeonChromeWiring(
        appContext = appContext,
        tabOps = BrowserEngineTabOps(engine),
        grantStore = SharedPreferencesGrantStore(appContext),
        scope = scope,
        historySource = { query, limit -> engineHistory(query, limit) },
    )

    /** chrome 层唯一入口：交给 BrowserChrome(gateway = ...)。 */
    val gateway: BrowserEngineGateway get() = wiring.gateway

    init {
        wiring.onWebViewRebound = { view -> onActiveWebViewChanged?.invoke(view) }
        // 下载入队回写：Agent 工具 browser_download_list 继续可读
        wiring.downloads.onEnqueued = { fileName, url, dmId ->
            engine.reportDownload(fileName, url, dmId)
        }
        // Agent 弹窗策略：安全默认 + 审计回写 lastDialog（保持 Agent 上下文注入）
        wiring.agentDecider = AgentDialogPolicy(
            scope = scope,
            onDecision = { request, choice, _, _ ->
                engine.reportDialogForContext(
                    "${request.kind.name.lowercase()}: ${request.message} → ${choiceLabel(choice)}"
                )
            },
        )
        engine.addUiCallback(this)
        // 弹窗入队即回写引擎上下文（对齐旧 onJsAlert 内联 lastDialog 赋值时点）
        scope.launch {
            wiring.router.dialogRequests.collect { list ->
                list.firstOrNull()?.let { req ->
                    engine.reportDialogForContext("${req.kind.name.lowercase()}: ${req.message}")
                }
            }
        }
        // 无人值守：弹窗自动交给 AgentDialogPolicy（安全默认）
        scope.launch {
            wiring.router.dialogRequests.collect { list ->
                if (list.isEmpty()) return@collect
                if (sessionState.value == BrowserEngine.BrowserSessionState.WAITING_HUMAN) return@collect
                gateway.resolveDialog(
                    list.first().id, JsDialogChoice.DISMISS, null, routeToAgent = true,
                )
            }
        }
        // 无人值守：网页权限一律安全拒绝（对齐引擎旧 deny 默认）
        scope.launch {
            wiring.router.permissionRequests.collect { list ->
                if (list.isEmpty()) return@collect
                if (sessionState.value == BrowserEngine.BrowserSessionState.WAITING_HUMAN) return@collect
                gateway.resolvePermission(list.first().id, PermissionDecision.DENY)
            }
        }
    }

    /** 标签结构与活动 WebView 对齐引擎实况（浮窗展开时调一次即可全量对齐）。 */
    fun resync() = wiring.resync()

    /* ---------------- BrowserUiCallback：状态联动 ---------------- */

    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        sessionState.value = state
        // 会话状态任何变化都重新对齐（浮窗展开、Agent 开新标签、崩溃重建）
        if (state != BrowserEngine.BrowserSessionState.HIDDEN) {
            wiring.resync()
        }
    }

    /* ---------------- 私有 ---------------- */

    private fun engineHistory(query: String, limit: Int): List<HistoryEntry> =
        engine.recentHistory(limit * 2)
            .filter { it.contains(query, ignoreCase = true) }
            .takeLast(limit)
            .map { HistoryEntry(url = it, title = "") }

    private fun choiceLabel(choice: JsDialogChoice): String = when (choice) {
        JsDialogChoice.POSITIVE -> "确认"
        JsDialogChoice.NEGATIVE -> "拒绝"
        JsDialogChoice.DISMISS -> "关闭"
    }
}

/**
 * BrowserEngine → [TabOperations] 直通（六个一行方法）。
 * 全部方法都在主线程被调用（Compose 命令链 / wiring.onMain），与引擎要求一致。
 */
private class BrowserEngineTabOps(
    private val engine: BrowserEngine,
) : TabOperations {

    override fun newTab(url: String?): String = engine.newTabImmediate(url).toString()

    override fun switchTab(tabId: String) {
        tabId.toIntOrNull()?.let { engine.switchTab(it) }
    }

    override fun closeTab(tabId: String) {
        tabId.toIntOrNull()?.let { engine.closeTab(it) }
    }

    override fun closeAllTabs() = engine.closeAllTabs()

    override fun currentTabs(): TabSet {
        val tabs = engine.tabsSnapshot().map { (id, url, title) ->
            TabIdentity(id = id.toString(), title = title, url = url)
        }
        val active = engine.activeTab()?.id?.toString()
        return TabSet(tabs = tabs, activeTabId = active)
    }

    override fun activeWebView(): WebView? = engine.activeWebView()
}
