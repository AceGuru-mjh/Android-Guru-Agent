package com.apex.agent.browser.chrome.bridge

import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/*
 * ChainingClients —— 链式客户端：不「替换」引擎已挂的 client，而是「包一层」。
 *
 * 动机：你们的 BrowserEngine 是自动化引擎，WebViewClient/WebChromeClient 上
 * 很可能挂着握手状态机、注入、自动 confirm 等既有逻辑。直接替换会静默丢掉
 * 所有未被覆写回调的路由——这是集成期最隐蔽的破坏面。
 *
 * 规则：
 * - 纯转发：本类未消费的全部回调逐个转发给 delegate（引擎原 client），引擎零感知；
 * - 采集：进度/标题/URL/加载完成 → PageStateSink（驱动快照自动合成，见 NeonChromeWiring）；
 * - 接管（不转发）：JS 弹窗 / 网页资源权限 / 地理定位权限 → DialogRequestRouter，
 *   由 Chrome 层呈现给人或 Agent 裁决——旧的「自动 confirm + lastDialog 回灌」从此退役。
 *
 * 注：极冷门的废弃回调（onJsTimeout/onExceededDatabaseQuota 等）未列入转发；
 * 若引擎依赖它们，可继承本类覆写后手动 super 或直接补转发。
 */

/** 页面状态采集口（实现方见 NeonChromeWiring；所有回调都在主线程）。 */
interface PageStateSink {
    fun onPageStarted(view: WebView, url: String?)
    fun onPageFinished(view: WebView, url: String?)
    /** SPA 路由变化（pushState/hash）也会走到这里，比 onPageStarted 更可靠。 */
    fun onUrlChanged(view: WebView, url: String?)
    fun onProgress(view: WebView, newProgress: Int)
    fun onTitle(view: WebView, title: String?)
    fun onFindResult(view: WebView, activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean)
}

/* ================= 1. WebViewClient 链 ================= */

open class ChainWebViewClient(
    private val sink: PageStateSink,
    private val delegate: WebViewClient? = null,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        delegate?.shouldOverrideUrlLoading(view, request) ?: false

    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        delegate?.shouldOverrideUrlLoading(view, url) ?: false

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        delegate?.onPageStarted(view, url, favicon)
        sink.onPageStarted(view, url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        delegate?.onPageFinished(view, url)
        sink.onPageFinished(view, url)
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        delegate?.onPageCommitVisible(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        delegate?.doUpdateVisitedHistory(view, url, isReload)
        sink.onUrlChanged(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        delegate?.onReceivedError(view, request, error)
    }

    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
    @Deprecated("Deprecated in Java")
    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        delegate?.onReceivedError(view, errorCode, description, failingUrl)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        delegate?.onReceivedHttpError(view, request, errorResponse)
    }

    /** SSL 错误绝不代引擎决策：有 delegate 交给引擎，没有则维持 WebView 缺省（取消）。 */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
        delegate?.onReceivedSslError(view, handler, error)
            ?: super.onReceivedSslError(view, handler, error)
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean =
        delegate?.onRenderProcessGone(view, detail) ?: super.onRenderProcessGone(view, detail)

    override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
        delegate?.onScaleChanged(view, oldScale, newScale)
    }

    override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) {
        delegate?.onReceivedLoginRequest(view, realm, account, args)
    }

    override fun onFormResubmission(view: WebView, dontResend: Message?, resend: Message?) {
        delegate?.onFormResubmission(view, dontResend, resend)
    }

    override fun onLoadResource(view: WebView, url: String?) {
        delegate?.onLoadResource(view, url)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        delegate?.shouldInterceptRequest(view, request)

    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        errorType: Int,
        callback: android.webkit.SafeBrowsingResponse,
    ) {
        delegate?.onSafeBrowsingHit(view, request, errorType, callback)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: android.webkit.HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        delegate?.onReceivedHttpAuthRequest(view, handler, host, realm)
    }
}

/* ================= 2. WebChromeClient 链 ================= */

open class ChainWebChromeClient(
    private val router: DialogRequestRouter,
    private val sink: PageStateSink,
    private val delegate: WebChromeClient? = null,
) : WebChromeClient() {

    /* ---- 接管：JS 弹窗（旧自动 confirm 逻辑退役） ---- */

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean =
        router.onJsAlert(url, message, result)

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean =
        router.onJsConfirm(url, message, result)

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean = router.onJsPrompt(url, message, defaultValue, result)

    /* ---- 接管：权限（资源 / 地理定位） ---- */

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.let { router.onWebPermissionRequest(it) } ?: delegate?.onPermissionRequest(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        if (request != null) router.onWebPermissionCanceled(request)
        delegate?.onPermissionRequestCanceled(request)
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        if (callback != null) router.onGeolocationPermissionsShowPrompt(origin, callback)
        else delegate?.onGeolocationPermissionsShowPrompt(origin, callback)
    }

    override fun onGeolocationPermissionsHidePrompt() {
        router.onGeolocationPermissionsHidden()
        delegate?.onGeolocationPermissionsHidePrompt()
    }

    /* ---- 采集 + 转发 ---- */

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        delegate?.onProgressChanged(view, newProgress)
        if (view != null) sink.onProgress(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        delegate?.onReceivedTitle(view, title)
        if (view != null) sink.onTitle(view, title)
    }

    /* ---- 纯转发 ---- */

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        delegate?.onReceivedIcon(view, icon)
    }

    // 注：onReceivedTouchIconUrl 等极冷门回调未覆写（不同 API 版本签名有差异），
    // 引擎若依赖可在子类中自行覆写补转发，链路不受影响。

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        delegate?.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        delegate?.onHideCustomView()
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean = delegate?.onCreateWindow(view, isDialog, isUserGesture, resultMsg) ?: false

    override fun onCloseWindow(window: WebView?) {
        delegate?.onCloseWindow(window)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean =
        delegate?.onConsoleMessage(consoleMessage) ?: false

    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
    @Deprecated("Deprecated in Java")
    override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) {
        delegate?.onConsoleMessage(message, lineNumber, sourceID)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<android.net.Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean = delegate?.onShowFileChooser(webView, filePathCallback, fileChooserParams) ?: false

    override fun getVideoLoadingProgressView(): View? = delegate?.videoLoadingProgressView

    override fun getVisitedHistory(callback: ValueCallback<Array<String>>?) {
        delegate?.getVisitedHistory(callback)
    }
}

/* ================= 3. FindListener 链 ================= */

/** 页内查找结果回填（WebView 的 FindListener 不带回 view，这里捕获之用于门控）。 */
class ChainFindListener(
    private val view: WebView,
    private val sink: PageStateSink,
) : WebView.FindListener {

    override fun onFindResultReceived(activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
        sink.onFindResult(view, activeMatchOrdinal, numberOfMatches, isDoneCounting)
    }
}
