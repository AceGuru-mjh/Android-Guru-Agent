# Apex Agent UI 质量审查报告

> 审查对象：[AceGuru-mjh/Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent)（Apex Agent v1.0.0, versionCode 1）
> 审查基线：`main` @ `e34c176`（feat(agent-engine): A68.1 — Execution Orchestrator Core）
> 审查时间：2026-08-27（UTC）
> 审查方式：纯云端静态审查（GitHub API + 只读 clone），未执行动态/模拟器测试（原因见附录 B）
> 关联文档：本报告是对 `docs/agent-ui-audit.md`（2026-08-11，聚焦"Agent 输出呈现"）的全应用扩展。该文档中的 P0-P3 缺口（错误高亮、工具分类、搜索结构化卡片）在当前代码中已基本落地，本报告不再重复计入。

---

## 1. 执行摘要

- **应用名称**：Apex Agent（Android 端自主 AI Agent，Kotlin + Jetpack Compose Material3，深色优先"终端美学"设计语言）
- **审查范围**：`app` 模块全部 7 个 Compose 屏幕（Agent / Terminal / Skill / Memory / Permissions / Log / Settings）+ 14 个共享 UI 组件 + 主题/排版/资源文件 + 悬浮窗 UI（BrowserOverlay / NeonRingView / CyberNeonBall）+ AndroidManifest / Gradle 构建配置；累计逐行审阅约 12,000 行 UI 代码，另对全模块做了 10 项系统性模式扫描。
- **问题总数**：**45 项** —— 高严重度 **9** 项、中严重度 **22** 项、低严重度 **14** 项。

### 总体 UI 质量评价

视觉设计语言统一且富有辨识度（霓虹 mint/amber/magenta 三色体系、终端等宽字体、玻璃拟态容器），单向数据流与 MaterialTheme token 消费纪律良好，Skill/Memory 屏的"空态 + 确认框 + 反馈"完成度可作团队模板。但工程短板高度系统化：**全应用 0 处 `imePadding`（键盘遮挡输入框）、0 处真实 `rememberSaveable`（76 处状态旋转即丢）、0 处 `stringResource`（1085 行硬编码中文，i18n 不可行）、0 处 `BackHandler`/Navigation 库（返回键直接退出应用）、0 处 `widthIn/maxWidth` 大屏适配**——这五个"零"加上触摸目标系统性低于 48dp、悬浮窗窗口参数错误，构成了当前体验的主要天花板。终端屏目前是"只读尾迹视图"（无滚动、无输入），设置屏存在 API Key 明文与坏掉的多行输入，属于最优先整改对象。

---

## 2. 问题清单（核心）

严重程度定义：**高** = 崩溃/功能不可用/严重可访问性违规/数据丢失风险；**中** = 明显影响体验（布局错乱、关键交互缺失、对比度不足、明显性能隐患）；**低** = 轻微瑕疵、建议性优化。

### 2.1 高严重度（9 项）

| 编号 | 严重程度 | 问题类型 | 问题描述 | 复现步骤/证据 | 建议修复方案 | 备注 |
|---|---|---|---|---|---|---|
| UI-001 | 高 | 布局 | **键盘系统性遮挡输入框**：`MainActivity` 启用 `enableEdgeToEdge()`（API 30+ 下 `adjustResize` 失效），而全应用 **0 处 `imePadding`**（仅 ImageLightbox 有 4 处 insets 使用），主聊天输入栏、全屏编辑器、终端页在键盘弹出时均被遮挡 | `MainActivity.kt:19`；grep `imePadding` 全 app 模块 0 命中；`AgentChatScreen.kt:488`（AdaptiveInputField）；`AdaptiveInputField.kt:219-232`（`decorFitsSystemWindows=false` 却无任何 insets 补偿） | 在根布局或各输入容器统一加 `Modifier.imePadding().navigationBarsPadding()`；全屏编辑器补 `statusBarsPadding()` | edge-to-edge 适配是 Android 15 (targetSdk 35) 强制趋势，越晚修代价越大 |
| UI-002 | 高 | 交互 | **全屏编辑器三重缺陷**：① 双击手势劫持文本框标准"选词"行为；② 关闭按钮 `×` 直接丢弃全部修改而返回键保存，语义相反且无确认，长 Prompt 编辑静默丢失；③ 双击全屏有 `>50 字符` 隐藏门槛，不可发现 | `AdaptiveInputField.kt:115-125`（`combinedClickable(onDoubleClick=…isFullscreen=true)` 挂在 TextField 上）；`:215-218` vs `:254-259`（`onDismissRequest={onConfirm(text)}` vs `IconButton(onClick=onDismiss)`） | 移除 combinedClickable（全屏入口改 trailingIcon 按钮）；`×` 与返回键统一为"保存"或弹"放弃修改？"确认；有修改时禁用静默丢弃 | 用户可感知的数据丢失，优先级最高的单点交互缺陷 |
| UI-003 | 高 | 功能 | **终端屏输出不可滚动**：`BasicText` 平铺 `fillMaxSize`，无 `verticalScroll`、无虚拟化、无跟随底部；设置允许缓冲 10000 行，但超出首屏的输出用户永远看不到 | `TerminalRenderer.kt:67-76`（无任何滚动修饰符，grep 验证） | 加 `verticalScroll` + 底部跟随，或按行虚拟化/Canvas 单元格渲染（代码注释自认 cell renderer 是 future work，但连文本滚动都未提供） | 与 UI-004 叠加后终端屏当前不可用 |
| UI-004 | 高 | 功能 | **终端屏没有任何命令输入通道**：无输入框、无 Ctrl/Esc/Tab 快捷键条、无软键盘适配；用户唯一能"驱动"终端的方式是依赖安装的副作用 | grep `terminal/` 目录无 `sendInput`/`writeToTerminal`；仅有的 `OutlinedTextField`（`TerminalScreen.kt:302/322`）是数字设置项而非命令输入 | 底部加输入行 + `imePadding()` + IME action 发送到 `terminalRuntime.run()` | 与 README "终端"能力宣传不符 |
| UI-005 | 高 | 边界 | **终端"一键安装依赖"执行 Windows 命令，在 Android 上必然失败**：依赖列表的安装命令是 `winget install …` / `scoop install …`，Android PTY 中不存在这些命令，用户点击只会收到一串 ❌ | `TerminalViewModel.kt:134-162`（`DepItem("jdk17", …, "winget install Microsoft.OpenJDK.17", "scoop install adopt17-hotspot")`） | 接入仓库已有的 `platform/terminal/pkg/PackageManager` / `UbuntuEnvironment`（PRoot）真实供给链路；接入前先做能力检测隐藏不可用项 | 呈现为"必死按钮"的功能比没有更伤信任 |
| UI-006 | 高 | 边界/安全 | **API Key 明文输入、明文落盘，且多行输入功能性损坏**：① 无 `PasswordVisualTransformation`、以 JSON 明文写入普通 `SharedPreferences`（未用 EncryptedSharedPreferences）；② 每次按键即时 `lines()+filter`，换行被立即吞掉——**手动无法录入第二个 Key** | `SettingsScreen.kt:658-663`；`SettingsRepository.kt:32-33,173`；grep `VisualTransformation` settings 目录 0 命中 | 掩码 + 可见性切换 + 本地 String 暂存（失焦/保存时才解析）+ `EncryptedSharedPreferences`/Keystore | 兼具安全（中危泄漏面）与功能（无法多 Key）双重问题 |
| UI-007 | 高 | 交互/稳定性 | **悬浮窗窗口参数错误**：① 全屏 `MATCH_PARENT` 窗口仅配 `FLAG_NOT_TOUCH_MODAL`（对全屏窗口形同虚设），collapsed 态有吞掉底层应用触摸的风险；② 缺 `FLAG_NOT_FOCUSABLE`，抢焦点并干扰底层输入与返回键；③ `OverlayLifecycleOwner` 从未通过 `setViewTreeLifecycleOwner` 挂到 ComposeView（全仓 0 命中），存在 attach 崩溃与 DisposableEffect 不回收风险 | `BrowserOverlay.kt:205-218`（窗口参数）；`:209-212`（缺 NOT_FOCUSABLE）；`:119-128` + `OverlayLifecycleOwner.kt`（未挂接） | 窗口高度改 wrap-content 只包控制条或 collapsed 时切 `FLAG_NOT_TOUCHABLE`；非 IME 态加 NOT_FOCUSABLE；doShow 中补 `setViewTreeLifecycleOwner`/`setViewTreeSavedStateRegistryOwner` | `SYSTEM_ALERT_WINDOW` 场景，影响整机其他应用 |
| UI-008 | 高 | 交互 | **悬浮球 dismiss 后永久无法再显示**：`dismiss()` 只 `EasyFloat.dismiss(tag)` 不清 `ballView` 缓存，`doShow()` 首行 `if (ballView != null) return` 短路；`@Singleton` 生命周期内球一旦关闭即"失踪" | `CyberNeonBallManager.kt:63-68`（dismiss 不置空）+ `:85` 附近（doShow 短路） | dismiss 回调里 `ballView = null`，或改为 dismiss 后可复用 `show()` | 状态机单行修复即可 |
| UI-009 | 高 | 边界/导航 | **权限屏 Shizuku 兜底跳转浏览器未捕获异常，可致崩溃**：设备无浏览器时 `startActivity` 抛 `ActivityNotFoundException` 未被捕获 | `PermissionsScreen.kt:296-301`（`catch(e){ …startActivity(Intent(ACTION_VIEW, uri)) }` 兜底本身无保护） | `runCatching` 包裹 + Toast 提示手动下载；同文件其余 4 处系统设置跳转失败被静默吞掉（UI-030），应统一失败反馈 | 崩溃类问题按高处理 |

### 2.2 中严重度（22 项）

| 编号 | 严重程度 | 问题类型 | 问题描述 | 复现步骤/证据 | 建议修复方案 | 备注 |
|---|---|---|---|---|---|---|
| UI-010 | 中 | 国际化 | **i18n 全面缺失**：`strings.xml` 仅 2 条字符串；UI 层 **1085 行硬编码中文**（34 文件，最多的为 AgentChatViewModel 199 行 / AgentChatScreen 178 行）；`stringResource` 使用 **0 处**；中英混用（`ImageLightbox.kt:75` "Full Image View" vs `:132` "关闭"；`SettingsScreen.kt:50` contentDescription "Back"）；manifest 声明 `supportsRtl="true"` 名存实亡 | `app/src/main/res/values/strings.xml`；grep 统计表见附录 A | 建立字符串资源骨架并分批迁移；默认资源建议英文 + `values-zh`；data 层（如 `SlashMenuProvider.kt:87` badge 文案）返回结构化状态而非文案 | 也被 Issue #69 间接波及（会话管理等功能都需新文案） |
| UI-011 | 中 | 布局 | **冷启动白闪**：`themes.xml` 仅 `android:Theme.Material.Light.NoActionBar`，无 `values-night`；深色用户在 Compose 内容渲染前看到亮色窗口背景 | `app/src/main/res/values/themes.xml:3`（对照 `ComposeFoundry` 已声明透明系统栏） | 增加 `values-night/themes.xml`（`Theme.Material.NoActionBar`）或统一 DayNight | 与深色优先设计语言直接矛盾 |
| UI-012 | 中 | 无障碍 | **触摸目标系统性低于 48dp**：输入栏一圈主控按钮 32-40dp（ToolkitRingButton 32dp、BrainMenuButton 36dp、Send/GitHub/Attach 40dp）；Agent 气泡操作钮 32dp、错误复制钮 28dp；`AttachmentPreviewBar` 注释宣称"48dp 触控区"实为**假修复**——外层 48dp Box 无 clickable，真实可点仅 20/24dp；`ContextMeterBar` 可点击长条实际仅 ~8dp 高；设置 `KeyValueEditor` 删除钮 28dp；日志行内复制 28dp；Skill 类型选择器 ~32dp；`RetryChip` ~30dp | `ToolkitRingButton.kt:112-118`、`BrainMenuButton.kt:91`、`AgentChatScreen.kt:818/832/1672`、`AttachmentPreviewBar.kt:96-105/164-170`、`ContextMeterBar.kt:75-80`、`SettingsScreen.kt:322-324`、`LogViewerScreen.kt:455`、`SkillScreen.kt:344-358`、`AgentChatScreen.kt:1227-1252` | 统一封装 `Modifier.minimumInteractiveComponentSize()`（或外层 48dp 容器持有 clickable，视觉尺寸不变）；删除误导性注释 | Material 无障碍硬性建议；涉及 10+ 处 |
| UI-013 | 中 | 无障碍 | **语义标注缺失/歧义**：icon-only 无标签 3 处（`SettingsScreen.kt:323/335`、`AttachmentPreviewBar.kt:173`）；ToolkitRingButton 各 chip 关闭钮 CD 全是"关闭"无法区分；GithubIconButton CD 恒为 "GitHub" 不播报连接状态；ContextMeterBar 无 `progressSemantics`（TalkBack 无法得知上下文占用）；抽屉导航项无 `selected` 语义且 `enabled=!selected` 让当前项被读成"已禁用"；BrainMenuButton 3 个 Slider 无标签；Toolkit 函数行 clickable+Checkbox 未用 `toggleable(Role.Checkbox)` 关联 | `ToolkitRingButton.kt:598-604`、`GithubIconButton.kt:77`、`ContextMeterBar.kt:80`、`ApexDrawerContent.kt:140-150`、`BrainMenuButton.kt:351-368`、`ToolkitRingButton.kt:171-182` | 补齐动态 contentDescription / `stateDescription` / `progressSemantics()` / `selectable(role)`；迁移时一并入 strings.xml | TalkBack 全链路基本不可用 |
| UI-014 | 中 | 边界 | **配置变更状态大面积丢失**：全应用 `rememberSaveable` 真实使用 **0 处**（唯一命中是注释），而 `remember { mutableStateOf }` 有 **76 处**；旋转/主题切换后：导航目的地重置回 Agent 页（`ApexRoot.kt:79`）、日志筛选词、各菜单/对话框开关、问题卡选择、滑块值、终端设置输入框全部丢失；`QuestionCard`/`UserInputDialog` 正在编辑的内容同样丢失 | grep 统计（附录 A #5）；代表性：`ApexRoot.kt:79`、`AgentChatScreen.kt:2160-2163`、`LogViewerScreen.kt:90-101`、`TerminalScreen.kt:299/318` | 导航态提升到 ViewModel/SavedStateHandle；纯 UI 布尔/文本/数值换 `rememberSaveable` | 输入草稿已用 SavedStateHandle 是好开始，UI 层完全未跟进 |
| UI-015 | 中 | 交互 | **无 Navigation 库、无 BackHandler，返回键直接退出应用**：7 个屏幕用 `when(currentDestination)` 切换（无入栈），系统返回手势在任何页面都会直接退出而非回到 Agent 主页；未声明 `enableOnBackInvokedCallback`（预测性返回未接入） | `ApexRoot.kt:152-164`；grep `BackHandler`/`NavHost` 全 app 模块 0 命中 | 引入 `androidx.navigation:navigation-compose` 或最低成本方案：以 `BackHandler(enabled = currentDestination != Agent)` 回退目的地；叠加 UI-014 用 rememberSaveable 保存目的地 | 与 Issue #69 "会话管理/历史列表"诉求天然契合，建议一并规划 |
| UI-016 | 中 | 一致性 | **主题外硬编码颜色 21 处**（浅色模式下对比度崩坏）：日志等级色 `0xFFE57373/0xFFFFB74D/0xFF81C784`；终端渲染区 `0xFF1E1E1E/0xFFD4D4D4`（浅色模式下近不可读）；ContextMeterBar 霓虹粉/橙 `0xFFFF4D8D/0xFFFFB020`；SlashCommandButton 青粉渐变 `0xFF00E5FF→0xFFFF4081`（青在浅底对比度 ≈1.4:1）；GitHub 状态点 `0xFF4CAF50/0xFF9E9E9E`；时间线 DONE 绿 `0xFF22C55E` 与风险三色 | `LogViewerScreen.kt:218-257/415-420`、`TerminalRenderer.kt:48/54/70`、`ContextMeterBar.kt:53-54/126`、`SlashCommandButton.kt:58-59`、`ApexDrawerContent.kt:249`、`AgentChatScreen.kt:1442/1832-1836` | 警告/危险/成功色入扩展色板（`ExtendedColors`），随明暗主题成对定义；终端区配色可保留但按主题切换底/前景 | 现状＝浅色主题不可用的一半原因 |
| UI-017 | 中 | 性能 | **日志屏每条新日志触发全量 `joinToString`**：`remember(records)` 依赖每次追加变化，持续把整个缓冲区（StatsBar 按 500MB 上限设计）拼成巨串，长会话 GC 抖动/主线程 O(n) | `LogViewerScreen.kt:134` | 仅在点击复制/导出时构建字符串（onClick 内计算） | 演示流畅、长跑翻车的典型 |
| UI-018 | 中 | 性能/边界 | **悬浮窗自绘与动画不受生命周期约束**：NeonRingView 用 `Handler.postDelayed(16ms)` 自绘循环（非 Choreographer），附着即常驻 60fps，被遮挡/锁屏不停，onDraw 每帧新建 3 个 SweepGradient+RectF；脉冲环退出 NEED_HUMAN 只 `clearAnimation()`，INFINITE ObjectAnimator 从不 `cancel()`（隐形耗电） | `NeonRingView.kt:80-86/100-109`、`CyberNeonBallManager.kt:146-149` | 改 ValueAnimator/Choreographer + 按 window visibility 暂停 + shader 缓存；持有 animator 引用并 cancel | |
| UI-019 | 中 | 性能 | **无限动画常驻空转**：ContextMeterBar 的 `rememberInfiniteTransition` 只要组合就跑（每帧重绘，叠加两处 blur），<80% 危险阈值时动画结果恒定不使用 | `ContextMeterBar.kt:65-71`（`glowAlpha = if (percent>=80) pulse else 0.5f`） | 条件组合 infinite transition 或 `snap` 停值；评估 blur 换预渲染渐变 | 常驻顶栏组件，整机功耗敏感 |
| UI-020 | 中 | 功能 | **设置屏表单工程质量缺陷集中**：删除 Provider/Profile 无确认（连带 API Key 误触即删）；"测试连接"无 loading/无超时、可无限重复点击；7 个 Slider 每帧全量 JSON 写盘；`IntFieldRow` 无法清空输入、无范围校验（超时可为负）；readOnly TextField 上挂 clickable 打不开菜单；"Prompt Preset" 是死控件（选了无效果）；保存无校验无反馈 | `SettingsScreen.kt:622/377-378/54-65/393-397/421-434/226-235/258-265/577-580/647`；`SettingsRepository.kt:170-179` | 危险操作加确认框；测试连接加 `testing` 态 + `withTimeout(15s)`；本地态编辑 + debounce 提交；改 `ExposedDropdownMenuBox`；未实现的控件隐藏或禁用 | 对照 Skill/Memory 屏的确认框范式补齐 |
| UI-021 | 中 | 功能 | **Skill 屏：同名创建静默覆盖 + 失败仍关对话框**：id 仅由 name 派生，重复创建直接覆写旧 manifest 且提示"已创建"；`createSkill` 返回 false（依赖缺失）时对话框照样关闭，用户已填内容全部丢失；导入在主线程读文件/装包（卡顿/ANR 风险）；Snackbar 绕过 `SnackbarHostState` 永不自动消失（MemoryScreen 同病） | `SkillScreen.kt:124/274-277/176/303-307/203-215`；`SkillRegistry.kt:128-131`（无同名检测） | 创建前查 id 冲突给"覆盖？"确认；失败保留对话框 + inline 错误；IO 移到 `Dispatchers.IO`；改用标准 SnackbarHostState | |
| UI-022 | 中 | 边界 | **权限屏从系统设置返回后状态不刷新**：仅 `LaunchedEffect(Unit)` 首次检测；授予无障碍/悬浮窗后返回本屏仍显示"未获得"；`requestPermission(1001)` 异步授权后立即重读必得旧值；其余系统设置跳转失败被 `runCatching` 静默吞掉（定制 ROM 上点了没反应） | `PermissionsScreen.kt:69-80/305-311/156-204` | `LifecycleEventObserver` ON_RESUME 重检 + 注册 Shizuku 回调；跳转失败 Toast + 兜底应用详情页 | 三态 Pill 的"颜色+文字"双通道是亮点（见 §3），输在时效性 |
| UI-023 | 中 | 性能 | **LazyColumn key 缺失或不稳定**：消息列表以 `index` 作 key（头部插入/撤回时复用错位、动画错乱）；日志列表（高频追加）无 key——行内 `remember{expanded}` 会串到错误记录；Settings/Terminal 各列表同样无 key；`itemsIndexed(steps)` 无 key | `AgentChatScreen.kt:293`（`key = { index, _ -> index }`）、`LogViewerScreen.kt:192`、`AgentChatScreen.kt:1437`、`SettingsScreen.kt:297/406/449`、`TerminalScreen.kt:336` | 消息/日志用稳定 id 作 key（LogRecord 已有 id）；步骤列表用时间戳+序号 | Skill/Memory 屏已正确用 key，可作参照 |
| UI-024 | 中 | 布局 | **大屏/折叠屏零适配**：全应用 0 处 `widthIn/maxWidth/WindowWidthSizeClass`；平板上气泡固定 `widthIn(max=320/340.dp)`、菜单固定 `width(300/280.dp)`、抽屉内容不可滚动（横屏 ~360dp 高时底部状态区不可达）、终端抽屉固定 340dp（与主抽屉 288dp 不一致）；双 LazyColumn 各占 `weight(1f, fill=false)` 布局僵硬（Memory 屏） | grep `widthIn|maxWidth|WindowSizeClass` 0 命中；`AgentChatScreen.kt:681/754`、`BrainMenuButton.kt:111`、`ToolkitRingButton.kt:135`、`ApexDrawerContent.kt:50-220`、`TerminalScreen.kt:159`、`MemoryScreen.kt:152-177` | 引入 WindowSizeClass：≥600dp 切两栏（会话列表 + 详情）；菜单改 `widthIn(min,max)`；抽屉加 `verticalScroll` | 平板/折叠屏当前＝拉伸放大的手机 UI |
| UI-025 | 中 | 功能 | **ask_user 选择类请求进入死路**：`UserInputDialog` 对 `InputType.CHOICE` 将提交按钮设为 `enabled = !isChoice`（恒禁用），用户只能"取消"，无法作答——Agent 等待回复直至超时 | `AgentChatScreen.kt:2384-2391`（`enabled = !isChoice // 选项类暂以确认框展示`） | CHOICE 渲染为单选列表 + 可提交；至少临时放开按钮或明示"此类型暂不支持，请取消" | 与 `AskUserChoiceTool` 能力不匹配 |
| UI-026 | 中 | 交互 | **ImeAction.Send 使多行输入无法换行**：`AdaptiveInputField` 最大 5 行，但键盘回车键被 Send 动作占据，用户无法手动换行（只能粘贴含换行文本） | `AdaptiveInputField.kt:134-140` | `imeAction = ImeAction.Default` + 发送走独立按钮；或提供"换行/发送"切换开关 | 与 AI 聊天输入的核心诉求直接冲突 |
| UI-027 | 中 | 边界/安全 | **FileProvider 暴露整个外部存储根**：`file_paths.xml` 兜底 `<external-path name="external" path="." />`，任何经 FileProvider 分享的 URI 授予后可读该路径 | `app/src/main/res/xml/file_paths.xml:16` | 收窄到 attachments/workspace 具体子目录；API≥29 设备可删除该条 | 附：`accessibility_config.xml` 跨模块悬空引用 app 模块字符串，重命名即断（`platform/privilege/.../accessibility_config.xml:10`） |
| UI-028 | 中 | 一致性 | **悬浮窗 Compose 未包 ApexTheme**：浮窗内是 M3 默认紫色 scheme，硬编码 `0xFF4CAF50/0xFF2196F3/0xFFFF9800` 与全应用 mint/amber/magenta 语言冲突；6 处硬编码 `fontSize=11/12/13.sp` 绕过 Typography | `BrowserOverlay.kt:244/255-260/307` | 包 `ApexTheme` 并改用 colorScheme/typography | 品牌一致性 |
| UI-029 | 中 | 性能/一致性 | **主线程 IO 与业务误判**：Skill 导入读 URI + 装包在主线程；Toolkit 规则 .md 导入在主线程；SlashMenuProvider 用显示字符串推断状态（`label.contains("未安装")`，改文案即全错）；规则/Schema 保存无校验（空值静默 no-op、坏 JSON Schema 直接入库传导到推理链路）、删除规则无确认无撤销 | `SkillScreen.kt:176/303-307`、`ToolkitRingButton.kt:378-380/524/419-431/474`、`SlashMenuProvider.kt:78` | IO 全部 `Dispatchers.IO`；data 层返回结构化 status；Schema 保存前 `runCatching { Json.parseToJsonElement }`；删除加确认/Snackbar 撤销 | |
| UI-030 | 中 | 边界 | **系统设置跳转失败静默 + 破坏性操作缺确认（横向扫查）**：权限屏 4 处 `openXxxSettings` 失败无反馈；日志屏"清空"一键清掉全部缓冲区无确认、导出用 `GlobalScope` + 空 catch 失败无感知；MarkdownText `copied` 永不复位 | `PermissionsScreen.kt:156-204`、`LogViewerScreen.kt:143-147/469/488-490`、`MarkdownText.kt:162/204` | 统一失败 Toast；清空加确认框；GlobalScope 换受管 scope；复制态延时复位 | 与 UI-022 合并整改亦可 |
| UI-031 | 中 | 无障碍 | **低 alpha 文字对比度不足**：`AttachmentBubbles` 文件大小 `onPrimaryContainer.copy(alpha=0.4f)`、多处 0.5-0.6f 文字/图标；终端空态 `Color.Gray` 对深底 ≈4.1:1（低于 13sp 正文 AA 要求 4.5:1）；`MarkdownParser` 循环内重复编译正则加剧长回复滚动卡顿（无链接渲染，`[text](url)` 以纯文本展示） | `AttachmentBubbles.kt:59-79`、`TerminalRenderer.kt:48`、`MarkdownParser.kt:54-95`、`MarkdownText.kt` | 正文性文字 alpha ≥0.75 或直接用 `onSurfaceVariant`；正则提升为顶层常量；链接至少下划线+可点击 | 对照 WCAG 4.5:1（正文）/3:1（大文本） |

### 2.3 低严重度（14 项）

| 编号 | 严重程度 | 问题类型 | 问题描述 | 复现步骤/证据 | 建议修复方案 | 备注 |
|---|---|---|---|---|---|---|
| UI-032 | 低 | 交互 | ImageLightbox 双指缩放忽略 centroid（锚点恒为中心），pan 无边界钳制（图片可拖出屏幕，与注释宣称相反） | `ImageLightbox.kt:84-91` | clamp offset + `detectTransformGestures` 的 centroid 缩放 | |
| UI-033 | 低 | 交互 | 双击全屏编辑有 `>50 字符` 隐藏门槛，短文本双击无任何反馈，功能不可发现 | `AdaptiveInputField.kt:121` | 去门槛或提供可见入口按钮 | 与 UI-002 一并重构 |
| UI-034 | 低 | 国际化 | `"%.2f".format()` 用默认 locale（德语等地区显示 "0,50"/"1,9MB"）；时间格式部分用 `Locale.US` 部分用默认 | `BrainMenuButton.kt:309/317`、`AttachmentModels.kt:68-78`、`SettingsScreen.kt:421-434` | 统一 `Locale.ROOT`/显式 locale | |
| UI-035 | 低 | 交互 | DropdownMenu 内嵌 3 个 Slider（BrainMenuButton），M3 菜单纵向滚动手势与滑块横向拖动冲突；弹窗内 schema 编辑器无 JSON 校验即保存 | `BrainMenuButton.kt:101-268`、`ToolkitRingButton.kt:524` | 参数区改独立 BottomSheet；保存前校验 | |
| UI-036 | 低 | 布局 | SlashCommandButton 的 `Popup` 无条件组合且 `focusable=true`（菜单关闭后弹窗窗口仍存在，有抢焦点/拦截返回键风险）；斜杠菜单 Column 无 `heightIn(max=…)`，内容多时超屏且 Popup 不翻转（对照：ToolkitRingButton 二级列表做了 220dp 上限） | `SlashCommandButton.kt:112-115/137-141` | `if (showMenu) Popup{…}`；加 `heightIn(max=360.dp)` | |
| UI-037 | 低 | 布局 | 终端抽屉命令列表：36dp 行槽容纳最小高度 40dp 的 TextButton（大字体下裁剪）；LazyColumn（≤4 项）嵌在 verticalScroll Column 里同向嵌套滚动反模式 | `TerminalScreen.kt:336-342` | 改 forEach + 行高 ≥48dp | |
| UI-038 | 低 | 边界 | 终端数字设置项输入 "5"（min=8）实际应用 8 但框内仍显示 "5"（显示值与实际值静默分叉）；每敲一位数字即 `putInt().apply()` 写盘一次；黑白名单 `startsWith` 前缀误伤（拉黑 `ls` 连带 `lsblk`） | `TerminalScreen.kt:298-314`、`TerminalViewModel.kt:68-76/99-102` | `isError`+supportingText；失焦提交；精确匹配或词边界 | |
| UI-039 | 低 | 一致性 | `TerminalRenderer` 用 `collectAsState` 而非 lifecycle 版本（终端后台仍收集高频 PTY 推送）；全屏字符串每次 VT 更新整串重排；`cursorRow/cursorCol` 计算后未使用且注释谎称"画了光标"；两个收集器 launch 进 viewModelScope 可叠加不可取消 | `TerminalRenderer.kt:38-43/61`、`TerminalViewModel.kt:198-212` | 统一 `collectAsStateWithLifecycle`；`buffer()`+diff；保存 Job；删死代码 | |
| UI-040 | 低 | 一致性 | 死代码/失实注释若干：`Status.Pending` 枚举未使用；`AttachmentModels.icon` emoji 属性全仓 0 引用；BrowserOverlay 注释宣称"Compose pointerInput 拖拽"实无实现；`Prompt Preset` 死控件（已在 UI-020 计）；`addView` 失败 catch 未清 `composeView` 引用 | `PermissionsScreen.kt:326`、`AttachmentModels.kt:24-31`、`BrowserOverlay.kt:133-134/147-149` | 清理或实现 | |
| UI-041 | 低 | 资源 | square 启动图标缺 `<monochrome>`（round 版有）→ Android 13 主题图标行为不一致；`view_cyber_neon_ball.xml` 用已弃用 `android:tint`、badge 文本 "!" 10sp 硬编码、ImageView 无 contentDescription（悬浮球装饰件，影响小）；无 colors.xml/dimens.xml，悬浮球系列色散落 | `mipmap-anydpi-v26/ic_launcher.xml` vs `ic_launcher_round.xml:5`；`view_cyber_neon_ball.xml:40/53` | 补 monochrome；tint 移到代码（Manager 已做 setColorFilter）；色值收敛为 color 资源 | 启动图标前景安全区经验证合格（对角 30dp < 33dp） |
| UI-042 | 低 | 一致性 | 版本号 "v1.0" 硬编码在抽屉（应读 BuildConfig）；Toast 风格不统一（7 处中文硬编码无封装）；`SlashCommandButton` 缩进错乱 + `Quad` 数据类冗余；`SettingsViewModel` 把原始 `e.message` 弹窗（可能带出端点细节） | `ApexDrawerContent.kt:108`、`FileOpener.kt/BrainMenuButton.kt/AgentChatScreen.kt`、`SettingsViewModel.kt:87` | BuildConfig.VERSION_NAME；统一 Snackbar/Toast 封装；错误信息脱敏 | |
| UI-043 | 低 | 性能 | 每行渲染新建 `SimpleDateFormat`（LogViewer:462-465、MemoryScreen:262-265）；关键词过滤逐键触发（collectLatest 缓解大半）；Memory 搜索无防抖且慢查询结果可晚到覆盖新结果（竞态）；AgentChatViewModel 抽屉层订阅整个 uiState（聊天每个 token 都重组抽屉） | `LogViewerScreen.kt:462`、`MemoryScreen.kt:121-129`+`MemoryViewModel.kt:73-84`、`ApexDrawerContent.kt:46-47` | 格式器复用；`debounce(300)`+`collectLatest`；抽屉订阅轻量子 Flow | |
| UI-044 | 低 | 布局 | 终端渲染区与日志列表单条消息无行数上限（超长异常 dump 一行撑出数千行布局）；安装日志无限增长不裁剪直接喂 Text；日志过滤无结果时无空态占位；日志顶部 4 按钮+计数窄屏挤压换行 | `LogViewerScreen.kt:426/185-197/128-182`、`TerminalViewModel.kt:235-301` | `maxLines`+展开；环形缓冲裁剪；空态占位；操作行改自适应换行 | |
| UI-045 | 低 | 构建 | 无 `resourceConfigurations`/`localeConfig`（全语言资源进 APK、不支持 per-app language）；compose/material-icons 版本内联坐标绕过 version catalog；`security-crypto 1.1.0-alpha06` 非稳定版；debug 无 applicationIdSuffix | `app/build.gradle.kts:14-20/56-100`、`gradle/libs.versions.toml:17-18` | `localeFilters += listOf("zh","en")` + localeConfig；版本收编 catalog；security-crypto 转 stable 或自封装 Keystore | 与 UI-006/UI-010 联动 |

---

## 3. 亮点与良好实践

1. **统一且有辨识度的设计语言**：`Theme.kt` 完整定义明暗两套 scheme（mint 主色 / amber 强调 / magenta 危险态），排版体系"显示 Sans + 正文等宽"呼应终端美学；绝大多数组件严格消费 `MaterialTheme.colorScheme/typography`，自定义动效（120-400ms tween、带 label）克制规范。
2. **`docs/agent-ui-audit.md` 的自审文化**：团队自己维护 UI 审查文档并按 P0-P3 逐项落地（ErrorBlock 红色高亮、ToolKindBadge 五类来源徽章、WebSearchResultsCard 结构化卡片均已实现），注释中保留"缺陷 N 修复"追溯——这是本仓库最值得保持的工程习惯。
3. **`GithubTokenDialog`（GithubIconButton.kt:174-259）是异步表单的正确姿势**：suspend 校验 + 验证期禁用输入/按钮 + inline 错误不关窗 + `ghp_/github_pat_` 格式预检 + `PasswordVisualTransformation`——全仓库唯一做对的密钥输入（对比 UI-006 的设置屏）。
4. **Skill/Memory 屏的"四件套"范式**：空态文案、`items(key=)`、删除二次确认、Snackbar 反馈齐备，可直接作为其他屏幕整改模板；权限屏三态 Pill 用"颜色 + 文字"双通道表达状态，对色盲用户友好。
5. **稳健的系统交互样板**：`FileOpener` 用 FileProvider + `ActivityNotFoundException` 替代 Android 11+ 失效的 `resolveActivity()`，配 MIME 兜底表与路径预检；`BrowserEngine` 11 处 `withContext(Dispatchers.Main)` 系统性包装 WebView 操作；输入草稿用 `SavedStateHandle`（规避 Bundle 1MB 限制）。
6. **零二进制资产的矢量资源链路**：8 个 drawable 全部 vector/shape，NeonRingView 纯代码 SweepGradient 绘制（无 Lottie），自适应图标前景严格落在 108dp 安全区，`dependencyLocking` 锁定解析版本提升 CI 复现性。

---

## 4. 改进路线图建议

### P0 — 止血（1-2 周，崩溃 / 不可用 / 数据丢失）
| 序 | 问题 | 目标 |
|---|---|---|
| 1 | UI-001 | 全局 `imePadding()` 适配，键盘不再遮挡任何输入框 |
| 2 | UI-002 | 全屏编辑器手势/关闭语义重构，杜绝静默丢稿 |
| 3 | UI-006 | API Key 掩码 + 失焦解析（先修"无法输第二个 Key"），随后迁 EncryptedSharedPreferences |
| 4 | UI-007/008 | 悬浮窗窗口参数三连修 + 悬浮球 dismiss 复活 |
| 5 | UI-009 | Shizuku 兜底跳转防崩溃 |
| 6 | UI-025 | CHOICE 死路解锁（或明示不支持） |

### P1 — 可用性核心（2-4 周）
| 序 | 问题 | 目标 |
|---|---|---|
| 7 | UI-003/004/005 | 终端屏最小可用闭环：可滚动 + 可输入 + 真实安装链路（接入 PackageManager/PRoot） |
| 8 | UI-012/013 | 触摸目标 48dp 批量整改 + 语义标注（一次封装 + 一次扫尾） |
| 9 | UI-014/015 | rememberSaveable 迁移 + BackHandler/Navigation（可结合 Issue #69 会话管理一并设计） |
| 10 | UI-020/021/022 | 设置/Skill/权限三屏交互补课（确认框、loading、返回刷新） |

### P2 — 一致性与质量（4-8 周）
| 序 | 问题 | 目标 |
|---|---|---|
| 11 | UI-010/045 | 字符串资源骨架 + 分批迁移 + localeConfig |
| 12 | UI-011/016/028/031 | values-night、扩展色板、悬浮窗主题化、对比度整改（浅色模式整体过关） |
| 13 | UI-017/018/019/023/043 | 性能专项（日志全量拼接、动画空转、lazy key、格式器复用） |
| 14 | UI-024 | WindowSizeClass 大屏两栏布局 |

### P3 — 打磨（持续）
- UI-032 ~ UI-041 低severity清单按屏清扫；死代码清理（UI-040）；build 配置收编 version catalog（UI-045）。

---

## 5. 附录

### A. 系统性扫描统计（app 模块，2026-08-27）

| # | 扫描项 | 结果 | 说明 |
|---|---|---|---|
| 1 | `Color(0x…)` 硬编码 | 81 处 / 8 文件 | Theme.kt 60 处属主题定义（合理）；主题外 21 处 |
| 2 | `contentDescription = null` | 32 处 / 9 文件 | 疑似问题 3 处（icon-only IconButton）；其余为"图标+文本"合法装饰 |
| 3 | UI 层中文字面量 | 1085 行 / 34 文件 | `stringResource` 引用 0 处 |
| 4 | 硬编码 `fontSize = x.sp` | 业务代码 8 处 | BrowserOverlay 6、TerminalRenderer 2 |
| 5 | `rememberSaveable` vs `remember{mutableStateOf}` | 0（仅注释）vs 76 处 | 配置变更状态全丢 |
| 6 | `BackHandler` | 0 处 | 预测性返回未接入 |
| 7 | insets 修饰符 | 5 处 / 2 文件 | `imePadding` 全模块 0 处 |
| 8 | LazyColumn/LazyRow | 10 处 | 4 处有稳定 key；Agent 消息用 index，其余无 key |
| 9 | `Toast.makeText` | 7 处 / 2 文件 | 均中文硬编码 |
| 10 | 主线程纪律 | 良好 | BrowserEngine 11 处 withContext(Main) 规范；ui 目录零 runBlocking/Thread.sleep |

### B. 未能执行的检查项及原因

| 检查项 | 原因 |
|---|---|
| 模拟器/真机动态测试、截图与录屏 | 云端沙箱无 Android 模拟器；仓库 Releases/Actions 未提供可直接安装验证的 APK 通道（Actions APK 构建产物需登录态下载） |
| `./gradlew lint` / Android Lint 报告 | 沙箱无 Android SDK；CI 亦未包含 lint 任务（建议后续加入 `lintDebug` 门禁，本报告 UI-012/013 类问题多数可被 lint 自动捕获） |
| TalkBack 实测、字体缩放实测 | 依赖动态环境；本报告相关结论基于语义代码审查推断 |
| GitHub Actions 日志深度分析 | 最近 3 次 failure 均在 `p69-ubuntu-rootfs-provisioning` 分支（PR #73 的终端测试），与 main（绿色）无关，未展开 |
| Codespaces 只读构建 | 需要仓库 Codespaces 配额授权，未启用 |

### C. 使用的工具与数据源

- GitHub REST API（repo/issues/actions 元数据）、只读 `git clone`（`main@e34c176`，深度 50）
- 逐行人工审查 ~12,000 行 UI 代码；ripgrep 模式扫描 10 项（见附录 A）
- 参考基线：Material Design 3 规范（触摸目标 ≥48dp、对比度 WCAG AA 4.5:1/3:1）、Android 官方 edge-to-edge 与预测性返回指南
- 仓库内既有文档：`docs/agent-ui-audit.md`、`docs/PERF.md`、Issue #69（用户体验优化）
- 交叉验证：所有"高"严重度结论均经独立 grep/源码复读复核（imePadding=0、BackHandler=0、Navigation=0、stringResource=0、rememberSaveable=0、VisualTransformation=0）

### D. 参考资料

- Material Design 3 — Accessibility: https://m3.material.io/foundations/accessible-design/overview
- Android Developers — Edge-to-edge handling: https://developer.android.com/develop/ui/compose/layouts/insets
- Android Developers — Save UI state (rememberSaveable): https://developer.android.com/develop/ui/compose/state-saving
- Android Developers — Predictive back gesture: https://developer.android.com/guide/navigation/predictive-back-gesture
- Android Developers — Lazy list performance (keys): https://developer.android.com/develop/ui/compose/lists#keys
- WCAG 2.1 — Target Size & Contrast: https://www.w3.org/WAI/WCAG21/Understanding/target-size.html

---

*报告生成：自动化 UI 质量审查流程（静态审查 + 交叉验证）；如需按屏幕拆分为独立 Issue 追踪，建议以 UI-XXX 编号为引用键。*
