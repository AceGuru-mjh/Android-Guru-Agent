package com.apex.agent.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * ═══════════════════════════════════════════════════════════════
 *  主题配色（Accent Palette）—— 多套预设配色方案
 * ═══════════════════════════════════════════════════════════════
 *
 * 设计原则：
 *  1. **中性基底共享**：background / surface / outline 等中性色阶沿用
 *     Terminal-native AI OS 的近黑带蓝基底（深色）/ 冷灰白基底（浅色），
 *     仅 primary / secondary / tertiary 三组强调色随配色方案切换 ——
 *     换配色 = 换霓虹倾向，而非整屏重涂；
 *  2. **深浅双态独立调色**：同一方案在深色态用「霓虹提亮」色（近黑底上
 *     发光），浅色态用「可读深色」（白底上对比度达标）；
 *  3. **MINT 为默认**：与既有品牌色完全一致（老用户零感知）；
 *  4. 与 Dynamic Color 的关系：Dynamic Color（Android 12+ 壁纸取色）
 *     开启时覆盖本系统 —— 预设配色仅在关闭动态取色时生效。
 *
 * 实现说明：enum 构造器无法持有 Compose [Color]（非编译期常量），
 * 故配色数据以 [AccentSet] 私有数据类 + when 映射提供，
 * 最终经 [accentColorScheme] 在既有基底 ColorScheme 上 copy 出完整方案。
 */

/**
 * 可在设置中心直接选择的预设配色方案。
 *
 * [MONO] 是极简黑白风格的专用灰阶方案，不进入选择器（由 UiStyle 驱动，
 * 见 [UiStylePickerExcluded]）——液态玻璃风格下仍可手动选到它获得
 * 「灰阶玻璃」观感。
 */
enum class AccentPalette(val key: String, val label: String) {
    MONO("mono", "墨·极简黑白"),
    MINT("mint", "霓虹薄荷"),
    AMBER("amber", "琥珀暖阳"),
    CORAL("coral", "珊瑚暖橘"),
    VIOLET("violet", "暗夜紫罗兰"),
    OCEAN("ocean", "海洋青"),
    ROSE("rose", "玫瑰粉"),
    FOREST("forest", "森林绿"),
    CYAN("cyan", "冰碧青"),
    SUNSET("sunset", "日落"),
    GOLD("gold", "鎏金"),
    CRIMSON("crimson", "绯红"),
    LAVENDER("lavender", "薰衣草"),
    MAGENTA("magenta", "品红霓虹"),
    LIME("lime", "电光青柠"),
    SAKURA("sakura", "樱花"),
    MATCHA("matcha", "抹茶"),
    PEACH("peach", "蜜桃"),
    PLUM("plum", "梅紫");

    companion object {
        /** 持久化 key → 枚举（未知值兜底 MINT，兼容旧版本数据）。 */
        fun fromKey(key: String?): AccentPalette =
            entries.firstOrNull { it.key == key } ?: MINT
    }
}

/**
 * 预设配色选择器的可见列表：[AccentPalette.MONO] 由极简风格独占驱动，
 * 不在液态玻璃的选择器里重复出现（避免两处入口同一语义）。
 */
val UiStylePickerExcluded: List<AccentPalette> = AccentPalette.entries.filter { it != AccentPalette.MONO }

/**
 * 一套配色方案的强调色组（primary / secondary / tertiary 全量 12 色）。
 * 不含中性色 —— 中性基底由 [DarkNeutralBase] / [LightNeutralBase] 提供。
 */
@Immutable
private data class AccentSet(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color
)

// ═══════════════════ 中性基底（全部方案共享）═══════════════════
// 即原 ApexTheme 的 Dark/Light ColorScheme —— 原值迁移，MINT 方案
// copy 后输出与旧主题逐字段一致（行为等价迁移）。

private val DarkNeutralBase: ColorScheme = darkColorScheme(
    background = Color(0xFF0A0E14),       // near-black w/ blue tint
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0F141C),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2530),
    onSurfaceVariant = Color(0xFF9DA9B8),
    surfaceContainerLowest = Color(0xFF080B11),
    surfaceContainerLow = Color(0xFF0F141C),
    surfaceContainer = Color(0xFF141B24),
    surfaceContainerHigh = Color(0xFF1C2530),
    surfaceContainerHighest = Color(0xFF26313D),
    outline = Color(0xFF324150),
    outlineVariant = Color(0xFF223040),
    error = Color(0xFFFF6B9D),
    onError = Color(0xFF3D0018),
    errorContainer = Color(0xFF52122E),
    onErrorContainer = Color(0xFFFFB3CE),
    scrim = Color(0xFF05070B)
)

private val LightNeutralBase: ColorScheme = lightColorScheme(
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF10161D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10161D),
    surfaceVariant = Color(0xFFE3E8EE),
    onSurfaceVariant = Color(0xFF4A5562),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F7),
    surfaceContainer = Color(0xFFEBEFF3),
    surfaceContainerHigh = Color(0xFFE5EAEF),
    surfaceContainerHighest = Color(0xFFDFE5EB),
    outline = Color(0xFFC2CAD3),
    outlineVariant = Color(0xFFD9DFE6),
    error = Color(0xFFBA1A4A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9E4),
    onErrorContainer = Color(0xFF3F0018),
    scrim = Color(0xFF0A0E14)
)

// ═══════════════════ MINT · 霓虹薄荷（默认，原品牌色）═══════════════════

private val MintDark = AccentSet(
    primary = Color(0xFF4EE9B0),          // neon mint
    onPrimary = Color(0xFF00251A),
    primaryContainer = Color(0xFF0C3A2C),
    onPrimaryContainer = Color(0xFF9CF3D2),
    secondary = Color(0xFFFFB454),        // amber accent
    onSecondary = Color(0xFF3A2400),
    secondaryContainer = Color(0xFF432D00),
    onSecondaryContainer = Color(0xFFFFD89E),
    tertiary = Color(0xFFFF6B9D),         // magenta (danger / highlight)
    onTertiary = Color(0xFF3D0018),
    tertiaryContainer = Color(0xFF52122E),
    onTertiaryContainer = Color(0xFFFFB3CE)
)

private val MintLight = AccentSet(
    primary = Color(0xFF0E7A57),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4F3DC),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF8A5300),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDDA8),
    onSecondaryContainer = Color(0xFF2E1A00),
    tertiary = Color(0xFFB33A65),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E4),
    onTertiaryContainer = Color(0xFF3F0018)
)

// ═══════════════════ AMBER · 琥珀暖阳 ═══════════════════

private val AmberDark = AccentSet(
    primary = Color(0xFFFFC24D),
    onPrimary = Color(0xFF3A2A00),
    primaryContainer = Color(0xFF524000),
    onPrimaryContainer = Color(0xFFFFE0B0),
    secondary = Color(0xFF82E7C4),
    onSecondary = Color(0xFF00382B),
    secondaryContainer = Color(0xFF00513F),
    onSecondaryContainer = Color(0xFFA5F2D9),
    tertiary = Color(0xFFFF90A6),
    onTertiary = Color(0xFF44081C),
    tertiaryContainer = Color(0xFF5C1D2F),
    onTertiaryContainer = Color(0xFFFFD9DE)
)

private val AmberLight = AccentSet(
    primary = Color(0xFF8A5800),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF2C2000),
    secondary = Color(0xFF006B5A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF2DA),
    onSecondaryContainer = Color(0xFF003730),
    tertiary = Color(0xFFA24B5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9DE),
    onTertiaryContainer = Color(0xFF3B071C)
)

// ═══════════════════ CORAL · 珊瑚暖橘 ═══════════════════

private val CoralDark = AccentSet(
    primary = Color(0xFFFF8F79),
    onPrimary = Color(0xFF4A1405),
    primaryContainer = Color(0xFF5C1F10),
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFFFFC46B),
    onSecondary = Color(0xFF482B00),
    secondaryContainer = Color(0xFF5A3D00),
    onSecondaryContainer = Color(0xFFFFE0B3),
    tertiary = Color(0xFF7FD3C1),
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF005044),
    onTertiaryContainer = Color(0xFFA5F2E4)
)

private val CoralLight = AccentSet(
    primary = Color(0xFFB3402A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF4A1405),
    secondary = Color(0xFF8A5E00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9E),
    onSecondaryContainer = Color(0xFF2C2000),
    tertiary = Color(0xFF006B5C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF2E0),
    onTertiaryContainer = Color(0xFF003730)
)

// ═══════════════════ VIOLET · 暗夜紫罗兰 ═══════════════════

private val VioletDark = AccentSet(
    primary = Color(0xFFC9B4FF),
    onPrimary = Color(0xFF2E1A66),
    primaryContainer = Color(0xFF46308C),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFFFD08A),
    onSecondary = Color(0xFF4A2F00),
    secondaryContainer = Color(0xFF5A4100),
    onSecondaryContainer = Color(0xFFFFE9C4),
    tertiary = Color(0xFFFFA6C9),
    onTertiary = Color(0xFF4C0730),
    tertiaryContainer = Color(0xFF632B48),
    onTertiaryContainer = Color(0xFFFFD9E7)
)

private val VioletLight = AccentSet(
    primary = Color(0xFF6B4FA0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF271457),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF9C4470),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E8),
    onTertiaryContainer = Color(0xFF3E0727)
)

// ═══════════════════ OCEAN · 海洋青 ═══════════════════

private val OceanDark = AccentSet(
    primary = Color(0xFF5AD8E8),
    onPrimary = Color(0xFF00323B),
    primaryContainer = Color(0xFF004956),
    onPrimaryContainer = Color(0xFFB8EEF7),
    secondary = Color(0xFFFFC46B),
    onSecondary = Color(0xFF482B00),
    secondaryContainer = Color(0xFF5A3D00),
    onSecondaryContainer = Color(0xFFFFE0B3),
    tertiary = Color(0xFF8AF0B4),
    onTertiary = Color(0xFF003922),
    tertiaryContainer = Color(0xFF005231),
    onTertiaryContainer = Color(0xFFB2FFD1)
)

private val OceanLight = AccentSet(
    primary = Color(0xFF0E6E7D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC0ECF6),
    onPrimaryContainer = Color(0xFF003640),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF2A6E4C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFADF0C6),
    onTertiaryContainer = Color(0xFF002716)
)

// ═══════════════════ ROSE · 玫瑰粉 ═══════════════════

private val RoseDark = AccentSet(
    primary = Color(0xFFFF8FB1),
    onPrimary = Color(0xFF4C0727),
    primaryContainer = Color(0xFF5C2238),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFFFD08A),
    onSecondary = Color(0xFF4A2F00),
    secondaryContainer = Color(0xFF5A4100),
    onSecondaryContainer = Color(0xFFFFE9C4),
    tertiary = Color(0xFF8FD8C4),
    onTertiary = Color(0xFF00382C),
    tertiaryContainer = Color(0xFF005041),
    onTertiaryContainer = Color(0xFFA5F2E2)
)

private val RoseLight = AccentSet(
    primary = Color(0xFFA83762),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3F0020),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF006B5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF2DF),
    onTertiaryContainer = Color(0xFF003730)
)

// ═══════════════════ FOREST · 森林绿 ═══════════════════

private val ForestDark = AccentSet(
    primary = Color(0xFF9BD98F),
    onPrimary = Color(0xFF1C3A14),
    primaryContainer = Color(0xFF325226),
    onPrimaryContainer = Color(0xFFC5F0B5),
    secondary = Color(0xFFFFCF6B),
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF554000),
    onSecondaryContainer = Color(0xFFFFE9B8),
    tertiary = Color(0xFFFF9E80),
    onTertiary = Color(0xFF4A1F0B),
    tertiaryContainer = Color(0xFF5C2515),
    onTertiaryContainer = Color(0xFFFFDBD0)
)

private val ForestLight = AccentSet(
    primary = Color(0xFF44682F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4EFA6),
    onPrimaryContainer = Color(0xFF1D3507),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF934B32),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCD),
    onTertiaryContainer = Color(0xFF3B0E01)
)

// ═══════════════════ CYAN · 冰碧青 ═══════════════════
// 青色（非蓝）：霓虹提亮 #22D3EE 系；tertiary 取薄荷梯次（青→绿和谐渐变）。

private val CyanDark = AccentSet(
    primary = Color(0xFF22D3EE),          // neon cyan
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004957),
    onPrimaryContainer = Color(0xFFB4EBF8),
    secondary = Color(0xFFFFD08A),        // warm gold accent
    onSecondary = Color(0xFF4A2F00),
    secondaryContainer = Color(0xFF5A4100),
    onSecondaryContainer = Color(0xFFFFE9C4),
    tertiary = Color(0xFF4EE9B0),        // jade mint（冰碧呼应）
    onTertiary = Color(0xFF00251A),
    tertiaryContainer = Color(0xFF0C3A2C),
    onTertiaryContainer = Color(0xFF9CF3D2)
)

private val CyanLight = AccentSet(
    primary = Color(0xFF155E75),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2ECFA),
    onPrimaryContainer = Color(0xFF001E2E),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF0E7A57),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB4F3DC),
    onTertiaryContainer = Color(0xFF00382A)
)

// ═══════════════════ SUNSET · 日落 ═══════════════════
// 橙→绯渐变感：primary #FB923C 橙，secondary 玫瑰绯，tertiary 晚霞紫。

private val SunsetDark = AccentSet(
    primary = Color(0xFFFB923C),          // sunset orange
    onPrimary = Color(0xFF4A2100),
    primaryContainer = Color(0xFF5C2E00),
    onPrimaryContainer = Color(0xFFFFDDBA),
    secondary = Color(0xFFFF8FA3),        // rose（橙→绯过渡）
    onSecondary = Color(0xFF4A0725),
    secondaryContainer = Color(0xFF5F1F33),
    onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = Color(0xFFD8B4FE),        // dusk violet
    onTertiary = Color(0xFF3A1D66),
    tertiaryContainer = Color(0xFF52378F),
    onTertiaryContainer = Color(0xFFEBDCFF)
)

private val SunsetLight = AccentSet(
    primary = Color(0xFF9A3412),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFFA03C50),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9DE),
    onSecondaryContainer = Color(0xFF3F0713),
    tertiary = Color(0xFF6B4FA0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF271457)
)

// ═══════════════════ GOLD · 鎏金 ═══════════════════
// primary #FBBF24 系：深色态鎏金发光，浅色态偏黄而非琥珀棕（与 AMBER 区分）；
// secondary 取与金色成对比的湖青。

private val GoldDark = AccentSet(
    primary = Color(0xFFFBBF24),          // molten gold
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5B4300),
    onPrimaryContainer = Color(0xFFFFE08C),
    secondary = Color(0xFF8AD8E8),        // lake cyan（金青对村）
    onSecondary = Color(0xFF00323B),
    secondaryContainer = Color(0xFF004956),
    onSecondaryContainer = Color(0xFFB8EEF7),
    tertiary = Color(0xFFFDA4AF),        // rose highlight
    onTertiary = Color(0xFF4A0723),
    tertiaryContainer = Color(0xFF662338),
    onTertiaryContainer = Color(0xFFFFD9E2)
)

private val GoldLight = AccentSet(
    primary = Color(0xFFA16207),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE08C),
    onPrimaryContainer = Color(0xFF2B2000),
    secondary = Color(0xFF0E6E7D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC0ECF6),
    onSecondaryContainer = Color(0xFF003640),
    tertiary = Color(0xFFA03A52),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3F0019)
)

// ═══════════════════ CRIMSON · 绯红 ═══════════════════
// primary #EF4444 系：深色态霓虹绯红；secondary 暖金，tertiary 冰青（火/冰对比）。

private val CrimsonDark = AccentSet(
    primary = Color(0xFFFF7A70),          // neon crimson
    onPrimary = Color(0xFF490005),
    primaryContainer = Color(0xFF6B1610),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFFFD08A),        // warm gold
    onSecondary = Color(0xFF4A2F00),
    secondaryContainer = Color(0xFF5A4100),
    onSecondaryContainer = Color(0xFFFFE9C4),
    tertiary = Color(0xFF8AD8E8),        // ice cyan
    onTertiary = Color(0xFF00323B),
    tertiaryContainer = Color(0xFF004956),
    onTertiaryContainer = Color(0xFFB8EEF7)
)

private val CrimsonLight = AccentSet(
    primary = Color(0xFFB3261E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF1FF),
    onTertiaryContainer = Color(0xFF001F26)
)

// ═══════════════════ LAVENDER · 薰衣草 ═══════════════════
// 淡紫罗兰（非蓝非深紫）：深色态 #B39DDB 系柔光淡紫，区别于 VIOLET 的冷紫霓虹；
// secondary 暖金、tertiary 薄荷 —— 花田清晨感。

private val LavenderDark = AccentSet(
    primary = Color(0xFFB39DDB),          // soft lavender
    onPrimary = Color(0xFF2B2054),
    primaryContainer = Color(0xFF4A3B8F),
    onPrimaryContainer = Color(0xFFE8DFFF),
    secondary = Color(0xFFFFD08A),        // warm gold accent
    onSecondary = Color(0xFF4A2F00),
    secondaryContainer = Color(0xFF5A4100),
    onSecondaryContainer = Color(0xFFFFE9C4),
    tertiary = Color(0xFF8AF0B4),         // field mint
    onTertiary = Color(0xFF003922),
    tertiaryContainer = Color(0xFF005231),
    onTertiaryContainer = Color(0xFFB2FFD1)
)

private val LavenderLight = AccentSet(
    primary = Color(0xFF5E60A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E1FF),
    onPrimaryContainer = Color(0xFF25265C),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF2A6E4C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFADF0C6),
    onTertiaryContainer = Color(0xFF002716)
)

// ═══════════════════ MAGENTA · 品红霓虹 ═══════════════════
// 霓虹品红 #FF5FD7：比 ROSE 更偏紫、更电光；secondary 湖青、tertiary 暖金
// （品红 × 青 = 经典霓虹对撞）。

private val MagentaDark = AccentSet(
    primary = Color(0xFFFF5FD7),          // neon magenta
    onPrimary = Color(0xFF4A0744),
    primaryContainer = Color(0xFF6A1F5E),
    onPrimaryContainer = Color(0xFFFFD9F0),
    secondary = Color(0xFF8AD8E8),        // lake cyan
    onSecondary = Color(0xFF00323B),
    secondaryContainer = Color(0xFF004956),
    onSecondaryContainer = Color(0xFFB8EEF7),
    tertiary = Color(0xFFFFD08A),         // warm gold
    onTertiary = Color(0xFF4A2F00),
    tertiaryContainer = Color(0xFF5A4100),
    onTertiaryContainer = Color(0xFFFFE9C4)
)

private val MagentaLight = AccentSet(
    primary = Color(0xFFA1036E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD6EC),
    onPrimaryContainer = Color(0xFF4A0035),
    secondary = Color(0xFF0E6E7D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC0ECF6),
    onSecondaryContainer = Color(0xFF003640),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9C),
    onTertiaryContainer = Color(0xFF261A00)
)

// ═══════════════════ LIME · 电光青柠 ═══════════════════
// 青柠荧光 #A3E635：终端荧光信号感；secondary 暮紫、tertiary 霓虹粉
// （青柠 × 品红 = 赛博对撞，区别于 FOREST 的柔和绿）。

private val LimeDark = AccentSet(
    primary = Color(0xFFA3E635),          // electric lime
    onPrimary = Color(0xFF1F3200),
    primaryContainer = Color(0xFF2F4D00),
    onPrimaryContainer = Color(0xFFCDF68C),
    secondary = Color(0xFFD8B4FE),        // dusk violet
    onSecondary = Color(0xFF3A1D66),
    secondaryContainer = Color(0xFF52378F),
    onSecondaryContainer = Color(0xFFEBDCFF),
    tertiary = Color(0xFFFF6B9D),         // neon pink (danger / highlight)
    onTertiary = Color(0xFF3D0018),
    tertiaryContainer = Color(0xFF52122E),
    onTertiaryContainer = Color(0xFFFFB3CE)
)

private val LimeLight = AccentSet(
    primary = Color(0xFF5A7A0B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCF587),
    onPrimaryContainer = Color(0xFF1A2D00),
    secondary = Color(0xFF6B4FA0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEADDFF),
    onSecondaryContainer = Color(0xFF271457),
    tertiary = Color(0xFFB33A65),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E4),
    onTertiaryContainer = Color(0xFF3F0018)
)

// ═══════════════════ SAKURA · 樱花 ═══════════════════
// 樱花粉 #F9A8C9：比 ROSE 更柔更淡的春樱粉；secondary 嫩叶绿、tertiary 暮色淡紫
// —— 花见时的樱吹雪配色。

private val SakuraDark = AccentSet(
    primary = Color(0xFFF9A8C9),          // sakura pink
    onPrimary = Color(0xFF4A1B35),
    primaryContainer = Color(0xFF62304C),
    onPrimaryContainer = Color(0xFFFFDCE9),
    secondary = Color(0xFF9BD98F),        // spring leaf
    onSecondary = Color(0xFF1C3A14),
    secondaryContainer = Color(0xFF325226),
    onSecondaryContainer = Color(0xFFC5F0B5),
    tertiary = Color(0xFFD8B4FE),        // twilight lavender
    onTertiary = Color(0xFF3A1D66),
    tertiaryContainer = Color(0xFF52378F),
    onTertiaryContainer = Color(0xFFEBDCFF)
)

private val SakuraLight = AccentSet(
    primary = Color(0xFFB0557E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E7),
    onPrimaryContainer = Color(0xFF46112F),
    secondary = Color(0xFF44682F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC4EFA6),
    onSecondaryContainer = Color(0xFF1D3507),
    tertiary = Color(0xFF6B4FA0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF271457)
)

// ═══════════════════ MATCHA · 抹茶 ═══════════════════
// 抹茶黄绿 #C7D86B：比 FOREST 偏黄、比 LIME 柔和的茶道绿；secondary 樱粉、
// tertiary 暖金 —— 和果子配色（抹茶 × 樱 × 金箔）。

private val MatchaDark = AccentSet(
    primary = Color(0xFFC7D86B),          // matcha
    onPrimary = Color(0xFF334000),
    primaryContainer = Color(0xFF4C5400),
    onPrimaryContainer = Color(0xFFE2F08C),
    secondary = Color(0xFFF9A8C9),        // sakura pink
    onSecondary = Color(0xFF4A1B35),
    secondaryContainer = Color(0xFF62304C),
    onSecondaryContainer = Color(0xFFFFDCE9),
    tertiary = Color(0xFFFFD08A),         // gold leaf
    onTertiary = Color(0xFF4A2F00),
    tertiaryContainer = Color(0xFF5A4100),
    onTertiaryContainer = Color(0xFFFFE9C4)
)

private val MatchaLight = AccentSet(
    primary = Color(0xFF6B7C23),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAF0BC),
    onPrimaryContainer = Color(0xFF242E00),
    secondary = Color(0xFFB0557E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E7),
    onSecondaryContainer = Color(0xFF46112F),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9C),
    onTertiaryContainer = Color(0xFF261A00)
)

// ═══════════════════ PEACH · 蜜桃 ═══════════════════
// 蜜桃暖粉橘 #FFB59E：比 CORAL 更浅更粉的绒毛桃色；secondary 湖青、tertiary 玫瑰
// （蜜桃 × 青 = 果肉与果叶的清爽对比）。

private val PeachDark = AccentSet(
    primary = Color(0xFFFFB59E),          // juicy peach
    onPrimary = Color(0xFF4B2114),
    primaryContainer = Color(0xFF5F3527),
    onPrimaryContainer = Color(0xFFFFD9CD),
    secondary = Color(0xFF7FD3C1),        // soft teal
    onSecondary = Color(0xFF00382E),
    secondaryContainer = Color(0xFF005044),
    onSecondaryContainer = Color(0xFFA5F2E4),
    tertiary = Color(0xFFFDA4AF),         // rose highlight
    onTertiary = Color(0xFF4A0723),
    tertiaryContainer = Color(0xFF662338),
    onTertiaryContainer = Color(0xFFFFD9E2)
)

private val PeachLight = AccentSet(
    primary = Color(0xFFA65D45),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD0),
    onPrimaryContainer = Color(0xFF46200D),
    secondary = Color(0xFF006B5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF2E0),
    onSecondaryContainer = Color(0xFF003730),
    tertiary = Color(0xFFA03A52),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3F0019)
)

// ═══════════════════ PLUM · 梅紫 ═══════════════════
// 梅子紫粉 #E1B3E8：比 VIOLET/LAVENDER 更偏粉的梅子色；secondary 暖金、
// tertiary 湖青（梅子 × 金 × 青 = 雅致东方感）。

private val PlumDark = AccentSet(
    primary = Color(0xFFE1B3E8),          // plum blossom
    onPrimary = Color(0xFF471F49),
    primaryContainer = Color(0xFF5F3A63),
    onPrimaryContainer = Color(0xFFF8DCF6),
    secondary = Color(0xFFFFD08A),        // warm gold
    onSecondary = Color(0xFF4A2F00),
    secondaryContainer = Color(0xFF5A4100),
    onSecondaryContainer = Color(0xFFFFE9C4),
    tertiary = Color(0xFF7FD3C1),         // soft teal
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF005044),
    onTertiaryContainer = Color(0xFFA5F2E4)
)

private val PlumLight = AccentSet(
    primary = Color(0xFF7D4F84),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF5DCF4),
    onPrimaryContainer = Color(0xFF401F46),
    secondary = Color(0xFF7C5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9C),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF006B5C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF2E0),
    onTertiaryContainer = Color(0xFF003730)
)

// ═══════════════════ MONO · 墨·极简黑白（极简风格专用）═══════════════════
// 纯灰阶强调色组：无色相、无饱和，仅明度分层 —— 深色态主色是「纸白」
// （近黑底上的反白），浅色态主色是「墨黑」（白底上的正字色）。银灰与
// 石墨作为 secondary / tertiary 维持层次，不引入任何色彩倾向。

private val MonoDark = AccentSet(
    primary = Color(0xFFF2F2F2),          // paper white
    onPrimary = Color(0xFF171717),
    primaryContainer = Color(0xFF2E2E2E),
    onPrimaryContainer = Color(0xFFE6E6E6),
    secondary = Color(0xFFB8B8B8),        // silver
    onSecondary = Color(0xFF1F1F1F),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFCCCCCC),
    tertiary = Color(0xFF8F8F8F),         // graphite
    onTertiary = Color(0xFF1C1C1C),
    tertiaryContainer = Color(0xFF262626),
    onTertiaryContainer = Color(0xFFB3B3B3)
)

private val MonoLight = AccentSet(
    primary = Color(0xFF1F1F1F),          // ink black
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E4),
    onPrimaryContainer = Color(0xFF2B2B2B),
    secondary = Color(0xFF5C5C5C),        // mid gray
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCDCDC),
    onSecondaryContainer = Color(0xFF3A3A3A),
    tertiary = Color(0xFF8A8A8A),         // silver graphite
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6D6D6),
    onTertiaryContainer = Color(0xFF3D3D3D)
)

/** 配色方案 → 当前深浅态的强调色组。 */
private fun accentSetFor(palette: AccentPalette, dark: Boolean): AccentSet = when (palette) {
    AccentPalette.MONO -> if (dark) MonoDark else MonoLight
    AccentPalette.MINT -> if (dark) MintDark else MintLight
    AccentPalette.AMBER -> if (dark) AmberDark else AmberLight
    AccentPalette.CORAL -> if (dark) CoralDark else CoralLight
    AccentPalette.VIOLET -> if (dark) VioletDark else VioletLight
    AccentPalette.OCEAN -> if (dark) OceanDark else OceanLight
    AccentPalette.ROSE -> if (dark) RoseDark else RoseLight
    AccentPalette.FOREST -> if (dark) ForestDark else ForestLight
    AccentPalette.CYAN -> if (dark) CyanDark else CyanLight
    AccentPalette.SUNSET -> if (dark) SunsetDark else SunsetLight
    AccentPalette.GOLD -> if (dark) GoldDark else GoldLight
    AccentPalette.CRIMSON -> if (dark) CrimsonDark else CrimsonLight
    AccentPalette.LAVENDER -> if (dark) LavenderDark else LavenderLight
    AccentPalette.MAGENTA -> if (dark) MagentaDark else MagentaLight
    AccentPalette.LIME -> if (dark) LimeDark else LimeLight
    AccentPalette.SAKURA -> if (dark) SakuraDark else SakuraLight
    AccentPalette.MATCHA -> if (dark) MatchaDark else MatchaLight
    AccentPalette.PEACH -> if (dark) PeachDark else PeachLight
    AccentPalette.PLUM -> if (dark) PlumDark else PlumLight
}

/**
 * 完整 ColorScheme：中性基底 + 强调色组 copy 合成。
 * MINT 输出与旧版单配色主题逐字段一致（等价迁移，老用户零感知）。
 */
fun accentColorScheme(palette: AccentPalette, dark: Boolean): ColorScheme {
    val base = if (dark) DarkNeutralBase else LightNeutralBase
    val accents = accentSetFor(palette, dark)
    return base.copy(
        primary = accents.primary,
        onPrimary = accents.onPrimary,
        primaryContainer = accents.primaryContainer,
        onPrimaryContainer = accents.onPrimaryContainer,
        secondary = accents.secondary,
        onSecondary = accents.onSecondary,
        secondaryContainer = accents.secondaryContainer,
        onSecondaryContainer = accents.onSecondaryContainer,
        tertiary = accents.tertiary,
        onTertiary = accents.onTertiary,
        tertiaryContainer = accents.tertiaryContainer,
        onTertiaryContainer = accents.onTertiaryContainer
    )
}

/**
 * 配色选择器里代表该方案的色点颜色（跟随当前深浅态的主色）。
 */
fun accentSwatchColor(palette: AccentPalette, dark: Boolean): Color =
    accentSetFor(palette, dark).primary

// ═══════════════════ 极简黑白中性基底 ═══════════════════
// 与 DarkNeutralBase / LightNeutralBase 的区别：去掉蓝灰倾向，色温严格
// 中性（R=G=B），背景更纯粹（纯黑 / 纯白）。仅 [minimalColorScheme] 使用。

private val MinimalDarkBase: ColorScheme = darkColorScheme(
    background = Color(0xFF000000),       // pure black
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFA3A3A3),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF242424),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFFF6B9D),
    onError = Color(0xFF3D0018),
    errorContainer = Color(0xFF52122E),
    onErrorContainer = Color(0xFFFFB3CE),
    scrim = Color(0xFF000000)
)

private val MinimalLightBase: ColorScheme = lightColorScheme(
    background = Color(0xFFFFFFFF),       // pure white
    onBackground = Color(0xFF191919),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191919),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF565656),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFFCFCFCF),
    outlineVariant = Color(0xFFDEDEDE),
    error = Color(0xFFBA1A4A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9E4),
    onErrorContainer = Color(0xFF3F0018),
    scrim = Color(0xFF000000)
)

/**
 * 极简黑白风格的完整 ColorScheme：纯灰阶基底 + MONO 强调色组合成。
 *
 * 独立于 Dynamic Color / 预设配色 —— 极简风格下两者均不参与（灰阶没有
 * 取色空间）；语义错误色保留（状态语义优先于风格纯粹性）。
 */
fun minimalColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) MinimalDarkBase else MinimalLightBase
    val accents = accentSetFor(AccentPalette.MONO, dark)
    return base.copy(
        primary = accents.primary,
        onPrimary = accents.onPrimary,
        primaryContainer = accents.primaryContainer,
        onPrimaryContainer = accents.onPrimaryContainer,
        secondary = accents.secondary,
        onSecondary = accents.onSecondary,
        secondaryContainer = accents.secondaryContainer,
        onSecondaryContainer = accents.onSecondaryContainer,
        tertiary = accents.tertiary,
        onTertiary = accents.onTertiary,
        tertiaryContainer = accents.tertiaryContainer,
        onTertiaryContainer = accents.onTertiaryContainer
    )
}
