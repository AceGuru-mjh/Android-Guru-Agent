package com.foundry.preview.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foundry.preview.state.FoundryViewModel

data class ComponentTemplate(
    val name: String,
    val description: String,
    val dsl: String
)

private val COMPONENT_TEMPLATES = listOf(
    ComponentTemplate(
        name = "Text",
        description = "Basic text label",
        dsl = """
        {
          "type": "Text",
          "attributes": { "text": "New Text", "fontSize": "16" },
          "modifier": { "padding": { "bottom": 8 } }
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Button",
        description = "Clickable button",
        dsl = """
        {
          "type": "Button",
          "attributes": { "text": "Click Me" },
          "modifier": { "padding": { "bottom": 8 } }
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Card",
        description = "Elevated card container",
        dsl = """
        {
          "type": "Card",
          "modifier": { "fillMaxWidth": true, "cornerRadius": 12, "padding": { "bottom": 8 } },
          "children": [
            {
              "type": "Text",
              "attributes": { "text": "Card Content", "fontSize": "14" },
              "modifier": { "padding": { "all": 16 } }
            }
          ]
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Row",
        description = "Horizontal layout",
        dsl = """
        {
          "type": "Row",
          "modifier": { "fillMaxWidth": true, "padding": { "bottom": 8 } },
          "children": [
            { "type": "Text", "attributes": { "text": "Left" } },
            { "type": "Spacer", "modifier": { "width": 8 } },
            { "type": "Text", "attributes": { "text": "Right" } }
          ]
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Column",
        description = "Vertical layout",
        dsl = """
        {
          "type": "Column",
          "modifier": { "fillMaxWidth": true, "padding": { "bottom": 8 } },
          "children": [
            { "type": "Text", "attributes": { "text": "Item 1" } },
            { "type": "Text", "attributes": { "text": "Item 2" } }
          ]
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Box",
        description = "Overlay / stack layout",
        dsl = """
        {
          "type": "Box",
          "modifier": { "width": 100, "height": 100, "background": "#FFE3F2FD", "cornerRadius": 8, "padding": { "bottom": 8 } },
          "children": [
            { "type": "Text", "attributes": { "text": "Centered", "fontSize": "12" } }
          ]
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "TextField",
        description = "Text input field",
        dsl = """
        {
          "type": "TextField",
          "attributes": { "label": "Input", "placeholder": "Type here..." },
          "modifier": { "fillMaxWidth": true, "padding": { "bottom": 8 } }
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Image Placeholder",
        description = "Image placeholder box",
        dsl = """
        {
          "type": "Image",
          "attributes": { "contentDescription": "Photo" },
          "modifier": { "width": 120, "height": 80, "cornerRadius": 8, "padding": { "bottom": 8 } }
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Divider",
        description = "Horizontal separator line",
        dsl = """
        {
          "type": "Divider",
          "modifier": { "padding": { "bottom": 8 } }
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Spacer",
        description = "Empty spacing element",
        dsl = """
        {
          "type": "Spacer",
          "modifier": { "height": 16 }
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Scroll Container",
        description = "Scrollable vertical container",
        dsl = """
        {
          "type": "Scroll",
          "modifier": { "fillMaxWidth": true, "height": 200, "padding": { "bottom": 8 } },
          "children": [
            { "type": "Text", "attributes": { "text": "Scrollable content" } }
          ]
        }
        """.trimIndent()
    ),
    ComponentTemplate(
        name = "Surface",
        description = "Themed surface container",
        dsl = """
        {
          "type": "Surface",
          "modifier": { "fillMaxWidth": true, "cornerRadius": 8, "padding": { "bottom": 8 } },
          "children": [
            { "type": "Text", "attributes": { "text": "Surface content" }, "modifier": { "padding": { "all": 12 } } }
          ]
        }
        """.trimIndent()
    )
)

@Composable
fun ComponentsScreen(viewModel: FoundryViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        item {
            Text(
                text = "Component Palette",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Tap a component to insert its DSL into the editor",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(COMPONENT_TEMPLATES) { template ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { viewModel.insertComponent(template.dsl) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = template.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
