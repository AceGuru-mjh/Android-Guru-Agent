package com.apex.agent.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 项目仓库地址 —— About 区唯一对外跳转目标。 */
private const val REPO_URL = "https://github.com/AceGuru-mjh/Android-Guru-Agent"

/** Star 引导行图标色：琥珀色，与 M3 主色拉开距离，白/深底上都醒目。 */
private val StarAmber = Color(0xFFF59E0B)

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

        // 仓库跳转：ACTION_VIEW 直接打开 GitHub 仓库页（不再走剪贴板复制）。
        // runCatching 兜底：极端环境无浏览器 Activity 时不崩溃，Toast 告知。
        val openRepo: () -> Unit = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
            }.onFailure {
                Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
            }
        }
        Button(
            onClick = openRepo,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("查看 GitHub 仓库")
        }

        // Star 引导小卡：整行可点击，同样跳到仓库页 —— 让用户顺手就能点 Star
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable(onClick = openRepo)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = StarAmber
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "请给我项目来一个 Star，谢谢！",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
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
