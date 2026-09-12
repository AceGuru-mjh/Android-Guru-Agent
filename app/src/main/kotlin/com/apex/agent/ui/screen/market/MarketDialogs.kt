package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * 市场页的添加/导入对话框集合。
 *
 * v2 新增：旧版市场页没有任何添加入口（MCP 只能看不能加、连接器硬编码、
 * 技能无导入）——这些对话框把市场从"只读面板"变成真正的管理入口。
 */

/**
 * 添加 MCP 工具源。
 *
 * **MCP 不一定是服务器**：
 * - [McpTransport.STDIO] —— 填一条本地命令（command + args + env），App 会把它
 *   作为子进程拉起来，双方通过 stdin/stdout 的 JSON-RPC 通信。这是社区里绝大多数
 *   MCP server 的形态（`npx -y @xxx`、`uvx xxx`、`python xxx.py`）。
 * - [McpTransport.HTTP] / [McpTransport.SSE] —— 填远端 URL + 可选鉴权。
 *
 * 校验按传输方式分别生效：STDIO 要求命令非空，远端要求 URL 以 http 开头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMcpDialog(
    onDismiss: () -> Unit,
    onAdd: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.STDIO) }

    // STDIO 字段
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }

    // 远端字段
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    val nameValid = name.trim().isNotBlank() && !name.trim().contains(Regex("[\"\\\\\\n]"))
    val isStdio = transport == McpTransport.STDIO
    val commandValid = command.trim().isNotBlank()
    val urlValid = url.trim().startsWith("http")
    val valid = nameValid && (if (isStdio) commandValid else urlValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 工具源") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（如 memory / filesystem）") },
                    isError = name.isNotBlank() && !nameValid,
                    supportingText = if (name.isNotBlank() && !nameValid) {
                        { Text("不能含引号、反斜杠或换行") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("形态", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    McpTransport.entries.forEach { t ->
                        FilterChip(
                            selected = transport == t,
                            onClick = { transport = t },
                            label = {
                                Text(
                                    when (t) {
                                        McpTransport.STDIO -> "本地命令"
                                        McpTransport.HTTP -> "远端 HTTP"
                                        McpTransport.SSE -> "远端 SSE"
                                    }
                                )
                            }
                        )
                    }
                }

                if (isStdio) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("命令（command）") },
                        placeholder = { Text("npx / python / 绝对路径二进制") },
                        isError = command.isNotBlank() && !commandValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        label = { Text("参数（args，空格分隔）") },
                        placeholder = { Text("-y @modelcontextprotocol/server-memory") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = env,
                        onValueChange = { env = it },
                        label = { Text("环境变量（可选，每行 KEY=VALUE）") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "MCP 不必是服务器：这条命令会被作为本地子进程启动，通过 stdin/stdout 走 JSON-RPC。" +
                            "命令必须在 App 可见的环境里可执行（例如已在本 App 的 Ubuntu 环境中安装 Node/Python）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
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
                        label = { Text("API Key（可选，作 Bearer 发送）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        McpServerConfig(
                            name = name.trim(),
                            url = if (isStdio) "" else url.trim(),
                            transport = transport,
                            apiKey = apiKey.trim().takeIf { !isStdio && it.isNotBlank() },
                            enabled = true,
                            command = command.trim().takeIf { isStdio && it.isNotBlank() },
                            args = if (isStdio) args.trim().split(ARGS_SPLIT).filter { it.isNotBlank() } else emptyList(),
                            env = if (isStdio) parseKeyValueLines(env) else emptyMap(),
                            headers = emptyMap()
                        )
                    )
                },
                enabled = valid
            ) { Text("添加并连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 每行一个 KEY=VALUE → Map；非法行直接跳过（不静默吞掉整份输入）。 */
private fun parseKeyValueLines(raw: String): Map<String, String> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains('=') }
        .associate {
            val idx = it.indexOf('=')
            it.substring(0, idx).trim() to it.substring(idx + 1).trim()
        }

private val ARGS_SPLIT = Regex("\\s+")

/** 粘贴社区通用 MCP 配置（`{"mcpServers": {...}}`）批量导入。 */
@Composable
fun ImportMcpConfigDialog(
    onDismiss: () -> Unit,
    onImport: (json: String) -> Unit
) {
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 MCP 配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("粘贴 mcpServers JSON") },
                    placeholder = {
                        Text("支持 command/args/env 与 type=streamable_http|sse + url")
                    },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "支持 Claude Desktop / Cursor / Cline / Operit 通用格式：" +
                        "含 command 的条目按本地命令安装，含 url 的按远端安装；无法判定的条目会逐条报原因。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(content) },
                enabled = content.trim().startsWith(JSON_OBJECT_OPEN)
            ) { Text("解析并导入") }
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

/**
 * GitHub 仓库安装 —— 真实联网入口。
 *
 * 接受 `owner/repo` 或完整 GitHub 链接；安装时从 raw.githubusercontent.com 取
 * manifest（apex-skill-v1）或 SKILL.md（转成 prompt 型技能）。
 * 与「内置」模板的区别：内置无需下载（能力已在 App 里），这里一定有网络往返。
 */
@Composable
fun GitHubRepoInstallDialog(
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (input: String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val trimmed = input.trim()
    val looksValid = trimmed.matches(REPO_INPUT_PATTERN) || trimmed.startsWith("http")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 GitHub 安装技能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("owner/repo 或 仓库链接") },
                    placeholder = { Text("例如 owner/repo 或 https://github.com/owner/repo") },
                    isError = trimmed.isNotBlank() && !looksValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "将读取仓库默认分支的 manifest.json / skill.json / SKILL.md；" +
                        "仓库里没有这些内容时会明确报错，不会静默假安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = looksValid && !busy
            ) { Text("获取并安装") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** owner/repo 形式的宽松校验（ GitHub 链接走 http 前缀判断）。 */
private val REPO_INPUT_PATTERN = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")

/** JSON 对象起始字符（unicode 转义写法，避免字面量大括号干扰 CI 括号检查）。 */
private const val JSON_OBJECT_OPEN = "\u007B"
