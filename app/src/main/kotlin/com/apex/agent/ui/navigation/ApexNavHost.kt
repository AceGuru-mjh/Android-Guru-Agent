package com.apex.agent.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.apex.agent.ui.screen.chat.ChatScreen
import com.apex.agent.ui.screen.project.ProjectScreen
import com.apex.agent.ui.screen.status.StatusScreen
import com.apex.agent.ui.screen.settings.SettingsScreen

sealed class Screen(val route: String, val label: String) {
    data object Chat : Screen("chat", "对话")
    data object Project : Screen("project", "项目")
    data object Status : Screen("status", "状态")
    data object Settings : Screen("settings", "设置")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val screens = listOf(Screen.Chat, Screen.Project, Screen.Status, Screen.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = when (screen) {
                                    Screen.Chat -> Icons.Default.Chat
                                    Screen.Project -> Icons.Default.Folder
                                    Screen.Status -> Icons.Default.Dashboard
                                    Screen.Settings -> Icons.Default.Settings
                                },
                                contentDescription = screen.label
                            )
                        },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Project.route) { ProjectScreen() }
            composable(Screen.Status.route) { StatusScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
