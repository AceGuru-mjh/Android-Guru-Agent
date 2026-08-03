package com.apex.agent.ui.screen.project

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ProjectInfo(
    val id: String,
    val name: String,
    val type: String,
    val path: String,
    val fileCount: Int = 0
)

@HiltViewModel
class ProjectViewModel @Inject constructor(
    // TODO: inject WorkspaceManager when the platform:workspace module is re-added
) : ViewModel() {

    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    fun showCreateDialog() {
        // TODO
    }
}
