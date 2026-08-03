package com.apex.agent.ui.screen.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.messages) { message ->
                when (message) {
                    is ChatMessage.User -> UserMessageBubble(message.text)
                    is ChatMessage.Agent -> AgentMessageBubble(message)
                    is ChatMessage.ToolCall -> ToolCallCard(message)
                    is ChatMessage.System -> SystemMessageCard(message)
                }
            }
        }

        // 加载指示器
        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 输入栏
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入指令...") },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large
                )

                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !uiState.isLoading
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun UserMessageBubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .align(Alignment.End)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun AgentMessageBubble(message: ChatMessage.Agent) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Text(
            text = message.text,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ToolCallCard(message: ChatMessage.ToolCall) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🔧",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = message.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                if (message.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            message.output?.let { output ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = output.take(300),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemMessageCard(message: ChatMessage.System) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}
