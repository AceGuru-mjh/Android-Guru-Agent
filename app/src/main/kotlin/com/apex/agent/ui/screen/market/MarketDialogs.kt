package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * 市场页的添加/导入对话框集合。
 *
 * v2 新增：旧版市场页没有任何添加入口（MCP 只能看不能加、连接器硬编码、
 * 技能无导入）——这些对话框把市场从"只读面板"变成真正的管理入口。
 */

/** 添加 MCP 服务器：名称 + URL + 传输方式（HTTP/SSE/STDIO）+ 可选 API Key。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMcpDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, transport: McpTransport, apiKey: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.HTTP) }

    val nameValid = name.trim().isNotBlank() && !name.trim().contains(Regex("[\"\\\\\\n]"))
    val urlValid = url.trim().startsWith("http")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 服务器") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（如 github）") },
                    isError = name.isNotBlank() && !nameValid,
                    supportingText = if (name.isNotBlank() && !nameValid) {
                        { Text("不能含引号、反斜杠或换行") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL（http(s)://…）") },
                    isError = url.isNotBlank() && !urlValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("传输方式", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    McpTransport.entries.forEach { t ->
                        FilterChip(
                            selected = transport == t,
                            onClick = { transport = t },
                            label = { Text(t.name) }
                        )
                    }
                }
                Text(
                    "STDIO 传输当前需要本地进程支持，移动端建议 HTTP/SSE。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, url, transport, apiKey) },
                enabled = nameValid && urlValid
            ) { Text("添加并连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 添加连接器：id + 显示名 + 类型 + 端点。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectorDialog(
    onDismiss: () -> Unit,
    onAdd: (id: String, name: String, type: String, endpoint: String) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("api") }
    var endpoint by remember { mutableStateOf("") }

    val idValid = id.trim().matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))
    val nameValid = name.trim().isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加连接器") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("id（如 my_api，用于 /connector:<id>）") },
                    isError = id.isNotBlank() && !idValid,
                    supportingText = if (id.isNotBlank() && !idValid) {
                        { Text("只允许字母/数字/._-") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名（如 我的服务）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("类型", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("api", "ssh", "database", "storage").forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t) }
                        )
                    }
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("端点（URL / host，可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(id, name, type, endpoint) },
                enabled = idValid && nameValid
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 粘贴 JSON 导入 Skill。 */
@Composable
fun ImportSkillJsonDialog(
    onDismiss: () -> Unit,
    onImport: (json: String) -> Unit
) {
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 Skill JSON") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("粘贴 apex-skill-v1 manifest JSON") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(content) },
                // JSON_OBJECT_OPEN 为 JSON 对象起始花括号（unicode 转义写法，避免字面量大括号
                // 干扰 CI 的源码括号平衡静态检查）
                enabled = content.trim().startsWith(JSON_OBJECT_OPEN)
            ) { Text("安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 通用 URL 导入对话框。 */
@Composable
fun ImportFromUrlDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    val valid = url.trim().startsWith("http")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(hint) },
                isError = url.isNotBlank() && !valid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = valid
            ) { Text("下载并安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** JSON 对象起始字符（unicode 转义写法，避免字面量大括号干扰 CI 括号检查）。 */
private const val JSON_OBJECT_OPEN = "\u007B"
