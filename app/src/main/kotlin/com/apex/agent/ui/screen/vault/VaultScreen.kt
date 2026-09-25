package com.apex.agent.ui.screen.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.vault.VaultEntry
import com.apex.agent.vault.VaultEvent
import com.apex.agent.vault.VaultOrigin
import com.apex.agent.vault.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保险库屏（#167）—— 人工储放加密密钥（GitHub token / AI API key 等）。
 *
 * 与 Agent 的边界（顶部安全说明）：本页对人类完全可读写（含明文查看），
 * 而 Agent 侧只能经 vault_list 看到标签与备注、经 vault_paste 直投使用，
 * 明文永不出库（工具层 + 执行器双层保证）。
 */
@Composable
fun VaultScreen(
    viewModel: VaultViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 对话框状态：新增(null) / 编辑(条目)
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    // 删除确认目标
    var pendingDelete by remember { mutableStateOf<VaultEntry?>(null) }
    // snackbar 文案（一次性事件的展示态）
    var snackbarText by remember { mutableStateOf<String?>(null) }
    // 事件 → snackbar（一次性）：文案在组合层解析（vaultEventText 是 @Composable，
    // 不能在 LaunchedEffect 的挂起块内调用），LaunchedEffect 只负责触发与消费。
    val currentEvent = event
    val eventText = if (currentEvent != null) vaultEventText(currentEvent) else null
    LaunchedEffect(currentEvent) {
        if (currentEvent != null) {
            snackbarText = eventText
            viewModel.consumeEvent()
        }
    }

    Scaffold(
        // 内层 Scaffold 置零 insets：状态栏已由根 Scaffold 顶栏承担，避免双重叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            snackbarText?.let { msg ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = {
                        TextButton(onClick = { snackbarText = null }) {
                            Text(stringResource(R.string.vault_got_it))
                        }
                    }
                ) { Text(msg) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showEditor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.vault_add)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ═══ 顶部安全说明卡（Agent 只能看到标签与备注）═══
            SecurityNoteCard()

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.vault_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        VaultEntryCard(
                            entry = entry,
                            onCopy = {
                                copyToClipboard(context, entry.secret)
                                snackbarText = context.getString(R.string.vault_copied)
                            },
                            onEdit = { editing = entry; showEditor = true },
                            onDelete = { pendingDelete = entry }
                        )
                    }
                }
            }
        }
    }

    // ═══ 新增 / 编辑对话框 ═══
    if (showEditor) {
        VaultEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { label, note, secret ->
                viewModel.saveEntry(label, note, secret, editing?.id)
                showEditor = false
            }
        )
    }

    // ═══ 删除确认对话框 ═══
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.vault_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.vault_delete_confirm_body, target.label))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(target.id)
                    pendingDelete = null
                }) {
                    Text(
                        stringResource(R.string.vault_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.vault_cancel))
                }
            }
        )
    }
}

/** 事件 → i18n 文案（VM 只发枚举，文案统一在本层取资源）。 */
@Composable
private fun vaultEventText(event: VaultEvent): String = when (event) {
    VaultEvent.SAVED -> stringResource(R.string.vault_saved)
    VaultEvent.DELETED -> stringResource(R.string.vault_deleted)
    VaultEvent.LABEL_EMPTY -> stringResource(R.string.vault_err_label_empty)
    VaultEvent.LABEL_EXISTS -> stringResource(R.string.vault_err_label_exists)
    VaultEvent.SECRET_EMPTY -> stringResource(R.string.vault_err_secret_empty)
    VaultEvent.ERROR -> stringResource(R.string.vault_err_generic)
}

/** 系统剪贴板复制（用户随后手动粘贴到任意应用）。 */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vault", text))
    }
}

/** 顶部安全说明卡：金库工作原理（Agent 只见标签与备注）。 */
@Composable
private fun SecurityNoteCard() {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(20.dp)
                )
            }
            Column {
                Text(
                    stringResource(R.string.vault_security_note_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.vault_security_note_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 条目卡：标签 / 备注 / 来源徽标 / 用量与更新时间 + 复制·显隐·编辑·删除。 */
@Composable
private fun VaultEntryCard(
    entry: VaultEntry,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var revealed by remember(entry.id) { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── 首行：标签 + 来源徽标 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                OriginBadge(entry.origin)
            }

            // ── 备注（Agent 可见）──
            if (entry.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── 密文区：默认密码式遮蔽，可切换明文（仅人类操作）──
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = if (revealed) entry.secret else "••••••••••••",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (revealed) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (revealed) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            // ── 元信息：用量 + 更新时间 ──
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(
                        if (entry.usageCount > 0) {
                            stringResource(R.string.vault_used_count, entry.usageCount)
                        } else {
                            stringResource(R.string.vault_never_used)
                        }
                    )
                    append("  ·  ")
                    append(
                        stringResource(
                            R.string.vault_updated_at,
                            formatDate(if (entry.lastUsedAt > 0) entry.lastUsedAt else entry.createdAt)
                        )
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── 操作行 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.vault_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.vault_hide else R.string.vault_show
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.vault_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.vault_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 来源徽标：HUMAN=盾牌（人工储放）/ AGENT=机器人（工具写入）。 */
@Composable
private fun OriginBadge(origin: VaultOrigin) {
    val (icon, labelRes, color) = when (origin) {
        VaultOrigin.HUMAN -> Triple(
            Icons.Default.Shield, R.string.vault_origin_human, MaterialTheme.colorScheme.primary
        )
        VaultOrigin.AGENT -> Triple(
            Icons.Default.SmartToy, R.string.vault_origin_agent, MaterialTheme.colorScheme.tertiary
        )
    }
    AssistChip(
        onClick = { /* 纯展示徽标，不可点击 */ },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        },
        label = {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    )
}

/** 新增 / 编辑对话框：label / note / secret（密码式 + 显隐切换）。 */
@Composable
private fun VaultEditorDialog(
    initial: VaultEntry?,
    onDismiss: () -> Unit,
    onSave: (label: String, note: String, secret: String) -> Unit
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var secret by remember { mutableStateOf(initial?.secret ?: "") }
    var secretVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.vault_add_title else R.string.vault_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.vault_label)) },
                    supportingText = { Text(stringResource(R.string.vault_label_hint)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.vault_note)) },
                    supportingText = { Text(stringResource(R.string.vault_note_hint)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.vault_secret)) },
                    supportingText = { Text(stringResource(R.string.vault_secret_hint)) },
                    singleLine = true,
                    visualTransformation = if (secretVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (secretVisible) R.string.vault_hide else R.string.vault_show
                                )
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, note, secret) },
                enabled = label.isNotBlank() && secret.isNotEmpty()
            ) {
                Text(stringResource(R.string.vault_save_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.vault_cancel))
            }
        }
    )
}

/** 本地化时间格式（minSdk 26；Locale.getDefault 随系统语言）。 */
private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
