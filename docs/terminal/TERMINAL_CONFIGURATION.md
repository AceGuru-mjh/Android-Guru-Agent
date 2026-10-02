# 终端配置系统（T87）

> Termux properties 的产品化等价物：配色方案 / bold-as-bright / 扩展键 / 字号 / 键栏显隐 / 振铃 / 常亮。

## 用户视角

**终端设置**（控制台顶栏 ⚙ 抽屉）：
- 字号（8–24，双指捏合同源；#233 边界已与 TerminalSettings.MIN/MAX 常量统一）；
- 单色模式（忽略 ANSI 颜色，保留字形）；
- 键盘辅助行（ESC/TAB/CTRL/方向…）显隐；
- **配色方案**：31 套 Termux 风格主题（含浅色 Solarized Light 与高对比白），点击即时生效；
- **粗体渲染为亮色**（bold-as-bright，默认开）；
- **命令历史**（500 条，持久化）；
- **扩展键行**（自定义宏，Termux extra-keys 等价物）；
- 振铃振动 / 屏幕常亮。

## 扩展键宏语法

设置抽屉「扩展键行」的添加框，每条一个宏：

```
apt修复=cmd:apt-get update    # 文本 + 回车（立即执行 —— 走与手敲完全相同的门禁/历史路径）
py3=text:python3              # 纯文本注入（不执行）
F1=key:F1                     # 特殊键（TerminalKey 名）
^C=ctrl:c                     # 控制字符
贴=paste                      # 系统粘贴
```

约束：标签 ≤ 8 字符；每行 ≤ 10 键、≤ 4 行（超出静默丢弃 —— 预算不变式有测试锁定）。

## 持久化键（SharedPreferences `apex_terminal`）

| 键 | 类型 | 默认 |
|----|------|------|
| `term_color_scheme_id` | String | `apex-mint` |
| `term_bold_as_bright` | Boolean | true |
| `term_extra_keys` | String（宏文本） | 内置默认布局 |
| `term_font_size` / `term_monochrome` / `term_show_keybar` / `term_vibrate_bell` / `term_keep_screen_on` | 既有 | 13 / false / true / true / false |
| `term_command_history` | StringSet（`%05d\|cmd` 序号编码） | — |

## 工程视角

- 方案定义：`app/.../terminal/scheme/TerminalColorSchemeDefs.kt`（31 套，ARGB Long，纯 Kotlin 可单测）；
- 渲染重映射：`TerminalAnsiRemapper`（引擎标准板 → scheme；见
  `docs/terminal/TERMINAL_EXPERIENCE_OVERHAUL_T87.md` §1 的协议）；
- 注入：`TerminalRenderer` 顶层 `CompositionLocalProvider`（scheme + boldAsBright）；
- 新增方案：在 `TerminalColorSchemeDefs` 加定义并进 `ALL` —— 选择器/持久化/兜底自动接入（id 唯一性由测试锁定）。
