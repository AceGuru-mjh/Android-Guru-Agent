package com.foundry.preview.dsl

import androidx.compose.ui.graphics.Color

data class ThemeConfig(
    val primaryColor: String = "#FF6200EE",
    val secondaryColor: String = "#FF03DAC5",
    val backgroundColor: String = "#FFFFFFFF",
    val surfaceColor: String = "#FFFFFFFF",
    val errorColor: String = "#FFB00020"
) {
    fun primary(): Color = try {
        Color(android.graphics.Color.parseColor(primaryColor))
    } catch (e: Exception) {
        Color(0xFF6200EE)
    }

    fun secondary(): Color = try {
        Color(android.graphics.Color.parseColor(secondaryColor))
    } catch (e: Exception) {
        Color(0xFF03DAC5)
    }

    fun background(): Color = try {
        Color(android.graphics.Color.parseColor(backgroundColor))
    } catch (e: Exception) {
        Color(0xFFFFFFFF)
    }

    fun surface(): Color = try {
        Color(android.graphics.Color.parseColor(surfaceColor))
    } catch (e: Exception) {
        Color(0xFFFFFFFF)
    }

    fun error(): Color = try {
        Color(android.graphics.Color.parseColor(errorColor))
    } catch (e: Exception) {
        Color(0xFFB00020)
    }
}

data class ModifierConfig(
    val width: Int? = null,
    val height: Int? = null,
    val fillMaxWidth: Boolean = false,
    val fillMaxHeight: Boolean = false,
    val fillMaxSize: Boolean = false,
    val padding: PaddingConfig? = null,
    val background: String? = null,
    val cornerRadius: Int? = null,
    val align: String? = null
)

data class PaddingConfig(
    val all: Int? = null,
    val horizontal: Int? = null,
    val vertical: Int? = null,
    val start: Int? = null,
    val top: Int? = null,
    val end: Int? = null,
    val bottom: Int? = null
)

data class UiElement(
    val type: String,
    val attributes: Map<String, Any> = emptyMap(),
    val modifier: ModifierConfig = ModifierConfig(),
    val children: List<UiElement> = emptyList()
)

data class DeviceConfig(
    val widthDp: Int = 412,
    val heightDp: Int = 915
)

data class UiDocument(
    val name: String,
    val theme: ThemeConfig,
    val device: DeviceConfig,
    val root: UiElement
)
