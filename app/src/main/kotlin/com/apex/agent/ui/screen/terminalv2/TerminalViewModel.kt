package com.apex.agent.ui.screen.terminalv2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * REWRITTEN TerminalViewModel — pure terminal state ONLY.
 *
 * Spec ref: ATR 2.0 Final Spec §41 / §43 (split EnvironmentProvisioner out)
 *
 * BEFORE (old TerminalViewModel.kt ~252 lines, mixed concerns):
 *   - terminal settings (font size, max lines, monochrome)
 *   - command blacklist/whitelist persistence
 *   - EnvironmentProvisioner logic (7 DepItems, installOfficial/installMirror, installAll)
 *   - session lifecycle (ensureSession, runCommand, execAndAppend)
 *
 * AFTER (this file, Phase 3):
 *   - holds sessionId + subscribes to SemanticState
 *   - delegates installs to [com.apex.agent.environment.EnvironmentProvisioner]
 *   - delegates blacklist/whitelist to [TerminalSettingsViewModel] (→ PolicyEngine)
 *   - NO direct PTY access (Runtime owns PTY; UI is a pure renderer per Spec §41)
 *
 * This is the SCAFFOLD showing the target shape. The real repo's TerminalViewModel.kt
 * (~252 lines) should be rewritten to this structure; the ~150 lines of DepItem/install
 * logic move to EnvironmentProvisioner.kt (already written in Phase 3).
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val runtime: TerminalRuntime
) : ViewModel() {

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    private val _semanticState = MutableStateFlow<TerminalSemanticState?>(null)
    val semanticState: StateFlow<TerminalSemanticState?> = _semanticState.asStateFlow()

    private val _fontSize = MutableStateFlow(14)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _monochrome = MutableStateFlow(false)
    val monochrome: StateFlow<Boolean> = _monochrome.asStateFlow()

    /** Ensure a Session exists; create if not. */
    fun ensureSession() {
        viewModelScope.launch {
            if (_sessionId.value == null) {
                val r = runtime.create()
                r.onSuccess { _sessionId.value = it.sessionId }
            }
        }
    }

    /** Subscribe to the session's SemanticState (for the renderer). */
    fun observeState() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            // Poll observe(SEMANTIC) at low frequency for the renderer.
            // (A real impl would use the EventBus Flow directly via a Runtime helper.)
            while (true) {
                val r = runtime.observe(sid, TerminalRuntime.ObserveMode.SEMANTIC)
                r.onSuccess { _semanticState.value = it.semantic }
                kotlinx.coroutines.delay(50L)  // 20 FPS UI refresh
            }
        }
    }

    fun setFontSize(v: Int) { _fontSize.value = v }
    fun setMonochrome(v: Boolean) { _monochrome.value = v }

    override fun onCleared() {
        // Note: we do NOT close the session here — the Runtime owns lifecycle.
        // Session close happens explicitly via terminal.close() (Agent or UI action).
        super.onCleared()
    }
}
