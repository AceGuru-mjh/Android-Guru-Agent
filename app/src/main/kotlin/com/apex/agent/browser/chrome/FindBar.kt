package com.apex.agent.browser.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * FindBar —— 页内查找（贴在内容区底部）。
 *
 * 输入防抖 250ms（控制器内实现），匹配计数由引擎回填 FindState。
 * 注意：AnimatedVisibility 隐藏即卸载，remember 自动清空上次输入。
 */
@Composable
internal fun FindBar(
    visible: Boolean,
    findState: FindState?,
    controller: BrowserChromeController,
    modifier: Modifier = Modifier,
) {
    val p = chromePalette()
    var query by remember { mutableStateOf("") }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = p.surfaceGlass,
            border = BorderStroke(1.dp, p.stroke),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(46.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { controller.closeFindBar() }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭查找",
                        tint = p.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        controller.onFindQueryChanged(it)
                    },
                    singleLine = true,
                    cursorBrush = SolidColor(p.accent),
                    textStyle = LocalTextStyle.current.copy(color = p.textPrimary, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(
                        onSearch = { if (query.isNotBlank()) controller.findNext() },
                    ),
                    decorationBox = { inner ->
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            androidx.compose.foundation.layout.Box {
                                if (query.isEmpty()) {
                                    Text(
                                        "查找页面内容",
                                        fontSize = 12.sp,
                                        color = p.textSecondary,
                                    )
                                }
                                inner()
                            }
                        }
                    },
                )
                if (findState != null && findState.totalMatches > 0) {
                    Text(
                        text = "${findState.activeMatch}/${findState.totalMatches}",
                        fontSize = 11.sp,
                        color = p.accent,
                        modifier = Modifier.padding(horizontal = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { controller.findPrev() }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "上一个",
                        tint = p.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = { controller.findNext() }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "下一个",
                        tint = p.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
