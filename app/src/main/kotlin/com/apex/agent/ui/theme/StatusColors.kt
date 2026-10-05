package com.apex.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 语义状态色统一入口（#244：成功 / 警告 / 失败 / 信息 / 中性五档，明暗成对）。
 *
 * 动机：任务历史、用量仪表盘、日志页的「成功绿 / 运行琥珀 / 失败红」此前散落
 * 单态硬编码 hex（3E9C51 / E0A63C / B06055 / 8A93A3 ...），全部绕过
 * MaterialTheme 明暗两套 —— 其中琥珀 E0A63C 对浅色 surface 底对比度仅约
 * 2.2:1（WCAG 图形件 3:1 底线以下，见 issue #244）。本文件按语义归口：
 *
 *  - success / warning：复用 UI-016 的 [LocalExtendedColors]（随 ApexTheme
 *    明暗注入，dynamicColor 路径同样生效）。警告色两套均达标：深色底
 *    FFB454（终端品牌 amber，约 10.6:1），浅色底 B45309（琥珀深一档，
 *    约 5.0:1）—— 替代旧 E0A63C 的 2.2:1。
 *  - error：直接取 colorScheme.error（槽位本就明暗成对：暗 FF6B9D /
 *    亮 BA1A4A，沿 ExtendedColors「danger 不新建」既定纪律）。
 *  - info：colorScheme 无蓝信息槽位 —— 沿 LogViewerScreen.levelColor /
 *    codeColorScheme 先例按 background.luminance() 局部判暗（勿用
 *    isSystemInDarkTheme，主题可被设置强制），蓝对取日志 DEBUG 既有色
 *    （暗 64B5F6 / 亮 1E6BB8，均 >= 4.5:1）。
 *  - neutral：onSurfaceVariant（已取消 / 未开始等弱语义态，明暗随主题）。
 *
 * 纯装饰性非语义色不归本文件管（#244 只收编语义色）。
 */
@Composable
fun statusSuccess(): Color = LocalExtendedColors.current.success

/** 运行中 / 等待 / 警告 —— 琥珀：暗 FFB454（约 10.6:1），亮 B45309（约 5.0:1）。 */
@Composable
fun statusWarning(): Color = LocalExtendedColors.current.warning

/** 失败 / 错误 —— 复用 colorScheme.error（暗 FF6B9D / 亮 BA1A4A，已成对槽位）。 */
@Composable
fun statusError(): Color = MaterialTheme.colorScheme.error

/** 信息 / 调试 —— 蓝：暗 64B5F6 / 亮 1E6BB8；明暗判定先例见文件头注释。 */
@Composable
fun statusInfo(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF64B5F6)
    else Color(0xFF1E6BB8)

/** 中性（已取消 / 挂起等弱状态）—— onSurfaceVariant 明暗随主题。 */
@Composable
fun statusNeutral(): Color = MaterialTheme.colorScheme.onSurfaceVariant
