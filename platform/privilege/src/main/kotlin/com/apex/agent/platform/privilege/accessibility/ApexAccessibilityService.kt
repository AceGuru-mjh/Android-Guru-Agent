package com.apex.agent.platform.privilege.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

/**
 * 核心无障碍服务
 *
 * Agent的"眼睛"和"手"：
 * - 眼睛：读取UI树、感知界面变化
 * - 手：点击、滑动、输入文本、执行全局操作
 *
 * 同时充当"不死心跳"：
 * - 由system_server管理，不受后台限制
 * - 主进程被杀时可重启
 */
class ApexAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ApexAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** #236 心跳门控用：keepAlive=false 的 JSON 快速判别（与 BootReceiver 同款）。 */
        private val KEEP_ALIVE_OFF_REGEX = Regex("\"keepAlive\"\\s*:\\s*false")

        // #210：服务连接/断开的进程内广播。DefaultPrivilegeManager 借此把
        // _accessibilityAvailable StateFlow 与真实生命周期同步 —— 此前状态流
        // 构造后恒为初始值，用户开启无障碍后 Agent 仍走 input 命令回退。
        // COW 列表：onServiceConnected 主线程遍历 vs 任意线程注册/反注册。
        private val lifecycleListeners =
            java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

        /**
         * 注册无障碍可用性监听（sticky：注册即用当前真实状态回调一次，
         * 消除「服务先连、管理器后建」的时序窗口）。
         *
         * @param listener 参数 connected=true 服务已连接，false 服务已断开
         */
        fun addLifecycleListener(listener: (Boolean) -> Unit) {
            lifecycleListeners.add(listener)
            listener(instance != null)
        }

        fun removeLifecycleListener(listener: (Boolean) -> Unit) {
            lifecycleListeners.remove(listener)
        }

        private fun notifyAvailabilityChanged(connected: Boolean) {
            lifecycleListeners.forEach { listener ->
                // #260：监听器异常不得中断扇出（其余监听器照常收通知），但必须留痕
                try { listener(connected) } catch (e: Exception) {
                    android.util.Log.w(
                        "ApexA11yService",
                        "lifecycle listener failed (connected=$connected): ${e.message}"
                    )
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // v1.4.4 UX 审查：改为 COW 列表 —— 主线程 onAccessibilityEvent 迭代 vs
    // EnvironmentStateUpdater 在 Dispatchers.Default 协程里 add/remove，
    // 普通 ArrayList 极端时序可抛 ConcurrentModificationException 崩掉无障碍服务。
    private val eventListeners = java.util.concurrent.CopyOnWriteArrayList<(AccessibilityEvent) -> Unit>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // #210：先落 instance 再广播，监听方回调内读到的状态与广播语义一致。
        notifyAvailabilityChanged(true)

        // 心跳：每30秒检查主进程。
        // P2 fix（生命周期竞态）：旧实现在主线程做 binder IPC（runningAppProcesses），
        // 且无 CoroutineExceptionHandler —— system_server 重启/binder 缓冲满时抛出
        // DeadObjectException/RuntimeException 会逃出 launch 导致无障碍进程崩溃。
        // 现移到 Default 调度器 + 单次失败不终止心跳循环 + 异常统一记录。
        scope.launch(Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("ApexA11yService", "heartbeat iteration failed", e)
        }) {
            while (isActive) {
                delay(30_000)
                runCatching { checkMainProcessAlive() }
                    .onFailure { android.util.Log.w("ApexA11yService", "checkMainProcessAlive failed: ${it.message}") }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        eventListeners.forEach { listener ->
            // #260：单监听器异常不阻断事件扇出（其余监听器照常消费），但必须留痕
            try { listener(event) } catch (e: Exception) {
                android.util.Log.w(
                    "ApexA11yService",
                    "event listener failed (${event.eventType}): ${e.message}"
                )
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent): Boolean {
        // #210：用户在系统设置关闭无障碍：系统先 onUnbind 再 onDestroy。
        // 两处都广播（消费方按 sticky 语义读 instance 真值，幂等）。
        notifyAvailabilityChanged(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        // #210：断开事件驱动 StateFlow 回落 false，下游选路立刻感知。
        notifyAvailabilityChanged(false)
        super.onDestroy()
    }

    // ═══ 公开API：UI感知 ═══

    /**
     * 获取当前屏幕的完整UI树
     * 返回扁平化的节点列表（带层级信息）
     */
    fun dumpUiTree(maxDepth: Int = 15): List<UiNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<UiNodeInfo>()
        traverseNode(root, result, 0, maxDepth)
        root.recycle()
        return result
    }

    /**
     * 通过resource-id查找节点。
     *
     * 所有权约定：返回的节点由 caller 负责 recycle；root 在本方法内回收
     * （除非返回的恰好是 root 本身，此时 root 的生命周期由 caller 接管）。
     */
    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val found = findNodeByResourceId(root, resourceId)
        // 若返回 root 本身，root 的生命周期由 caller 负责；若返回 child/null，root 由本方法回收。
        if (found !== root) root.recycle()
        return found
    }

    /**
     * 通过文本查找节点。
     *
     * 所有权约定同 [findNodeById]。
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val found = findNodeByText(root, text)
        if (found !== root) root.recycle()
        return found
    }

    /**
     * 获取当前前台应用包名。
     */
    fun getForegroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            // root 仅用于读取 packageName，用完立即 recycle，避免泄漏。
            root.recycle()
        }
    }

    // ═══ 公开API：UI操作 ═══

    /**
     * 点击指定坐标
     */
    fun clickAt(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription) { callback?.invoke(false) }
        }, null)
    }

    /**
     * 滑动
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 对节点执行点击
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // 不可点击时，获取中心坐标用手势点击
        val rect = Rect()
        node.getBoundsInScreen(rect)
        clickAt(rect.centerX(), rect.centerY())
        return true
    }

    /**
     * 对节点输入文本
     */
    fun inputTextToNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * #239：Android 11+ 无障碍截图（API 30 `AccessibilityService.takeScreenshot`）
     * 的真实现 —— 旧实现是空壳（DefaultPrivilegeManager 里直接 `return false` 且
     * 无诊断信息，还把 root 回退链短路成不可达）。
     *
     * suspendCancellableCoroutine 桥接异步回调；HardwareBuffer → 软件 ARGB_8888
     * 拷贝（buffer close 后位图仍可用）。低版本/服务未连接/回调失败 → null，
     * 调用方（DefaultPrivilegeManager.takeScreenshot）回退 root screencap 通道。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    suspend fun takeScreenshotBitmap(): Bitmap? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(
                    // 主屏（displayId 是首个参数 —— API 30 套件签名三参）。
                    Display.DEFAULT_DISPLAY,
                    // 直通 executor：回调在 binder 线程上执行（仅做位图拷贝 +
                    // resume，无阻塞操作）—— 不为每次截图新建线程（线程泄漏）。
                    java.util.concurrent.Executor { it.run() },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bmp = screenshot.hardwareBuffer?.let { hb ->
                                try {
                                    // colorSpace 传 null = 默认 sRGB 语义。
                                    Bitmap.wrapHardwareBuffer(hb, null)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    hb.close()
                                }
                            }
                            if (cont.isActive) cont.resume(bmp) {}
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null) {}
                        }
                    }
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null) {}
            }
        }
    }

    /**
     * 全局操作
     */
    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun performLockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun performScreenshot(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    // ═══ 事件监听 ═══

    fun addEventListener(listener: (AccessibilityEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (AccessibilityEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    // ═══ 内部方法 ═══

    private fun checkMainProcessAlive() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val alive = am.runningAppProcesses?.any { it.processName == packageName } ?: false
        if (!alive) {
            // #236（心跳门控）：Keep Alive 关闭时用户已明确拒绝常驻 —— 无障碍
            // “不死心跳”不再拉起前台服务（与 BootReceiver 同款 SharedPreferences
            // 正则快读；解析失败视为开，宁可多拉一次也不静默违背用户预期）。
            if (!keepAliveEnabled()) return
            try {
                val intent = android.content.Intent().apply {
                    setClassName(packageName, "$packageName.service.ApexCoreService")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                // #260：心跳拉活失败必须留痕 —— 静默失败时无障碍「不死心跳」
                // 形同虚设且完全不可诊断
                android.util.Log.w("ApexA11yService", "heartbeat restart of main process failed: ${e.message}")
            }
        }
    }

    /** 快读主进程 apex_settings/agent_settings_v2 的 keepAlive 布尔（缺省/损坏 → true）。
     * 与 app 模块 BootReceiver.keepAliveEnabled 同款模式（本模块无法依赖 app 层类，
     * 双端实现由 CI 双向注释锁定同步）。 */
    private fun keepAliveEnabled(): Boolean {
        val raw = runCatching {
            getSharedPreferences("apex_settings", Context.MODE_PRIVATE)
                .getString("agent_settings_v2", null)
        }.getOrNull() ?: return true
        return !KEEP_ALIVE_OFF_REGEX.containsMatchIn(raw ?: "")
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        result: MutableList<UiNodeInfo>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val rect = Rect()
        node.getBoundsInScreen(rect)

        result.add(UiNodeInfo(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            resourceId = node.viewIdResourceName ?: "",
            bounds = rect,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            depth = depth
        ))

        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                traverseNode(child, result, depth + 1, maxDepth)
                child.recycle()
            } catch (e: Exception) {
                // #260：高频 UI 树遍历路径用 debug 级（避免刷屏）；单子树失败
                // 不阻断整树采集（其余兄弟节点继续）
                android.util.Log.d("ApexA11yService", "traverseNode child[$i] failed: ${e.message}")
            }
        }
    }

    private fun findNodeByResourceId(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        if (root.viewIdResourceName == resourceId) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByResourceId(child, resourceId)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.toString()?.contains(text, ignoreCase = true) == true) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) return found
            child.recycle()
        }
        return null
    }
}

/**
 * UI节点信息（扁平化）
 */
data class UiNodeInfo(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val depth: Int
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    override fun toString(): String {
        val parts = mutableListOf<String>()
        if (resourceId.isNotBlank()) parts.add("id=$resourceId")
        if (text.isNotBlank()) parts.add("text=\"$text\"")
        if (contentDescription.isNotBlank()) parts.add("desc=\"$contentDescription\"")
        parts.add("class=${className.substringAfterLast('.')}")
        parts.add("bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
        if (clickable) parts.add("clickable")
        if (editable) parts.add("editable")
        if (scrollable) parts.add("scrollable")
        return parts.joinToString(" ")
    }
}
