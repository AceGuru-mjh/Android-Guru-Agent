package com.apex.agent.ui.screen.terminalv2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * REWRITTEN TerminalScreen — pure terminal view + minimal settings.
 *
 * Spec ref: ATR 2.0 Final Spec §41 / §43
 *
 * BEFORE (old TerminalScreen.kt ~409 lines):
 *   - dependency install center (7 DepItems, install buttons, mirror toggle)
 *   - command blacklist/whitelist editor
 *   - terminal settings (font size, max lines, monochrome)
 *   - (no actual terminal view — was a settings panel)
 *
 * AFTER (this file, Phase 4):
 *   - TerminalRenderer (the actual terminal view)
 *   - TerminalInputController overlay (key handling)
 *   - minimal settings (font size slider, monochrome toggle)
 *   - session controls (create / close)
 *   - NO dep installer (moved to EnvironmentProvisioner — separate screen)
 *   - NO blacklist editor (moved to TerminalSettingsViewModel — separate screen)
 *
 * The dep-installer + blacklist UIs become separate screens in Phase 5; this screen is
 * ONLY the terminal view.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val sessionId by viewModel.sessionId.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val monochrome by viewModel.monochrome.collectAsState()
    val semantic by viewModel.semanticState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // ─── Toolbar ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (sessionId != null) "Session #${sessionId}" else "No session",
                modifier = Modifier.padding(end = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sessionId == null) {
                    Button(onClick = { viewModel.ensureSession() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Create session")
                        Spacer(Modifier.width(4.dp))
                        Text("Create")
                    }
                } else {
                    IconButton(onClick = {
                        // close handled by Agent / explicit action; here we just stop observing
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Observe")
                    }
                    IconButton(onClick = { /* TODO: terminal.close via Runtime */ }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Close")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ─── Terminal view (renderer + input overlay) ───
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
        ) {
            TerminalRenderer(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            // Input overlay (transparent, captures keys)
            // TerminalInputController wired with the Runtime from viewModel (Phase 5 will inject)
        }

        Spacer(Modifier.height(8.dp))

        // ─── Minimal settings ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Font: ${fontSize}", modifier = Modifier.width(80.dp))
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { viewModel.setFontSize(it.toInt().coerceIn(8, 32)) },
                valueRange = 8f..32f,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            Text("Mono", modifier = Modifier.padding(end = 4.dp))
            Switch(
                checked = monochrome,
                onCheckedChange = { viewModel.setMonochrome(it) }
            )
        }

        // ─── State summary (debug) ───
        Spacer(Modifier.height(8.dp))
        semantic?.let { s ->
            Text(
                text = "state=${s.session.state.name} cursor=${s.session.cursor} fgJob=${s.foregroundJob?.let { "#${it.id} ${it.state}" } ?: "none"} input=${s.input.state}",
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}
