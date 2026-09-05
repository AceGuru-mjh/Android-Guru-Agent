package com.apex.agent.platform.privilege

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import com.apex.agent.platform.privilege.accessibility.ApexAccessibilityService
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
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
            suPaths.any { File(it).exists() } || whichSuExists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * v2 修复：旧实现 `exec("which su").waitFor()` 无超时——本 @Singleton 在 DI 首次
     * 注入（通常主线程）时执行，个别设备上 su 命令挂起会让主线程永久阻塞（ANR）。
     * 现在 2 秒超时 + destroyForcibly 兜底，与 PrivilegeDetector 的做法对齐。
     */
    private fun whichSuExists(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("which su")
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
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

    // 之前 timeoutMs 被静默忽略：`process.waitFor()` 无超时，stdout/stderr 的 bufferedReader 也从不 close。
    // 一个 su 提示被拒绝/`tail -f` 之类阻塞命令会让调用方永久挂起并泄漏 FD。
    // 现在：用 `waitFor(timeoutMs)` 兑现超时；finally 中关闭 reader + 强杀进程，杜绝 FD 泄漏。
    private suspend fun executeViaRoot(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            val process: Process = try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } catch (e: Exception) {
                return@withContext ShellResult(false, "Root exec error: ${e.message}", -1, ExecutionVia.ROOT)
            }
            val stdout = process.inputStream.bufferedReader()
            val stderr = process.errorStream.bufferedReader()
            try {
                // 后台并发排空 stdout/stderr，避免管道缓冲写满导致 waitFor 死锁。
                val stdoutDeferred = async(Dispatchers.IO) { runCatching { stdout.readText() }.getOrDefault("") }
                val stderrDeferred = async(Dispatchers.IO) { runCatching { stderr.readText() }.getOrDefault("") }
                val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!completed) {
                    // 超时：强杀 su 子进程，让阻塞中的 readText 自然返回 EOF。
                    process.destroyForcibly()
                    stdoutDeferred.await()
                    stderrDeferred.await()
                    ShellResult(
                        success = false,
                        output = "Root command timed out after ${timeoutMs}ms",
                        exitCode = -1,
                        executedVia = ExecutionVia.ROOT
                    )
                } else {
                    val output = stdoutDeferred.await()
                    val error = stderrDeferred.await()
                    val exitCode = process.exitValue()
                    ShellResult(
                        success = exitCode == 0,
                        output = if (output.isNotBlank()) output else error,
                        exitCode = exitCode,
                        executedVia = ExecutionVia.ROOT
                    )
                }
            } catch (e: Exception) {
                ShellResult(false, "Root exec error: ${e.message}", -1, ExecutionVia.ROOT)
            } finally {
                // 无论正常返回、超时、异常，都关闭 reader + 杀进程，避免 FD 泄漏与僵尸 su 子进程。
                runCatching { stdout.close() }
                runCatching { stderr.close() }
                if (process.isAlive) process.destroyForcibly()
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

        // rootInActiveWindow 拿到的 ref 必须由本方法 recycle，否则每次 getUiTree 泄漏一个 AccessibilityNodeInfo。
        try {
            val nodes = mutableListOf<UiNode>()
            traverseNode(rootNode, nodes)
            return UiTreeResult(success = true, nodes = nodes)
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 递归展平节点为 UiNode 列表。
     *
     * 所有权约定：node 自身由 caller 负责 recycle（这里是 [getUiTree] 在 finally 中 recycle rootNode）；
     * 本方法在递归时获取的每个 child 在用完后立即 recycle，避免 AccessibilityNodeInfo 泄漏。
     */
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
            // child 必须在本循环内 recycle，否则递归遍历会累积泄漏所有中间节点。
            val child = node.getChild(i) ?: continue
            try {
                traverseNode(child, result, depth + 1)
            } finally {
                child.recycle()
            }
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
        // 之前是 TODO stub，永远返回 false；现在委托给 ShizukuCommandExecutor 真实探测
        // binder 存活 + 已授权（内部调用 Shizuku.pingBinder() + checkSelfPermission）。
        //
        // TODO（听众接线）：ApexApp.initShizuku() 已注册 addBinderReceivedListenerSticky /
        // addBinderDeadListener，但只 LOG，未把 binder 状态回灌 _shizukuAvailable；
        // 应在那些回调里触发 refreshStatus() 让 StateFlow 实时反映 Shizuku 启停。
        return try {
            ShizukuCommandExecutor.isAvailable() && ShizukuCommandExecutor.hasPermission()
        } catch (e: Exception) {
            false
        }
    }
}
