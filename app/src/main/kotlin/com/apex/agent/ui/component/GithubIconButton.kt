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
 * - 已连接 → 显示用户名 + 断开按钮
 * - 未连接 → "连接 GitHub"（浏览器跳转）+ "Token 密钥访问"（弹对话框输入）
 */
@Composable
fun GithubIconButton(
    tokenManager: GithubTokenManager,
    modifier: Modifier = Modifier
) {
    val connectionState by tokenManager.connectionState.collectAsStateWithLifecycle()
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
            onSuccess = { token, username ->
                tokenManager.saveToken(token, username)
                showTokenDialog = false
            }
        )
    }
}

/**
 * Token 输入对话框。
 *
 * 修复点：
 * 1. onSubmit 改为 suspend + nullable username 返回值，避免 UI 假死；
 * 2. 增加 errorMessage 字段，验证失败时 inline 提示，不直接关闭弹窗；
 * 3. 调用期间禁用输入框与按钮；
 * 4. 加入格式预检（ghp_ / github_pat_ 前缀）；
 * 5. #229：全部文案 stringResource 化，随系统语言取词（原硬编码中文）。
 *    错误提示经资源 id 存态（onClick/协程非 composable），展示层解析。
 *
 * `internal`（非 private）以便 [com.apex.agent.ui.screen.agent.AgentChatScreen]
 * 在 `/mcp:github` 未连接时复用同一个对话框 —— 避免在两处维护一份 Token 输入 UI。
 */
@Composable
internal fun GithubTokenDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> String?,
    onSuccess: (token: String, username: String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    // #229：错误文案以资源 id 存态（onClick/协程是非 composable 上下文，
    // 不能直接 stringResource），展示层再解析 —— 与导出 chooser 标题同款模式
    var errorMessageRes by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

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
                // #229：标题随语言取词（原硬编码中文）
                Text(stringResource(R.string.github_token_dialog_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    // #229：说明文案随语言取词（原硬编码中文，\n 换行双侧一致）
                    stringResource(R.string.github_token_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        errorMessageRes = null
                    },
                    // #229：占位 label 随语言取词（原硬编码中文）
                    label = { Text(stringResource(R.string.github_token_field_label)) },
                    singleLine = true,
                    isError = errorMessageRes != null,
                    supportingText = errorMessageRes?.let { res ->
                        {
                            Text(stringResource(res), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
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

                    // 格式预检：避免无效 token 浪费网络请求（#229：文案键直存态）
                    if (!isValidTokenFormat(trimmedToken)) {
                        errorMessageRes = R.string.github_token_error_format
                        return@Button
                    }

                    scope.launch {
                        isValidating = true
                        errorMessageRes = null
                        val username = onSubmit(trimmedToken)
                        isValidating = false
                        if (username != null) {
                            onSuccess(trimmedToken, username)
                            onDismiss()
                        } else {
                            errorMessageRes = R.string.github_token_error_validate
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
                // #229：验证中/连接/取消随语言取词（原硬编码中文）
                Text(
                    stringResource(
                        if (isValidating) R.string.github_token_validating
                        else R.string.github_token_connect
                    )
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isValidating
            ) { Text(stringResource(R.string.github_token_cancel)) }
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
