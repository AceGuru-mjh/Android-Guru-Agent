package com.apex.agent.browser.chrome

/*
 * SnapshotComposer —— 快照合成器（纯函数，JVM 可单测）。
 *
 * 职责：把两个频率完全不同的输入源合成一份 EngineSnapshot：
 * - TabSet   ：引擎低频发布（标签增删/切换时才变）；
 * - LivePageState：链式客户端从 WebView 回调自动采集的高频细节
 *               （进度/标题/URL/加载中/前进后退），只属于当前活动页。
 *
 * 合并规则：
 * 1. 引擎清单为空 → 退化为「单标签模式」，用实时状态合成一张卡；
 * 2. 活动标签以实时状态优先（title/url 实时覆盖引擎可能过期的值）；
 * 3. 非活动标签只用引擎清单值，绝不掺入实时状态；
 * 4. activeTabId 缺省时回落到清单首个标签（UI 永远有一个可渲染的活动页）。
 */

/** 当前活动页的实时细节（由 WebView 回调驱动，见 bridge/ChainingClients.kt）。 */
data class LivePageState(
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean = false,
    /** 0f..1f。 */
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/** 引擎对自家标签的最小描述——引擎只需要说出它知道的。 */
data class TabIdentity(
    val id: String,
    val title: String? = null,
    val url: String? = null,
)

/** 引擎低频发布的标签结构。 */
data class TabSet(
    val tabs: List<TabIdentity> = emptyList(),
    val activeTabId: String? = null,
)

object SnapshotComposer {

    /** 引擎未发布任何标签时的单标签伪 id。 */
    const val SOLO_TAB_ID = "solo"

    fun compose(
        tabSet: TabSet,
        live: LivePageState,
        findState: FindState? = null,
        desktopMode: Boolean = false,
    ): EngineSnapshot {
        if (tabSet.tabs.isEmpty()) {
            val card = cardOf(SOLO_TAB_ID, TabIdentity(SOLO_TAB_ID, live.title, live.url), live, isActive = true)
            return EngineSnapshot(tabs = listOf(card), activeTabId = SOLO_TAB_ID, findState = findState, desktopMode = desktopMode)
        }
        val activeId = tabSet.activeTabId?.takeIf { id -> tabSet.tabs.any { it.id == id } }
            ?: tabSet.tabs.first().id
        val cards = tabSet.tabs.map { cardOf(it.id, it, live, isActive = it.id == activeId) }
        return EngineSnapshot(tabs = cards, activeTabId = activeId, findState = findState, desktopMode = desktopMode)
    }

    private fun cardOf(id: String, identity: TabIdentity, live: LivePageState, isActive: Boolean): TabCard {
        val url = (if (isActive) live.url ?: identity.url else identity.url).orEmpty()
        val title = (if (isActive) live.title ?: identity.title else identity.title) ?: url
        return TabCard(
            id = id,
            title = title,
            url = url,
            host = UrlUtils.hostOf(url),
            isSecure = url.startsWith("https://") || url.startsWith("about:"),
            isLoading = if (isActive) live.isLoading else false,
            progress = if (isActive) live.progress else 0f,
            canGoBack = if (isActive) live.canGoBack else false,
            canGoForward = if (isActive) live.canGoForward else false,
        )
    }
}
