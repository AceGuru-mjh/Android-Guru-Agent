package com.foundry.preview.engine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foundry.preview.dsl.*

@Composable
fun UiRenderer(
    element: UiElement,
    theme: ThemeConfig,
    modifier: Modifier = Modifier
) {
    val appliedModifier = element.modifier.toComposeModifier(modifier, theme)

    when (element.type) {
        "Text" -> RenderText(element, theme, appliedModifier)
        "Button" -> RenderButton(element, theme, appliedModifier)
        "TextField" -> RenderTextField(element, theme, appliedModifier)
        "Image" -> RenderImage(element, theme, appliedModifier)
        "Column" -> RenderColumn(element, theme, appliedModifier)
        "Row" -> RenderRow(element, theme, appliedModifier)
        "Box" -> RenderBox(element, theme, appliedModifier)
        "Scroll" -> RenderScroll(element, theme, appliedModifier)
        "Card" -> RenderCard(element, theme, appliedModifier)
        "Surface" -> RenderSurface(element, theme, appliedModifier)
        "Divider" -> RenderDivider(element, theme, appliedModifier)
        "Spacer" -> RenderSpacer(element, appliedModifier)
        else -> Text("Unknown: ${element.type}", modifier = appliedModifier)
    }
}

@Composable
private fun RenderText(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    val text = element.attributes["text"] as? String ?: "Text"
    val fontSize = (element.attributes["fontSize"] as? String)?.toIntOrNull()?.sp ?: 16.sp
    val fontWeight = when (element.attributes["fontWeight"] as? String) {
        "Bold" -> FontWeight.Bold
        "Medium" -> FontWeight.Medium
        "Light" -> FontWeight.Light
        else -> FontWeight.Normal
    }
    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = theme.primary(),
        modifier = modifier
    )
}

@Composable
private fun RenderButton(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    val text = element.attributes["text"] as? String ?: "Button"
    Button(
        onClick = { },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary())
    ) {
        Text(text)
    }
}

@Composable
private fun RenderTextField(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    val label = element.attributes["label"] as? String ?: ""
    val placeholder = element.attributes["placeholder"] as? String ?: ""
    OutlinedTextField(
        value = "",
        onValueChange = { },
        label = { if (label.isNotEmpty()) Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        modifier = modifier
    )
}

@Composable
private fun RenderImage(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    val contentDesc = element.attributes["contentDescription"] as? String ?: "Image"
    Box(
        modifier = modifier
            .background(Color.LightGray)
            .border(1.dp, Color.Gray),
        contentAlignment = Alignment.Center
    ) {
        Text("🖼️ $contentDesc", color = Color.DarkGray)
    }
}

@Composable
private fun RenderColumn(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    Column(modifier = modifier) {
        element.children.forEach { child ->
            UiRenderer(child, theme)
        }
    }
}

@Composable
private fun RenderRow(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    Row(modifier = modifier) {
        element.children.forEach { child ->
            UiRenderer(child, theme)
        }
    }
}

@Composable
private fun RenderBox(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    Box(modifier = modifier) {
        element.children.forEach { child ->
            UiRenderer(child, theme)
        }
    }
}

@Composable
private fun RenderScroll(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
    ) {
        element.children.forEach { child ->
            UiRenderer(child, theme)
        }
    }
}

@Composable
private fun RenderCard(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = theme.surface())
    ) {
        element.children.forEach { child ->
            UiRenderer(child, theme)
        }
    }
}

@Composable
private fun RenderSurface(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = theme.surface()
    ) {
        element.children.forEach { child ->
            UiRenderer(child, theme)
        }
    }
}

@Composable
private fun RenderDivider(element: UiElement, theme: ThemeConfig, modifier: Modifier) {
    Divider(color = theme.primary().copy(alpha = 0.3f), modifier = modifier)
}

@Composable
private fun RenderSpacer(element: UiElement, modifier: Modifier) {
    Spacer(modifier = modifier)
}

private fun ModifierConfig.toComposeModifier(base: Modifier, theme: ThemeConfig): Modifier {
    var result = base

    width?.let { result = result.width(it.dp) }
    height?.let { result = result.height(it.dp) }

    if (fillMaxWidth) result = result.fillMaxWidth()
    if (fillMaxHeight) result = result.fillMaxHeight()
    if (fillMaxSize) result = result.fillMaxSize()

    padding?.let { p ->
        val all = p.all ?: 0
        result = result.padding(
            start = (p.start ?: p.horizontal ?: all).dp,
            top = (p.top ?: p.vertical ?: all).dp,
            end = (p.end ?: p.horizontal ?: all).dp,
            bottom = (p.bottom ?: p.vertical ?: all).dp
        )
    }

    background?.let { bg ->
        try {
            result = result.background(Color(android.graphics.Color.parseColor(bg)))
        } catch (_: Exception) {}
    }

    cornerRadius?.let { radius ->
        result = result.clip(RoundedCornerShape(radius.dp))
    }

    return result
}
