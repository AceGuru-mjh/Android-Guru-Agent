package com.apex.agent.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import com.apex.agent.R

/**
 * 自适应输入框。
 *
 * 特性：
 * 1. 根据内容自动扩展行数（1 → 最大 5 行）；
 * 2. 长文本（>200 字符）时显示字符计数；
 * 3. 详细输入按钮（常显，分层编辑长 prompt / 代码片段的入口）；
 * 4. 全屏模式支持 IME action 完成；
 *    发送键行为可配置：sendKeyBehavior = "newline" 时回车仅换行，发送交给按钮。
 *
 * ## 修复：点击输入框不弹输入法
 *
 * 旧实现在 `OutlinedTextField` 上挂了 `.combinedClickable(onClick = {}, onDoubleClick = …)`：
 * 点击手势会被 clickable **消费**，事件永远传不到内部的文本输入节点 —— 文本框拿不到
 * 焦点，`InputConnection` 不建立，**输入法永远不弹出**，光标也无法定位。
 * 一句话：为了一个双击手势，把文本输入最基本的能力弄没了。
 *
 * 现在彻底移除该 clickable，交回文本框原生点击处理（聚焦 + 弹键盘 + 定位光标）；
 * "双击进全屏"改为常显的全屏按钮（文本框内双击本就是选中单词的标准手势，
 * 抢占它会误伤选词）。
 *
 * ## 修复：部分设备点击后键盘不弹（P0，与主线程卡死并列的键盘两大根因之二）
 *
 * 终端页 [com.apex.agent.ui.screen.terminal.TerminalRenderer] 已验证：**只靠获得焦点
 * 在部分设备/输入法上不会拉起 IME**（焦点到位但 IME 未被请求显示），必须显式
 * `keyboardController.show()`。聊天页此前完全没有这层兑底。现在：焦点从无到有
 * （用户点击 / 程序请求）即显式 show() —— 不挂 pointerInput/clickable，避免重蹈
 * 上方 KDoc 记载的「手势吃掉点击」旧 bug。
 *
 * @param value 输入文本
 * @param onValueChange 文本变化回调
 * @param modifier 外部 Modifier
 * @param placeholder 占位文本
 * @param focusRequester 焦点请求器（可选，外部用于自动聚焦）
 * @param onSend IME「发送」动作回调（仅 sendKeyBehavior == "send" 时挂接）
 * @param sendKeyBehavior 发送键行为："send" → 回车直接发送；"newline" → 回车仅换行
 */
@Composable
fun AdaptiveInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { Text("输入指令...") },
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSend: () -> Unit = {},
    sendKeyBehavior: String = "send"
) {
    var isFullscreen by remember { mutableStateOf(false) }

    // 根据内容自动计算行数：内容行数 coerce 到 1-5 行（超出 5 行由 maxLines=5 内部滚动）。
    // P3-j（6-c）：修正注释——实现为 coerceIn(1, 5)，与旧注释"6-12 行展开"不符（选改注释，最小风险）。
    // newline 模式下固定允许 5 行：maxLines=1 会吞掉回车插入的换行符，
    // 导致「换行行为」永远无法生效（内容进不了多行态）。
    val effectiveMaxLines = if (sendKeyBehavior == "newline") 5 else remember(value) {
        val lineCount = value.count { it == '\n' } + 1
        lineCount.coerceIn(1, 5)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // ── P0 键盘兑底：焦点到位 ≠ IME 显示（部分设备/输入法），显式补一次 show()。──
    // 触发条件严格限定 false→true（避免隐藏键盘后又被动弹出）；失败静默
    //（controller 未挂载等时序异常不应炸 UI）。与 TerminalRenderer.showKeyboard 同款。
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isFocused) {
        if (isFocused) keyboardController?.show()
    }
    // ── Press 提前 show（不消费事件，安全）：按下瞬间即请求 IME，早于焦点建立 ──
    // 覆盖「点击后焦点到位但 IME 迟迟不弹」的设备；与上方 isFocused 兑底双保险。
    // 注意：不加 clickable / pointerInput（历史教训 c649934：父级手势会吃掉 TextField 点击）。
    LaunchedEffect(Unit) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) keyboardController?.show()
        }
    }
    val fieldBackground by animateColorAsState(
        targetValue = if (isFocused)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
        else
            Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "field_focus_background"
    )

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            interactionSource = interactionSource,
            // 不再叠加 clickable —— 交回文本框原生点击处理（聚焦 / 弹输入法 / 定位光标）
            modifier = Modifier
                .fillMaxWidth()
                .background(fieldBackground, RoundedCornerShape(8.dp))
                .focusRequester(focusRequester),
            placeholder = placeholder,
            maxLines = effectiveMaxLines,
            minLines = 1,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                // 发送键行为：send → IME「发送」直接发出；newline → 回车插入换行不触发发送。
                // 注：Compose 无 ImeAction.Newline —— 多行回车键由 ImeAction.Default 呈现。
                imeAction = if (sendKeyBehavior == "newline") ImeAction.Default else ImeAction.Send
            ),
            keyboardActions = if (sendKeyBehavior == "newline") KeyboardActions.Default
            else KeyboardActions(onSend = { onSend() }),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 长文本字符计数
                    AnimatedVisibility(
                        visible = value.length > 200,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = "${value.length}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (value.length > 1000)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    // 详细输入按钮（常显，分层编辑入口 —— 原「双击进全屏」手势已移除，见类 KDoc）
                    IconButton(
                        onClick = { isFullscreen = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.detailed_input),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )

        // 全屏编辑对话框（详细输入：分层编辑长文本）
        if (isFullscreen) {
            FullscreenEditorDialog(
                initialValue = value,
                onDismiss = { isFullscreen = false },
                onConfirm = { newText ->
                    onValueChange(newText)
                    isFullscreen = false
                }
            )
        }
    }
}

/**
 * 详细输入（全屏编辑对话框）。
 *
 * - 占满整个屏幕，适合分层编辑长 prompt / 代码片段；
 * - 支持 IME action 完成；
 * - 点击关闭按钮或返回键均保存（与 KDoc 声明一致；关闭按钮丢弃修改属于静默数据丢失）。
 */
@Composable
private fun FullscreenEditorDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    Dialog(
        onDismissRequest = {
            // 返回键：自动保存
            onConfirm(text)
        },
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
                // 顶部栏
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
                        // 字符计数
                        Text(
                            "${text.length}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = { onConfirm(text) }) { // 修复：关闭按钮同样保存（原为静默丢弃全部修改）
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭并保存"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 辅助说明：分层编辑长文本，支持多行
                Text(
                    stringResource(R.string.detailed_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 编辑区域
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
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 底部确认按钮
                Button(
                    onClick = { onConfirm(text) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("完成")
                }
            }
        }
    }
}
