package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 终端 grid 渲染器（P83 — Terminal 产品化核心件）。
 *
 * 数据：[TerminalViewModel.renderState]（TerminalRenderSnapshot —— 逐 cell 颜色/属性、
 * 光标、DEC 模式、scrollback）。渲染链（Spec §41 事件驱动）：
 *
 *   PTY → Pump → VT(TerminalCore) → ObservationEngine.styledState →(33ms sample)→ Compose
 *
 * 组件职责（纯渲染，不 fork PTY / 不持会话状态）：
 *  - **Grid**：scrollback + 可见屏逐行 styled 文本（LazyColumn —— 只组合可视行）
 *  - **光标**：DECTCEM 可见时绘制闪烁 beam；x 按行内 cell 宽度步进（CJK 2 列对齐）
 *  - **滚动**：跟随输出自动吸底；用户上滚即脱离，出现“跳到最新”浮标
 *  - **选择/复制**：长按起选，拖动扩选（cell 级），复制入系统剪贴板
 *  - **输入**：隐藏 BasicTextField 捕获 IME（增量 diff → RAW，组合期间等待提交）；
 *    硬件键盘经 onPreviewKeyEvent 映射（Ctrl+字母 / 箭头 / Home…）
 *  - **Resize**：视图尺寸 → PTY rows/cols（SIGWINCH）
 *  - **特殊键工具栏**：ESC/TAB/CTRL 锁存/方向/Home/End/PgUp/PgDn/CTRL+C/D/Z/粘贴
 */
@Composable
fun TerminalRenderer(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val render by viewModel.renderState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    TerminalGrid(
        render = render,
        fontSize = settings.fontSize,
        monochrome = settings.monochrome,
        showKeybar = settings.showKeybar,
        onText = viewModel::sendInput,
        onKey = viewModel::sendKey,
        onControl = viewModel::sendControlChar,
        onPaste = viewModel::pasteText,
        onResize = viewModel::resizeTerminal,
        modifier = modifier
    )
}

/** 终端主题（深色底自含调色 —— 终端内容不受 app 主题影响）。 */
private object TerminalTheme {
    val background = Color(0xFF14161A)
    val foreground = Color(0xFFD4D7DE)
    val selection = Color(0x664C8DFF)
    val cursor = Color(0xFF9CC3FF)
    val toolbarBg = Color(0xFF1B1F26)
    val toolbarKey = Color(0xFF232A35)
}

/** cell 级选择区间（行/列；列区间左闭右开，含 from 至 to 前一列）。 */
private data class SelRange(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

@Composable
fun TerminalGrid(
    render: TerminalRenderSnapshot?,
    fontSize: Int,
    monochrome: Boolean,
    showKeybar: Boolean = true,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onPaste: (String) -> Unit,
    onResize: (rows: Int, cols: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── 字体度量（monospace）：探测字符宽 + 1.25×行高 ──
    val baseStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
    val textMeasurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSize) {
        val probe = textMeasurer.measure("0".repeat(10), baseStyle)
        (probe.size.width / 10f).coerceAtLeast(1f)
    }
    val lineHeightSp = remember(fontSize) { (fontSize * 1.25f).sp }
    val lineHeightPx = with(density) { lineHeightSp.toPx() }
    val lineHeightDp = with(density) { lineHeightSp.toDp() }

    // 全量行 = scrollback（旧→新）+ 可见屏
    val allRows: List<List<RenderCell>> = remember(render) {
        if (render == null) emptyList() else render.scrollback + render.lines
    }
    val totalRows = allRows.size
    // rememberUpdatedState 让手势闭包读到最新行内容（避免 pointerInput 陈旧捕获）
    val rowsState = rememberUpdatedState(allRows)

    // ── 滚动：跟随输出吸底；用户上滚即脱离 ──
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (!canForward) follow = true
                else if (scrolling) follow = false
            }
    }
    LaunchedEffect(totalRows) {
        if (follow && totalRows > 0) listState.scrollToItem(totalRows - 1)
    }

    // ── 选择状态（cell 级；anchor=起点，head=终点）──
    var selectionAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectionHead by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val selectionActive = selectionAnchor != null && selectionHead != null

    // ── CTRL 锁存（下一次字母输入转控制码）──
    var ctrlLatched by remember { mutableStateOf(false) }

    // ── IME 隐藏桥 + 焦点 ──
    val focusRequester = remember { FocusRequester() }
    var imeBuffer by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()

    /**
     * 拉起输入法：聚焦隐藏 IME 桥 + 显式 `show()`。
     *
     * 只调 `requestFocus()` 在部分设备/输入法上不会弹键盘（焦点到了但 IME 没被请求显示），
     * 因此这里显式补一次 show()。失败不能炸 UI —— `runCatching` 兜住未挂载等时序异常。
     */
    fun showKeyboard() {
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    // 会话就绪（首次拿到渲染快照）后自动聚焦 IME 桥：进入终端即可直接敲命令，
    // 不必"先点一下碰运气"。仅聚焦一次，避免与用户主动隐藏键盘反复打架。
    LaunchedEffect(render != null) {
        if (render != null) runCatching { focusRequester.requestFocus() }
    }

    // 指针 → 行列（滚动偏移 + 行内 cell 宽度步进 —— CJK 对齐）
    fun cellAt(offset: Offset): Pair<Int, Int>? {
        val rows = rowsState.value
        if (rows.isEmpty()) return null
        val contentY = offset.y + listState.firstVisibleItemIndex * lineHeightPx +
            listState.firstVisibleItemScrollOffset
        val row = (contentY / lineHeightPx).toInt().coerceIn(0, rows.size - 1)
        val cells = rows[row]
        var px = 0f
        var col = 0
        while (col < cells.size && px < offset.x) {
            px += if (cells[col].flags and RenderCell.FLAG_WIDE != 0) charWidthPx * 2 else charWidthPx
            col++
        }
        return row to col
    }

    fun selRange(): SelRange? {
        val a = selectionAnchor ?: return null
        val h = selectionHead ?: return null
        return if (a.first < h.first || (a.first == h.first && a.second <= h.second)) {
            SelRange(a.first, a.second, h.first, h.second)
        } else {
            SelRange(h.first, h.second, a.first, a.second)
        }
    }

    fun selectedText(): String {
        val range = selRange() ?: return ""
        val rows = rowsState.value
        val builder = StringBuilder()
        for (r in range.startRow..range.endRow) {
            val cells = rows.getOrNull(r) ?: continue
            val from = if (r == range.startRow) range.startCol else 0
            val to = if (r == range.endRow) range.endCol else cells.size
            for (c in from until minOf(to, cells.size)) builder.append(cells[c].text)
            if (r != range.endRow) builder.append('\n')
        }
        return builder.toString()
    }

    // ── 硬件键盘（preview 优先消费；支持长按重复）──
    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val kc = event.nativeKeyEvent.keyCode
        if (event.isCtrlPressed && kc in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
            onControl('a' + (kc - android.view.KeyEvent.KEYCODE_A))
            return true
        }
        return when (event.key) {
            Key.Enter -> { onKey(TerminalKey.ENTER); true }
            Key.Backspace -> { onKey(TerminalKey.BACKSPACE); true }
            Key.Tab -> { onKey(TerminalKey.TAB); true }
            Key.Escape -> { onKey(TerminalKey.ESC); true }
            Key.DirectionUp -> { onKey(TerminalKey.ARROW_UP); true }
            Key.DirectionDown -> { onKey(TerminalKey.ARROW_DOWN); true }
            Key.DirectionLeft -> { onKey(TerminalKey.ARROW_LEFT); true }
            Key.DirectionRight -> { onKey(TerminalKey.ARROW_RIGHT); true }
            Key.MoveHome -> { onKey(TerminalKey.HOME); true }
            Key.MoveEnd -> { onKey(TerminalKey.END); true }
            Key.PageUp -> { onKey(TerminalKey.PAGE_UP); true }
            Key.PageDown -> { onKey(TerminalKey.PAGE_DOWN); true }
            Key.Delete -> { onKey(TerminalKey.DELETE); true }
            else -> false
        }
    }

    // ── Resize：视图尺寸 → rows/cols（与当前 PTY 尺寸不同才发）──
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val currentRows = render?.rows ?: 0
    val currentCols = render?.cols ?: 0
    LaunchedEffect(viewSize, charWidthPx, lineHeightPx, currentRows, currentCols) {
        if (render == null || viewSize == IntSize.Zero) return@LaunchedEffect
        val rows = (viewSize.height / lineHeightPx).toInt().coerceIn(2, 512)
        val cols = (viewSize.width / charWidthPx).toInt().coerceIn(4, 500)
        if (rows != currentRows || cols != currentCols) onResize(rows, cols)
    }

    Column(modifier = modifier.fillMaxSize().background(TerminalTheme.background)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .onPreviewKeyEvent { handleHardwareKey(it) }
                // 点击整个终端区域（含"终端未启动"占位）都拉起输入法 —— 旧实现只挂在
                // LazyColumn 上，会话未启动 / 无输出时点哪都没反应。
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (selectionActive) {
                                selectionAnchor = null; selectionHead = null
                            }
                            showKeyboard()
                        }
                    )
                }
        ) {
            if (render == null || totalRows == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "终端未启动",
                        color = Color(0xFF5A6270),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                // ── 输出 grid（只组合可视行；tap=聚焦/清除选择；长按起选+拖动扩选）──
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 点击聚焦已上移到外层 Box（覆盖"终端未启动"等无输出场景）
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    cellAt(offset)?.let { selectionAnchor = it; selectionHead = it }
                                },
                                onDrag = { change, _ ->
                                    cellAt(change.position)?.let { selectionHead = it }
                                },
                                onDragEnd = { /* 保留选择直至点击/取消 */ }
                            )
                        }
                ) {
                    items(totalRows) { index ->
                        TerminalRow(
                            cells = allRows[index],
                            baseStyle = baseStyle,
                            lineHeightDp = lineHeightDp,
                            monochrome = monochrome
                        )
                    }
                }

                // ── 选择高亮（可视行 → 视口坐标）──
                if (selectionActive) {
                    SelectionOverlay(
                        listState = listState,
                        rowsState = rowsState,
                        selRange = selRange(),
                        charWidthPx = charWidthPx,
                        lineHeightPx = lineHeightPx
                    )
                }

                // ── 光标（闪烁 beam；x 按行内 cell 宽度步进）──
                if (render.cursorVisible && !selectionActive) {
                    CursorOverlay(
                        listState = listState,
                        render = render,
                        charWidthPx = charWidthPx,
                        lineHeightPx = lineHeightPx
                    )
                }

                // ── 复制 / 取消浮标 ──
                if (selectionActive) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .background(Color(0xFF263041), RoundedCornerShape(10.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(selectedText()))
                            selectionAnchor = null; selectionHead = null
                        }) { Text("复制", fontSize = 12.sp) }
                        TextButton(onClick = {
                            selectionAnchor = null; selectionHead = null
                        }) { Text("取消", fontSize = 12.sp, color = Color(0xFF8A93A3)) }
                    }
                }

                // ── 跳到最新浮标（脱离吸底时）──
                if (!follow && totalRows > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .background(Color(0xE6263041), RoundedCornerShape(14.dp))
                            .clickable {
                                follow = true
                                scope.launch { listState.scrollToItem(totalRows - 1) }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "↓ 跳到最新",
                            fontSize = 12.sp,
                            color = TerminalTheme.cursor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // ── 隐藏 IME 桥（增量 diff → RAW；组合期间等待提交；删除 → Backspace）──
            BasicTextField(
                value = imeBuffer,
                onValueChange = { new ->
                    if (new.composition != null) {
                        imeBuffer = new
                        return@BasicTextField
                    }
                    val old = imeBuffer.text
                    if (new.text.length < old.length) {
                        repeat(old.length - new.text.length) { onKey(TerminalKey.BACKSPACE) }
                    } else if (new.text.length > old.length && new.text.startsWith(old)) {
                        val inserted = new.text.substring(old.length)
                        if (ctrlLatched && inserted.length == 1 && inserted[0].isLetter()) {
                            onControl(inserted[0])
                            ctrlLatched = false
                        } else {
                            onText(inserted.replace('\n', '\r'))
                        }
                    }
                    imeBuffer = TextFieldValue("", TextRange(0))
                },
                textStyle = baseStyle.copy(color = Color.Transparent),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(1.dp)
                    .height(1.dp)
                    .focusRequester(focusRequester)
            )
        }

        // ── 特殊键工具栏（触屏必备；横向滚动；可在终端设置中隐藏换显示区）──
        if (showKeybar) {
            KeyToolbar(
                ctrlActive = ctrlLatched,
                onCtrlToggle = { ctrlLatched = !ctrlLatched },
                onKey = onKey,
                onControl = onControl,
                onShowKeyboard = ::showKeyboard,
                onPaste = {
                    clipboard.getText()?.text?.let { onPaste(it) }
                }
            )
        }
    }
}

// ═══════════════════════ 行渲染 ═══════════════════════

@Composable
private fun TerminalRow(
    cells: List<RenderCell>,
    baseStyle: TextStyle,
    lineHeightDp: Dp,
    monochrome: Boolean
) {
    val annotated = remember(cells, monochrome, baseStyle.fontSize) { buildRowAnnotated(cells, monochrome) }
    BasicText(
        text = annotated,
        style = baseStyle,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .height(lineHeightDp)
    )
}

/** 逐 cell 构建 AnnotatedString：同 style 连续段合并；hidden → 等宽空格。 */
private fun buildRowAnnotated(cells: List<RenderCell>, monochrome: Boolean): AnnotatedString {
    if (cells.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        var i = 0
        while (i < cells.size) {
            var j = i
            while (j < cells.size && sameStyle(cells[i], cells[j])) j++
            val builder = StringBuilder()
            for (k in i until j) {
                val cell = cells[k]
                if (cell.flags and RenderCell.FLAG_HIDDEN != 0) builder.append(' ')
                else builder.append(cell.text)
            }
            append(builder.toString())
            spanStyleFor(cells[i], monochrome)?.let { addStyle(it, i, i + (j - i)) }
            i = j
        }
    }
}

/** 两 cell 的可合并渲染样式是否一致（文本内容不参与）。 */
private fun sameStyle(a: RenderCell, b: RenderCell): Boolean =
    a.fg == b.fg && a.bg == b.bg && a.flags == b.flags

/** RenderCell → SpanStyle；monochrome 忽略颜色（保留字形/下划线语义）。 */
private fun spanStyleFor(cell: RenderCell, monochrome: Boolean): SpanStyle? {
    val inverse = cell.flags and RenderCell.FLAG_INVERSE != 0
    val bold = cell.flags and RenderCell.FLAG_BOLD != 0
    val dim = cell.flags and RenderCell.FLAG_DIM != 0
    val italic = cell.flags and RenderCell.FLAG_ITALIC != 0
    val underline = cell.flags and RenderCell.FLAG_UNDERLINE != 0
    val strike = cell.flags and RenderCell.FLAG_STRIKE != 0

    var fg: Color? = null
    var bg: Color? = null
    if (!monochrome) {
        val rawFg = if (cell.fg != 0L) Color(cell.fg.toInt()) else null
        val rawBg = if (cell.bg != 0L) Color(cell.bg.toInt()) else null
        if (inverse) {
            // 反显：fg↔bg 交换；双默认反显 = 亮底深字
            fg = rawBg ?: TerminalTheme.background
            bg = rawFg ?: TerminalTheme.foreground
        } else {
            fg = rawFg
            bg = rawBg
        }
        if (dim && fg != null) fg = fg.copy(alpha = 0.55f)
    }
    if (fg == null && bg == null && !bold && !italic && !underline && !strike) return null
    return SpanStyle(
        color = fg ?: TerminalTheme.foreground,
        background = bg ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = when {
            underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            underline -> TextDecoration.Underline
            strike -> TextDecoration.LineThrough
            else -> null
        }
    )
}

// ═══════════════════════ 光标 / 选择 overlay ═══════════════════════

@Composable
private fun CursorOverlay(
    listState: LazyListState,
    render: TerminalRenderSnapshot,
    charWidthPx: Float,
    lineHeightPx: Float
) {
    val itemIndex = render.scrollback.size + render.cursorRow
    val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        ?: return
    // x：行内按 cell 宽度步进（宽字符 2 列），越界尾部按 1 列步进
    val rowCells = render.lines.getOrNull(render.cursorRow) ?: emptyList()
    var x = 0f
    var col = 0
    while (col < render.cursorCol && col < rowCells.size) {
        x += if (rowCells[col].flags and RenderCell.FLAG_WIDE != 0) charWidthPx * 2 else charWidthPx
        col++
    }
    if (render.cursorCol > rowCells.size) x += (render.cursorCol - rowCells.size) * charWidthPx

    val transition = rememberInfiniteTransition(label = "cursor-blink")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "cursor-alpha"
    )
    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), visible.offset) }
            .width(2.dp)
            .height(with(LocalDensity.current) { (lineHeightPx * 0.86f).toDp() })
            .background(TerminalTheme.cursor.copy(alpha = 0.9f * alpha))
    )
}

@Composable
private fun SelectionOverlay(
    listState: LazyListState,
    rowsState: State<List<List<RenderCell>>>,
    selRange: SelRange?,
    charWidthPx: Float,
    lineHeightPx: Float
) {
    val range = selRange ?: return
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (info in listState.layoutInfo.visibleItemsInfo) {
            val r = info.index
            if (r < range.startRow || r > range.endRow) continue
            val cells = rowsState.value.getOrNull(r) ?: continue
            val from = if (r == range.startRow) range.startCol else 0
            val to = if (r == range.endRow) range.endCol else cells.size
            if (to <= from) continue
            val x0 = columnX(cells, from, charWidthPx)
            val x1 = columnX(cells, to, charWidthPx)
            drawRect(
                color = TerminalTheme.selection,
                topLeft = Offset(x0, info.offset.toFloat()),
                size = Size(x1 - x0, lineHeightPx * 0.96f)
            )
        }
    }
}

/** 行内列号 → 像素 x（宽字符 2 列步进；越界按 1 列）。 */
private fun columnX(cells: List<RenderCell>, col: Int, charWidthPx: Float): Float {
    var x = 0f
    var i = 0
    while (i < col && i < cells.size) {
        x += if (cells[i].flags and RenderCell.FLAG_WIDE != 0) charWidthPx * 2 else charWidthPx
        i++
    }
    if (col > cells.size) x += (col - cells.size) * charWidthPx
    return x
}

// ═══════════════════════ 特殊键工具栏 ═══════════════════════

@Composable
private fun KeyToolbar(
    ctrlActive: Boolean,
    onCtrlToggle: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onShowKeyboard: () -> Unit,
    onPaste: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalTheme.toolbarBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 显式拉起输入法：触屏上"点一下没反应"的兜底入口
        ToolbarKey("⌨") { onShowKeyboard() }
        ToolbarKey("ESC") { onKey(TerminalKey.ESC) }
        ToolbarKey("TAB") { onKey(TerminalKey.TAB) }
        ToolbarKey(
            label = "CTRL",
            highlighted = ctrlActive,
            onClick = onCtrlToggle
        )
        ToolbarKey("↑") { onKey(TerminalKey.ARROW_UP) }
        ToolbarKey("↓") { onKey(TerminalKey.ARROW_DOWN) }
        ToolbarKey("←") { onKey(TerminalKey.ARROW_LEFT) }
        ToolbarKey("→") { onKey(TerminalKey.ARROW_RIGHT) }
        ToolbarKey("HOME") { onKey(TerminalKey.HOME) }
        ToolbarKey("END") { onKey(TerminalKey.END) }
        ToolbarKey("PGUP") { onKey(TerminalKey.PAGE_UP) }
        ToolbarKey("PGDN") { onKey(TerminalKey.PAGE_DOWN) }
        ToolbarKey("^C") { onControl('c') }
        ToolbarKey("^D") { onControl('d') }
        ToolbarKey("^Z") { onControl('z') }
        ToolbarKey("^L") { onControl('l') }
        ToolbarKey("粘贴") { onPaste() }
    }
}

@Composable
private fun ToolbarKey(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (highlighted) Color(0xFF4C8DFF) else TerminalTheme.toolbarKey,
                RoundedCornerShape(7.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (highlighted) Color.White else Color(0xFFAAB3C2)
        )
    }
}
