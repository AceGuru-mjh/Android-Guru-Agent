package com.apex.agent.browser.chrome.bridge

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.apex.agent.browser.chrome.BrowserEngineGateway
import com.apex.agent.browser.chrome.DownloadItem
import com.apex.agent.browser.chrome.EngineSnapshot
import com.apex.agent.browser.chrome.HistoryEntry
import com.apex.agent.browser.chrome.JsDialogChoice
import com.apex.agent.browser.chrome.JsDialogRequest
import com.apex.agent.browser.chrome.PermissionDecision
import com.apex.agent.browser.chrome.PermissionRequest
import com.apex.agent.browser.chrome.TabCard
import com.apex.agent.browser.chrome.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/*
 * BrowserEngineGatewayAdapter —— 引擎适配器（模板，按注释微调即可用）。
 *
 * 你们的引擎已具备 newTab / switchTab / goBack / reload 等命令，
 * 本适配器做三件事：
 * 1. 命令转发：Chrome → 引擎（多数一行直通，见 EngineCommands）；
 * 2. 快照发布：把引擎的标签/进度/标题变化发布成 EngineSnapshot StateFlow；
 * 3. 桥接弹窗/权限/下载三个 StateFlow（来自 EngineSideHooks 的三个组件）。
 *
 * ============ 需要你调整的两处（都有标记） ============
 * [A] EngineCommands 实现类：把方法体替换成你 BrowserEngine 的真实调用；
 *     （用 NeonChromeWiring 时此项已由其内置 DirectCommands 完成，无需手写。）
 * [B] 快照源：二选一 —— bind Flow（推荐）或在引擎回调里手动 publishSnapshot；
 *     （用 NeonChromeWiring 时由链式客户端自动采集合成，也无需手写。）
 */

/** [A] 引擎命令外观 —— 与你现有 BrowserEngine 的方法一一对应，逐行替换方法体。 */
interface EngineCommands {
    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()
    fun loadUrl(url: String)
    /** 新标签：返回标签 id；url 为 null 时打开引擎默认页。 */
    fun newTab(url: String?): String
    fun switchTab(tabId: String)
    fun closeTab(tabId: String)
    fun closeAllTabs()
    fun findInPage(query: String, forward: Boolean)
    fun clearFindMatches()
    fun setDesktopMode(enabled: Boolean)
}

class BrowserEngineGatewayAdapter(
    private val commands: EngineCommands,
    private val appContext: Context,
    private val router: DialogRequestRouter,
    private val downloadBridge: ChromeDownloadManager,
    /** 地址栏非 URL 输入的搜索模板。 */
    private val searchTemplate: String = "https://www.bing.com/search?q=%s",
    /** [B] 方式一（推荐）：引擎已有标签状态 Flow 时直接传入。 */
    snapshotSource: Flow<EngineSnapshot>? = null,
    /** 历史联想数据源（可选；NeonChromeWiring 构造参数 historySource 会传到这里）。 */
    private val historySource: (suspend (query: String, limit: Int) -> List<HistoryEntry>)? = null,
) : BrowserEngineGateway {

    private val _state = MutableStateFlow(EngineSnapshot())
    override val state: StateFlow<EngineSnapshot> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var snapshotCollector: Job? = null

    init {
        snapshotSource?.let { source ->
            snapshotCollector = scope.launch { source.collect { _state.value = it } }
        }
    }

    /** [B] 方式二：引擎无 Flow 时，在标签/进度/标题变化回调里手动调用本方法。 */
    fun publishSnapshot(snapshot: EngineSnapshot) {
        _state.value = snapshot
    }

    fun release() {
        snapshotCollector?.cancel()
        scope.cancel()
    }

    /* ---- 弹窗 / 权限 / 下载：直通 EngineSideHooks 的三个组件 ---- */

    override val dialogRequests: StateFlow<List<JsDialogRequest>> = router.dialogRequests
    override val permissionRequests: StateFlow<List<PermissionRequest>> = router.permissionRequests
    override val downloads: StateFlow<List<DownloadItem>> = downloadBridge.downloads

    override fun resolveDialog(
        requestId: String,
        choice: JsDialogChoice,
        promptValue: String?,
        routeToAgent: Boolean,
    ) = router.resolveDialog(requestId, choice, promptValue, routeToAgent)

    override fun resolvePermission(requestId: String, decision: PermissionDecision) =
        router.resolvePermission(requestId, decision)

    override fun revokeRememberedGrants(originHost: String) =
        router.revokeRememberedGrants(originHost)

    override fun cancelDownload(id: String) = downloadBridge.cancel(id)
    override fun retryDownload(id: String) = downloadBridge.retry(id)
    override fun openDownload(id: String) = downloadBridge.open(id)

    /* ---- 导航与标签 ---- */

    override fun goBack() = commands.goBack()
    override fun goForward() = commands.goForward()
    override fun reload() = commands.reload()
    override fun stopLoading() = commands.stopLoading()
    override fun loadUrl(url: String) = commands.loadUrl(url)

    override fun search(query: String) {
        commands.loadUrl(UrlUtils.searchUrl(searchTemplate, query))
    }

    override fun newTab(url: String?): String = commands.newTab(url)
    override fun switchTab(tabId: String) = commands.switchTab(tabId)
    override fun closeTab(tabId: String) = commands.closeTab(tabId)
    override fun closeAllTabs() = commands.closeAllTabs()

    override fun findInPage(query: String, forward: Boolean) = commands.findInPage(query, forward)
    override fun clearFindMatches() = commands.clearFindMatches()
    override fun setDesktopMode(enabled: Boolean) = commands.setDesktopMode(enabled)

    override fun openInExternalBrowser(url: String) {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun clipboardUrlOrNull(): String? {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return null
        return text.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    override suspend fun queryHistory(query: String, limit: Int): List<HistoryEntry> {
        // [可选] 接你们的浏览历史存储；默认返回空（地址栏仅少一类联想，不影响其他功能）。
        return historySource?.invoke(query, limit) ?: emptyList()
    }

    companion object {
        /** 引擎快照构造辅助：把原始标签信息组装成 TabCard（host/isSecure 自动推导）。 */
        fun tabCard(
            id: String,
            title: String,
            url: String,
            isLoading: Boolean,
            progress: Float,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ): TabCard = TabCard(
            id = id,
            title = title,
            url = url,
            host = UrlUtils.hostOf(url),
            isSecure = url.startsWith("https://") || url.startsWith("about:"),
            isLoading = isLoading,
            progress = progress,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
        )
    }
}
