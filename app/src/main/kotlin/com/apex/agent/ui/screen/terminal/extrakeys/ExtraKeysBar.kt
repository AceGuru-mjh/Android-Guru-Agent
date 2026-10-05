package com.apex.agent.ui.screen.terminal.extrakeys

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.platform.terminal.io.TerminalKey

/**
 * T87：扩展键行渲染器（Termux extra-keys 等价物 —— 用户自定义宏）。
 *
 * 数据来自 [ExtraKeysConfig]（设置抽屉可增删；默认布局贴近本项目高频命令）。
 * 与内置 [com.apex.agent.ui.screen.terminal.KeyToolbar] 互补：内置行覆盖
 * 通用编辑/导航，本行覆盖「项目特定」命令（apt-fix / python3 / git status…）。
 */
@Composable
fun ExtraKeysBar(
    layout: List<List<ExtraKeysConfig.ExtraKey>>,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onPaste: () -> Unit
) {
    if (layout.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // #244：chrome 改主题感知（旧写死 0xFF101613 深色与浅色主题冲突）
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (row in layout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    // T90：垂直 padding 对齐 KeyToolbar（旧版只有水平 5dp +
                    // 尾部 2dp Spacer —— 两栏堆叠处垂直节奏不一致，视觉接缝）
                    .padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (key in row) {
                    ExtraKeyButton(
                        key = key,
                        onText = onText,
                        onKey = onKey,
                        onControl = onControl,
                        onPaste = onPaste
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    key: ExtraKeysConfig.ExtraKey,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onPaste: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            // #244：键帽改 primaryContainer（暗态 MINT = 0C3A2C，与旧 0xFF1F3328
            // 观感几乎一致；亮态随主题浅调，强调色区分于内置键栏的恒深 chrome）
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable {
                when (key.kind) {
                    ExtraKeysConfig.MacroKind.TEXT -> onText(key.payload)
                    // CMD：文本 + 回车 —— 与用户手动敲完按 Enter 完全同路
                    //（经 VM 的 sendInput → 黑白名单门禁 + 历史记录，零旁路）。
                    ExtraKeysConfig.MacroKind.CMD -> onText(key.payload + "\r")
                    ExtraKeysConfig.MacroKind.KEY -> resolveTerminalKey(key.payload)?.let(onKey)
                    ExtraKeysConfig.MacroKind.CTRL -> key.payload.firstOrNull()?.let(onControl)
                    ExtraKeysConfig.MacroKind.PASTE -> onPaste()
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            key.label,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            // #244：与键帽成对的 onPrimaryContainer（暗态 MINT = 9CF3D2 ≈ 旧 0xFFB9E8D2）
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** 宏载荷 → TerminalKey（未知名静默忽略 —— 宁可无动作也不误发 ENTER）。 */
private fun resolveTerminalKey(name: String): TerminalKey? = runCatching {
    TerminalKey.valueOf(name.uppercase().replace(' ', '_'))
}.getOrNull()

/* #244：旧 KeybarChromeColors（bg=0xFF101613 / keyAccent=0xFF1F3328 /
   keyTextAccent=0xFFB9E8D2 写死深色 chrome）已收编为主题槽位：
   条带 surfaceVariant、键帽 primaryContainer、键面文字 onPrimaryContainer ——
   深色保持既有观感，浅色随主题协调，dynamicColor / 18 套 accent 方案自动跟随。 */
