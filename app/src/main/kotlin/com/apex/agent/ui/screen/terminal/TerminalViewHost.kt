package com.apex.agent.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.terminal.io.KeyEventMapping
import com.apex.agent.platform.terminal.io.TerminalKey as UiKey
import com.apex.agent.terminalemulator.KeyModifiers
import com.apex.agent.terminalemulator.MouseTrackingMode
import com.apex.agent.terminalemulator.TerminalKey as VtKey
import com.apex.agent.terminalemulator.TerminalMouseEventType
import com.apex.agent.terminalview.TerminalContextMenuItem
import com.apex.agent.terminalview.TerminalPalette
import com.apex.agent.terminalview.TerminalView
import com.apex.agent.terminalview.TerminalViewClient
import com.apex.agent.terminalview.TerminalViewSettings
import com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysBar
import com.apex.agent.ui.screen.terminal.scheme.TerminalColorScheme
import com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeRegistry
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * T88（3）：Compose ↔ [TerminalView] 桥 —— 用 :terminal-view 的 Canvas 直绘
 * 替换旧 LazyColumn+BasicText 渲染链（TerminalRenderer.kt 已删除）。
 *
 * ## 客户端回调 → VM 映射表（TerminalViewClient ⇒ TerminalViewModel）
 *
 * | TerminalViewClient 回调 | 归宿 | 说明 |
 * |---|---|---|
 * | onTerminalWrite(text) | sendInput(text) | View 已归一 \n→\r；黑白名单/行缓冲门禁全在 VM |
 * | onTerminalKey(key, mods) | sendKey(映射后 UiKey, mods) | VtKey→UiKey 枚举映射；NUMPAD_前缀键走 sendHardwareKey / sendInput |
 * | onTerminalControlChar(code) | sendControlChar(字母面) | View 给**控制码**、VM 吃**字母面**（KeySequenceEncoder.controlByte 契约）；DEL(0x7F) 特判走 sendKey(BACKSPACE) 保行缓冲退格 |
 * | onTerminalMouse(col, row, type, mods, …) | sendMouseEvent(type, button, mods, col, row) | 1-based 屏内坐标双端一致；released/mouseMode 冗余（type 与 VM 重读的模式即权威） |
 * | onTerminalFocus(gained) | notifyTerminalFocus(gained) | 与 ON_RESUME/ON_PAUSE 生命周期源合并去重（双源 → 单流） |
 * | onTerminalViewSizeChanged(rows, cols, …) | resizeTerminal(rows, cols) | View 侧字体度量 + 150ms 防抖驱动 resize（像素域无消费者，弃） |
 * | onTerminalBell() | vibrateOnce（本文件） | 振动逻辑留在渲染宿主（VM 行数预算纪律）；受 vibrateOnBell 设置门控 |
 * | onTerminalTitle(title) | 忽略 | VM 的 renderState 收集器已消费 snapshot.title 更新会话标签（单一数据源） |
 * | onTerminalClipboardCopy(text) | ClipboardManager.setPrimaryClip | |
 * | onTerminalPasteRequest() | ClipboardManager → pasteText | bracketed-paste 包裹在 VM 内完成 |
 * | onTerminalLinkOpen(uri) | Intent(ACTION_VIEW) | runCatching 兜住无浏览器场景 |
 * | onTerminalScrollChanged(topRow, atBottom) | 本地 atBottom 状态 | 驱动「↓ 跳到最新」浮标 |
 * | onTerminalSelectionChanged(text) | 忽略 | 复制动作经 onTerminalClipboardCopy；无额外 UI 消费者 |
 * | onTerminalFontSizeChanged(newSp) | setFontSize(newSp.roundToInt()) | VM 钳制 8..24 与设置抽屉一致 |
 * | onTerminalContextMenu(items, x, y) | 本地 Popup 状态 | Compose Popup 定位在触摸点；点击路由 view.onContextMenuAction(id) |
 *
 * IME 彻底换轨：旧「隐藏 BasicTextField 增量 diff」黑客桥删除 —— View 自带
 * InputConnection（组合文本直通 + 提交归一 + 每字符退格），焦点/拉起键盘由
 * View 的 tap 处理与 requestFocusAndShowKeyboard 完成。
 */
@Composable
fun TerminalViewHost(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val render by viewModel.renderState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val schemeId by viewModel.colorSchemeId.collectAsStateWithLifecycle()
    val boldAsBright by viewModel.boldAsBright.collectAsStateWithLifecycle()
    val extraKeys by viewModel.extraKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // T86：窗口焦点变化 → ESC[I / ESC[O（DECSET 1004）。生命周期近似（ON_RESUME=
    // 聚焦，ON_PAUSE=失焦）与 View 的 onWindowFocusChanged 双源经 client 合并去重。
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { TerminalViewHostClient(viewModel, context) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> client.reportFocus(true)
                Lifecycle.Event.ON_PAUSE -> client.reportFocus(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ── 方案 → 调色板（仅 schemeId/boldAsBright 变化时重建）──
    val scheme = remember(schemeId) { TerminalColorSchemeRegistry.byId(schemeId) }
    val palette = remember(schemeId, boldAsBright) { buildPalette(scheme, boldAsBright) }
    val viewSettings = remember(settings.fontSize, settings.monochrome, palette) {
        TerminalViewSettings(
            fontSizeSp = settings.fontSize.toFloat(),
            minFontSp = TerminalViewModel.TerminalSettings.MIN_FONT_SIZE.toFloat(),
            maxFontSp = TerminalViewModel.TerminalSettings.MAX_FONT_SIZE.toFloat(),
            monochrome = settings.monochrome,
            palette = palette
        )
    }

    // ── View 实例（工厂唯一创建；后续换肤/字号走 updateSettings 原子替换）──
    val viewState = remember { mutableStateOf<TerminalView?>(null) }
    LaunchedEffect(viewSettings) {
        viewState.value?.updateSettings(viewSettings)
    }
    // 响铃振动受设置门控（每次重组后同步，避免陈旧捕获）
    SideEffect { client.bellVibrate = settings.vibrateOnBell }

    // ★ 键盘拉起时机（T89 输入修复）：旧版只在「首帧 VT 快照到达」（render != null）
    // 才拉 —— shell 无输出/泵慢时用户点键盘毫无反应。本 Host 挂载即有活跃会话
    //（TerminalScreen 只在有会话时组合它），组合即拉；快照到达后再补一次
    //（首帧后窗口焦点已稳定，IME 响应率更高）。仅一次语义，不与用户主动
    // 收起键盘打架。
    LaunchedEffect(Unit) {
        delay(120)   // 等 View attach + 焦点稳定
        viewState.value?.requestFocusAndShowKeyboard()
    }
    var keyboardBoosted by remember { mutableStateOf(false) }
    LaunchedEffect(render != null) {
        if (render != null && !keyboardBoosted) {
            keyboardBoosted = true
            viewState.value?.requestFocusAndShowKeyboard()
        }
    }

    // ★ 会话切换重握手网格尺寸（T90）：后台创建的会话（agent 附属/装依赖/第二
    // 会话）从未收到过 resize —— 一直以 runtime 默认 24×80 悬空：80 列行在窄屏
    // 右侧被裁、≤24 行内容浮在高视口中间大片留白（「页面不对称 + 输出格式乱」
    // 的大头）。View 网格是尺寸唯一真源（字体度量 + 视口像素推导）—— 活跃会话
    // 变化时把它当前网格推给新会话（resize 幂等：native ioctl + VT 同步）。
    // 首个会话创建后同样受益（不等 150ms 防抖的 onSizeChanged 通道）。
    val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    LaunchedEffect(activeSessionId) {
        viewState.value?.currentGridSize()?.let { viewModel.resizeTerminal(it.rows, it.cols) }
    }

    // ── 工具栏锁存（CTRL/SHIFT/ALT 一次性 —— 与下一个特殊键/字母组合后释放）──
    fun latchedMods(): Int {
        var m = 0
        if (client.ctrlLatched.value) m = m or KeyEventMapping.MOD_CTRL
        if (client.shiftLatched.value) m = m or KeyEventMapping.MOD_SHIFT
        if (client.altLatched.value) m = m or KeyEventMapping.MOD_ALT
        return m
    }

    /** 特殊键按当前锁存修饰发送（锁存随发出即释放 —— 一次性语义）。 */
    fun sendKeyWithLatches(key: UiKey) {
        val mods = latchedMods()
        viewModel.sendKey(key, mods)
        if (mods != 0) {
            client.ctrlLatched.value = false
            client.shiftLatched.value = false
            client.altLatched.value = false
        }
    }

    Column(modifier = modifier.fillMaxSize().background(scheme.backgroundC)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx).apply {
                        attach(client, viewSettings)
                        viewState.value = this
                    }
                },
                // 每次重组推送快照（View 内部同引用去重 + 任意线程 hop，零风暴）
                update = { view -> view.submit(render) }
            )

            // 会话未启动占位（与旧渲染器同文案；View 自身画纯底色）
            if (render == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.term_not_started),
                        color = ConsoleTheme.dim,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ── 跳到最新浮标（脱离吸底时；点击走 View 的 Termux 滚动模型）──
            if (!client.atBottom.value && render != null) {
                JumpToLatestPill(
                    accent = scheme.cursorC,
                    onClick = { viewState.value?.scrollToBottom() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                )
            }

            // ── 长按/链接上下文菜单（View 出稳定 id，本层出 UI 并回灌点击）──
            client.menuRequest.value?.let { request ->
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(request.x.toInt(), request.y.toInt()),
                    onDismissRequest = { client.menuRequest.value = null },
                    properties = PopupProperties(focusable = true)
                ) {
                    Column(
                        modifier = Modifier
                            .background(PopupChrome.bg, RoundedCornerShape(10.dp))
                            .padding(vertical = 4.dp)
                    ) {
                        for (item in request.items) {
                            Text(
                                localizedMenuLabel(item),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = ConsoleTheme.text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        client.menuRequest.value = null
                                        viewState.value?.onContextMenuAction(item.id)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── T87：扩展键行（用户自定义宏；空布局零占位）──
        if (extraKeys.isNotEmpty()) {
            ExtraKeysBar(
                layout = listOf(extraKeys),
                onText = viewModel::sendInput,
                onKey = { key -> sendKeyWithLatches(key) },
                onControl = viewModel::sendControlChar,
                onPaste = {
                    clipboard.getText()?.text?.let { viewModel.pasteText(it) }
                }
            )
        }

        // ── 特殊键工具栏（触屏必备；横向滚动；可在终端设置中隐藏换显示区）──
        if (settings.showKeybar) {
            KeyToolbar(
                ctrlActive = client.ctrlLatched.value,
                onCtrlToggle = { client.ctrlLatched.value = !client.ctrlLatched.value },
                shiftActive = client.shiftLatched.value,
                onShiftToggle = { client.shiftLatched.value = !client.shiftLatched.value },
                altActive = client.altLatched.value,
                onAltToggle = { client.altLatched.value = !client.altLatched.value },
                onText = viewModel::sendInput,
                onKey = { key -> sendKeyWithLatches(key) },
                onControl = viewModel::sendControlChar,
                onShowKeyboard = { viewState.value?.requestFocusAndShowKeyboard() },
                onPaste = {
                    clipboard.getText()?.text?.let { viewModel.pasteText(it) }
                }
            )
        }
    }
}

// ═══════════════════════ 客户端桥实现 ═══════════════════════

/** 上下文菜单请求（View 触发点坐标 —— 视口局部像素，Popup 定位用）。 */
private data class ContextMenuRequest(
    val items: List<TerminalContextMenuItem>,
    val x: Float,
    val y: Float
)

/**
 * [TerminalViewClient] 的 app 侧适配器：回调 → [TerminalViewModel] 既有方法。
 *
 * 持有少量 Compose 状态（atBottom / 菜单请求 / 修饰锁存）供宿主 Composable
 * 直读直写 —— remember 单例 + 状态对象，杜绝闭包陈旧捕获。生命周期/窗口焦点
 * 双源在 [reportFocus] 合并去重后转 `notifyTerminalFocus`。
 */
private class TerminalViewHostClient(
    private val viewModel: TerminalViewModel,
    private val context: Context
) : TerminalViewClient {

    /** 滚动位置联动（「跳到最新」浮标可见性）。 */
    val atBottom = mutableStateOf(true)

    /** 待展示的上下文菜单（null = 无）。 */
    val menuRequest = mutableStateOf<ContextMenuRequest?>(null)

    /** 工具栏修饰锁存（CTRL/SHIFT/ALT 一次性组合语义）。 */
    val ctrlLatched = mutableStateOf(false)
    val shiftLatched = mutableStateOf(false)
    val altLatched = mutableStateOf(false)

    /** 响铃振动开关（宿主按 settings.vibrateOnBell 同步）。 */
    var bellVibrate = true

    /** 焦点上报去重状态（null = 尚未上报）。 */
    private var lastFocusReported: Boolean? = null

    private val clipboardManager: ClipboardManager? by lazy {
        runCatching {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }.getOrNull()
    }

    /** 统一焦点上报（生命周期源 + View 窗口焦点源 → 去重单流）。 */
    fun reportFocus(gained: Boolean) {
        if (lastFocusReported == gained) return
        lastFocusReported = gained
        viewModel.notifyTerminalFocus(gained)
    }

    override fun onTerminalWrite(text: String) {
        // CTRL/ALT 锁存对 IME 单字母生效（与旧渲染器 deliver() 同款一次性语义）
        if (ctrlLatched.value && text.length == 1 && text[0].isLetter()) {
            viewModel.sendControlChar(text[0])
            ctrlLatched.value = false
            return
        }
        if (altLatched.value && text.length == 1) {
            viewModel.sendInput("\u001B$text")
            altLatched.value = false
            return
        }
        viewModel.sendInput(text)
    }

    override fun onTerminalKey(key: VtKey, mods: Int) {
        // emulator 枚举（UP/DOWN/…）→ 平台枚举（ARROW_UP/…）；同名键
        //（HOME/END/PAGE_*/INSERT/DELETE/ENTER/TAB/BACKSPACE/F1-12）直查。
        val uiKey: UiKey? = when (key) {
            VtKey.UP -> UiKey.ARROW_UP
            VtKey.DOWN -> UiKey.ARROW_DOWN
            VtKey.LEFT -> UiKey.ARROW_LEFT
            VtKey.RIGHT -> UiKey.ARROW_RIGHT
            VtKey.ESCAPE -> UiKey.ESC
            else -> runCatching { UiKey.valueOf(key.name) }.getOrNull()
        }
        if (uiKey != null) {
            viewModel.sendKey(uiKey, mods)
            return
        }
        if (key == VtKey.SPACE) {
            when {
                mods and KeyModifiers.CTRL != 0 -> viewModel.sendControlChar(' ')
                mods and KeyModifiers.ALT != 0 -> viewModel.sendInput("\u001B ")
                else -> viewModel.sendInput(" ")
            }
            return
        }
        numpadKeyCodeOf(key)?.let { viewModel.sendHardwareKey(it, mods) }
    }

    override fun onTerminalControlChar(code: Int) {
        // View 上报控制码本体；VM 的 sendControlChar 吃字母/符号面
        //（KeySequenceEncoder.controlByte 的输入契约）。DEL(0x7F) 特判走
        // BACKSPACE 键路径 —— 行缓冲退格语义与旧 IME 桥一致。
        when (code) {
            0x7F -> viewModel.sendKey(UiKey.BACKSPACE)
            in 1..26 -> viewModel.sendControlChar('a' + (code - 1))
            0x00 -> viewModel.sendControlChar(' ')
            0x1B -> viewModel.sendControlChar('[')
            0x1C -> viewModel.sendControlChar('\\')
            0x1D -> viewModel.sendControlChar(']')
            0x1E -> viewModel.sendControlChar('^')
            0x1F -> viewModel.sendControlChar('_')
            else -> Unit
        }
    }

    override fun onTerminalMouse(
        col: Int,
        row: Int,
        type: TerminalMouseEventType,
        mods: Int,
        mouseMode: MouseTrackingMode,
        released: Boolean,
        button: Int
    ) {
        // 1-based 屏内坐标与 VM/MouseEncoder 契约一致；type 已含 press/release
        // 语义（released 冗余），mouseMode 由 VM 从最新快照重读（更权威）。
        viewModel.sendMouseEvent(type, button, mods, col, row)
    }

    override fun onTerminalFocus(gained: Boolean) {
        reportFocus(gained)
    }

    override fun onTerminalViewSizeChanged(rows: Int, cols: Int, pixelWidth: Int, pixelHeight: Int) {
        viewModel.resizeTerminal(rows, cols)
    }

    override fun onTerminalBell() {
        if (bellVibrate) runCatching { vibrateOnce(context) }
    }

    override fun onTerminalTitle(title: String) {
        // 忽略：VM 的 renderState 收集器已消费 snapshot.title 更新会话标签
        //（此处再转发会双触发 tab 刷新 —— 单一数据源原则）。
    }

    override fun onTerminalClipboardCopy(text: String) {
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        // #234：复制成功反馈（此前完全静默——用户无从确认是否已复制到剪贴板）。
        runCatching {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.term_copy_done, text.length),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onTerminalPasteRequest() {
        val text = clipboardManager?.primaryClip?.getItemAt(0)
            ?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) {
            viewModel.pasteText(text)
        } else {
            // #234：空剪贴板粘贴——旧实现无任何动作（用户以为 app 没响应）。
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.term_clipboard_empty),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onTerminalLinkOpen(uri: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }
    }

    override fun onTerminalScrollChanged(topRow: Int, atBottom: Boolean) {
        this.atBottom.value = atBottom
    }

    override fun onTerminalSelectionChanged(selectedText: String?) {
        // 复制动作经 onTerminalClipboardCopy 走剪贴板 —— 无额外 UI 消费者。
    }

    override fun onTerminalFontSizeChanged(newSp: Float) {
        viewModel.setFontSize(newSp.roundToInt())
    }

    override fun onTerminalContextMenu(items: List<TerminalContextMenuItem>, x: Float, y: Float) {
        menuRequest.value = ContextMenuRequest(items, x, y)
    }
}

// ═══════════════════════ 纯映射辅助 ═══════════════════════

/**
 * 小键盘键身份 → Android keycode（KeyEventMapping 常量镜像）。
 * DECKPAM 应用模式编码由 VM 的 sendHardwareKey 完成；null = 非小键盘。
 */
private fun numpadKeyCodeOf(key: VtKey): Int? = when (key) {
    VtKey.NUMPAD_ENTER -> KeyEventMapping.KEYCODE_NUMPAD_ENTER
    VtKey.NUMPAD_SEPARATOR -> KeyEventMapping.KEYCODE_NUMPAD_COMMA
    VtKey.NUMPAD_DECIMAL -> KeyEventMapping.KEYCODE_NUMPAD_DOT
    VtKey.NUMPAD_ADD -> KeyEventMapping.KEYCODE_NUMPAD_ADD
    VtKey.NUMPAD_SUBTRACT -> KeyEventMapping.KEYCODE_NUMPAD_SUBTRACT
    VtKey.NUMPAD_MULTIPLY -> KeyEventMapping.KEYCODE_NUMPAD_MULTIPLY
    VtKey.NUMPAD_DIVIDE -> KeyEventMapping.KEYCODE_NUMPAD_DIVIDE
    else -> key.name.takeIf { it.startsWith("NUMPAD_") && it.last().isDigit() }
        ?.let { KeyEventMapping.KEYCODE_NUMPAD_0 + (it.last() - '0') }
}

/** app scheme（ARGB Long）→ :terminal-view 调色板（[TerminalPalette.fromSchemeMap] 契约键）。 */
private fun buildPalette(scheme: TerminalColorScheme, boldAsBright: Boolean): TerminalPalette {
    val map = HashMap<Int, Int>(24)
    scheme.ansi.forEachIndexed { i, c -> map[i] = c.toInt() }   // 0..15 ANSI 槽位
    map[SCHEME_KEY_BACKGROUND] = scheme.background.toInt()       // -1
    map[SCHEME_KEY_FOREGROUND] = scheme.foreground.toInt()       // -2
    map[SCHEME_KEY_CURSOR] = scheme.cursor.toInt()               // -3
    map[SCHEME_KEY_SELECTION_BG] = scheme.selectionBackground.toInt() // -4
    scheme.selectionForeground?.let { map[SCHEME_KEY_SELECTION_FG] = it.toInt() } // -6
    map[SCHEME_KEY_BOLD_AS_BRIGHT] = if (boldAsBright) 1 else 0  // -9
    map[SCHEME_KEY_DARK] = if (scheme.dark) 1 else 0             // -10
    // -5 linkColor 未注入 → View 默认 0xFF6BB8FF（scheme 模型无链接色字段）
    return TerminalPalette.fromSchemeMap(map, dark = scheme.dark)
}

/** fromSchemeMap 的语义键（负数区；正数 0..255 为 ANSI/扩展槽位）。 */
private const val SCHEME_KEY_BACKGROUND = -1
private const val SCHEME_KEY_FOREGROUND = -2
private const val SCHEME_KEY_CURSOR = -3
private const val SCHEME_KEY_SELECTION_BG = -4
private const val SCHEME_KEY_SELECTION_FG = -6
private const val SCHEME_KEY_BOLD_AS_BRIGHT = -9
private const val SCHEME_KEY_DARK = -10

/** 单次轻振动（30ms）。失败静默 —— 没有振动硬件/权限不该崩 UI。 */
private fun vibrateOnce(context: Context) {
    val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(
        VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
    )
}

// ═══════════════════════ 浮标 / 菜单文案 ═══════════════════════

/** 「跳到最新」浮标（脱离吸底时出现；点击回到底部）。 */
@Composable
private fun JumpToLatestPill(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(PopupChrome.bg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.term_jump_latest),
            fontSize = 12.sp,
            color = accent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

/** 浮层 chrome（上下文菜单/跳底浮标）：恒深色 mint 控制台底 —— 旧值
 * 0xE6263041 是旧主题蓝灰残留，与 ConsoleTheme 色系冲突（T89 清理）。 */
private object PopupChrome {
    val bg = Color(0xE60F1613)
}

/** 菜单项 id → 本地化文案（未知名回落 View 默认英文 label）。 */
@Composable
private fun localizedMenuLabel(item: TerminalContextMenuItem): String = when (item.id) {
    TerminalContextMenuItem.ID_COPY -> stringResource(R.string.term_copy)
    TerminalContextMenuItem.ID_PASTE -> stringResource(R.string.term_paste)
    TerminalContextMenuItem.ID_SELECT_ALL -> stringResource(R.string.term_select_all)
    TerminalContextMenuItem.ID_CLEAR_SELECTION -> stringResource(R.string.term_clear_selection)
    TerminalContextMenuItem.ID_OPEN_LINK -> stringResource(R.string.term_open_link)
    TerminalContextMenuItem.ID_COPY_LINK -> stringResource(R.string.term_copy_link)
    else -> item.label
}
