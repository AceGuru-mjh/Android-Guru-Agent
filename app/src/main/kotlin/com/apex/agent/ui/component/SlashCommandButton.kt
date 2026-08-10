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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.apex.agent.core.tools.skill.SkillMenuItem
import com.apex.agent.core.tools.skill.SkillMenuProvider

/**
 * 斜杠指令按钮（动态版）
 * 菜单数据从 SkillRegistry 实时读取
 */
@Composable
fun SlashCommandButton(
    skillMenuProvider: SkillMenuProvider,
    onCommandSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(36.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
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
            color = MaterialTheme.colorScheme.primary
        )
    }

    if (showMenu) {
        DynamicSlashMenuPopup(
            skillMenuProvider = skillMenuProvider,
            onDismiss = { showMenu = false },
            onCommandSelected = { command ->
                onCommandSelected(command)
                showMenu = false
            }
        )
    }
}

/**
 * 动态级联菜单
 * Skills 从 SkillRegistry 实时加载
 * MCP/连接器/插件 保持静态（待后续接入）
 */
@Composable
private fun DynamicSlashMenuPopup(
    skillMenuProvider: SkillMenuProvider,
    onDismiss: () -> Unit,
    onCommandSelected: (String) -> Unit
) {
    // 实时读取 SkillRegistry
    val activeSkills by remember { mutableStateOf(skillMenuProvider.getActiveSkills()) }
    val builtinTemplates by remember { mutableStateOf(skillMenuProvider.getBuiltinTemplates()) }
    val allSkillItems = activeSkills + builtinTemplates

    var expandedCategory by remember { mutableStateOf<String?>(null) }

    // 构建动态菜单数据
    val menuData = remember(activeSkills, builtinTemplates) {
        listOf(
            DynamicMenuCategory(
                id = "skills",
                title = "Skills",
                icon = Icons.Default.Extension,
                items = allSkillItems.map { s ->
                    DynamicMenuItem(label = s.label, command = s.command)
                },
                badge = if (activeSkills.isNotEmpty()) "${activeSkills.size}" else null
            ),
            DynamicMenuCategory(
                id = "mcp",
                title = "MCP",
                icon = Icons.Default.Api,
                items = listOf(
                    DynamicMenuItem("GitHub MCP", "/mcp:github "),
                    DynamicMenuItem("PostgreSQL MCP", "/mcp:postgres "),
                    DynamicMenuItem("Filesystem MCP", "/mcp:filesystem ")
                )
            ),
            DynamicMenuCategory(
                id = "connectors",
                title = "连接器",
                icon = Icons.Default.Link,
                items = listOf(
                    DynamicMenuItem("Google Drive", "/connector:google_drive "),
                    DynamicMenuItem("Notion", "/connector:notion "),
                    DynamicMenuItem("SSH", "/connector:ssh ")
                )
            ),
            DynamicMenuCategory(
                id = "plugins",
                title = "插件",
                icon = Icons.Default.Build,
                items = listOf(
                    DynamicMenuItem("PDF 阅读器", "/plugin:pdf_reader "),
                    DynamicMenuItem("实时翻译", "/plugin:translator "),
                    DynamicMenuItem("工作流引擎", "/plugin:workflow ")
                )
            )
        )
    }

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
                    DynamicCategoryItem(
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
private fun DynamicCategoryItem(
    category: DynamicMenuCategory,
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
                // 数量角标
                if (category.badge != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = category.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
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
            if (category.items.isEmpty()) {
                Text(
                    text = "暂无可用项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 42.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
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
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══ 数据模型 ═══

data class DynamicMenuCategory(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val items: List<DynamicMenuItem>,
    val badge: String? = null
)

data class DynamicMenuItem(
    val label: String,
    val command: String
)
