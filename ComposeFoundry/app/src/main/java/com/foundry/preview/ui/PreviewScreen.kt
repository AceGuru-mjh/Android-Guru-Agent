package com.foundry.preview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foundry.preview.engine.DiagnosticsEngine
import com.foundry.preview.sandbox.PreviewSurface
import com.foundry.preview.state.FoundryViewModel

@Composable
fun PreviewScreen(viewModel: FoundryViewModel) {
    val document by viewModel.document.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val devicePreset by viewModel.devicePreset.collectAsState()
    val (width, height) = viewModel.getDeviceDimensions()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "Preview",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Device:",
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodySmall
            )
            FilterChip(
                selected = devicePreset == "Small Phone",
                onClick = { viewModel.setDevicePreset("Small Phone") },
                label = { Text("Small") }
            )
            FilterChip(
                selected = devicePreset == "Pixel 7",
                onClick = { viewModel.setDevicePreset("Pixel 7") },
                label = { Text("Phone") }
            )
            FilterChip(
                selected = devicePreset == "Pixel Tablet",
                onClick = { viewModel.setDevicePreset("Pixel Tablet") },
                label = { Text("Tablet") }
            )
        }

        OutlinedButton(
            onClick = { viewModel.render() },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text("Refresh Preview")
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            PreviewSurface(
                document = document,
                deviceWidth = width,
                deviceHeight = height,
                diagnostics = diagnostics
            )
        }
    }
}
