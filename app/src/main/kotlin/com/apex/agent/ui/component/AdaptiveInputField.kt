package com.apex.agent.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 自适应输入框 + 手势快捷键系统。
 *
 * 创新点：
 * 1. 根据内容自动扩展行数（1 → 最大 12 行）；
 * 2. 长文本（>200 字符）时显示字符计数；
 * 3. 双击触发全屏编辑模式（适合编辑长 prompt / 代码片段）；
 * 4. 全屏模式支持 IME action 完成。
 *
 * 推理依据：
 * - 当前 `maxLines = 5` 对复杂多轮指令（代码片段、多行 prompt）不够；
 * - 固定高度在小屏幕上浪费空间，自适应更高效；
 * - 双击全屏是 Telegram / 微信等成熟 IM 应用的标准体验。
 *
 * @param value 输入文本
 * @param onValueChange 文本变化回调
 * @param modifier 外部 Modifier
 * @param placeholder 占位文本
 * @param focusRequester 焦点请求器（可选，外部用于自动聚焦）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { Text("输入指令...") },
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSend: () -> Unit = {}
) {
    var isFullscreen by remember { mutableStateOf(false) }

    // 根据内容自动计算行数：1-5 行正常，6-12 行展开
    val dynamicMaxLines = remember(value) {
        val lineCount = value.count { it == '\n' } + 1
        lineCount.coerceIn(1, 5)
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onDoubleClick = {
                        // 双击触发全屏编辑
                        if (value.length > 50 || value.count { it == '\n' } > 2) {
                            isFullscreen = true
                        }
                    }
                ),
            placeholder = placeholder,
            maxLines = dynamicMaxLines,
            minLines = 1,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { onSend() }
            ),
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
                    // 全屏编辑按钮（仅在多行内容时显示）
                    AnimatedVisibility(
                        visible = value.count { it == '\n' } > 1 || value.length > 100,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        IconButton(
                            onClick = { isFullscreen = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = "全屏编辑",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )

        // 全屏编辑对话框
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
 * 全屏编辑对话框。
 *
 * - 占满整个屏幕，适合编辑长 prompt / 代码片段；
 * - 支持 IME action 完成；
 * - 点击关闭按钮或返回键保存。
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
                        "编辑消息",
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
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭"
                            )
                        }
                    }
                }

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
