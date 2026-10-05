package com.apex.agent.ui.screen.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R

/**
 * # Code Workspace Panel — Git 工作区面板（v6，右上角入口）
 *
 * 用户规格：「在 coding 模式右上角设立一个工作区，显示变动的文件和
 * git 功能」。ModalBottomSheet 形态（与长任务中心/思考指南同款语言）：
 *
 * - 头部：分支 + 变更计数 + 刷新；非仓库态 → 初始化引导；
 * - 变更文件列表：porcelain 状态徽标 + 路径；点击行按需展开 diff
 *   （controller.loadDiff 缓存，等宽渲染，高度封顶内滚）；
 * - 提交区：说明输入 + 一键提交全部变更（agent 身份署名）；
 * - 空态：工作区干净。
 *
 * 列表高度纪律：LazyColumn heightIn(max) 封顶内滚（长变更列表不把
 * sheet 撑出屏幕）；diff 区同规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeWorkspacePanel(
    controller: CodeGitPanelController,
    onDismiss: () -> Unit
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var commitDraft by remember { mutableStateOf("") }
    var expandedPath by remember { mutableStateOf<String?>(null) }

    // 打开即拉取一次现场（后续手动刷新）
    LaunchedEffect(Unit) { controller.refresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // ═══ 头部：标题 + 分支 + 刷新 ═══
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CallSplit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.code_git_panel_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    state.branch?.let { branch ->
                        Text(
                            text = stringResource(R.string.code_git_branch_fmt, branch),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { controller.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.code_git_refresh),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.size(12.dp))

            when {
                // ═══ 非仓库：初始化引导 ═══
                !state.isRepo -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.code_git_not_repo),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(10.dp))
                            Button(onClick = { controller.initRepo() }) {
                                Text(stringResource(R.string.code_git_init))
                            }
                        }
                    }
                }

                // ═══ 干净工作区 ═══
                !state.hasChanges -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.code_git_clean),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ═══ 变更列表 + 提交区 ═══
                else -> {
                    Text(
                        text = stringResource(R.string.code_git_changes_fmt, state.changes.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(6.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                    ) {
                        items(state.changes, key = { it.path }) { change ->
                            GitFileRow(
                                change = change,
                                expanded = expandedPath == change.path,
                                diffText = state.diffs[change.path],
                                onClick = {
                                    val target = if (expandedPath == change.path) null else change.path
                                    expandedPath = target
                                    if (target != null) controller.loadDiff(target)
                                }
                            )
                        }
                    }

                    Spacer(Modifier.size(12.dp))

                    // ═══ 提交区 ═══
                    OutlinedTextField(
                        value = commitDraft,
                        onValueChange = { commitDraft = it },
                        placeholder = { Text(stringResource(R.string.code_git_commit_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                controller.commitAll(commitDraft)
                                commitDraft = ""
                            },
                            enabled = commitDraft.isNotBlank() && !state.committing
                        ) {
                            Text(stringResource(R.string.code_git_commit))
                        }
                        if (state.committing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            // ═══ 操作结果回执（成功摘要 / 错误）═══
            state.message?.let { msg ->
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller.dismissMessage() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (state.messageIsError) Icons.Default.ErrorOutline
                            else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 单行变更：状态徽标 + 路径；展开态内嵌 diff（等宽 + 内滚封顶）。 */
@Composable
private fun GitFileRow(
    change: GitFileChange,
    expanded: Boolean,
    diffText: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "${change.badge} ${change.path}" }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            StatusBadge(change.badge)
            Spacer(Modifier.width(8.dp))
            Text(
                text = change.path,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded) {
            val diff = diffText ?: return
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (diff.isBlank()) {
                    Text(
                        text = stringResource(R.string.code_git_new_file),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                } else {
                    Text(
                        text = diff,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}

/** 状态徽标：M=修改 A=新增 D=删除 N=未跟踪 U=冲突 其他=原码首字符。 */
@Composable
private fun StatusBadge(badge: String) {
    val (bg, fg) = when (badge) {
        "A", "N" -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        "D" -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        "U" -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        modifier = Modifier.size(width = 22.dp, height = 20.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = fg
            )
        }
    }
}
