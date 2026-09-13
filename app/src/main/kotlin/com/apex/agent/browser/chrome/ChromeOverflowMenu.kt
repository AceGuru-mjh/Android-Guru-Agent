package com.apex.agent.browser.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ChromeOverflowMenu —— 顶栏「更多」菜单 + 用户脚本面板。
 */

@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    val p = chromePalette()
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = p.textPrimary) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = label,
                tint = p.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = if (checked) {
            {
                Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.size(18.dp))
            }
        } else {
            null
        },
        onClick = onClick,
    )
}

@Composable
internal fun ChromeOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    ui: ChromeUiState,
    controller: BrowserChromeController,
    onCopyLink: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = chromePalette().surface,
    ) {
        MenuRow("新标签页", Icons.Filled.Add) { controller.onMenuAction(ChromeMenuAction.NewTab) }
        MenuRow("标签页总览", Icons.Filled.ViewModule) { controller.onMenuAction(ChromeMenuAction.ShowTabs) }
        MenuRow("关闭其他标签", Icons.Filled.Close) { controller.onMenuAction(ChromeMenuAction.CloseOthers) }
        MenuRow("关闭全部标签", Icons.Filled.Close) { controller.onMenuAction(ChromeMenuAction.CloseAll) }
        HorizontalDivider(color = chromePalette().stroke)
        MenuRow("页内查找", Icons.Filled.FindInPage) { controller.onMenuAction(ChromeMenuAction.FindInPage) }
        MenuRow(
            "桌面版网站",
            Icons.Filled.DesktopWindows,
            checked = ui.desktopMode,
        ) { controller.toggleDesktopMode() }
        MenuRow("复制链接", Icons.Filled.ContentCopy) { onCopyLink() }
        HorizontalDivider(color = chromePalette().stroke)
        MenuRow("下载内容", Icons.Filled.Download) { controller.onMenuAction(ChromeMenuAction.Downloads) }
        MenuRow("用户脚本", Icons.Filled.Code) { controller.onMenuAction(ChromeMenuAction.Userscripts) }
        MenuRow("在外部浏览器打开", Icons.AutoMirrored.Filled.OpenInNew) { controller.onMenuAction(ChromeMenuAction.OpenExternal) }
    }
}

/** 用户脚本面板：引擎实现 UserscriptRegistry 后自动出现内容。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserscriptsSheet(
    visible: Boolean,
    registry: UserscriptRegistry?,
    onDismiss: () -> Unit,
) {
    if (!visible || registry == null) return
    val p = chromePalette()
    val scripts by registry.scripts.collectAsState()

    ChromeBottomSheet(visible = true, onDismiss = onDismiss) {
        Text(
            text = "用户脚本",
            fontSize = 15.sp,
            color = p.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = p.stroke, modifier = Modifier.padding(vertical = 6.dp))

        if (scripts.isEmpty()) {
            Text(
                text = "暂无脚本。引擎侧实现 UserscriptRegistry 并注入脚本后，此处自动列出。",
                fontSize = 12.sp,
                color = p.textSecondary,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
            ) {
                items(scripts, key = { it.id }) { script ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(script.name, fontSize = 13.sp, color = p.textPrimary)
                            Text(
                                script.matches,
                                fontSize = 10.sp,
                                color = p.textSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Switch(
                            checked = script.enabled,
                            onCheckedChange = { registry.setEnabled(script.id, it) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}
