package com.apex.agent.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Puzzle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 斜杠指令按钮
 * 36dp 正方形，1dp 灰色边框，中间显示 /
 * 点击弹出级联菜单（Skills / MCP / 连接器 / 插件）
 */
@Composable
fun SlashCommandButton(
    onCommandSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(36.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(6.dp)
            )
            // TalkBack: announce the button's purpose so screen-reader users
            // know this opens the Skills / MCP / 连接器 / 插件 command menu.
            .semantics { contentDescription = "打开斜杠指令菜单" }
            .clickable { showMenu = true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "/",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    if (showMenu) {
        SlashCommandPopup(
            onDismiss = { showMenu = false },
            onCommandSelected = { command ->
                onCommandSelected(command)
                showMenu = false
            }
        )
    }
}

@Composable
private fun SlashCommandPopup(
    onDismiss: () -> Unit,
    onCommandSelected: (String) -> Unit
) {
    val menuData = remember { buildSlashMenuData() }
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
            tonalElevation = 3.dp,
            modifier = Modifier.width(280.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "快捷指令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                menuData.forEach { category ->
                    SlashMenuCategoryItem(
                        category = category,
                        isExpanded = expandedCategory == category.id,
                        onToggle = {
                            expandedCategory = if (expandedCategory == category.id) null else category.id
                        },
                        onItemClick = { command -> onCommandSelected(command) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlashMenuCategoryItem(
    category: SlashMenuCategoryData,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onItemClick: (String) -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "arrow_rotation"
    )

    Column {
        Surface(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = if (isExpanded)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150))
        ) {
            Column(modifier = Modifier.padding(start = 20.dp)) {
                category.items.forEach { item ->
                    Surface(
                        onClick = { onItemClick(item.command) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "•",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

data class SlashMenuCategoryData(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val items: List<SlashMenuItemData>
)

data class SlashMenuItemData(
    val label: String,
    val command: String
)

private fun buildSlashMenuData(): List<SlashMenuCategoryData> = listOf(
    SlashMenuCategoryData(
        id = "skills", title = "Skills", icon = Icons.Default.Extension,
        items = listOf(
            SlashMenuItemData("代码解释器", "/skill:code_interpreter "),
            SlashMenuItemData("网页搜索", "/skill:web_search "),
            SlashMenuItemData("图表生成", "/skill:chart_generator "),
            SlashMenuItemData("文件整理", "/skill:file_organizer "),
            SlashMenuItemData("数据爬取", "/skill:web_scraper ")
        )
    ),
    SlashMenuCategoryData(
        id = "mcp", title = "MCP", icon = Icons.Default.Api,
        items = listOf(
            SlashMenuItemData("GitHub MCP", "/mcp:github "),
            SlashMenuItemData("PostgreSQL MCP", "/mcp:postgres "),
            SlashMenuItemData("Filesystem MCP", "/mcp:filesystem ")
        )
    ),
    SlashMenuCategoryData(
        id = "connectors", title = "连接器", icon = Icons.Default.Link,
        items = listOf(
            SlashMenuItemData("Google Drive", "/connector:google_drive "),
            SlashMenuItemData("Notion", "/connector:notion "),
            SlashMenuItemData("SSH", "/connector:ssh ")
        )
    ),
    SlashMenuCategoryData(
        id = "plugins", title = "插件", icon = Icons.Default.Puzzle,
        items = listOf(
            SlashMenuItemData("PDF 阅读器", "/plugin:pdf_reader "),
            SlashMenuItemData("实时翻译", "/plugin:translator "),
            SlashMenuItemData("工作流引擎", "/plugin:workflow ")
        )
    )
)
