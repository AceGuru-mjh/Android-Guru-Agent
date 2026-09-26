package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
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
 *
 * @param sandboxAvailable PRoot 沙箱是否就绪（内嵌 Ubuntu rootfs 已安装，由
 *   MarketViewModel 传入，Issue #163）。就绪时 STDIO 表单的「在 PRoot 沙箱中
 *   运行」开关可用且默认开（Android 宿主没有 npx/node，本地命令几乎必然要
 *   沙箱）；未就绪时开关禁用并提示先安装 Ubuntu 环境 —— 默认值 false 保证
 *   接线缺失时用户看到的是诚实的「不可用」而不是能点但必败的开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMcpDialog(
    onDismiss: () -> Unit,
    onAdd: (McpServerConfig) -> Unit,
    sandboxAvailable: Boolean = false
) {
    McpConfigFormDialog(
        title = stringResource(R.string.market_mcp_add_title),
        initial = null,
        sandboxAvailable = sandboxAvailable,
        confirmLabel = stringResource(R.string.market_mcp_add_connect),
        onDismiss = onDismiss,
        onConfirm = onAdd
    )
}

/**
 * 编辑已配置的 MCP 工具源（市场 → 已安装管理 → MCP 行「编辑」）。
 *
 * 表单预填现有配置（URL / 命令 / 参数 / 环境变量 / 沙箱开关），名称锁定只读
 * （改名涉及删旧建新，容易把已连接会话与斜杠菜单状态拆散；需要改名走删除重加）。
 * 保存后由 VM 断开旧连接 → 覆盖写配置 → 自动重连（配置变了旧连接必然失效）。
 *
 * @param initial 当前配置快照（预填表单）。
 * @param sandboxAvailable PRoot 沙箱就绪态（与 [AddMcpDialog] 同源）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMcpDialog(
    initial: McpServerConfig,
    sandboxAvailable: Boolean = false,
    onSave: (McpServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    McpConfigFormDialog(
        title = stringResource(R.string.market_mcp_edit_title),
        initial = initial,
        sandboxAvailable = sandboxAvailable,
        confirmLabel = stringResource(R.string.market_mcp_edit_save),
        onDismiss = onDismiss,
        onConfirm = onSave
    )
}

/**
 * MCP 配置表单的公共主体（添加 / 编辑共用）。
 *
 * - initial == null → 添加模式：名称可编辑，transport 默认 STDIO，沙箱开关默认
 *   跟随可用性；
 * - initial != null → 编辑模式：名称锁定（锁定原因见 [EditMcpDialog]），全部字段
 *   预填，保留原 enabled 偏好。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpConfigFormDialog(
    title: String,
    initial: McpServerConfig?,
    sandboxAvailable: Boolean,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (McpServerConfig) -> Unit
) {
    val isEdit = initial != null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var transport by remember { mutableStateOf(initial?.transport ?: McpTransport.STDIO) }

    // STDIO 字段
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var args by remember { mutableStateOf(initial?.args?.joinToString(" ") ?: "") }
    var env by remember {
        mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "")
    }

    // 远端字段
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }

    // 沙箱开关（Issue #163）：就绪即默认开 —— Android 宿主没有 npx
    //（编辑模式预填原值：用户已显式选过沙箱形态的不再被重置）
    var runInSandbox by remember {
        mutableStateOf(if (isEdit) (initial?.runInSandbox ?: false) else sandboxAvailable)
    }

    val nameValid = name.trim().isNotBlank() && !name.trim().contains(Regex("[\"\\\\\\n]"))
    val isStdio = transport == McpTransport.STDIO
    val commandValid = command.trim().isNotBlank()
    val urlValid = url.trim().startsWith("http")
    val valid = nameValid && (if (isStdio) commandValid else urlValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!isEdit) name = it },
                    readOnly = isEdit,
                    label = { Text(stringResource(R.string.market_mcp_name_label)) },
                    isError = !isEdit && name.isNotBlank() && !nameValid,
                    supportingText = when {
                        isEdit -> {
                            // 编辑模式：名称锁定只读（改名走删除重加）
                            { Text(stringResource(R.string.market_mcp_edit_name_locked)) }
                        }
                        name.isNotBlank() && !nameValid -> {
                            { Text(stringResource(R.string.market_mcp_name_invalid)) }
                        }
                        else -> null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    stringResource(R.string.market_mcp_form_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // BUILTIN 是 App 预置的进程内 transport（工厂在 McpManager 注册表里，
                    // 用户不可自建）—— 添加对话框只暴露 STDIO / HTTP / SSE 三种形态。
                    McpTransport.entries.filter { it != McpTransport.BUILTIN }.forEach { t ->
                        FilterChip(
                            selected = transport == t,
                            onClick = { transport = t },
                            label = {
                                Text(
                                    when (t) {
                                        McpTransport.STDIO -> stringResource(R.string.market_mcp_form_stdio)
                                        McpTransport.HTTP -> stringResource(R.string.market_mcp_form_http)
                                        McpTransport.SSE -> stringResource(R.string.market_mcp_form_sse)
                                        McpTransport.BUILTIN -> stringResource(R.string.market_builtin)
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
                        label = { Text(stringResource(R.string.market_mcp_command_label)) },
                        placeholder = { Text(stringResource(R.string.market_mcp_command_hint)) },
                        isError = command.isNotBlank() && !commandValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        label = { Text(stringResource(R.string.market_mcp_args_label)) },
                        placeholder = { Text("-y @modelcontextprotocol/server-memory") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = env,
                        onValueChange = { env = it },
                        label = { Text(stringResource(R.string.market_mcp_env_label)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Issue #163：沙箱开关 —— Android 宿主没有 npx/node，本地命令
                    // 推荐在 PRoot Ubuntu 内跑；未装环境时禁用并给引导文案。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.market_mcp_sandbox_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(
                                    if (sandboxAvailable) R.string.market_mcp_sandbox_desc
                                    else R.string.market_mcp_sandbox_unavailable
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = runInSandbox && sandboxAvailable,
                            onCheckedChange = { runInSandbox = it },
                            enabled = sandboxAvailable
                        )
                    }
                    Text(
                        stringResource(R.string.market_mcp_stdio_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.market_mcp_url_label)) },
                        isError = url.isNotBlank() && !urlValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.market_mcp_apikey_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        McpServerConfig(
                            name = name.trim(),
                            url = if (isStdio) "" else url.trim(),
                            transport = transport,
                            apiKey = apiKey.trim().takeIf { !isStdio && it.isNotBlank() },
                            // 编辑模式保留原 enabled 偏好（用户禁用的不会因编辑被重新打开）
                            enabled = if (isEdit) initial?.enabled ?: true else true,
                            command = command.trim().takeIf { isStdio && it.isNotBlank() },
                            args = if (isStdio) args.trim().split(ARGS_SPLIT).filter { it.isNotBlank() } else emptyList(),
                            env = if (isStdio) parseKeyValueLines(env) else emptyMap(),
                            // Issue #163：沙箱开关透传（远端形态强制 false；
                            // 未就绪时双重保险归 false，防止意外态写出沙箱配置）
                            runInSandbox = isStdio && runInSandbox && sandboxAvailable,
                            // 编辑模式保留导入路径写入的自定义请求头
                            headers = initial?.headers ?: emptyMap()
                        )
                    )
                },
                enabled = valid
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_cancel)) }
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
        title = { Text(stringResource(R.string.market_mcp_import_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.market_mcp_paste_label)) },
                    placeholder = {
                        Text(stringResource(R.string.market_mcp_paste_hint))
                    },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.market_mcp_paste_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(content) },
                enabled = content.trim().startsWith(JSON_OBJECT_OPEN)
            ) { Text(stringResource(R.string.market_mcp_parse_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_cancel)) }
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
        title = { Text(stringResource(R.string.market_connectors_add_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(stringResource(R.string.market_connector_id_label)) },
                    isError = id.isNotBlank() && !idValid,
                    supportingText = if (id.isNotBlank() && !idValid) {
                        { Text(stringResource(R.string.market_connector_id_invalid)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.market_connector_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.market_connector_type_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
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
                    label = { Text(stringResource(R.string.market_connector_endpoint_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(id, name, type, endpoint) },
                enabled = idValid && nameValid
            ) { Text(stringResource(R.string.market_action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_cancel)) }
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
        title = { Text(stringResource(R.string.market_skill_json_title)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.market_skill_json_label)) },
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
            ) { Text(stringResource(R.string.market_action_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_cancel)) }
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
            ) { Text(stringResource(R.string.market_url_download_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_cancel)) }
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
        title = { Text(stringResource(R.string.market_github_install_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.market_github_input_label)) },
                    placeholder = { Text(stringResource(R.string.market_github_input_hint)) },
                    isError = trimmed.isNotBlank() && !looksValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.market_github_install_body),
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
            ) { Text(stringResource(R.string.market_github_fetch_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_cancel)) }
        }
    )
}

/** owner/repo 形式的宽松校验（ GitHub 链接走 http 前缀判断）。 */
private val REPO_INPUT_PATTERN = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")

/** JSON 对象起始字符（unicode 转义写法，避免字面量大括号干扰 CI 括号检查）。 */
private const val JSON_OBJECT_OPEN = "\u007B"

// ═══ v2 认知市场：依赖图可视化 + 干运行预览对话框 ═══

/**
 * 依赖图对话框 —— 拓扑排序展示技能依赖关系（被依赖者在前）。
 *
 * 用 [SkillDependencyResolver.buildDependencyGraph] 计算节点深度 + 边 + 环 + 缺失依赖。
 * 节点按深度缩进渲染，环节点标红，缺失依赖标黄。
 *
 * @param skills 待展示的技能清单列表
 * @param available 已安装技能 id 集（用于标记缺失/已装）
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SkillDependencyGraphDialog(
    skills: List<com.apex.agent.core.tools.skill.SkillManifest>,
    available: Set<String>,
    onDismiss: () -> Unit
) {
    val graph = remember(skills, available) {
        com.apex.agent.core.tools.skill.SkillDependencyResolver
            .buildDependencyGraph(skills, available)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_close)) }
        },
        title = {
            Column {
                Text(
                    stringResource(R.string.market_depgraph_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.market_depgraph_subtitle, graph.nodes.size, graph.edges.size) +
                        " · " + stringResource(
                            if (graph.isHealthy) {
                                R.string.market_depgraph_healthy
                            } else {
                                R.string.market_depgraph_unhealthy
                            }
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (graph.isHealthy) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 环警告
                if (graph.cycles.isNotEmpty()) {
                    Text(
                        stringResource(R.string.market_depgraph_cycles, graph.cycles.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    graph.cycles.forEach { cycle ->
                        Text(
                            "  " + cycle.joinToString(" → "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 缺失依赖
                if (graph.missingDependencies.isNotEmpty()) {
                    Text(
                        stringResource(R.string.market_depgraph_missing, graph.missingDependencies.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    graph.missingDependencies.forEach { dep ->
                        Text(
                            "  • $dep",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 拓扑节点列表
                Text(
                    stringResource(R.string.market_depgraph_topo),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                graph.nodes.forEach { node ->
                    val indent = "  ".repeat(node.depth)
                    val marker = when {
                        node.isCyclic -> "⟳"
                        node.isInstalled -> "✓"
                        else -> "○"
                    }
                    val color = when {
                        node.isCyclic -> MaterialTheme.colorScheme.error
                        node.isInstalled -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        "$indent$marker ${node.name} (${node.id})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color
                    )
                }
            }
        }
    )
}

/**
 * 干运行预览对话框 —— 安装前预览解析后的 manifest 摘要 + 缺失依赖 + 工具数 + 权限要求。
 *
 * @param preview [MarketInstallManager.dryRunInstall] 返回的预览数据
 * @param onConfirm 确认安装回调
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ManifestDryRunDialog(
    preview: com.apex.agent.marketplace.MarketInstallManager.ManifestPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = preview.canInstall
            ) { Text(stringResource(R.string.market_dryrun_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_cancel)) }
        },
        title = {
            Column {
                Text(preview.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${preview.id} · v${preview.version}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 描述
                if (preview.description.isNotBlank()) {
                    Text(
                        preview.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // 元数据网格
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PreviewChip(
                        stringResource(R.string.market_meta_author),
                        preview.author.ifBlank { "—" }
                    )
                    PreviewChip(stringResource(R.string.market_meta_license), preview.license)
                    PreviewChip(stringResource(R.string.market_meta_trust), preview.trustLevel)
                    PreviewChip(
                        stringResource(R.string.market_meta_category),
                        preview.category ?: "—"
                    )
                    PreviewChip(
                        stringResource(R.string.market_meta_tools),
                        preview.toolCount.toString()
                    )
                    if (preview.hasPromptInjection) {
                        PreviewChip(
                            "Prompt",
                            stringResource(R.string.market_meta_prompt_injection)
                        )
                    }
                    PreviewChip(
                        stringResource(R.string.market_meta_privilege),
                        preview.privilegeLevel
                    )
                }
                // 标签
                if (preview.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.market_meta_tags,
                            preview.tags.joinToString(" ") { "#$it" }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 缺失依赖警告
                if (preview.missingDependencies.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.market_depgraph_missing,
                            preview.missingDependencies.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    preview.missingDependencies.forEach { dep ->
                        Text(
                            "  • $dep",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.market_dryrun_missing_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 权限要求
                if (preview.requirements.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.market_dryrun_requirements),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        preview.requirements.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    )
}

/** 预览 chip —— 键值小标签。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PreviewChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
