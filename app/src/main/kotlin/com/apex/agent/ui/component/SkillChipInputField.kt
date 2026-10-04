package com.apex.agent.ui.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apex.agent.R
import com.apex.agent.ui.screen.agent.PendingPipelineCommand

/**
 * ═══ 技能 chip 输入框（EditText + ReplacementSpan 方案）═══
 *
 * 用户从斜杠菜单选中的 Skill / MCP / 连接器 / 插件不再挂输入栏上方的独立
 * 胶囊行（行高突变 + 与输入框视觉重叠），而是以**行内 chip** 直接追加到
 * 输入框文字后面：
 *
 * ```
 * ┌────────────────────────────────────────┐
 * │ 帮我查一下这个订单 [网页搜索] [物流]▏   │
 * └────────────────────────────────────────┘
 * ```
 *
 * ## 实现选型（用户指定方案）
 *
 * 纯 Compose `BasicTextField` 无法把可删除 chip 内联进可编辑文本（无
 * Spannable 对等物）；本组件走 **AndroidView + EditText +
 * SpannableStringBuilder + [SkillChipSpan]（[ReplacementSpan]）**：
 * - 每个 chip 在 buffer 里只占一个 `\uFFFC`（OBJECT REPLACEMENT CHARACTER）
 *   字符 —— **退格天然删除整个 chip**，无需按键拦截；
 * - chip 的圆角底 + 居中文字由 span 自绘（[ReplacementSpan.getSize] 定宽 /
 *   draw 绘制）；
 * - **点击 chip 删除**：触摸监听按 offset 命中 span 区间（touchSlop 内的
 *   抬起才算点按，滚动/长按拖选不误删）；
 * - **多选**：依次追加到文字后面，按 `type:id` 自动去重；
 * - **发送时分离**：纯文本（剥 \uFFFC + 收敛 chip 插入产生的连续空格，
 *   保留用户换行）与 chip 集合各自上报 —— ViewModel 拿到的是
 *   `text + List<PendingPipelineCommand>` 结构化两通道。
 *
 * ## 状态同步（单向数据流 + 回声抑制）
 *
 * - 组合态：`value`（纯文本草稿，VM SavedStateHandle）+ `chips`（VM 列表）；
 * - View 态：EditText buffer（文字 + chip span 混排）；
 * - View → 状态：TextWatcher 每次变更后提取纯文本/chip 集合，与最近一次
 *   上报值不同才上报（镜像存普通字段而非快照状态 —— 避免 update lambda
 *   反向写快照引发重组回环）；自己按键的回声不回写（沿用
 *   AdaptiveInputField（已由本组件取代） 的回声抑制纪律，保住中文组合段）；
 * - 状态 → View：update lambda 同步 —— 外部文本变更（草稿恢复/发送清空/
 *   全屏编辑确认）才 `setText`（光标尽量保留）；chip 集合差异增删 span
 *   （新增追加到文字后面、消失的按区间删除）。factory 只跑一次，回调经
 *   [ChipCallbacks] holder 转发，update 时刷新引用 —— 规避 AndroidView
 *   闭包过期经典坑。
 *
 * ## 键盘可靠性（移植自 AdaptiveInputField（已由本组件取代） 的两轮 P0 修复）
 *
 * - 焦点从无到有 → 显式 `showSoftInput`（部分设备/输入法焦点到位但 IME
 *   不弹）；
 * - 按下（ACTION_DOWN）→ 提前 show（不消费事件，早于焦点建立）；
 * - 两者同时 `requestApplyInsets()`（弹层关闭后 stale IME insets 的标准
 *   对策，防止输入栏不上抬）。
 *
 * ## 保留能力（对齐 AdaptiveInputField（已由本组件取代））
 *
 * 字符计数（>200 显示）、全屏分层编辑对话框、发送键行为可配置
 * （"send" 回车直发 / "newline" 回车仅换行）、自动增高 1→5 行。
 *
 * @param value 纯文本草稿（不含 \uFFFC）
 * @param onValueChange 纯文本变更（已剥离 chip 字符）
 * @param chips 当前挂载的 chip 列表（顺序 = 追加顺序）
 * @param onChipsChange chip 集合变更（退格/点击删除/外部清空）
 * @param placeholder 占位文本（空草稿时的 hint）
 * @param onSend IME「发送」动作回调（仅 sendKeyBehavior == "send" 挂接）
 * @param sendKeyBehavior "send" → 回车直接发送；"newline" → 回车仅换行
 */
@Composable
fun SkillChipInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    chips: List<PendingPipelineCommand> = emptyList(),
    onChipsChange: (List<PendingPipelineCommand>) -> Unit = {},
    placeholder: String = "",
    onSend: () -> Unit = {},
    sendKeyBehavior: String = "send"
) {
    // ── 焦点态（View 监听器写入 → 焦点背景动画）──
    var isFocused by remember { mutableStateOf(false) }

    // factory 只跑一次 → 回调经 holder 转发（update 时刷新引用）。
    // lastReportedText：View 侧最近上报的纯文本 —— 回声抑制锚点：用户按键的
    // 回声与它相同 → update 不 setText（保住光标/组合段）；不同 = 外部变更
    // （草稿恢复/发送清空/全屏编辑确认/斜杠回填）才回写 buffer。
    val callbacks = remember { ChipCallbacks() }
    callbacks.onValueChange = onValueChange
    callbacks.onChipsChange = onChipsChange
    callbacks.onFocusChange = { isFocused = it }
    callbacks.onSend = onSend
    callbacks.sendKeyBehavior = sendKeyBehavior

    // ── 主题快照（重组时刷新进 View 与 span）──
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurface.toArgb()
    val hintColor = scheme.onSurfaceVariant.toArgb()
    val chipBg = scheme.primaryContainer.toArgb()
    val chipFg = scheme.onPrimaryContainer.toArgb()
    // update lambda 非组合域 —— density 在组合域捕获后传入
    val density = LocalDensity.current

    val fieldBackground by animateColorAsState(
        targetValue = if (isFocused)
            scheme.primary.copy(alpha = 0.04f)
        else
            Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "chip_field_focus_background"
    )

    var isFullscreen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .background(fieldBackground, RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart),
                factory = { context -> createChipEditText(context, callbacks) },
                update = { editText ->
                    // 1) 主题刷新（日夜间切换/重组）
                    editText.setTextColor(textColor)
                    editText.setHintTextColor(hintColor)
                    if (editText.hint?.toString() != placeholder) editText.hint = placeholder
                    // 正文 16sp（与 bodyLarge 对齐）；px 比对避免每键重排版
                    val targetTextPx = with(density) { 16.dp.toPx() }
                    if (editText.textSize != targetTextPx) editText.textSize = 16f

                    // 1b) 发送键行为：send → 回车直发（无换行插入）；newline →
                    //     回车插入换行（MULTI_LINE flag）。变更才写，避免重置 IME。
                    val multiline = sendKeyBehavior == "newline"
                    val targetInputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                    if (editText.inputType != targetInputType) editText.inputType = targetInputType
                    val targetIme = (editText.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or
                        (if (multiline) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_SEND)
                    if (editText.imeOptions != targetIme) editText.imeOptions = targetIme

                    // 2) 文本同步：外部变更才 setText（本地按键回声不动 buffer）
                    if (value != callbacks.lastReportedText) {
                        val preserved = editText.selectionStart
                        editText.setText(value)
                        val newLen = editText.text?.length ?: 0
                        editText.setSelection(preserved.coerceIn(0, newLen))
                        callbacks.lastReportedText = value
                        // setText 替换整个 buffer，全部 span 已失 —— 第 3 步整体重建
                    }

                    // 3) chip 集合同步：diff 现有 span vs 期望集合（幂等，无需镜像）
                    syncChips(editText, chips, chipBg, chipFg)
                }
            )
        }

        // ── 长文本字符计数（>200 显示，对齐 AdaptiveInputField）──
        AnimatedVisibility(
            visible = value.length > 200,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = "${value.length}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (value.length > 1000) scheme.error else scheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 2.dp)
            )
        }

        // ── 全屏分层编辑按钮（常显入口）──
        IconButton(
            onClick = { isFullscreen = true },
            // UI-012：48dp 触区红线（原 32dp）
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Icon(
                Icons.Default.Fullscreen,
                contentDescription = stringResource(R.string.detailed_input),
                modifier = Modifier.size(18.dp),
                tint = scheme.onSurfaceVariant
            )
        }
    }

    // ── 全屏分层编辑对话框（确认 → 外部文本变更 → update setText）──
    if (isFullscreen) {
        ChipFieldFullscreenEditor(
            initialValue = value,
            onDismiss = { isFullscreen = false },
            onConfirm = { newText ->
                onValueChange(newText)
                isFullscreen = false
            }
        )
    }
}

/** 回调 holder：AndroidView factory 闭包只执行一次，引用经组合体刷新。 */
private class ChipCallbacks {
    var onValueChange: (String) -> Unit = {}
    var onChipsChange: (List<PendingPipelineCommand>) -> Unit = {}
    var onFocusChange: (Boolean) -> Unit = {}
    var onSend: () -> Unit = {}
    var sendKeyBehavior: String = "send"

    /** View 侧最近上报的纯文本（回声抑制锚点，update 与 watcher 双向维护）。 */
    var lastReportedText: String = ""
}

/**
 * 输入框内技能 chip 的 [ReplacementSpan] —— buffer 中占位一个 `\uFFFC`
 * 字符，自绘圆角胶囊底 + 居中文字（用户参考实现同构）。
 *
 * @param key 去重键：`type:id`
 * @param label 完整展示名（上报 VM 时保留原文，不受截断影响）
 * @param displayLabel 绘制用标签（超长截断 + 省略号）
 */
private class SkillChipSpan(
    val key: String,
    val type: String,
    val id: String,
    val label: String,
    val displayLabel: String,
    var bgColor: Int,
    var fgColor: Int,
    private val horizontalPaddingPx: Float
) : ReplacementSpan() {

    /** chip 内文字略小于正文，视觉更像标签而非正文。 */
    private val labelSizeRatio = 0.92f

    private fun chipWidth(paint: Paint): Float {
        val saved = paint.textSize
        paint.textSize = saved * labelSizeRatio
        val width = paint.measureText(displayLabel) + horizontalPaddingPx * 2
        paint.textSize = saved
        return width
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = chipWidth(paint).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = chipWidth(paint)
        val oldColor = paint.color
        // 胶囊底：chip 高度 = 行高（上下各留 1px 呼吸），全圆角
        val rect = RectF(x, top.toFloat() + 1f, x + width, bottom.toFloat() - 1f)
        paint.color = bgColor
        paint.isAntiAlias = true
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        // 居中文字（chip 纵心 - 字形纵半高）
        paint.color = fgColor
        val savedSize = paint.textSize
        paint.textSize = savedSize * labelSizeRatio
        val textY = (top + bottom) / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(displayLabel, x + horizontalPaddingPx, textY, paint)
        paint.textSize = savedSize
        paint.color = oldColor
    }
}

/** chip 在 buffer 里的占位字符（OBJECT REPLACEMENT CHARACTER）。 */
private const val CHIP_TOKEN = "\uFFFC"

/** 展示标签截断上限（超长技能名不撑爆输入框宽度；上报仍保留全名）。 */
private const val CHIP_LABEL_MAX = 14

/** 提取纯文本：剥 chip 占位字符 + 收敛连续空格（只压空格，保留换行）。 */
private fun plainOf(editable: Editable?): String {
    val raw = editable?.toString() ?: return ""
    val stripped = raw.replace(CHIP_TOKEN, "")
    val collapsed = if (stripped.contains("  ")) stripped.replace(Regex(" {2,}"), " ") else stripped
    return collapsed.trim()
}

/** 提取 chip 集合（按 buffer 出现顺序 + type:id 去重；label 保留全名）。 */
private fun collectChips(editable: Editable?): List<PendingPipelineCommand> {
    if (editable == null) return emptyList()
    return editable.getSpans(0, editable.length, SkillChipSpan::class.java)
        .sortedBy { editable.getSpanStart(it) }
        .map { PendingPipelineCommand(type = it.type, id = it.id, label = it.label) }
        .distinctBy { it.type + ":" + it.id }
}

/**
 * 追加一个 chip 到 buffer 文字后面（用户参考实现语义）：
 * 去重 → 末尾非空白先补空格 → 插入占位字符 + span → 尾随空格 → 光标移末尾。
 */
private fun appendChip(
    editText: EditText,
    command: PendingPipelineCommand,
    chipBg: Int,
    chipFg: Int
) {
    val editable = editText.text ?: return
    val key = command.type + ":" + command.id
    if (editable.getSpans(0, editable.length, SkillChipSpan::class.java).any { it.key == key }) return
    if (editable.isNotEmpty() && !editable.last().isWhitespace()) {
        editable.append(" ")
    }
    val density = editText.resources.displayMetrics.density
    val span = SkillChipSpan(
        key = key,
        type = command.type,
        id = command.id,
        label = command.label,
        displayLabel = if (command.label.length > CHIP_LABEL_MAX) {
            command.label.take(CHIP_LABEL_MAX) + "…"
        } else command.label,
        bgColor = chipBg,
        fgColor = chipFg,
        horizontalPaddingPx = 7f * density
    )
    val chip = SpannableStringBuilder(CHIP_TOKEN)
    chip.setSpan(span, 0, CHIP_TOKEN.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    editable.append(chip)
    editable.append(" ")
    editText.setSelection(editable.length)
}

/** 删除一个 span（连同其相邻的一个空格，避免残留双空格）。 */
private fun deleteSpan(editable: Editable, span: SkillChipSpan) {
    val start = editable.getSpanStart(span)
    val end = editable.getSpanEnd(span)
    if (start < 0 || end < 0) return
    var from = start
    var to = end
    if (from > 0 && editable[from - 1] == ' ') from--
    else if (to < editable.length && editable[to] == ' ') to++
    editable.delete(from, to)
}

/** 状态 → View 的 chip 集合 diff 同步（新增追加、消失删除、主题刷新）。 */
private fun syncChips(editText: EditText, desired: List<PendingPipelineCommand>, chipBg: Int, chipFg: Int) {
    val editable = editText.text ?: return
    val existing = editable.getSpans(0, editable.length, SkillChipSpan::class.java).toList()
    // 主题刷新（日夜间切换）
    existing.forEach {
        it.bgColor = chipBg
        it.fgColor = chipFg
    }
    val desiredKeys = desired.map { it.type + ":" + it.id }.toSet()
    // 消失的删掉
    existing.filter { it.key !in desiredKeys }.forEach { deleteSpan(editable, it) }
    // 缺的补上（保持期望顺序，追加到文字后面）
    val existingKeys = existing.map { it.key }.toSet()
    desired.filter { (it.type + ":" + it.id) !in existingKeys }
        .forEach { appendChip(editText, it, chipBg, chipFg) }
}

/**
 * 组装 EditText：透明背景 + 多行自适应（1→5 行）+ IME 可靠性兜底 +
 * chip 触摸删除（touchSlop 防滚动误删）+ TextWatcher 双通道上报。
 */
private fun createChipEditText(context: Context, callbacks: ChipCallbacks): EditText {
    val editText = EditText(context)
    editText.background = null
    editText.isSingleLine = false
    editText.maxLines = 5
    editText.minLines = 1
    editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    editText.imeOptions = EditorInfo.IME_ACTION_SEND
    editText.setPadding(0, 0, 0, 0)
    editText.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // ── IME 可靠性（两轮 P0 修复移植）：焦点到位 ≠ IME 显示 ──
    editText.setOnFocusChangeListener { v, hasFocus ->
        callbacks.onFocusChange(hasFocus)
        if (hasFocus) {
            showIme(v)
            v.requestApplyInsets()
        }
    }

    // 触摸：按下提前 show（不消费）；抬起在 touchSlop 内且命中 chip → 删除
    val downPos = FloatArray(2)
    editText.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                showIme(v)
                downPos[0] = event.x
                downPos[1] = event.y
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(v.context).scaledTouchSlop * 2
                val withinSlop = kotlin.math.abs(event.x - downPos[0]) <= slop &&
                    kotlin.math.abs(event.y - downPos[1]) <= slop
                if (withinSlop) {
                    val offset = editText.getOffsetForPosition(event.x, event.y)
                    val editable = editText.text
                    if (editable != null && offset >= 0) {
                        val hit = editable.getSpans(0, editable.length, SkillChipSpan::class.java)
                            .firstOrNull { editable.getSpanStart(it) <= offset && offset < editable.getSpanEnd(it) }
                        if (hit != null) {
                            deleteSpan(editable, hit)
                            v.performClick()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
        }
        // 其余一律不消费 —— 焦点/光标/滚动/长按选词归 EditText 原生处理
        false
    }

    // ── IME 发送动作（send 模式；newline 模式回车由系统插入换行）──
    editText.setOnEditorActionListener { _, actionId, keyEvent ->
        val sendAction = actionId == EditorInfo.IME_ACTION_SEND ||
            (keyEvent?.keyCode == KeyEvent.KEYCODE_ENTER &&
                keyEvent.action == KeyEvent.ACTION_UP &&
                callbacks.sendKeyBehavior == "send")
        if (sendAction) {
            callbacks.onSend()
            true
        } else {
            false
        }
    }

    // ── TextWatcher：纯文本 + chip 集合双通道上报（带回声抑制）──
    var lastText = ""
    var lastChips: List<PendingPipelineCommand> = emptyList()
    editText.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            val plain = plainOf(s)
            if (plain != lastText) {
                lastText = plain
                callbacks.lastReportedText = plain
                callbacks.onValueChange(plain)
            }
            val chipsNow = collectChips(s)
            if (chipsNow != lastChips) {
                lastChips = chipsNow
                callbacks.onChipsChange(chipsNow)
            }
        }
    })
    return editText
}

/** 显式拉起输入法（部分设备/输入法仅靠焦点不弹 IME）。 */
private fun showIme(view: View) {
    runCatching {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, 0)
    }
}

// ═══ 全屏分层编辑（与 AdaptiveInputField 的 FullscreenEditorDialog 同款）═══

/** 详细输入（全屏编辑对话框）—— 分层编辑长 prompt / 代码片段。 */
@Composable
internal fun ChipFieldFullscreenEditor(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    Dialog(
        onDismissRequest = { onConfirm(text) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.detailed_input),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${text.length}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = { onConfirm(text) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.chat_cd_close)
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.detailed_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    maxLines = Int.MAX_VALUE,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    ),
                    keyboardActions = KeyboardActions.Default
                )

                Button(
                    onClick = { onConfirm(text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}
