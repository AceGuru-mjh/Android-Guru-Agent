package com.foundry.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.foundry.preview.state.FoundryViewModel
import com.foundry.preview.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: FoundryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sampleDsl = try {
            assets.open("sample_preview.androidui.json")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (sampleDsl != null) {
            viewModel.initializeWithSample(sampleDsl)
        }

        setContent {
            MainScreen(viewModel = viewModel)
        }
    }
}
