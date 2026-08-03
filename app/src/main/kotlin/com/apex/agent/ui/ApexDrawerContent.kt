package com.apex.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.apex.agent.ui.screen.agent.AgentChatViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ApexDrawerContent(
    currentDestination: DrawerDestination,
    onDestinationSelected: (DrawerDestination) -> Unit
) {
    // 注入 AgentChatViewModel 用于在抽屉底部显示当前模式/思考深度
    val agentVm: AgentChatViewModel = hiltViewModel()
    val agentState by agentVm.uiState.collectAsStateWithLifecycle()

    ModalDrawerSheet(
        modifier = Modifier.width(280.dp)
    ) {
        // ═══ 头部 ═══
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column {
                    Text(
                        "Apex Agent",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "全能AI助手",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(8.dp))

        // ═══ 导航项 ═══
        val destinations = listOf(
            DrawerDestination.Agent,
            DrawerDestination.Skill,
            DrawerDestination.Memory,
            DrawerDestination.Model,
            DrawerDestination.Permissions,
            DrawerDestination.Settings
        )

        destinations.forEach { dest ->
            NavigationDrawerItem(
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(dest.label) },
                selected = currentDestination == dest,
                onClick = { onDestinationSelected(dest) },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══ 底部状态 ═══
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "模式: ${agentState.mode.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "思考: ${agentState.thinkingLevel.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (agentState.historyDepth > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "记忆: ${agentState.historyDepth} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
