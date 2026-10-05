package com.apex.agent.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.github.GithubRepoNormalizer
import com.apex.agent.github.GithubTokenManager
import kotlinx.coroutines.launch

/**
 * GitHub 图标按钮（输入栏中，/ 和 + 之间）
 *
 * 图标使用 GitHub 官方 Octocat mark（ic_github_mark）——旧实现用通用
 * Link/LinkOff 图标，无法与其它“连接器”语义区分。连接态以颜色区分：
 * - 已连接：mark + primary 色
 * - 未连接：mark + onSurfaceVariant 色
 *
 * 点击展开下拉菜单：
 * - 已连接 → 默认仓库信息行（v3 S3，defaultRepo 流）+ 用户名 + 断开按钮
 * - 未连接 → "连接 GitHub"（浏览器跳转）+ "Token 密钥访问"（弹对话框输入）
 */
@Composable
fun GithubIconButton(
    tokenManager: GithubTokenManager,
    modifier: Modifier = Modifier
) {
    val connectionState by tokenManager.connectionState.collectAsStateWithLifecycle()
    // v3 S3：默认仓库信息行（连接态菜单首行；未设置时显示引导文案）
    val defaultRepo by tokenManager.defaultRepo.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = if (connectionState.isConnected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else
                MaterialTheme.colorScheme.surfaceContainerHigh,
            // UI-012：48dp 触区红线（原 40dp，与输入行全列按钮统一 48 系）
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github_mark),
                    contentDescription = "GitHub",
                    tint = if (connectionState.isConnected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            if (connectionState.isConnected) {
                // 默认仓库信息行（非交互 Text —— 菜单 Column 直接子项）：
                // 与设置页、系统提示词注入共用同一 defaultRepo 数据源
                Text(
                    text = if (defaultRepo.isBlank()) {
                        stringResource(R.string.github_v3_menu_default_repo_unset)
                    } else {
                        stringResource(R.string.github_v3_menu_default_repo, defaultRepo)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                // P2 i18n：菜单文案随语言取词（旧实现硬编码中文）
                                stringResource(R.string.github_menu_connected, connectionState.username ?: "GitHub"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                stringResource(R.string.github_menu_disconnect),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        tokenManager.disconnect()
                        showMenu = false
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.github_menu_connect)) },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_github_mark), null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        showMenu = false
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://github.com/settings/tokens?type=beta")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.github_menu_token)) },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_github_mark), null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        showMenu = false
                        showTokenDialog = true
                    }
                )
            }
        }
    }

    if (showTokenDialog) {
        GithubTokenDialog(
            onDismiss = { showTokenDialog = false },
            onSubmit = { token ->
                // suspend 调用：在 IO 调度器中验证 Token；返回 username 表示成功，null 表示失败
                tokenManager.validateToken(token)
            },
            onSuccess = { token, username, normalizedRepo ->
                tokenManager.saveToken(token, username)
                // 地址框已归一化的 canonical 直存（null/未填 = 不改动既有默认仓库）
                normalizedRepo?.let { tokenManager.saveDefaultRepoCanonical(it) }
                showTokenDialog = false
            }
        )
    }
}

/**
 * Token + 默认仓库输入对话框（v3 S3 升级）。
 *
 * 相对旧版（单 Token 输入）的升级：
 * 1. 新增「GitHub 地址（可选）」输入框：placeholder 提示三种形态；输入
 *    非空时 supportingText 实时显示 [GithubRepoNormalizer] 归一化结果
 *    （`✓ 已识别：仓库 owner/repo` / `✓ 已识别：用户 owner` / 红字无法
 *    识别）。归一化失败不阻断提交——按未填写处理（onSuccess 第三参传
 *    null，调用方不改动既有默认仓库）；
 * 2. `onSubmit` 只负责验证 Token（返回 username 或 null）；地址归一化由
 *    对话框自行完成，确认时经 `onSuccess(token, username, normalizedRepo)`
 *    透传 canonical（`owner` / `owner/repo`）；
 * 3. Token 侧原样保留：格式预检（ghp_ / github_pat_ 前缀）、密码掩码、
 *    验证失败 inline 错误、验证期间禁用输入与按钮。
 *
 * `internal` 以便 CodeScreen（/mcp:github 未连接信号）与市场页 GitHub 账号
 * 分区复用同一份输入 UI —— 避免多处维护；3 个调用点签名保持一致。
 */
@Composable
internal fun GithubTokenDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (token: String) -> String?,
    onSuccess: (token: String, username: String, normalizedRepo: String?) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var repoInput by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 地址实时归一化反馈（每次输入重算——纯函数，开销可忽略）：
    // repoRef = null 表示未填写（空）或无法识别。文案在组合上下文内预解析，
    // slot lambda 只捕获纯字符串（与 Token 字段 errorMessage 同款模式）。
    val repoRef = GithubRepoNormalizer.normalize(repoInput)
    val repoFeedbackText: String? = when {
        repoInput.isBlank() -> null
        repoRef == null -> stringResource(R.string.github_v3_dialog_repo_invalid)
        repoRef.repo != null -> stringResource(
            R.string.github_v3_dialog_repo_ok_repo,
            GithubRepoNormalizer.canonical(repoRef)
        )
        else -> stringResource(R.string.github_v3_dialog_repo_ok_user, repoRef.owner)
    }
    val repoFeedbackIsError = repoInput.isNotBlank() && repoRef == null
    // onClick（非组合上下文）内使用的文案预解析（stringResource 不可在点击回调里调）
    val tokenPrefixErrorText = stringResource(R.string.github_v3_dialog_token_prefix_error)
    val tokenInvalidText = stringResource(R.string.github_v3_dialog_token_invalid)

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Text(stringResource(R.string.github_v3_dialog_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.github_v3_dialog_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.github_v3_dialog_token_label)) },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { msg ->
                        {
                            Text(msg, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )
                OutlinedTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    label = { Text(stringResource(R.string.github_v3_dialog_repo_label)) },
                    placeholder = { Text(stringResource(R.string.github_v3_dialog_repo_placeholder)) },
                    singleLine = true,
                    // 输入非空时实时归一化反馈（合法=绿色已识别；非法=红字提示
                    // 但不阻断提交——按未填写处理，Token 连接照常进行）
                    supportingText = repoFeedbackText?.let { feedback ->
                        {
                            Text(
                                feedback,
                                color = if (repoFeedbackIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedToken = token.trim()
                    if (trimmedToken.isBlank()) return@Button

                    // 格式预检：避免无效 token 浪费网络请求
                    if (!isValidTokenFormat(trimmedToken)) {
                        errorMessage = tokenPrefixErrorText
                        return@Button
                    }

                    scope.launch {
                        isValidating = true
                        errorMessage = null
                        val username = onSubmit(trimmedToken)
                        isValidating = false
                        if (username != null) {
                            // 地址归一化在确认时定格（失败/未填 → null，不改动既有值）
                            val normalizedRepo = GithubRepoNormalizer.normalize(repoInput)
                                ?.let { GithubRepoNormalizer.canonical(it) }
                            onSuccess(trimmedToken, username, normalizedRepo)
                            onDismiss()
                        } else {
                            errorMessage = tokenInvalidText
                        }
                    }
                },
                enabled = token.isNotBlank() && !isValidating
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (isValidating) stringResource(R.string.github_v3_dialog_validating)
                    else stringResource(R.string.github_v3_dialog_connect)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isValidating
            ) { Text(stringResource(R.string.github_v3_dialog_cancel)) }
        }
    )
}

/**
 * GitHub Personal Access Token 格式预检。
 * - 经典 PAT：ghp_xxxx...（40 字符）
 * - Fine-grained PAT：github_pat_xxxx...
 */
private fun isValidTokenFormat(token: String): Boolean =
    token.startsWith("ghp_") || token.startsWith("github_pat_")
