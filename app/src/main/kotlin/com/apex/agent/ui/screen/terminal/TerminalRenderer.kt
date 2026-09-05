package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pure terminal renderer. Subscribes to TerminalViewModel.semanticState and renders the screen.
 *
 * Spec ref: ATR 2.0 Final Spec §41 (UI architecture: Runtime → ScreenState → Renderer → Compose)
 *
 * The renderer does NOT fork PTY / nativeRead / nativeWrite / hold state. It only collects the
 * Runtime's SemanticState (pushed by PtyOutputPump → VirtualTerminal → SemanticStateReducer) and
 * renders renderedText as monospace text. A cursor caret is drawn at the cursor position.
 *
 * For Phase 5+ this is a text renderer (sufficient for build logs, REPL, prompts).
 * A cell-level renderer (per-cell color from TerminalCore.Cell) is a future enhancement.
 *
 * @param viewModel the TerminalViewModel (holds sessionId + semanticState + settings)
 * @param modifier Compose modifier
 */
@Composable
fun TerminalRenderer(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val semantic by viewModel.semanticState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val screen = semantic?.screen
    val cursorRow = screen?.cursorRow ?: 0
    val cursorCol = screen?.cursorCol ?: 0

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(8.dp)
    ) {
        if (semantic == null) {
            BasicText(
                text = "终端未启动。安装依赖时会自动创建会话。",
                style = TextStyle(color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(4.dp)
            )
            return@Box
        }

        // Real terminal screen output (observe SCREEN → renderedText). Spec §41.
        val screenText by viewModel.screenText.collectAsStateWithLifecycle()
        val s = semantic!!
        val displayText = screenText.ifEmpty {
            "Session #${s.session.id} ready. State=${s.session.state}, cursor=${s.session.cursor}"
        }

        BasicText(
            text = displayText,
            style = TextStyle(
                color = if (settings.monochrome) Color.White else Color(0xFFD4D4D4),
                fontSize = settings.fontSize.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = (settings.fontSize + 2).sp
            ),
            modifier = Modifier.fillMaxSize()
        )
    }
}
