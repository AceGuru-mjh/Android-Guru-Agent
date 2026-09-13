package com.apex.agent.browser.chrome

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/*
 * BrowserChromeController —— Chrome 层唯一的 UI 状态机。
 *
 * 职责：
 * - 把引擎快照 + 本地交互态（编辑/浮层/查找/关闭宽限）合并成 ChromeUiState；
 * - 把 Compose 回调翻译成网关命令；
 * - 「关标签 4 秒可撤销」的宽限期完全在控制器内消化，引擎侧永远收到的是立即 close。
 *
 * 线程模型：start(scope) 后假定在主线程调用各 intent 函数（Compose 点击天然满足）。
 */
class BrowserChromeController(
    private val gateway: BrowserEngineGateway,
    private val scripts: UserscriptRegistry? = null,
    val config: ChromeConfig = ChromeConfig(),
) {

    /** 编辑会话内部形态。 */
    private data class EditSession(
        val text: String,
        val suggestions: List<Suggestion>,
        val clipboardShown: Boolean,
    )

    private val _ui = MutableStateFlow(ChromeUiState())
    val ui: StateFlow<ChromeUiState> = _ui.asStateFlow()

    private val _snacks = MutableSharedFlow<ChromeSnackBar>(extraBufferCapacity = 8)
    val snacks = _snacks.asSharedFlow()

    private val editing = MutableStateFlow<EditSession?>(null)
    private val sheet = MutableStateFlow<ChromeSheet?>(null)
    private val findVisible = MutableStateFlow(false)
    private val closingIds = MutableStateFlow<Set<String>>(emptySet())

    private var scope: kotlinx.coroutines.CoroutineScope? = null
    private var collector: Job? = null
    private val closeJobs = mutableMapOf<String, Job>()
    private var findDebounceJob: Job? = null
    private var lastFindQuery: String = ""

    @Volatile
    private var historyCache: List<HistoryEntry> = emptyList()

    /* ---------------- 生命周期 ---------------- */

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (collector?.isActive == true) return
        this.scope = scope
        collector = scope.launch {
            combine(
                gateway.state, editing, sheet, findVisible, closingIds,
            ) { snap, edit, sheetV, findV, closingV ->
                render(snap, edit, sheetV, findV, closingV)
            }.collect { _ui.value = it }
        }
    }

    fun stop() {
        collector?.cancel(); collector = null
        findDebounceJob?.cancel()
        closeJobs.values.forEach { it.cancel() }
        closeJobs.clear()
        scope = null
    }

    private fun render(
        snap: EngineSnapshot,
        edit: EditSession?,
        sheetV: ChromeSheet?,
        findV: Boolean,
        closingV: Set<String>,
    ): ChromeUiState {
        val active = snap.tabs.firstOrNull { it.id == snap.activeTabId }
        val address = edit?.let {
            AddressBarUi.Editing(text = it.text, suggestions = it.suggestions)
        } ?: run {
            val display = active?.let { UrlUtils.splitDisplay(it.url) }
            AddressBarUi.Collapsed(
                host = display?.host.orEmpty(),
                pathQuery = display?.pathQuery,
                isSecure = active?.isSecure ?: false,
                progress = if (active?.isLoading == true) active.progress else null,
            )
        }
        return ChromeUiState(
            address = address,
            tabs = snap.tabs,
            activeTabId = snap.activeTabId,
            activeTab = active,
            closingTabIds = closingV,
            findBarVisible = findV,
            findState = snap.findState,
            sheet = sheetV,
            desktopMode = snap.desktopMode,
            tabStripVisible = snap.tabs.size > 1 || !config.hideTabStripWhenSingle,
        )
    }

    /* ---------------- 导航 ---------------- */

    fun goBack() = gateway.goBack()
    fun goForward() = gateway.goForward()
    fun reload() = gateway.reload()
    fun stopLoading() = gateway.stopLoading()

    fun currentUrl(): String? = _ui.value.activeTab?.url

    /* ---------------- 地址栏 ---------------- */

    fun enterAddressEdit(initial: String? = null) {
        val text = (initial ?: currentUrl()).orEmpty()
        val s = scope
        if (s != null) {
            s.launch { historyCache = gateway.queryHistory("", 30) }
        }
        editing.value = EditSession(
            text = text,
            clipboardShown = text.isNotBlank(),
            suggestions = SuggestionsBuilder.build(
                text = text,
                tabs = _ui.value.tabs,
                history = historyCache,
                clipboardUrl = gateway.clipboardUrlOrNull(),
                includeClipboard = text.isBlank(),
                max = config.maxSuggestions,
                activeTabId = _ui.value.activeTabId,
            ),
        )
    }

    fun onAddressTextChanged(t: String) {
        val cur = editing.value ?: return
        editing.value = cur.copy(
            text = t,
            clipboardShown = cur.clipboardShown || t.isNotBlank(),
            suggestions = SuggestionsBuilder.build(
                text = t,
                tabs = _ui.value.tabs,
                history = historyCache,
                clipboardUrl = gateway.clipboardUrlOrNull(),
                includeClipboard = t.isBlank() && !cur.clipboardShown,
                max = config.maxSuggestions,
                activeTabId = _ui.value.activeTabId,
            ),
        )
    }

    fun exitAddressEdit() {
        editing.value = null
    }

    fun submitAddress(raw: String) {
        val t = raw.trim()
        exitAddressEdit()
        if (t.isEmpty()) return
        gateway.loadUrl(UrlUtils.resolveInput(t, config.searchTemplate))
    }

    fun pickSuggestion(s: Suggestion) {
        when (s.kind) {
            SuggestionKind.URL, SuggestionKind.HISTORY, SuggestionKind.CLIPBOARD ->
                s.actionUrl?.let { gateway.loadUrl(it) }
            SuggestionKind.SEARCH -> gateway.search(s.primary)
            SuggestionKind.OPEN_TAB -> s.tabId?.let { gateway.switchTab(it) }
        }
        exitAddressEdit()
    }

    fun pasteAndGo() {
        gateway.clipboardUrlOrNull()?.let { submitAddress(it) }
    }

    /* ---------------- 标签 ---------------- */

    fun newTab() {
        gateway.newTab(config.homeUrl)
    }

    fun selectTab(tabId: String) {
        exitAddressEdit()
        gateway.switchTab(tabId)
        if (sheet.value == ChromeSheet.TABS) sheet.value = null
    }

    /** 关闭标签：宽限期内只是视觉淡化，实际 close 延迟触发，可撤销。 */
    fun closeTab(tabId: String) {
        if (config.undoGraceMillis <= 0) {
            gateway.closeTab(tabId)
            return
        }
        closeJobs.remove(tabId)?.cancel()
        closingIds.value = closingIds.value + tabId
        val job = scope?.launch {
            delay(config.undoGraceMillis)
            gateway.closeTab(tabId)
            closingIds.value = closingIds.value - tabId
            closeJobs.remove(tabId)
        }
        if (job != null) closeJobs[tabId] = job
        showSnack(
            message = "已关闭标签",
            actionLabel = "撤销",
            onAction = { undoClose(tabId) },
        )
    }

    fun undoClose(tabId: String) {
        closeJobs.remove(tabId)?.cancel()
        closingIds.value = closingIds.value - tabId
    }

    fun closeOthers() {
        val active = _ui.value.activeTabId
        _ui.value.tabs.filter { it.id != active }.forEach { gateway.closeTab(it.id) }
        showSnack("已关闭其他标签")
    }

    fun closeAll() {
        closeJobs.values.forEach { it.cancel() }
        closeJobs.clear()
        closingIds.value = emptySet()
        gateway.closeAllTabs()
        showSnack("已关闭全部标签")
    }

    /* ---------------- 页内查找 ---------------- */

    fun openFindBar() {
        findVisible.value = true
    }

    fun closeFindBar() {
        findVisible.value = false
        lastFindQuery = ""
        gateway.clearFindMatches()
    }

    fun onFindQueryChanged(q: String) {
        lastFindQuery = q
        findDebounceJob?.cancel()
        if (q.isBlank()) {
            gateway.clearFindMatches()
            return
        }
        findDebounceJob = scope?.launch {
            delay(250)
            gateway.findInPage(q, forward = true)
        }
    }

    fun findNext() {
        val q = _ui.value.findState?.query ?: lastFindQuery
        if (q.isNotBlank()) gateway.findInPage(q, forward = true)
    }

    fun findPrev() {
        val q = _ui.value.findState?.query ?: lastFindQuery
        if (q.isNotBlank()) gateway.findInPage(q, forward = false)
    }

    /* ---------------- 浮层与菜单 ---------------- */

    fun openSheet(s: ChromeSheet) {
        if (s == ChromeSheet.SCRIPTS && scripts == null) return
        sheet.value = s
    }

    fun closeSheet() {
        sheet.value = null
    }

    fun toggleDesktopMode() {
        val next = !_ui.value.desktopMode
        gateway.setDesktopMode(next)
        showSnack(if (next) "已切换桌面版网站" else "已切换回移动版")
    }

    fun openExternal() {
        currentUrl()?.let { gateway.openInExternalBrowser(it) }
    }

    fun onMenuAction(action: ChromeMenuAction) {
        when (action) {
            ChromeMenuAction.NewTab -> newTab()
            ChromeMenuAction.ShowTabs -> openSheet(ChromeSheet.TABS)
            ChromeMenuAction.CloseOthers -> closeOthers()
            ChromeMenuAction.CloseAll -> closeAll()
            ChromeMenuAction.FindInPage -> openFindBar()
            is ChromeMenuAction.SetDesktopMode -> gateway.setDesktopMode(action.enabled)
            ChromeMenuAction.CopyLink -> Unit // 由 ChromeBar 用剪贴板处理
            ChromeMenuAction.Downloads -> openSheet(ChromeSheet.DOWNLOADS)
            ChromeMenuAction.Userscripts -> openSheet(ChromeSheet.SCRIPTS)
            ChromeMenuAction.OpenExternal -> openExternal()
        }
    }

    /* ---------------- 系统返回键 ---------------- */

    /** 返回 true 表示 Chrome 已消费（编辑态 → 查找 → 浮层，依次让位）。 */
    fun handleBack(): Boolean {
        if (editing.value != null) {
            exitAddressEdit(); return true
        }
        if (findVisible.value) {
            closeFindBar(); return true
        }
        if (sheet.value != null) {
            closeSheet(); return true
        }
        return false
    }

    /* ---------------- 轻提示 ---------------- */

    fun showSnack(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        _snacks.tryEmit(ChromeSnackBar(message, actionLabel, onAction))
    }
}
