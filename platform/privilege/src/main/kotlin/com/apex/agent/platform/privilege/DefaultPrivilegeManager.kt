package com.apex.agent.platform.privilege

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import com.apex.agent.platform.privilege.accessibility.ApexAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPrivilegeManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PrivilegeManager {

    private val _rootAvailable = MutableStateFlow(false)
    override val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    private val _shizukuAvailable = MutableStateFlow(false)
    override val shizukuAvailable: StateFlow<Boolean> = _shizukuAvailable.asStateFlow()

    private val _accessibilityAvailable = MutableStateFlow(false)
    override val accessibilityAvailable: StateFlow<Boolean> = _accessibilityAvailable.asStateFlow()

    init {
        checkRoot()
        // Shizuku和无障碍状态会在运行时更新
    }

    private fun checkRoot() {
        _rootAvailable.value = try {
            val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
            suPaths.any { File(it).exists() } ||
                Runtime.getRuntime().exec("which su").waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun executeShell(command: String, timeoutMs: Long): ShellResult {
        // 优先级：Root > Shizuku
        if (_rootAvailable.value) {
            return executeViaRoot(command, timeoutMs)
        }
        
        if (_shizukuAvailable.value) {
            return executeViaShizuku(command, timeoutMs)
        }
        
        return ShellResult(
            success = false,
            output = "Error: No privilege available. Need Root or Shizuku.",
            exitCode = -1,
            executedVia = ExecutionVia.NONE
        )
    }

    private suspend fun executeViaRoot(command: String, timeoutMs: Long): ShellResult {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                
                ShellResult(
                    success = exitCode == 0,
                    output = if (output.isNotBlank()) output else error,
                    exitCode = exitCode,
                    executedVia = ExecutionVia.ROOT
                )
            } catch (e: Exception) {
                ShellResult(false, "Root exec error: ${e.message}", -1, ExecutionVia.ROOT)
            }
        }
    }

    private suspend fun executeViaShizuku(command: String, timeoutMs: Long): ShellResult {
        // Shizuku执行逻辑
        // 实际实现需要Shizuku UserService
        return ShellResult(
            success = false,
            output = "Shizuku execution not yet implemented",
            exitCode = -1,
            executedVia = ExecutionVia.SHIZUKU
        )
    }

    override suspend fun executeUiAction(action: UiAction): UiResult {
        // 优先级：无障碍（有语义）> Root input命令（纯坐标）
        val a11yService = ApexAccessibilityService.instance
        if (a11yService != null) {
            return executeViaAccessibility(a11yService, action)
        }
        
        if (_rootAvailable.value) {
            return executeViaRootInput(action)
        }
        
        if (_shizukuAvailable.value) {
            return executeViaShizukuInput(action)
        }
        
        return UiResult(false, "No privilege for UI action")
    }

    private suspend fun executeViaAccessibility(
        service: AccessibilityService,
        action: UiAction
    ): UiResult {
        return when (action) {
            is UiAction.Back -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                UiResult(true)
            }
            is UiAction.Home -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                UiResult(true)
            }
            is UiAction.Recents -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                UiResult(true)
            }
            is UiAction.OpenNotifications -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                UiResult(true)
            }
            is UiAction.Click -> {
                // 使用手势API点击坐标
                val path = android.graphics.Path().apply {
                    moveTo(action.x.toFloat(), action.y.toFloat())
                }
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 100
                    ))
                    .build()
                service.dispatchGesture(gesture, null, null)
                UiResult(true)
            }
            is UiAction.InputText -> {
                // 需要找到当前焦点节点
                UiResult(false, "InputText via A11y requires focused node")
            }
            else -> UiResult(false, "Unsupported action")
        }
    }

    private suspend fun executeViaRootInput(action: UiAction): UiResult {
        val command = when (action) {
            is UiAction.Click -> "input tap ${action.x} ${action.y}"
            is UiAction.Swipe -> "input swipe ${action.x1} ${action.y1} ${action.x2} ${action.y2} ${action.durationMs}"
            is UiAction.InputText -> "input text '${action.text.replace("'", "'\\''")}'"
            is UiAction.PressKey -> "input keyevent ${action.keyCode}"
            is UiAction.Back -> "input keyevent 4"
            is UiAction.Home -> "input keyevent 3"
            is UiAction.Recents -> "input keyevent 187"
            is UiAction.OpenNotifications -> "input keyevent 26"  // 不完全准确
            is UiAction.ClickNode -> return UiResult(false, "ClickNode requires accessibility")
        }
        val result = executeViaRoot(command, 5000)
        return UiResult(result.success, result.output)
    }

    private suspend fun executeViaShizukuInput(action: UiAction): UiResult {
        // 类似Root但通过Shizuku
        return UiResult(false, "Not implemented")
    }

    override suspend fun getUiTree(): UiTreeResult {
        // UI树只能通过无障碍获取
        val a11yService = ApexAccessibilityService.instance
            ?: return UiTreeResult(false, "Accessibility service not running")
        
        // 遍历root节点
        val rootNode = a11yService.rootInActiveWindow
            ?: return UiTreeResult(false, "No active window")
        
        val nodes = mutableListOf<UiNode>()
        traverseNode(rootNode, nodes)
        
        return UiTreeResult(success = true, nodes = nodes)
    }

    private fun traverseNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        result: MutableList<UiNode>,
        depth: Int = 0
    ) {
        if (depth > 20) return  // 防止无限递归

        val boundsRect = android.graphics.Rect()
        node.getBoundsInScreen(boundsRect)

        result.add(UiNode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            resourceId = node.viewIdResourceName ?: "",
            bounds = boundsRect.toString(),
            clickable = node.isClickable,
            scrollable = node.isScrollable
        ))
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseNode(it, result, depth + 1) }
        }
    }

    override suspend fun takeScreenshot(): ScreenshotResult {
        val a11yService = ApexAccessibilityService.instance
        if (a11yService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 无障碍截图API
            // 需要异步回调，这里简化
            return ScreenshotResult(false, null)
        }
        
        // 降级：通过shell screencap
        val result = executeShell("screencap -p /data/local/tmp/screen.png && cat /data/local/tmp/screen.png")
        return ScreenshotResult(result.success, result.output.toByteArray())
    }

    override suspend fun refreshStatus() {
        checkRoot()
        _shizukuAvailable.value = checkShizuku()
        _accessibilityAvailable.value = ApexAccessibilityService.instance != null
    }

    private fun checkShizuku(): Boolean {
        return try {
            Class.forName("rikka.shizuku.Shizuku")
            // 实际检查需要Shizuku API
            false // TODO
        } catch (e: Exception) {
            false
        }
    }
}
