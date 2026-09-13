package com.apex.agent.browser.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * AddressEditPane —— 地址栏编辑行 + 联想面板。
 *
 * 交互：
 * - 进入编辑自动聚焦、光标霓虹色；
 * - IME「前往」直接提交（URL 自动归一化，非 URL 走搜索）；
 * - 联想分五类：剪贴板粘贴即走 / URL 直达 / 已打开标签切换 / 历史 / 搜索兜底。
 */

@Composable
internal fun AddressEditRow(
    edit: AddressBarUi.Editing,
    controller: BrowserChromeController,
    modifier: Modifier = Modifier,
) {
    val p = chromePalette()
    val pillShape = RoundedCornerShape(50)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = pillShape,
            color = p.surfaceGlass,
            border = BorderStroke(1.dp, p.accent.copy(alpha = 0.55f)),
            modifier = Modifier.weight(1f).height(38.dp),
        ) {
            BasicTextField(
                value = edit.text,
                onValueChange = { controller.onAddressTextChanged(it) },
                singleLine = true,
                cursorBrush = SolidColor(p.accent),
                textStyle = LocalTextStyle.current.copy(color = p.textPrimary, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(
                    onGo = { controller.submitAddress(edit.text) },
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .padding(horizontal = 12.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) { inner() }
                        if (edit.text.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清空",
                                tint = p.textSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { controller.onAddressTextChanged("") },
                            )
                        }
                    }
                },
            )
        }
        IconButton(
            onClick = { controller.submitAddress(edit.text) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "前往",
                tint = p.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = { controller.exitAddressEdit() },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Cancel,
                contentDescription = "取消",
                tint = p.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun SuggestionsPanel(
    suggestions: List<Suggestion>,
    controller: BrowserChromeController,
    modifier: Modifier = Modifier,
) {
    val p = chromePalette()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = p.surface,
        border = BorderStroke(1.dp, p.stroke),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .heightIn(max = 220.dp),
    ) {
        LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
            items(suggestions, key = { "${it.kind}:${it.tabId}:${it.actionUrl}:${it.primary}" }) { s ->
                SuggestionRow(suggestion = s) { controller.pickSuggestion(s) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    onClick: () -> Unit,
) {
    val p = chromePalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint, desc) = when (suggestion.kind) {
            SuggestionKind.URL -> Triple(Icons.Filled.Link, p.accent, "网址")
            SuggestionKind.SEARCH -> Triple(Icons.Filled.Search, p.accent, "搜索")
            SuggestionKind.OPEN_TAB -> Triple(Icons.Outlined.Tab, p.accentAlt, "切换标签")
            SuggestionKind.HISTORY -> Triple(Icons.Filled.History, p.textSecondary, "历史")
            SuggestionKind.CLIPBOARD -> Triple(Icons.Outlined.ContentPaste, p.ok, "粘贴即走")
        }
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (suggestion.kind == SuggestionKind.SEARCH) {
                    "搜索 \"${suggestion.primary}\""
                } else {
                    suggestion.primary
                },
                fontSize = 13.sp,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            suggestion.secondary?.let { secondary ->
                Text(
                    text = secondary,
                    fontSize = 10.sp,
                    color = p.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
