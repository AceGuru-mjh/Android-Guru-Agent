package com.apex.agent.platform.privilege.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val eventListeners = mutableListOf<(AccessibilityEvent) -> Unit>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 心跳：每30秒检查主进程
        scope.launch {
            while (isActive) {
                delay(30_000)
                checkMainProcessAlive()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        eventListeners.forEach { listener ->
            try { listener(event) } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        instance = null
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
     * 通过resource-id查找节点
     */
    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByResourceId(root, resourceId)
    }

    /**
     * 通过文本查找节点
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByText(root, text)
    }

    /**
     * 获取当前前台应用包名
     */
    fun getForegroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return root.packageName?.toString()
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
            try {
                val intent = android.content.Intent().apply {
                    setClassName(packageName, "$packageName.service.ApexCoreService")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {}
        }
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
            } catch (_: Exception) {}
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
