package com.apex.agent.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.apex.agent.github.GithubTokenManager
import kotlinx.coroutines.launch

/**
 * GitHub 图标按钮（输入栏中，/ 和 + 之间）
 *
 * - 已连接：Link 图标 + primary 色
 * - 未连接：LinkOff 图标 + onSurfaceVariant 色
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
            modifier = Modifier.size(40.dp)
        ) {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (connectionState.isConnected) Icons.Default.Link else Icons.Default.LinkOff,
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
                                "已连接: ${connectionState.username ?: "GitHub"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "点击断开",
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
                    text = { Text("连接 GitHub") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Link, null,
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
                    text = { Text("GitHub Token (ghp_*) 密钥访问") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.LinkOff, null,
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
 * 4. 加入格式预检（ghp_ / github_pat_ 前缀）。
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
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = { Text("输入 GitHub Personal Access Token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "在 GitHub → Settings → Developer settings → Personal access tokens 中创建。\n需要 repo 权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        errorMessage = null
                    },
                    label = { Text("ghp_xxxx 或 github_pat_xxx") },
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedToken = token.trim()
                    if (trimmedToken.isBlank()) return@Button

                    // 格式预检：避免无效 token 浪费网络请求
                    if (!isValidTokenFormat(trimmedToken)) {
                        errorMessage = "Token 应以 ghp_ 或 github_pat_ 开头"
                        return@Button
                    }

                    scope.launch {
                        isValidating = true
                        errorMessage = null
                        val username = onSubmit(trimmedToken)
                        isValidating = false
                        if (username != null) {
                            onSuccess(trimmedToken, username)
                            onDismiss()
                        } else {
                            errorMessage = "Token 验证失败，请检查权限或网络"
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
                Text(if (isValidating) "验证中..." else "连接")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isValidating
            ) { Text("取消") }
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
