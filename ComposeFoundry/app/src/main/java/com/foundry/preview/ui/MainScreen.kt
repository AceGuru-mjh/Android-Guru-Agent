package com.foundry.preview.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.foundry.preview.dsl.ThemeConfig
import com.foundry.preview.sandbox.FoundryTheme
import com.foundry.preview.state.FoundryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: FoundryViewModel) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val document by viewModel.document.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importFromUri(context, it) }
    }

    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportToUri(context, it) }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(statusMessage)
            viewModel.clearStatus()
        }
    }

    FoundryTheme(
        themeConfig = document?.theme ?: ThemeConfig(),
        isDarkTheme = isDarkTheme
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("ComposeFoundry") },
                    actions = {
                        TextButton(onClick = { openFileLauncher.launch(arrayOf("application/json", "*/*")) }) {
                            Text("Open")
                        }
                        TextButton(onClick = { createFileLauncher.launch("preview.androidui.json") }) {
                            Text("Save")
                        }
                        TextButton(onClick = { viewModel.toggleTheme() }) {
                            Text(if (isDarkTheme) "Light" else "Dark")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { viewModel.selectTab(0) },
                        icon = { Icon(Icons.Filled.Edit, contentDescription = "Editor") },
                        label = { Text("Editor") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { viewModel.selectTab(1) },
                        icon = { Icon(Icons.Filled.Preview, contentDescription = "Preview") },
                        label = { Text("Preview") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { viewModel.selectTab(2) },
                        icon = { Icon(Icons.Filled.Widgets, contentDescription = "Components") },
                        label = { Text("Components") }
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedTab) {
                    0 -> EditorScreen(viewModel = viewModel)
                    1 -> PreviewScreen(viewModel = viewModel)
                    2 -> ComponentsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
