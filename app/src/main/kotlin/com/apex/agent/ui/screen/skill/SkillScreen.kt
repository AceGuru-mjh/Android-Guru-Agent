package com.apex.agent.ui.screen.skill

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.core.tools.skill.SkillImplementation
import com.apex.agent.core.tools.skill.SkillManifest
import com.apex.agent.core.tools.skill.SkillToolDef
import com.apex.agent.core.tools.skill.SkillRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SkillViewModel @Inject constructor(
    private val skillRegistry: SkillRegistry
) : ViewModel() {

    private val _skills = MutableStateFlow<List<SkillRegistry.InstalledSkill>>(emptyList())
    val skills: StateFlow<List<SkillRegistry.InstalledSkill>> = _skills.asStateFlow()

    /** 最近一次安装/创建的结果消息，供 UI 提示。 */
    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    init { refresh() }

    fun refresh() {
        _skills.value = skillRegistry.getInstalled()
    }

    fun toggleEnabled(skillId: String, enabled: Boolean) {
        skillRegistry.setEnabled(skillId, enabled)
        refresh()
    }

    fun clearMessage() { _lastMessage.value = null }

    /** 供 UI 主动报告提示（如导入文件读取失败）。 */
    fun notify(msg: String) { _lastMessage.value = msg }

    /** 从 manifest JSON 字符串安装 Skill（导入场景）。 */
    fun installFromJson(json: String): Boolean {
        return skillRegistry.install(json).fold(
            onSuccess = { m ->
                refresh()
                _lastMessage.value = "已安装：${m.name}"
                true
            },
            onFailure = { e ->
                _lastMessage.value = "安装失败：${e.message}"
                false
            }
        )
    }

    /**
     * 创建一个最小可运行的新 Skill（Prompt/Composite 类型），落盘后即出现在列表中。
     * 生成的 id 基于 name 的 snake_case，author 标记为 agent-created 以便图标区分。
     */
    fun createSkill(
        name: String,
        description: String,
        type: String,
        prompt: String = ""
    ): Boolean {
        val id = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "skill_${System.currentTimeMillis() }" }
        val manifest = SkillManifest(
            id = id,
            name = name.trim().ifBlank { id },
            description = description.trim(),
            author = "agent-created",
            tools = listOf(
                SkillToolDef(
                    id = id,
                    name = name.trim().ifBlank { id },
                    description = description.trim(),
                    parameters = "{}",
                    implementation = SkillImplementation(type = type)
                )
            ),
            promptInjection = if (type == "prompt") prompt.ifBlank { description } else null
        )
        val json = kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(manifest)
        return installFromJson(json).also {
            if (it) _lastMessage.value = "已创建：${manifest.name}"
        }
    }

    fun uninstall(skillId: String): Boolean {
        val ok = skillRegistry.uninstall(skillId)
        if (ok) {
            refresh()
            _lastMessage.value = "已卸载：$skillId"
        }
        return ok
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillScreen(
    viewModel: SkillViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val message by viewModel.lastMessage.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    var showToast by remember { mutableStateOf(false) }

    // 导入 manifest 文件（apex-skill-v1 JSON）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val json = readUriText(context, uri)
        if (json != null) {
            viewModel.installFromJson(json)
        } else {
            viewModel.notify("无法读取文件，请选择有效的 JSON 文件")
        }
    }

    // 安装/卸载结果提示
    LaunchedEffect(message) {
        if (message != null) showToast = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skill 管理") },
                actions = {
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "导入")
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, contentDescription = "创建")
                    }
                }
            )
        },
        snackbarHost = {
            val msg = message
            if (showToast && msg != null) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showToast = false; viewModel.clearMessage() }) {
                            Text("知道了")
                        }
                    }
                ) { Text(msg) }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 提示条
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(
                        "提示：你可以对Agent说\"帮我下载一个网页自动化skill\"，Agent会自动搜索并安装。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (skills.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有安装任何 Skill\n\n可以在对话中让 Agent 帮你下载，\n或点击右上角 [+] 创建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(skills, key = { it.manifest.id }) { skill ->
                        SkillCard(
                            skill = skill,
                            onToggle = { enabled -> viewModel.toggleEnabled(skill.manifest.id, enabled) },
                            onUninstall = { pendingUninstall = skill.manifest.id }
                        )
                    }
                }
            }

            // ── 创建 Skill 对话框 ──
            if (showCreate) {
                CreateSkillDialog(
                    onDismiss = { showCreate = false },
                    onCreate = { name, desc, type, prompt ->
                        viewModel.createSkill(name, desc, type, prompt)
                        showCreate = false
                    }
                )
            }

            // ── 卸载确认 ──
            pendingUninstall?.let { skillId ->
                AlertDialog(
                    onDismissRequest = { pendingUninstall = null },
                    title = { Text("卸载 Skill") },
                    text = { Text("确定要卸载 $skillId 吗？该操作会从磁盘删除 manifest。") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.uninstall(skillId)
                            pendingUninstall = null
                        }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
                    }
                )
            }
        }
    }
}

/** 读取 content URI 的文本内容（用于导入 manifest 文件）。 */
private fun readUriText(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}

/** 创建 Skill 对话框：填写名称/描述/类型，Prompt 类型可附注入文本。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, type: String, prompt: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val types = listOf("composite" to "组合", "prompt" to "提示注入", "script" to "脚本")
    var selectedType by remember { mutableStateOf("composite") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建 Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { (value, label) ->
                        val sel = value == selectedType
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedType = value }
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                if (selectedType == "prompt") {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt 注入内容") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, description, selectedType, prompt) }
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SkillCard(
    skill: SkillRegistry.InstalledSkill,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit
) {
    val enabledBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (skill.enabled)
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = enabledBorderColor,
                            style = Stroke(1.dp.toPx()),
                            cornerRadius = CornerRadius(12.dp.toPx())
                        )
                    }
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when (skill.manifest.author) {
                            "apex-builtin" -> Icons.Default.Star
                            "agent-created" -> Icons.Default.AutoFixHigh
                            "apex-community" -> Icons.Default.Download
                            else -> Icons.Default.Star
                        },
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    skill.manifest.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    skill.manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "v1.0.0 · ${skill.manifest.author ?: "unknown"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle
            )

            IconButton(onClick = onUninstall) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "卸载",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
