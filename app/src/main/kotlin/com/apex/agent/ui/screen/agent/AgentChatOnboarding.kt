package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.screen.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

// ═══════════════════════════════════════════════════════════════
// UX-3：未配置 API 引导卡（空会话 Onboarding）
// ═══════════════════════════════════════════════════════════════
// 首启用户没有模型 API Key 时发送消息只会得到 NoOpLlmClient 的静默空转
// （DynamicLlmClient 在 LlmConfig.isValid=false 时降级）。与其让用户撞墙，
// 不如在空会话聊天区顶部给出一条明确的配置路径。独立文件原因同
// AgentMessageActions.kt：AgentChatViewModel 已贴 1200 行门禁。

/**
 * LLM 是否已配置的聚合判断流。
 *
 * 判定口径与运行时完全一致：默认 Profile → 其 Provider → [LlmConfig.isValid]
 * （baseUrl / apiKey / model 均非空且 http(s) scheme）——这正是
 * DynamicLlmClient.buildDelegate 决定"真 client vs NoOp 降级"的同一条边界，
 * 引导卡只在引擎真的无法出话时出现，不会误伤已配置用户。
 *
 * Ollama/LM Studio 等本地端点经内置 Provider（apiKeys 为空）同样判未配置：
 * 与实际行为一致（fromProfile 取 apiKeys.firstOrNull() 为空串 → isValid=false），
 * 用户在设置里为本地 Provider 填任意占位 Key 后卡片即消失。
 */
internal fun SettingsRepository.llmConfiguredFlow(scope: CoroutineScope): StateFlow<Boolean> =
    combine(profiles, providers) { _, _ -> defaultLlmConfig().isValid }
        .stateIn(scope, SharingStarted.Eagerly, defaultLlmConfig().isValid)

/**
 * 未配置 API 引导卡（消息列表为空 && !llmConfigured 时渲染在聊天区顶部）。
 *
 * 视觉与全局主题一致的 emerald/mint 色调（colorScheme.primary 族，
 * 深色 neon-mint / 浅色 emerald 双主题自动适配），描边卡片语言与
 * 气泡/思考卡/错误块同族（drawBehind + Stroke）。
 */
@Composable
internal fun LlmSetupGuideCard(onOpenSettings: () -> Unit) {
    val border = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = border,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "配置你的 AI 大脑",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "3 分钟接入一个大模型，Agent 才能开始思考",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "尚未配置可用的模型端点。设置一个 Provider 并填入 API Key，" +
                    "即可开始对话、执行工具与长任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text("去配置")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持 DeepSeek / OpenRouter / Ollama 等任意 OpenAI 兼容端点",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
