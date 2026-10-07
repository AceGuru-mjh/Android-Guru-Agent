package com.apex.agent.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ═══════════════════════════════════════════════════════════════
 *  全局界面风格（UiStyle）—— 与主题模式正交的外观维度
 * ═══════════════════════════════════════════════════════════════
 *
 * 主题系统此前只有「深浅模式 × 强调色」两个维度，整套 UI 默认玻璃化。
 * 本维度补上**风格层**：同一套业务组件在两种截然不同的视觉语言间切换——
 *
 *  - [MINIMAL]（默认）：极简黑白。纯灰阶配色（近黑基底 / 纯白基底，
 *    主色即墨与纸），玻璃组件**整体短路为扁平表面**（实色底 + 发丝
 *    描边，零 blur / 零折射 / 零光带），信息密度优先、静音克制；
 *  - [LIQUID_GLASS]：液态玻璃。kyant0 backdrop 真采样 + GPU blur +
 *    lens 折射 + 边缘受光的完整玻璃管线，配合 18 套预设强调色。
 *
 * 设计约束：
 *  1. 风格切换**立即生效**（CompositionLocal 驱动，无需 recreate）；
 *  2. 玻璃短路收敛在 [com.apex.agent.ui.glass.GlassSurface] 单点 ——
 *     业务组件零感知，禁止各页面自行判断风格；
 *  3. MINIMAL 下 Dynamic Color / 预设配色不参与（灰阶没有取色空间），
 *     液态玻璃风格下两者照常生效；
 *  4. 语义状态色（success / warning / error）在两种风格下都保持原色 ——
 *     它们承载状态语义而非装饰，极简不等于色盲。
 */
enum class UiStyle(val key: String, val label: String) {
    /** 极简黑白：纯灰阶 + 扁平表面（默认）。 */
    MINIMAL("minimal", "极简黑白"),

    /** 液态玻璃：完整玻璃材质管线 + 预设强调色。 */
    LIQUID_GLASS("liquid_glass", "液态玻璃");

    companion object {
        /** 持久化 key → 枚举；未知值回退默认极简（防御式，绝不抛）。 */
        fun fromKey(key: String?): UiStyle =
            entries.firstOrNull { it.key == key } ?: MINIMAL
    }
}

/** 当前界面风格（由 ApexTheme 注入；未注入时兜底极简）。 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MINIMAL }
