package com.apex.agent.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 输入框「/」实时联想。
 *
 * 输入以 "/" 开头且命令词尚未出现空格时，在输入行上方弹出斜杠命令候选；
 * 点击候选项回填完整命令（自带尾随空格，可继续输入参数）。
 * 数据复用斜杠菜单的 [SlashMenuProvider]，技能/MCP/插件/连接器同源。
 */
@Composable
fun SlashAutoCompleteHost(
    inputText: String,
    slashMenuProvider: SlashMenuProvider,
    onCommandSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 用户点击外部关闭后记住被关闭时的文本；继续输入（文本变化）即重新弹出
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val visible = inputText.startsWith("/") &&
        !inputText.contains(' ') &&
        inputText != dismissedFor

    val menu by slashMenuProvider.menu.collectAsStateWithLifecycle()
    // 弹出时刷新一次，保证技能/MCP/插件状态是最新
    LaunchedEffect(visible) {
        if (visible) slashMenuProvider.refresh()
    }

    if (!visible) return

    val query = inputText.trim().removePrefix("/")
    val suggestions = menu.categories
        .flatMap { category -> category.items }
        .filter { item ->
            item.command.trim().removePrefix("/").contains(query, ignoreCase = true) ||
                item.label.contains(query, ignoreCase = true)
        }
        .take(8)
    if (suggestions.isEmpty()) return

    DropdownMenu(
        expanded = true,
        onDismissRequest = { dismissedFor = inputText },
        modifier = modifier.heightIn(max = 280.dp),
        // P0 修复（键盘被联想菜单收起）：M3 DropdownMenu 默认 focusable=true ——
        // 弹窗夺取窗口焦点，IME 随即收起，用户输入 "/" 后无法继续盲打命令名
        //（Compose 已知坑）。联想菜单不需要键盘导航（选中靠点击），显式
        // focusable=false 让输入框保持焦点、键盘常驻。
        properties = PopupProperties(focusable = false)
    ) {
        suggestions.forEach { item ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.description.isNotBlank()) {
                            Text(
                                item.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                leadingIcon = {
                    Text(
                        item.command.trim().substringBefore(' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                },
                onClick = {
                    onCommandSelected(item.command)
                    dismissedFor = item.command
                }
            )
        }
    }
}
