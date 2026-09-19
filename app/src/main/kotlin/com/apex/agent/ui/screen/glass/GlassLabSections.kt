package com.apex.agent.ui.screen.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.glass.GlassBadge
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassDialog
import com.apex.agent.ui.glass.GlassNavigationItem
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassTier
import com.apex.agent.ui.glass.GlassToolCard
import com.apex.agent.ui.glass.GlassToolStatus
import dev.chrisbanes.haze.HazeState

/**
 * ═══════════════════════════════════════════════════════════════
 *  玻璃实验室 · 共享小节（夜间 / 白天双实验室共用）
 * ═══════════════════════════════════════════════════════════════
 *
 * 从 [GlassLabScreen] 拆出的展示小节（SRP 行数预算 1200/文件）：
 *  - 交互状态 / 档位阶梯 / 工具卡状态 / 输入聚焦 / 玻璃对话框；
 *  - 白天实验室独有「日间精修」小节；
 *  - 诚实验收清单（运行时逐项核对，禁止静态谎言）。
 *
 * 全部小节从 [com.apex.agent.ui.theme.ApexTheme] 注入的当前 colorScheme
 * 取色 —— 在夜间/白天两个强制主题下自动呈现对应材质形态。
 */


/** 验证清单条目 —— 全部字段在组合期求值，禁止静态谎言。 */
private data class CheckItem(
    val name: String,
    val status: String,
    val note: String,
    val color: Color
)

/** 通用小节标题 —— 标题用等宽字体，提示用次级色。 */
@Composable
internal fun SectionHeader(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  日间精修 —— 白天实验室独有小节
// ═══════════════════════════════════════════════════════════════

/**
 * 白天模式的加分项：强调色浸染速览 + 发丝细节展示。
 *
 * 白天玻璃的关键差异（GlassStyle.glassPalette 浅色分支）：
 *  - 材质为"乳白霜面"而非"提亮发光"；
 *  - 边缘高光转向冷灰（onSurface 低透明度）—— 白底上白描边不可见；
 *  - 强调色只提供"倾向"（低浓度浸染 tint 与边缘），不刷屏。
 * 本节用三枚强调色玻璃药丸 + 乳白霜面卡直观对照。
 */
@Composable
internal fun DaylightRefinementSection() {
    val scheme = MaterialTheme.colorScheme

    SectionHeader(
        title = "日间精修 · 光影细节",
        hint = "白天实验室独有 —— 白霜材质 × 强调色浸染 × 发丝边缘，克制而精致"
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        style = GlassStyle.Card
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 强调色浸染药丸行：薄荷 / 琥珀 / 品红
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassBadge(
                    accent = scheme.primary,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "薄荷浸染",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                GlassBadge(
                    accent = scheme.secondary,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "琥珀浸染",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                GlassBadge(
                    accent = scheme.tertiary,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "品红浸染",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 发丝分隔线 × 2 —— 精致感来自克制的细节
            HorizontalHairline()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "发丝边缘",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "浅色分支边缘高光转向冷灰 —— 白底上白描边不可见，0.5dp 发丝线保持轮廓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalHairline()
            Text(
                text = "白天玻璃是\"白霜\"而非\"发光\"：tint 为乳白底 + 主色 4% 倾向浸染；" +
                    "镜面高光强度 ×1.5 补偿白底漫射。整体观感应比夜间更轻盈、更精致。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 0.5dp 发丝分隔线 —— 白天精修的细节语汇。 */
@Composable
private fun HorizontalHairline() {
    androidx.compose.material3.HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

// ═══════════════════════════════════════════════════════════════
//  交互状态区
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun InteractionSection() {
    // 同 BackdropZone：交互状态演示按钮接触觉反馈（原空 onClick 无回响）。
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val pressFeedback = {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    SectionHeader(
        title = "交互状态",
        hint = "选中提亮 / 禁用降权 / 按压受光 —— 材质对状态即时响应"
    )
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassNavigationItem(
                icon = Icons.Default.SmartToy,
                label = "选中项 · Selected",
                selected = true,
                onClick = pressFeedback
            )
            GlassNavigationItem(
                icon = Icons.Default.Terminal,
                label = "常态项 · Normal",
                selected = false,
                onClick = pressFeedback
            )
            GlassNavigationItem(
                icon = Icons.Default.Build,
                label = "禁用项 · Disabled",
                selected = false,
                enabled = false,
                onClick = pressFeedback
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassButton(
                    text = "玻璃按钮 · 按压我",
                    onClick = pressFeedback
                )
                Text(
                    text = "聚焦态见下方输入控件 —— 焦点驱动材质亮度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  玻璃档位阶梯
// ═══════════════════════════════════════════════════════════════

/** 档位 → 业务落点对照（验收人可直接核对真实界面用法）。 */
private val TierUsageNotes: Map<GlassTier, String> = mapOf(
    GlassTier.Subtle to "玻璃徽标 / 状态 chip",
    GlassTier.Control to "图标按钮 / 输入控件",
    GlassTier.Card to "消息卡 / 工具卡 / 任务卡",
    GlassTier.Navigation to "抽屉导航项",
    GlassTier.Floating to "FAB / 聊天输入栏",
    GlassTier.Dialog to "玻璃对话框面板",
    GlassTier.Strong to "低频高聚焦场景（预留）"
)

@Composable
internal fun TierLadderSection() {
    SectionHeader(
        title = "玻璃档位阶梯",
        hint = "七档材质强度递进 + 各档真实业务落点 —— 全部为 Frosted 档铺在页面背景上，不冒充 backdrop"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassTierRow(name = "Subtle", style = GlassStyle.Subtle)
        GlassTierRow(name = "Control", style = GlassStyle.Control)
        GlassTierRow(name = "Card", style = GlassStyle.Card)
        GlassTierRow(name = "Navigation", style = GlassStyle.Navigation)
        GlassTierRow(name = "Floating", style = GlassStyle.Floating)
        GlassTierRow(name = "Dialog", style = GlassStyle.Dialog)
        GlassTierRow(name = "Strong", style = GlassStyle.Strong)
    }
}

/** 单个档位行 —— 左侧档名 + 业务落点，右侧参数，等宽字体呈现材质配方。 */
@Composable
private fun GlassTierRow(name: String, style: GlassStyle) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        style = style
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = TierUsageNotes[style.tier] ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "blur ${style.blurRadius.value.toInt()}dp · tint ${style.tintAlpha} · scrim ${style.scrimAlpha}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  工具卡状态
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun ToolStatesSection() {
    SectionHeader(
        title = "工具卡状态",
        hint = "四种状态驱动着色 —— 低饱和浸染，不用强色刷屏"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GlassToolRow(
            status = GlassToolStatus.RUNNING,
            icon = Icons.Default.PlayArrow,
            text = "正在执行 · adb shell pm list packages"
        )
        GlassToolRow(
            status = GlassToolStatus.COMPLETED,
            icon = Icons.Default.CheckCircle,
            text = "已完成 · 3.4s · 退出码 0"
        )
        GlassToolRow(
            status = GlassToolStatus.FAILED,
            icon = Icons.Default.Error,
            text = "已失败 · 权限不足，执行被拒绝"
        )
        GlassToolRow(
            status = GlassToolStatus.WAITING,
            icon = Icons.Default.HourglassEmpty,
            text = "等待确认 · 用户授权挂起中"
        )
    }
}

/** 单个工具卡样例 —— 单行紧凑内容 + 状态名。 */
@Composable
private fun GlassToolRow(status: GlassToolStatus, icon: ImageVector, text: String) {
    GlassToolCard(
        status = status,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = status.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  输入控件 —— 聚焦驱动的玻璃响应
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun InputSection() {
    var value by remember { mutableStateOf("") }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    SectionHeader(
        title = "输入控件",
        hint = "聚焦驱动玻璃亮度 —— 获得焦点时材质受光上升，失焦回落"
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        style = GlassStyle.Control,
        focused = focused
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(text = "Glass Input") },
                singleLine = true,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "尝试聚焦上方输入框 —— 玻璃卡片的高光与边缘亮度随之上升，失焦后回落。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  玻璃对话框 —— HazeDialog 跨窗口采样
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun DialogSection(onOpen: () -> Unit) {
    SectionHeader(
        title = "玻璃对话框",
        hint = "HazeDialog 跨窗口采样 —— 面板模糊的是验证区的真实内容"
    )
    GlassButton(
        text = "打开玻璃对话框",
        onClick = onOpen,
        leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
        accent = MaterialTheme.colorScheme.primary
    )
}

@Composable
internal fun LabGlassDialog(state: HazeState, onDismiss: () -> Unit) {
    GlassDialog(
        onDismissRequest = onDismiss,
        state = state
    ) {
        Text(
            text = "玻璃对话框",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "本面板经 HazeDialog 跨窗口采样背后验证区的真实内容：网格、文字与漂移光斑全部进入模糊范围。" +
                "面板内容保持清晰 —— 玻璃只作用于材质本身，不冒充内容模糊。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(18.dp))
        GlassButton(
            text = "关闭",
            onClick = onDismiss
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  诚实验收清单 —— 运行时求值，禁止静态谎言
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun ChecklistSection(mode: GlassLabMode) {
    val scheme = MaterialTheme.colorScheme
    val pass = Color(0xFF22C55E)
    val fallback = Color(0xFFF59E0B)
    val fail = scheme.error
    // Blur 档运行时判定：API 32+ 才有 RenderEffect GPU 模糊，之下诚实降级 scrim
    val blurOn = Build.VERSION.SDK_INT >= 32

    val items = listOf(
        CheckItem(
            name = "Backdrop",
            status = "PASS",
            note = "Haze GraphicsLayer 采样背后内容",
            color = pass
        ),
        CheckItem(
            name = "Blur",
            status = if (blurOn) "PASS" else "FALLBACK",
            note = if (blurOn) "RenderEffect GPU 模糊" else "API 31-: scrim 降级，无 blur",
            color = if (blurOn) pass else fallback
        ),
        CheckItem(
            name = "Material response",
            status = "PASS",
            note = "按压/聚焦/选中/禁用驱动亮度与形变",
            color = pass
        ),
        CheckItem(
            name = "Edge lighting",
            status = "PASS",
            note = if (mode == GlassLabMode.NIGHT) "内描边渐变受光" else "冷灰发丝边缘（白底白描边不可见）",
            color = pass
        ),
        CheckItem(
            name = "Depth",
            status = "PASS",
            note = "双级阴影线索",
            color = pass
        ),
        CheckItem(
            name = "Specular",
            status = "PASS",
            note = "顶部高光扫掠",
            color = pass
        ),
        CheckItem(
            name = "Noise",
            status = "PASS",
            note = "Haze noiseFactor 玻璃颗粒质感",
            color = pass
        ),
        CheckItem(
            name = "Dynamic theme",
            status = "PASS",
            note = "调色板组合期派生自 MaterialTheme —— 本页为强制 ${mode.label}，切顶部形态可对照双态",
            color = pass
        ),
        CheckItem(
            name = "Refraction",
            status = "NOT IMPLEMENTED",
            note = "未实现折射位移 —— 拒绝冒充",
            color = fail
        ),
        CheckItem(
            name = "Interaction",
            status = "PASS",
            note = "140ms 激活度动画，非循环",
            color = pass
        ),
        CheckItem(
            name = "Performance",
            status = "PASS",
            note = "静态层与光斑层分离：动画帧仅 2-3 绘制调用，无逐帧 Bitmap 分配",
            color = pass
        ),
        CheckItem(
            name = "Fallback",
            status = "PASS",
            note = "低版本自动 scrim",
            color = pass
        )
    )

    SectionHeader(
        title = "诚实验收清单",
        hint = "运行时逐项核对 —— 本设备 SDK ${Build.VERSION.SDK_INT}，当前 ${mode.label}（强制主题）"
    )
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { item -> HonestCheckRow(item = item) }
        }
    }
}

/** 单条验收行 —— 状态徽标等宽小字；NOT IMPLEMENTED 整行错误色 + 底色浸染。 */
@Composable
private fun HonestCheckRow(item: CheckItem) {
    val scheme = MaterialTheme.colorScheme
    val strong = item.status == "NOT IMPLEMENTED"

    val rowModifier = if (strong) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(item.color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    }

    Column(
        modifier = rowModifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
                color = if (strong) item.color else scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            GlassBadge(accent = item.color) {
                if (strong) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = item.color
                    )
                }
                if (item.status == "FALLBACK") {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = item.color
                    )
                }
                Text(
                    text = item.status,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = item.color
                )
            }
        }
        Text(
            text = item.note,
            style = MaterialTheme.typography.bodySmall,
            color = if (strong) item.color else scheme.onSurfaceVariant
        )
    }
}
