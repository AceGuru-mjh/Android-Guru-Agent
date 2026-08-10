package com.apex.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.ui.screen.agent.AgentChatScreen
import com.apex.agent.ui.screen.memory.MemoryScreen
import com.apex.agent.ui.screen.model.ModelScreen
import com.apex.agent.ui.screen.permissions.PermissionsScreen
import com.apex.agent.ui.screen.settings.SettingsScreen
import com.apex.agent.ui.screen.skill.SkillScreen
import kotlinx.coroutines.launch

/**
 * 抽屉导航目标
 */
sealed class DrawerDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Agent : DrawerDestination("agent", "Agent", Icons.Default.SmartToy)
    data object Skill : DrawerDestination("skill", "Skill", Icons.Default.AddComment)
    data object Memory : DrawerDestination("memory", "记忆", Icons.Default.Psychology)
    data object Model : DrawerDestination("model", "模型", Icons.Default.Hub)
    data object Permissions : DrawerDestination("permissions", "权限", Icons.Default.Security)
    data object Settings : DrawerDestination("settings", "设置", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexRoot() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentDestination by remember { mutableStateOf<DrawerDestination>(DrawerDestination.Agent) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ApexDrawerContent(
                currentDestination = currentDestination,
                onDestinationSelected = { dest ->
                    currentDestination = dest
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        currentDestination.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = currentDestination.label,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "打开导航",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentDestination) {
                    DrawerDestination.Agent -> AgentChatScreen()
                    DrawerDestination.Skill -> SkillScreen()
                    DrawerDestination.Memory -> MemoryScreen()
                    DrawerDestination.Model -> ModelScreen()
                    DrawerDestination.Permissions -> PermissionsScreen()
                    DrawerDestination.Settings -> SettingsScreen()
                }
            }
        }
    }
}
