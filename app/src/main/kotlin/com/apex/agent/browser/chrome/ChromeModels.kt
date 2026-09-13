package com.apex.agent.browser.chrome

import kotlinx.coroutines.flow.StateFlow

/*
 * ChromeModels —— 浏览器 Chrome 层的全部不可变 UI 模型。
 *
 * 设计原则：
 * 1. 模型只描述「渲染需要什么」，不携带任何引擎/WebView 引用，保证 UI 可离线预览与单测。
 * 2. 命名与结构均为本项目自研，与任何第三方实现无关联。
 */

/** 单个标签页的渲染快照。 */
data class TabCard(
    val id: String,
    val title: String,
    val url: String,
    /** 展示用主机名（已去 scheme / www.）。 */
    val host: String,
    val isSecure: Boolean,
    val isLoading: Boolean,
    /** 0f..1f，仅 isLoading 时有意义。 */
    val progress: Float,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/** 页内查找状态，由引擎回填。 */
data class FindState(
    val query: String,
    val activeMatch: Int,
    val totalMatches: Int,
)

/** 引擎对外发布的整体快照（标签列表 + 活动标签 + 查找态 + 桌面模式）。 */
data class EngineSnapshot(
    val tabs: List<TabCard> = emptyList(),
    val activeTabId: String? = null,
    val findState: FindState? = null,
    val desktopMode: Boolean = false,
)

/** 地址栏拆分展示：host + pathQuery。 */
data class DisplayUrl(
    val host: String,
    val pathQuery: String? = null,
)

/** 地址栏两种形态。 */
sealed interface AddressBarUi {
    data class Collapsed(
        val host: String,
        val pathQuery: String?,
        val isSecure: Boolean,
        /** null 表示空闲不渲染进度。 */
        val progress: Float?,
    ) : AddressBarUi

    data class Editing(
        val text: String,
        val suggestions: List<Suggestion>,
    ) : AddressBarUi
}

enum class SuggestionKind { URL, SEARCH, OPEN_TAB, HISTORY, CLIPBOARD }

/** 地址栏联想项。 */
data class Suggestion(
    val kind: SuggestionKind,
    val primary: String,
    val secondary: String? = null,
    /** URL / HISTORY / CLIPBOARD 类型的跳转目标。 */
    val actionUrl: String? = null,
    /** OPEN_TAB 类型切换目标。 */
    val tabId: String? = null,
)

/** Chrome 层的三种浮层。 */
enum class ChromeSheet { TABS, DOWNLOADS, SCRIPTS }

/** 轻提示。 */
data class ChromeSnackBar(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** Chrome 层聚合后的完整 UI 状态。 */
data class ChromeUiState(
    val address: AddressBarUi = AddressBarUi.Collapsed("", null, false, null),
    val tabs: List<TabCard> = emptyList(),
    val activeTabId: String? = null,
    val activeTab: TabCard? = null,
    val closingTabIds: Set<String> = emptySet(),
    val findBarVisible: Boolean = false,
    val findState: FindState? = null,
    val sheet: ChromeSheet? = null,
    val desktopMode: Boolean = false,
    val tabStripVisible: Boolean = false,
)

/** Chrome 层行为开关（全部有默认值，可零配置接入）。 */
data class ChromeConfig(
    /** 地址栏非 URL 输入的搜索模板，需含 %s。 */
    val searchTemplate: String = "https://www.bing.com/search?q=%s",
    /** 新标签默认页；null 交给引擎决定（如 about:blank）。 */
    val homeUrl: String? = null,
    /** 关闭标签的撤销宽限期；0 = 立即关闭不可撤销。 */
    val undoGraceMillis: Long = 4_000L,
    /** 只剩一个标签时自动隐藏标签条，最大化内容区。 */
    val hideTabStripWhenSingle: Boolean = true,
    val maxSuggestions: Int = 8,
    /** JS 弹窗是否显示「交给 Agent 决定」入口。 */
    val agentRoutingEnabled: Boolean = true,
)

/* ---------------- JS 弹窗（人机双通道） ---------------- */

enum class JsDialogKind { ALERT, CONFIRM, PROMPT }

enum class JsDialogChoice { POSITIVE, NEGATIVE, DISMISS }

/** 弹窗由谁裁决。 */
enum class JsDialogRouting { HUMAN, AGENT }

data class JsDialogRequest(
    val id: String,
    val kind: JsDialogKind,
    val originHost: String,
    val message: String,
    val promptDefault: String? = null,
    val routing: JsDialogRouting = JsDialogRouting.HUMAN,
)

/* ---------------- 网页权限请求 ---------------- */

enum class WebPermissionResource { CAMERA, MICROPHONE, LOCATION, NOTIFICATIONS, PROTECTED_MEDIA_ID, MIDI }

enum class PermissionDecision { GRANT_ONCE, GRANT_ALWAYS, DENY }

data class PermissionRequest(
    val id: String,
    val resources: Set<WebPermissionResource>,
    val originHost: String,
)

/* ---------------- 下载 ---------------- */

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class DownloadItem(
    val id: String,
    val fileName: String,
    val url: String,
    val mimeType: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val speedBps: Long? = null,
)

/* ---------------- 历史联想 ---------------- */

data class HistoryEntry(
    val url: String,
    val title: String,
    val lastVisitedAt: Long = 0L,
)

/* ---------------- 用户脚本（引擎侧实现注册表，Chrome 只做 UI） ---------------- */

data class Userscript(
    val id: String,
    val name: String,
    val matches: String,
    val enabled: Boolean,
)

interface UserscriptRegistry {
    val scripts: StateFlow<List<Userscript>>
    fun setEnabled(id: String, enabled: Boolean)
}

/** 未接入脚本能力时的空实现：菜单项自动隐藏。 */
object UnavailableUserscriptRegistry : UserscriptRegistry {
    private val empty: StateFlow<List<Userscript>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    override val scripts: StateFlow<List<Userscript>> = empty
    override fun setEnabled(id: String, enabled: Boolean) = Unit
}

/** 溢出菜单动作。 */
sealed interface ChromeMenuAction {
    object NewTab : ChromeMenuAction
    object ShowTabs : ChromeMenuAction
    object CloseOthers : ChromeMenuAction
    object CloseAll : ChromeMenuAction
    object FindInPage : ChromeMenuAction
    data class SetDesktopMode(val enabled: Boolean) : ChromeMenuAction
    object CopyLink : ChromeMenuAction
    object Downloads : ChromeMenuAction
    object Userscripts : ChromeMenuAction
    object OpenExternal : ChromeMenuAction
}
