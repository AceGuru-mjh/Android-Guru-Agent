package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import android.widget.Toast

/**
 * 设置页「关于」区（SettingsScreen 界面页签第 4 区块）。
 *
 * 从 SettingsScreen.kt 拆出（文件行数预算 1200）。
 * 全部真实数据：BuildConfig 版本号 + 实际依赖清单 —— 无写死版本串。
 */
@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    SectionCard(
        title = "关于",
        icon = Icons.Default.Info,
        subtitle = "版本 / 开源组件 / 仓库",
        initiallyExpanded = false
    ) {
        // 版本（BuildConfig 真实值）
        SettingInfoRow("版本", "${com.apex.agent.BuildConfig.VERSION_NAME} (${com.apex.agent.BuildConfig.VERSION_CODE})")
        SettingInfoRow("应用包名", com.apex.agent.BuildConfig.APPLICATION_ID)
        SettingInfoRow("构建类型", com.apex.agent.BuildConfig.BUILD_TYPE)

        // 开源组件（本项目直接引入的运行时依赖 —— 真实清单，非装饰）
        Text(
            "开源组件",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Jetpack Compose · Hilt (Dagger) · Room · OkHttp · kotlinx.serialization · " +
                "kotlinx.coroutines · Coil · Shizuku API · WorkManager · androidx.security " +
                "· Haze (Liquid Glass) · Vico (图表) · EasyFloat",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        // 仓库链接（复制到剪贴板 —— 无浏览器依赖的诚实交互）
        val clipboard = LocalClipboardManager.current
        OutlinedButton(onClick = {
            val repo = "https://github.com/AceGuru-mjh/Android-Guru-Agent"
            clipboard.setText(AnnotatedString(repo))
            Toast.makeText(context, "仓库地址已复制", Toast.LENGTH_SHORT).show()
        }) {
            Text("复制项目仓库地址")
        }
        Text(
            "本项目大量架构受益于开源社区（Termux、proot、Operit 等），致敬所有贡献者。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SettingInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
