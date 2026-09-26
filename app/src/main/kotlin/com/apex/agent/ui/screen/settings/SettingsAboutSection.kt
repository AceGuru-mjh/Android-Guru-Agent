package com.apex.agent.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.BuildConfig
import com.apex.agent.R

/** 项目仓库地址 —— About 区对外跳转目标（源代码 / Issue / PR）。 */
private const val REPO_URL = "https://github.com/AceGuru-mjh/Android-Guru-Agent"

/** Star 引导行图标色：琥珀色，与 M3 主色拉开距离，白/深底上都醒目。 */
private val StarAmber = Color(0xFFF59E0B)

/**
 * 设置页「关于」区（SettingsScreen 界面页签第 4 区块）。
 *
 * 从 SettingsScreen.kt 拆出（文件行数预算 1200）。
 * 全部真实数据：BuildConfig 版本号 + 实际依赖清单 —— 无写死版本串。
 * v1.4.1 起内置更新检查（发布仓库 version.json）；v1.4.2 起更新面板升级为
 * 独立组件 [UpdatePanel]：补丁增量更新 + 高速节点/镜像选择（见该文件头注释）。
 */
@Composable
internal fun AboutSection() {
    val context = LocalContext.current

    SectionCard(
        title = stringResource(R.string.settings_about_title),
        icon = Icons.Default.Info,
        subtitle = stringResource(R.string.settings_about_subtitle),
        initiallyExpanded = false
    ) {
        // 版本（BuildConfig 真实值）
        SettingInfoRow(stringResource(R.string.settings_about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        SettingInfoRow(stringResource(R.string.settings_about_package), BuildConfig.APPLICATION_ID)
        SettingInfoRow(stringResource(R.string.settings_about_build_type), BuildConfig.BUILD_TYPE)

        // ── 更新面板（检查更新 / 增量补丁 / 高速节点镜像）────────────────────
        UpdatePanel()

        // 开源组件（本项目直接引入的运行时依赖 —— 真实清单，非装饰）
        Text(
            stringResource(R.string.settings_about_components),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.settings_about_components_list),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        // 仓库跳转：ACTION_VIEW 直接打开 GitHub 仓库页（不再走剪贴板复制）。
        // runCatching 兜底：极端环境无浏览器 Activity 时不崩溃，Toast 告知。
        // Toast 文案上提到组合层取词（stringResource 不可在非 Compose lambda 中调用）
        val noBrowserHint = stringResource(R.string.settings_about_no_browser)
        val openRepo: () -> Unit = { openUrl(context, REPO_URL, noBrowserHint) }
        Button(
            onClick = openRepo,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_about_view_repo))
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
                stringResource(R.string.settings_about_star),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            stringResource(R.string.settings_about_credits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * ACTION_VIEW 打开外链的统一出口：runCatching 兜底极端环境无浏览器 Activity
 * 时不崩溃，Toast 告知（仓库页与更新下载共用 —— 本区与 [UpdatePanel] 同包）。
 */
internal fun openUrl(context: android.content.Context, url: String, noBrowserHint: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, noBrowserHint, Toast.LENGTH_SHORT).show()
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
