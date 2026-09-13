package com.apex.agent.browser.chrome.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.apex.agent.browser.chrome.DownloadItem
import com.apex.agent.browser.chrome.DownloadState
import com.apex.agent.browser.chrome.JsDialogBookkeeping
import com.apex.agent.browser.chrome.JsDialogChoice
import com.apex.agent.browser.chrome.JsDialogKind
import com.apex.agent.browser.chrome.JsDialogRequest
import com.apex.agent.browser.chrome.PermissionDecision
import com.apex.agent.browser.chrome.PermissionRequest as ChromePermissionRequest
import com.apex.agent.browser.chrome.WebPermissionResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.app.DownloadManager

/*
 * EngineSideHooks —— 引擎侧桥接件（P1 后进一步减负）。
 *
 * 三个组件都是「拿来即用」的具体实现：
 * 1. DialogRequestRouter     —— JS 弹窗 + 权限请求的路由与回放（薄壳，逻辑在
 *                              chrome 层 JsDialogBookkeeping，可 JVM 单测）；
 *                              构造时传 PermissionGrantStore 即获得「总是允许」
 *                              跨会话持久化（P1）；
 * 2. ChromeWebChromeClient   —— 挂到 WebView 上的 WebChromeClient；
 * 3. ChromeDownloadManager   —— 基于 system DownloadManager 的下载桥。
 *
 * 在你的 BrowserEngine 初始化处（示意）：
 *   val router = DialogRequestRouter(SharedPreferencesGrantStore(appContext))
 *   val downloads = ChromeDownloadManager(appContext, engineScope)
 *   webView.webChromeClient = ChromeWebChromeClient(router)
 *   webView.setDownloadListener(downloads)
 *   // 快照发布由 NeonChromeWiring 自动完成，见适配器文件。
 */

/* ================= 1. 弹窗与权限路由 ================= */

/*
 * DialogRequestRouter —— Android 侧薄壳（P1 重构）。
 *
 * 队列/裁决/Agent 路由/幂等回放的逻辑已提取到 chrome 层的 JsDialogBookkeeping
 * （纯 JVM，可直接单测）；本类只做三件事：
 * 1. 把 android.webkit.JsResult 适配成回放 lambda；
 * 2. 把网页资源/地理定位权限适配成 Chrome 层的 PermissionRequest；
 * 3. 接入 PermissionGrantStore：GRANT_ALWAYS 落库、已记忆的 origin 静默放行。
 *
 * 公开 API 与上一版完全兼容（新增构造参数有默认值），ChainingClients /
 * NeonChromeWiring / BrowserEngineGatewayAdapter 均无需改动。
 */
class DialogRequestRouter(
    /** 按 origin 记忆的「总是允许」授权；传 SharedPreferencesGrantStore 即完成 P1 持久化。 */
    private val grants: PermissionGrantStore? = null,
) {

    /** 引擎侧 Agent 决策钩子：返回 true 表示由 Agent 异步裁决（稍后调用 finish）。 */
    var agentDecider: ((JsDialogRequest, suspend (JsDialogChoice, String?) -> Unit) -> Boolean)?
        get() = bookkeeping.agentDecider
        set(value) { bookkeeping.agentDecider = value }

    private val bookkeeping = JsDialogBookkeeping()

    val dialogRequests: StateFlow<List<JsDialogRequest>> = bookkeeping.requests

    private val _permissionRequests = MutableStateFlow<List<ChromePermissionRequest>>(emptyList())
    val permissionRequests: StateFlow<List<ChromePermissionRequest>> = _permissionRequests.asStateFlow()

    private val pendingWebPermission = ConcurrentHashMap<String, PermissionRequest>()
    private val pendingGeolocation = ConcurrentHashMap<String, Pair<String, GeolocationPermissions.Callback>>()

    private fun hostOf(url: String?): String =
        url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "unknown"

    /** android.webkit.PermissionRequest.resources → Chrome 层资源枚举。 */
    private fun mapResources(request: PermissionRequest): Set<WebPermissionResource> =
        request.resources.mapNotNull { res ->
            when (res) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> WebPermissionResource.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> WebPermissionResource.MICROPHONE
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> WebPermissionResource.PROTECTED_MEDIA_ID
                PermissionRequest.RESOURCE_MIDI_SYSEX -> WebPermissionResource.MIDI
                else -> null
            }
        }.toSet()

    /* ---- WebChromeClient 回调入口 ---- */

    fun onJsAlert(url: String?, message: String?, result: JsResult): Boolean {
        pushDialog(JsDialogKind.ALERT, hostOf(url), message.orEmpty(), null, result)
        return true
    }

    fun onJsConfirm(url: String?, message: String?, result: JsResult): Boolean {
        pushDialog(JsDialogKind.CONFIRM, hostOf(url), message.orEmpty(), null, result)
        return true
    }

    fun onJsPrompt(url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
        pushDialog(JsDialogKind.PROMPT, hostOf(url), message.orEmpty(), defaultValue, result)
        return true
    }

    private fun pushDialog(
        kind: JsDialogKind,
        host: String,
        message: String,
        defaultValue: String?,
        result: JsResult,
    ) {
        bookkeeping.submit(kind, host, message, defaultValue) { choice, promptValue ->
            replayJsResult(kind, result, choice, promptValue)
        }
    }

    /** 回放：把裁决写回 JsResult（恰好一次，由 bookkeeping 保证）。 */
    private fun replayJsResult(kind: JsDialogKind, result: JsResult, choice: JsDialogChoice, promptValue: String?) {
        when (choice) {
            JsDialogChoice.POSITIVE -> {
                if (kind == JsDialogKind.PROMPT && result is JsPromptResult) {
                    result.confirm(promptValue.orEmpty())
                } else {
                    result.confirm()
                }
            }
            JsDialogChoice.NEGATIVE, JsDialogChoice.DISMISS -> result.cancel()
        }
    }

    /* ---- Chrome 层裁决入口 ---- */

    fun resolveDialog(requestId: String, choice: JsDialogChoice, promptValue: String?, routeToAgent: Boolean) {
        bookkeeping.resolve(requestId, choice, promptValue, routeToAgent)
    }

    fun onWebPermissionRequest(request: PermissionRequest) {
        val resources = mapResources(request)
        val host = hostOf(request.origin.toString())
        // P1：已「总是允许」的 origin 静默放行，不再弹窗打扰。
        if (PermissionGate.autoGrantable(grants, host, resources)) {
            request.grant(request.resources)
            return
        }
        val id = UUID.randomUUID().toString()
        pendingWebPermission[id] = request
        _permissionRequests.value = _permissionRequests.value + ChromePermissionRequest(
            id = id, resources = resources, originHost = host,
        )
    }

    fun resolvePermission(requestId: String, decision: PermissionDecision) {
        // 网页资源权限（相机/麦克风等）
        pendingWebPermission.remove(requestId)?.let { req ->
            val host = hostOf(req.origin.toString())
            val resources = mapResources(req)
            PermissionGate.applyDecision(grants, host, resources, decision)
            when (decision) {
                PermissionDecision.GRANT_ONCE, PermissionDecision.GRANT_ALWAYS -> req.grant(req.resources)
                PermissionDecision.DENY -> req.deny()
            }
            return
        }
        // 地理定位：GRANT_ALWAYS 时 retain=true（WebView 自行按 origin 记住）+ 落库
        pendingGeolocation.remove(requestId)?.let { (origin, callback) ->
            PermissionGate.applyDecision(grants, origin, setOf(WebPermissionResource.LOCATION), decision)
            callback.invoke(origin, decision != PermissionDecision.DENY, decision == PermissionDecision.GRANT_ALWAYS)
        }
    }

    /** 网页侧撤回了权限请求（页面关闭等）：出队，不给用户留死按钮。 */
    fun onWebPermissionCanceled(request: PermissionRequest) {
        val id = pendingWebPermission.entries.firstOrNull { it.value === request }?.key ?: return
        pendingWebPermission.remove(id)
        _permissionRequests.value = _permissionRequests.value.filterNot { it.id == id }
    }

    /** 地理定位权限请求（WebChromeClient.onGeolocationPermissionsShowPrompt）。 */
    fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
        val host = origin ?: "unknown"
        // P1：已记忆「总是允许」的 origin 直接放行（不弹窗、不 retain 重复写）。
        if (grants != null && grants.isGranted(host, WebPermissionResource.LOCATION)) {
            callback.invoke(host, true, false)
            return
        }
        val id = UUID.randomUUID().toString()
        pendingGeolocation[id] = host to callback
        _permissionRequests.value = _permissionRequests.value + ChromePermissionRequest(
            id = id, resources = setOf(WebPermissionResource.LOCATION), originHost = host,
        )
    }

    /** 网页侧不再需要地理定位提示：清空待决的定位请求。 */
    fun onGeolocationPermissionsHidden() {
        if (pendingGeolocation.isEmpty()) return
        val ids = pendingGeolocation.keys.toSet()
        pendingGeolocation.clear()
        _permissionRequests.value = _permissionRequests.value.filterNot { it.id in ids }
    }

    /**
     * P1：撤销某 origin 的全部记忆授权（引擎「站点权限管理」界面用）。
     * 撤销定位授权时建议同时调 GeolocationPermissions.getInstance().clear(host, null)
     * 清掉 WebView 自身的 retain，两处记忆保持一致。
     */
    fun revokeRememberedGrants(originHost: String) {
        grants?.revoke(originHost, null)
    }
}

/* ================= 2. WebChromeClient ================= */

class ChromeWebChromeClient(private val router: DialogRequestRouter) : WebChromeClient() {

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

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.let { router.onWebPermissionRequest(it) }
    }
}

/* ================= 3. 下载桥（system DownloadManager） ================= */

class ChromeDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
) : android.webkit.DownloadListener {

    data class Rec(val dmId: Long, val url: String, val mimeType: String?)

    /**
     * 下载入队回调（ApexChromeWiring 用它回写 BrowserEngine.lastDownload，
     * 保持 Agent 侧 browser_download_list 工具语义不变）。
     */
    var onEnqueued: ((fileName: String, url: String, dmId: Long) -> Unit)? = null

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val records = ConcurrentHashMap<String, Rec>() // Chrome DownloadItem.id -> dmId

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private var pollJob: Job? = null

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        if (url == null || dm == null) return
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimetype)
            .setTitle(fileName)
            .setDescription(runCatching { Uri.parse(url).host }.getOrNull() ?: url)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 与 BrowserEngine.setDownloadListener 保持一致：公共 Downloads 目录
            //（用户在系统「下载」App 里也能看到，与 Agent 自动下载同一下载池）
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val dmId = runCatching { dm.enqueue(request) }.getOrNull() ?: run {
            push(DownloadItem(id = newId(), fileName = fileName, url = url, mimeType = mimetype, state = DownloadState.FAILED))
            return
        }
        onEnqueued?.invoke(fileName, url, dmId)
        val id = "dl-$dmId"
        records[id] = Rec(dmId, url, mimetype)
        push(
            DownloadItem(
                id = id, fileName = fileName, url = url, mimeType = mimetype,
                state = DownloadState.RUNNING, bytesDownloaded = 0,
                totalBytes = if (contentLength > 0) contentLength else null,
            ),
        )
        ensurePolling()
    }

    private fun newId() = "dl-${UUID.randomUUID()}"

    private fun push(item: DownloadItem) {
        _downloads.value = listOf(item) + _downloads.value.filterNot { it.id == item.id }
    }

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        val cur = _downloads.value.firstOrNull { it.id == id } ?: return
        push(transform(cur))
    }

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val active = _downloads.value.filter {
                    it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
                }
                if (active.isEmpty()) {
                    delay(1500)
                    continue
                }
                active.forEach { refresh(it) }
                delay(500)
            }
        }
    }

    private fun refresh(item: DownloadItem) {
        val rec = records[item.id] ?: return
        val dmRef = dm ?: return
        val query = DownloadManager.Query().setFilterById(rec.dmId)
        runCatching {
            dmRef.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val state = when (status) {
                        DownloadManager.STATUS_RUNNING -> DownloadState.RUNNING
                        DownloadManager.STATUS_PAUSED -> DownloadState.PAUSED
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadState.COMPLETED
                        DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                        DownloadManager.STATUS_PENDING -> DownloadState.QUEUED
                        else -> DownloadState.RUNNING
                    }
                    update(item.id) {
                        it.copy(
                            state = state, bytesDownloaded = done,
                            totalBytes = if (total > 0) total else it.totalBytes,
                            speedBps = if (state == DownloadState.RUNNING && done > it.bytesDownloaded) {
                                ((done - it.bytesDownloaded) * 2) // 500ms 轮询 ×2 折算 B/s
                            } else {
                                it.speedBps
                            },
                        )
                    }
                }
            }
        }
    }

    /* ---- Chrome 层命令 ---- */

    fun cancel(id: String) {
        val rec = records[id] ?: return
        dm?.remove(rec.dmId)
        update(id) { it.copy(state = DownloadState.CANCELLED) }
    }

    fun retry(id: String) {
        val rec = records.remove(id) ?: return
        _downloads.value = _downloads.value.filterNot { it.id == id }
        onDownloadStart(rec.url, null, null, rec.mimeType, -1)
    }

    fun open(id: String) {
        if (_downloads.value.none { it.id == id }) return
        // 稳妥策略：跳系统下载 UI。如需直接打开文件，请为 external/files/Downloads 配 FileProvider。
        runCatching {
            context.startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
