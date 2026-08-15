package com.apex.agent.ui.screen.terminalv2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.platform.terminal.screen.TerminalScreenState

/**
 * Pure terminal renderer. Subscribes to the Runtime's ScreenState Flow and renders cells.
 *
 * Spec ref: ATR 2.0 Final Spec §41 (UI architecture) / §42 (UI forbidden)
 *
 * Runtime → ScreenState → TerminalRenderer → Compose UI
 *
 * The renderer does NOT:
 *   - fork PTY
 *   - nativeRead / nativeWrite
 *   - hold independent terminal state
 *   - poll (it collects a StateFlow pushed by PtyOutputPump → VirtualTerminal.snapshot())
 *
 * The renderer DOES:
 *   - collect TerminalViewModel.semanticState (which carries screen snapshot)
 *   - render renderedText as monospace text
 *   - draw a cursor caret at (cursorRow, cursorCol)
 *   - apply fontSize / monochrome from ViewModel settings
 *
 * For Phase 4 this is a text-based renderer (sufficient for build logs, REPL, prompts).
 * A cell-level renderer (per-cell color/attributes from VT100Emulator.Cell) is a Phase 5
 * enhancement once we expose the cell grid (currently ScreenState carries renderedText only).
 *
 * @param viewModel the TerminalViewModel (holds sessionId + semanticState + settings)
 * @param modifier  Compose modifier
 */
@Composable
fun TerminalRenderer(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val semantic by viewModel.semanticState.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val monochrome by viewModel.monochrome.collectAsState()

    val screen: TerminalScreenState? = semantic?.fullScreen
        ?: semantic?.screen?.let {
            com.apex.agent.platform.terminal.screen.TerminalScreenState(
                rows = it.rows, cols = it.cols,
                cursorRow = it.cursorRow, cursorCol = it.cursorCol,
                alternateScreen = it.alternateScreen, title = it.title,
                renderedText = null, changedRows = null
            )
        }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(8.dp)
    ) {
        if (screen == null) {
            BasicText(
                text = "No session. Tap '+' to create one.",
                style = TextStyle(color = Color.Gray, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(8.dp)
            )
            return@Box
        }

        val text = screen.renderedText ?: ""
        val lines = text.split('\n')
        val displayText = lines.joinToString("\n") { it.take(screen.cols) }

        BasicText(
            text = displayText,
            style = TextStyle(
                color = if (monochrome) Color.White else Color(0xFFD4D4D4),
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = (fontSize + 2).sp
            ),
            modifier = Modifier.fillMaxSize()
        )

        // Cursor caret overlay
        if (screen.cursorRow < screen.rows && screen.cursorCol < screen.cols) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val charW = size.width / screen.cols
                val charH = size.height / screen.rows
                val x = screen.cursorCol * charW
                val y = screen.cursorRow * charH
                drawRect(
                    color = Color(0xFF4D9FFF).copy(alpha = 0.5f),
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(charW, charH * 0.9f)
                )
            }
        }
    }
}
