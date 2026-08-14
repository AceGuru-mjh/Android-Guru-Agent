package com.foundry.preview.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foundry.preview.engine.Diagnostic
import com.foundry.preview.engine.DiagnosticLevel
import com.foundry.preview.engine.DiagnosticsEngine
import com.foundry.preview.state.FoundryViewModel

@Composable
fun EditorScreen(viewModel: FoundryViewModel) {
    val code by viewModel.code.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "DSL Editor",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.undo() }) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = { viewModel.redo() }) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { viewModel.updateCode(it) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = { Text("UI DSL (JSON)") },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            maxLines = Int.MAX_VALUE
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.render() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Render Preview")
        }

        if (diagnostics.diagnostics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Diagnostics (${diagnostics.errorCount}E / ${diagnostics.warningCount}W)",
                style = MaterialTheme.typography.labelMedium,
                color = if (diagnostics.hasErrors) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 120.dp)
                    .fillMaxWidth()
            ) {
                items(diagnostics.diagnostics) { d ->
                    DiagnosticItem(d)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticItem(diagnostic: Diagnostic) {
    val color = when (diagnostic.level) {
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticLevel.WARNING -> Color(0xFFFF9800)
        DiagnosticLevel.INFO -> Color(0xFF2196F3)
    }
    val tag = when (diagnostic.level) {
        DiagnosticLevel.ERROR -> "ERR"
        DiagnosticLevel.WARNING -> "WARN"
        DiagnosticLevel.INFO -> "INFO"
    }
    Text(
        text = "[$tag] [${diagnostic.path}] ${diagnostic.message}",
        fontSize = 10.sp,
        color = color,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
