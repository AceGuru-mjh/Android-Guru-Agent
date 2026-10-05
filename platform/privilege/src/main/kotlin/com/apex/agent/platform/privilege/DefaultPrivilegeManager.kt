package com.apex.agent.platform.privilege

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.apex.agent.platform.privilege.accessibility.ApexAccessibilityService
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
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
        // #210：无障碍服务连接/断开事件驱动 _accessibilityAvailable（sticky：注册即回当前态）。
        // 此前 StateFlow 构造后恒为初始值 —— 用户授权后 Agent 仍走 input 命令回退。
        ApexAccessibilityService.addLifecycleListener { connected ->
            _accessibilityAvailable.value = connected
        }
        watchShizukuBinder()
    }

    /**
     * #212：Shizuku binder 生命周期 → StateFlow 实时回灌。
     *
     * received（sticky）/ dead / 授权结果三类事件直接写 _shizukuAvailable
     * （同步赋值，无协程派发延迟），同步失效 PrivilegeDetector 的 30s 级别缓存，
     * 保证授权/停止后 executeShell、终端通道、persistence 安装立即按新状态选路。
     * 与 ApexApp 的日志监听并存（Shizuku 支持多监听者）：本处是状态真源。
     */
    private fun watchShizukuBinder() {
        try {
            Shizuku.addBinderReceivedListenerSticky {
                _shizukuAvailable.value = checkShizuku()
                PrivilegeDetector.invalidateCache()
            }
            Shizuku.addBinderDeadListener {
                _shizukuAvailable.value = false
                PrivilegeDetector.invalidateCache()
            }
            Shizuku.addRequestPermissionResultListener { _, _ ->
                _shizukuAvailable.value = checkShizuku()
                PrivilegeDetector.invalidateCache()
            }
        } catch (_: Exception) {
            // Shizuku 未安装/未初始化：StateFlow 保持 false，executeShell 走 Root/NONE 路径
        }
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

    /**
     * Shizuku 通道执行（#211 引导链接线 / T92 #255 审计收敛）。
     *
     * 旧实现是 "Shizuku execution not yet implemented" 占位 stub —— 而真实
     * 的 Shizuku 执行能力早已在 [ShizukuCommandExecutor]（IShizukuService
     * .newProcess AIDL，uid=2000）落地，主链路（PrivilegeDetector /
     * PrivilegedCommandSpawner）用的也是它。任何误入本方法的调用者都会拿到
     * 假失败：无 Root 用户走完「装 Shizuku → 配对 → 授权」漫长引导后，
     * 提权命令仍以 stub 错误收场（#211 的用户影响）。现直接委托同一真实
     * 执行器，行为与主链路完全一致（超时强杀、诚实报错、绝不降级伪装）；
     * 失败也返回执行器的结构化真实错误（JVM 契约锁见 ShizukuWiringContractTest）。
     */
    private suspend fun executeViaShizuku(command: String, timeoutMs: Long): ShellResult {
        // 委托 ShizukuCommandExecutor（IShizukuService.newProcess AIDL，uid=2000
        // 真实 ADB 级执行）。失败语义与之对齐：诚实报错，不回退本地 app-shell
        // 冒充 Shizuku——降级决策由调用方基于真实错误做。
        return try {
            val result = ShizukuCommandExecutor.execute(command, timeoutMs)
            ShellResult(
                success = result.success,
                output = result.output,
                exitCode = result.exitCode,
                executedVia = ExecutionVia.SHIZUKU
            )
        } catch (e: Exception) {
            ShellResult(false, "Shizuku exec failed: ${e.message}", -1, ExecutionVia.SHIZUKU)
        }
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
        service: ApexAccessibilityService,
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
            // #240 收尾：收起通知栏 —— 无障碍无专用 GLOBAL_ACTION，BACK 的
            // 系统语义就是收合 shade（状态栏展开时 BACK = collapse）。手势派发
            // 在已展开时是同一效果；未展开时 BACK 退一层（与用户手动按返回一致）。
            is UiAction.CloseNotifications -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
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
                // 双通道输入：rootInActiveWindow.findFocus(FOCUS_INPUT) 定位
                // 当前输入焦点节点，ACTION_SET_TEXT 整段替换（复用
                // ApexAccessibilityService.inputTextToNode 的 Bundle 协议）；
                // 无焦点节点/动作被拒 → 落回 Root 档 `input text` 命令回放。
                val root = service.rootInActiveWindow
                if (root != null) {
                    try {
                        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (focused != null) {
                            try {
                                if (service.inputTextToNode(focused, action.text)) {
                                    return UiResult(true)
                                }
                            } finally {
                                focused.recycle()
                            }
                        }
                    } finally {
                        root.recycle()
                    }
                }
                // a11y 通道失败 → 按 executeUiAction 的标准优先级落回
                // Root / Shizuku 的 `input text` 命令回放。
                if (_rootAvailable.value) {
                    executeViaRootInput(action)
                } else {
                    executeViaShizukuInput(action)
                }
            }
            else -> UiResult(false, "Unsupported action")
        }
    }

    /**
     * UiAction → `input` 命令映射（Root / Shizuku 档共用）。
     * ClickNode 需要节点级语义，仅无障碍通道可执行（返回 null）。
     */
    private fun inputCommandFor(action: UiAction): String? = when (action) {
        is UiAction.Click -> "input tap ${action.x} ${action.y}"
        is UiAction.Swipe -> "input swipe ${action.x1} ${action.y1} ${action.x2} ${action.y2} ${action.durationMs}"
        is UiAction.InputText -> "input text '${action.text.replace("'", "'\\''")}'"
        is UiAction.PressKey -> "input keyevent ${action.keyCode}"
        is UiAction.Back -> "input keyevent 4"
        is UiAction.Home -> "input keyevent 3"
        is UiAction.Recents -> "input keyevent 187"
        // #240：旧映射 input keyevent 26 是电源键（熄屏/唤醒）—— 用户要求
        // 「打开通知栏」却把屏幕关了。cmd statusbar expand-notifications 才是
        // 正解（API 24+，等价 service call statusbar 1；无障碍通道走
        // GLOBAL_ACTION_NOTIFICATIONS 不受影响）。
        is UiAction.OpenNotifications -> "cmd statusbar expand-notifications"
        // #240 收尾：收合通知栏（与 expand 对称；`input keyevent 4`（BACK）
        // 也可达但语义间接 —— statusbar 直控不受当前焦点影响）。
        is UiAction.CloseNotifications -> "cmd statusbar collapse"
        is UiAction.ClickNode -> null
    }

    private suspend fun executeViaRootInput(action: UiAction): UiResult {
        val command = inputCommandFor(action)
            ?: return UiResult(false, "ClickNode requires accessibility")
        val result = executeViaRoot(command, 5000)
        return UiResult(result.success, result.output)
    }

    /**
     * #211：Shizuku 档 UI 动作。旧 stub 恒返回 UiResult(false, "Not implemented")
     * —— 无 Root 用户完成 Shizuku 安装/配对/授权后，UI 动作仍全部假失败。
     * ShizukuCommandExecutor 无原生 UI 注入 API，但 uid=2000 的 shell 可执行
     * `input tap/swipe/text`（等价 adb shell input）——与 Root 档共用命令
     * 映射，经 [executeViaShizuku] 的 AIDL 通道下发，授权后的 UI 动作真实生效。
     */
    private suspend fun executeViaShizukuInput(action: UiAction): UiResult {
        val command = inputCommandFor(action)
            ?: return UiResult(false, "ClickNode requires accessibility")
        val result = executeViaShizuku(command, 5000)
        return UiResult(result.success, result.output)
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
            val rootUiNode = buildUiNodeTree(rootNode, depth = 0)
            return UiTreeResult(
                success = true,
                // nodes：DFS 先序扁平视图（旧消费方逐节点遍历的兼容契约），
                // 剥离 children 引用避免把嵌套结构当扁平结构二次递归。
                nodes = flattenDetached(rootUiNode),
                // roots：真实嵌套树（父节点 children 已填充）——cs-mem
                // 修剪/空间拓扑边/指纹父上下文的输入契约。
                roots = listOf(rootUiNode)
            )
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 递归构建真实嵌套的 UiNode 树（父节点 children 填充子节点）。
     *
     * 嵌套树是 cs-mem 边子系统的前提：UiTreePruner.generateSpatialEdges 按
     * children 生成父子边/兄弟邻接边——旧实现把节点展平进列表、children 恒空，
     * 边生成零产出，MemoryGraphStore.ingestEdges 永远收不到边。
     *
     * 所有权约定：node 自身由 caller 负责 recycle（这里是 [getUiTree] 在 finally 中 recycle rootNode）；
     * 本方法在递归时获取的每个 child 在用完后立即 recycle，避免 AccessibilityNodeInfo 泄漏。
     */
    private fun buildUiNodeTree(
        node: AccessibilityNodeInfo,
        depth: Int
    ): UiNode {
        val boundsRect = android.graphics.Rect()
        node.getBoundsInScreen(boundsRect)

        val children = mutableListOf<UiNode>()
        // 防止无限递归：深度上限 20（子节点深度 = depth + 1 ≤ 20 才展开）。
        if (depth < MAX_UI_TREE_DEPTH) {
            for (i in 0 until node.childCount) {
                // child 必须在本循环内 recycle，否则递归遍历会累积泄漏所有中间节点。
                val child = node.getChild(i) ?: continue
                try {
                    children.add(buildUiNodeTree(child, depth + 1))
                } finally {
                    child.recycle()
                }
            }
        }

        return UiNode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            resourceId = node.viewIdResourceName ?: "",
            bounds = boundsRect.toString(),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            children = children
        )
    }

    /**
     * 嵌套树 → 扁平 DFS 先序列表（兼容旧「扁平列表」消费方）。
     * 叶子节点直接复用实例；容器节点 copy 出 children=空的版本——旧消费方
     * 把列表元素当独立根逐个处理，保留 children 引用会对同一子树重复递归
     * （指纹重复、遍历放大）。
     */
    private fun flattenDetached(root: UiNode): List<UiNode> {
        val out = ArrayList<UiNode>(32)
        collectDetached(root, out)
        return out
    }

    private fun collectDetached(node: UiNode, out: MutableList<UiNode>) {
        out.add(if (node.children.isEmpty()) node else node.copy(children = emptyList()))
        for (child in node.children) {
            collectDetached(child, out)
        }
    }

    override suspend fun takeScreenshot(): ScreenshotResult {
        // #239：Android 11+ 无障碍截图（API 30）真实现 —— 旧实现此处是空壳
        // `return ScreenshotResult(false, null)`，且因提前 return 连下面的 root
        // screencap 回退都不可达（「开了无障碍 = 关了截图」）。现在无障碍路径
        // 失败（低版本/回调拒绝/编码失败）时静默落回 root screencap 通道，
        // 降级链真正闭合。
        val a11yService = ApexAccessibilityService.instance
        if (a11yService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bitmap = runCatching { a11yService.takeScreenshotBitmap() }.getOrNull()
            if (bitmap != null) {
                val out = java.io.ByteArrayOutputStream()
                val encoded = runCatching {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }.getOrDefault(false)
                if (encoded) {
                    return ScreenshotResult(true, out.toByteArray())
                }
            }
        }

        // P2 fix（审计 6-b）：PNG 二进制绝不经 String↔bytes 往返（任何 charset 解码都会
        // 损坏字节流 —— 原 `cat png` 后 toByteArray() 产出必然损坏的“截图”）。
        // 改走 base64 文本通道（toybox base64，-w 0 不换行），Android 侧 Base64.decode
        // 还原；输出含非法 base64 字符时返回明确错误而非静默损坏数据。
        val result = executeShell(
            "screencap -p /data/local/tmp/screen.png && base64 -w 0 < /data/local/tmp/screen.png"
        )
        if (!result.success) {
            return ScreenshotResult(false, null, error = "screencap failed: ${result.output.take(200)}")
        }
        val encoded = result.output.trim()
        return try {
            ScreenshotResult(true, Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            ScreenshotResult(
                false, null,
                error = "screenshot output is not valid base64 (len=${encoded.length}, head=${encoded.take(32)})"
            )
        }
    }

    /**
     * #210：全仓此前零调用，StateFlow 构造后恒为 false。现在挂在三个生命周期点：
     * 1. [watchShizukuBinder] 的 binder received/dead/授权结果回调（同步写流，不走本方法）；
     * 2. ApexApp 的 Shizuku 事件回调（binder 变化后全量重探）；
     * 3. 权限页 ON_RESUME / 授权动作返回（PermissionsViewModel.refresh）。
     * 幂等：任意时刻任意次数调用结果一致；IO 调度 —— checkRoot 最多 fork 2s，
     * 不压调用方线程。
     */
    override suspend fun refreshStatus() {
        withContext(Dispatchers.IO) {
            checkRoot()
            _shizukuAvailable.value = checkShizuku()
            _accessibilityAvailable.value = ApexAccessibilityService.instance != null
        }
    }

    private fun checkShizuku(): Boolean {
        // 之前是 TODO stub，永远返回 false；现在委托给 ShizukuCommandExecutor 真实探测
        // binder 存活 + 已授权（内部调用 Shizuku.pingBinder() + checkSelfPermission）。
        //
        // #212 已接线：ApexApp.initShizuku() 与本类 [watchShizukuBinder] 均在 binder
        // received/dead/授权结果回调里回灌 _shizukuAvailable，本方法负责“探测”，
        // 事件驱动负责“实时”，二者配合让状态流不再滞后于 Shizuku 启停。
        return try {
            ShizukuCommandExecutor.isAvailable() && ShizukuCommandExecutor.hasPermission()
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        /** UI 树遍历深度上限（与旧 traverseNode 的 depth > 20 截断语义一致）。 */
        private const val MAX_UI_TREE_DEPTH = 20
    }
}
