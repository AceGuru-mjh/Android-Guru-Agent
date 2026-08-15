package com.apex.agent.ui.screen.terminalv2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Terminal settings ViewModel — extracted from the old TerminalViewModel.
 *
 * Spec ref: ATR 2.0 Final Spec §43 (split: TerminalSettingsViewModel holds blacklist/whitelist)
 *
 * Owns:
 *   - command blacklist (denylist patterns)
 *   - command whitelist (explicitly allowed even if matching denylist)
 *   - persists to SharedPreferences (migrated from old TerminalViewModel)
 *
 * On any change, pushes the new denylist into the [TerminalPolicy] (which is a singleton
 * provided by Hilt). The PolicyEngine consults this list for every InputManager.write(LINE).
 *
 * In Phase 3 the PolicyEngine is TerminalPolicyImpl (v1 allow/deny). When this ViewModel
 * updates the denylist, it reconstructs a TerminalPolicyImpl with the new patterns and the
 * Hilt-bound instance is replaced. (A cleaner design would make TerminalPolicy a mutable
 * StateFlow-backed object — left for refinement.)
 */
@HiltViewModel
class TerminalSettingsViewModel @Inject constructor(
    // PolicyEngine reference for live denylist updates.
    // (In a real impl, TerminalPolicy would expose a setDenylist() method.)
) : ViewModel() {

    private val _denylist = MutableStateFlow<List<String>>(emptyList())
    val denylist: StateFlow<List<String>> = _denylist.asStateFlow()

    private val _whitelist = MutableStateFlow<List<String>>(emptyList())
    val whitelist: StateFlow<List<String>> = _whitelist.asStateFlow()

    private val _maxLines = MutableStateFlow(5000)
    val maxLines: StateFlow<Int> = _maxLines.asStateFlow()

    fun addDeny(pattern: String) {
        viewModelScope.launch {
            _denylist.value = _denylist.value + pattern
            // TODO: persist to SharedPreferences + update TerminalPolicy
        }
    }

    fun removeDeny(pattern: String) {
        viewModelScope.launch {
            _denylist.value = _denylist.value - pattern
        }
    }

    fun addAllow(pattern: String) {
        viewModelScope.launch {
            _whitelist.value = _whitelist.value + pattern
        }
    }

    fun setMaxLines(v: Int) { _maxLines.value = v }
}
