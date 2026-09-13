package com.apex.agent.browser.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * JsDialogHost —— JS alert / confirm / prompt 的「人机双通道」对话框。
 *
 * 与「自动 confirm + 回灌 snapshot」旧方案的本质区别：
 * - 人在环：真实弹窗，用户裁决；
 * - Agent 在环：「交给 Agent 决定」把弹窗转交引擎侧 Agent 策略异步裁决，
 *   对话框关闭，最终结果仍走 resolve 流（引擎负责回放 JsResult）；
 * - 弹窗队列逐个处理，不会并发叠弹。
 */

@Composable
fun JsDialogHost(
    gateway: BrowserEngineGateway,
    config: ChromeConfig = ChromeConfig(),
) {
    val requests by gateway.dialogRequests.collectAsState()
    val request = requests.firstOrNull() ?: return
    val p = chromePalette()

    var promptValue by remember(request.id) {
        mutableStateOf(request.promptDefault.orEmpty())
    }

    fun resolve(choice: JsDialogChoice, routeToAgent: Boolean = false) {
        gateway.resolveDialog(
            requestId = request.id,
            choice = choice,
            promptValue = if (request.kind == JsDialogKind.PROMPT) promptValue else null,
            routeToAgent = routeToAgent,
        )
    }

    val icon: ImageVector = when (request.kind) {
        JsDialogKind.ALERT -> Icons.Filled.Info
        JsDialogKind.CONFIRM -> Icons.AutoMirrored.Filled.HelpOutline
        JsDialogKind.PROMPT -> Icons.Filled.Edit
    }

    // 浮窗安全：不用 AlertDialog（其底层 android.app.Dialog 需窗口 token，
    // overlay 场景 BadToken）；ChromeScrimDialog 自绘遮罩 + 卡片，零窗口依赖。
    ChromeScrimDialog(onDismiss = { resolve(JsDialogChoice.DISMISS) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = p.accent, modifier = Modifier.height(20.dp))
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                text = if (request.routing == JsDialogRouting.AGENT) {
                    "Agent 待决 · ${request.originHost}"
                } else {
                    "网页消息 · ${request.originHost}"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(text = request.message, fontSize = 13.sp, lineHeight = 19.sp)
        if (request.kind == JsDialogKind.PROMPT) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = promptValue,
                onValueChange = { promptValue = it },
                singleLine = true,
                label = { Text("输入内容") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (config.agentRoutingEnabled && request.kind != JsDialogKind.ALERT) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { resolve(JsDialogChoice.POSITIVE, routeToAgent = true) }) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.height(14.dp),
                )
                Spacer(Modifier.padding(start = 4.dp))
                Text("交给 Agent 决定", fontSize = 12.sp, color = p.accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (request.kind != JsDialogKind.ALERT) {
                TextButton(onClick = { resolve(JsDialogChoice.NEGATIVE) }) { Text("取消") }
            }
            TextButton(onClick = { resolve(JsDialogChoice.POSITIVE) }) {
                Text(
                    text = when (request.kind) {
                        JsDialogKind.ALERT -> "知道了"
                        else -> "确认"
                    },
                    color = p.accent,
                )
            }
        }
    }
}
