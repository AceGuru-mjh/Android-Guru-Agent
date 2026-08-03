package com.apex.agent.platform.privilege.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

/**
 * 核心无障碍服务
 * 
 * 职责：
 * 1. Agent的"眼睛"：读取UI树
 * 2. Agent的"手"：执行UI操作（点击/滑动/输入）
 * 3. 不死心跳：监控主进程，被杀则重启
 * 4. 事件感知：监听全局UI变化
 */
class ApexAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ApexAccessibilityService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 事件监听器列表
    private val eventListeners = mutableListOf<(AccessibilityEvent) -> Unit>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // 启动心跳：每20秒检查主进程
        scope.launch {
            while (isActive) {
                delay(20_000)
                checkMainProcessAlive()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 转发给所有监听器
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

    // ═══ 公开API ═══

    fun addEventListener(listener: (AccessibilityEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (AccessibilityEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    /**
     * 获取当前窗口的完整UI树
     */
    fun dumpUiTree(): List<UiNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<UiNodeInfo>()
        traverseTree(root, result, 0)
        return result
    }

    /**
     * 通过手势点击指定坐标
     */
    fun clickAt(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * 通过手势滑动
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
     * 对指定节点执行操作
     */
    fun performActionOnNode(nodeId: String, action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByResourceId(root, nodeId) ?: return false
        return node.performAction(action)
    }

    /**
     * 截图（Android 11+）
     */
    fun takeScreenshot(callback: (android.accessibilityservice.AccessibilityService.TakeScreenshotResult?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: TakeScreenshotResult) {
                        callback(result)
                    }
                    override fun onFailure(errorCode: Int) {
                        callback(null)
                    }
                })
        } else {
            callback(null)
        }
    }

    // ═══ 内部方法 ═══

    private fun checkMainProcessAlive() {
        // 检查com.apex.agent进程是否存活
        // 如果不存活，尝试重启
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val alive = am.runningAppProcesses?.any { it.processName == packageName } ?: false
        if (!alive) {
            val intent = android.content.Intent().apply {
                setClassName(packageName, "$packageName.service.ApexCoreService")
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    private fun traverseTree(
        node: AccessibilityNodeInfo,
        result: MutableList<UiNodeInfo>,
        depth: Int
    ) {
        if (depth > 25) return
        
        result.add(UiNodeInfo(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            resourceId = node.viewIdResourceName ?: "",
            bounds = node.boundsInScreen,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            depth = depth
        ))
        
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { traverseTree(it, result, depth + 1) }
            } catch (_: Exception) {}
        }
    }

    private fun findNodeByResourceId(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        if (root.viewIdResourceName == resourceId) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByResourceId(child, resourceId)
            if (found != null) return found
        }
        return null
    }
}

data class UiNodeInfo(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: android.graphics.Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val depth: Int
)
