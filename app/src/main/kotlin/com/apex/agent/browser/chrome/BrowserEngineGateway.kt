package com.apex.agent.browser.chrome

import kotlinx.coroutines.flow.StateFlow

/*
 * BrowserEngineGateway —— Chrome 层与自动化引擎之间的唯一边界。
 *
 * 设计动机：
 * - Chrome UI 不 import 任何 WebView/引擎类型，因此可以：
 *   a) 用 FakeEngineGateway 做 @Preview 与 UI 调试；
 *   b) 用 JVM 单测覆盖「UI 状态机」而不需要 Android 设备；
 *   c) 引擎未来重构 / 更换内核时，Chrome 层零改动。
 * - 你们的引擎已具备 newTab / switchTab / goBack / reload 等能力，
 *   只需一个薄适配器（见 bridge/BrowserEngineGatewayAdapter.kt）即可满足本接口。
 */
interface BrowserEngineGateway {

    /* ---------------- 状态源 ---------------- */

    /** 标签与导航状态的唯一事实源。 */
    val state: StateFlow<EngineSnapshot>

    /** 待处理的 JS 弹窗（首个元素即当前展示的弹窗）。 */
    val dialogRequests: StateFlow<List<JsDialogRequest>>

    /** 待处理的网页权限请求。 */
    val permissionRequests: StateFlow<List<PermissionRequest>>

    /** 下载列表（新在前）。 */
    val downloads: StateFlow<List<DownloadItem>>

    /* ---------------- 导航 ---------------- */

    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()

    /** 直接加载 URL（应已归一化，见 UrlUtils.resolveInput）。 */
    fun loadUrl(url: String)

    /** 以搜索引擎处理非 URL 输入。 */
    fun search(query: String)

    /* ---------------- 标签 ---------------- */

    /** 新建标签，返回标签 id；url 传 null 表示打开默认页。 */
    fun newTab(url: String?): String

    fun switchTab(tabId: String)

    /** 立即关闭（Chrome 层的撤销宽限已在控制器内消化，引擎无需感知）。 */
    fun closeTab(tabId: String)

    fun closeAllTabs()

    /* ---------------- 页内查找 ---------------- */

    fun findInPage(query: String, forward: Boolean)

    fun clearFindMatches()

    /* ---------------- 站点行为 ---------------- */

    fun setDesktopMode(enabled: Boolean)

    fun openInExternalBrowser(url: String)

    /* ---------------- 输入与对话 ---------------- */

    /** 剪贴板中可「粘贴即走」的 URL；无则 null。 */
    fun clipboardUrlOrNull(): String? = null

    /**
     * 用户对 JS 弹窗的裁决。
     * @param routeToAgent 用户选择让 Agent 代替裁决（引擎侧异步给出最终答复）。
     */
    fun resolveDialog(
        requestId: String,
        choice: JsDialogChoice,
        promptValue: String? = null,
        routeToAgent: Boolean = false,
    )

    fun resolvePermission(requestId: String, decision: PermissionDecision)

    /**
     * 撤销某 origin 的全部「总是允许」记忆授权（站点权限管理界面用）。
     * 未接持久化时为空操作。
     */
    fun revokeRememberedGrants(originHost: String) {}

    /* ---------------- 下载 ---------------- */

    fun cancelDownload(id: String) {}
    fun retryDownload(id: String) {}
    fun openDownload(id: String) {}

    /* ---------------- 历史（可选，供地址栏联想） ---------------- */

    suspend fun queryHistory(query: String, limit: Int): List<HistoryEntry> = emptyList()
}
