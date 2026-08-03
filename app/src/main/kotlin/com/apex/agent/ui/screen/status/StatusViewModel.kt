package com.apex.agent.ui.screen.status

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class SystemStatus(
    val hasRoot: Boolean = false,
    val hasShizuku: Boolean = false,
    val hasAccessibility: Boolean = false,
    val foregroundServiceRunning: Boolean = false,
    val llmProvider: String? = null,
    val toolCount: Int = 0,
    val pluginCount: Int = 0,
    val linuxRuntime: String? = null,
    val pythonVersion: String? = null
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    // privilegeManager, pluginManager, linuxRuntime 等
) : ViewModel() {

    private val _status = MutableStateFlow(SystemStatus())
    val status: StateFlow<SystemStatus> = _status.asStateFlow()
}
