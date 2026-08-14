# MEMORY

## 项目：Android-Guru-Agent 悬浮球视觉
- 项目实际使用的是 `android.webkit.WebView`（系统 WebView，Chromium 内核），并非启动 com.android.chrome 应用（证据：BrowserEngine.kt `WebView(applicationContext())`）。
- 悬浮球方案：保留液态球体底座 `bg_obsidian_glossy`，中心图标用中性 web/globe 图标 `ic_web_core`（替代原 AI 三角 `ic_cyber_core`）。
- 用户偏好：不纠结浏览器商标、快速出成品，优先最小改造量（改 XML/drawable，不动 Kotlin）。
- 浮球由 `CyberNeonBallManager.kt`（EasyFloat）管理，中心图标仅 `findViewById(R.id.imgCoreIcon)`，代码不改 src。
- **2026-08-13：已移除 Lottie 可选分支**（依赖 + import + 布局节点 + 代码 raw 资源分支 + 死文件 `res/raw/anim_neon_running.json`）。霓虹环现**仅由代码绘制 `NeonRingView`**，零 Lottie 依赖、零额外包体。注释里出现的 "Lottie" 字样仅作决策说明，无实际引用。
- EasyFloat 依赖加固（A+）：坐标固定进 `libs.versions.toml`（2.0.4）；`settings.gradle.kts` 用 `exclusiveContent` 仅放行 `com.github.princekin-f` group 走 JitPack；`gradle.properties` 加 HTTP 超时 300s；`app/build.gradle.kts` 启用 `dependencyLocking { lockAllConfigurations() }`；CI 加依赖预热+重试（**不用 `--refresh-dependencies`**）。

## Agent 工作约定：代码阅读策略（2026-08-13 与用户确认）
- 大文件（>600 行）用 `read_file` 的 `offset/limit` 分段读，agent 可自由选任意起止行，不机械按固定 700 行切。
- 每片读完后，agent 自行评估"是否已定位目标 / 是否覆盖用户问题"：够用即停，不读后续段；不够再读下一段。决策权在 agent，目标是避免全量灌爆 context 与白读。
- **不在 context 内边读边写总结**（总结是有损压缩，易失真且占空间）；读完停止后，将"文件级结论 / 关键位置"写入 working_memory（持久、跨会话、不占下次 context）。
- 仅在 >3000 行且必须通读（如全调用链审计）时降级：读一片 → 记一条极简笔记到 working_memory → 清掉 context 内该片段，靠持久笔记串全貌。
- 写后 diff 摘要：每次 `replace_in_file`/`write_to_file` 后，回读改动区，回复中给出"新增 X 行 / 删除 Y 行"的人工 diff 摘要（内置 write 工具不回显增删行数，此为弥补）。

## 项目：Agent 对话 UI（2026-08-13 改造）
- Agent 消息气泡（`AgentBubble`，`app/.../screen/agent/AgentChatScreen.kt`）原本无操作行；已新增末尾操作行：复制（`LocalClipboardManager`）+ 脑子图标（`Icons.Default.Psychology`，点击"已整理到记忆"Toast）。
- "整理到记忆"当前为 UI 占位：`AgentChatViewModel.organizeToMemory(text)` 仅打日志 + TODO，未实接 CS-Mem 后端（CsMemSessionManager 仅有 startSession/afterAction/finishSession 生命周期，缺"整理现成对话文本"API）。

## 项目：工具触发机制（2026-08-13 与用户核对）
- 工具注册在 `ToolModule.provideToolRegistry`（51 个：44 基础 + 7 GitHub 条件），经 `SafeAgentTool` 包装后入 `ToolRegistry`。
- **触发有四类，勿混淆**：
  1. **LLM 自主**：system prompt 给工具列表，模型推理选工具（绝大多数 browser_*/文件/网络工具）。
  2. **用户显式指定（斜杠）**：`/skill:xxx` → SkillToolAdapter 编排多工具；`/mcp:github` → GitHub 工具组（SlashCommandParser/Router + AgentChatViewModel.handleSlashCommand）。
  3. **用户经反问指定**：`AskUserChoiceTool` + `UserQuestionBridge` 弹选项，用户点选后继续对应工具。
  4. **框架被动（observer）**：仅 CS-Mem 记忆采集（`CsMemSessionObserver` 实现 `ExecutionMemoryObserver`，onTaskStart/afterAction/finishSession 自动写记忆），**不走工具接口**，直接调 CsMemSessionManager。
- 关键澄清：`browser_*` 等工具是"LLM 主动 + 用户可斜杠指定"驱动，**非被动**；真正被动的只有 CS-Mem 记忆层。WebView 内部回调（onPermissionRequest/setDownloadListener/onReceivedSslError）是引擎机制，非"工具被动触发"。

## 项目：内置工具能力边界（2026-08-13 核对）
- **CodeBuddy 宿主内置工具**（read_file/write_to_file/search_content/execute_command）：客户端原生，项目改不了，只能提产品需求（已起草 docs/feature-request-write-tool-diff-stat.md：要求 write 原生返回 diffStat）。
- **本 Agent 引擎内置工具**（core/tool-registry/.../builtin/，Kotlin）：可自由增强——加新工具（继承 Tool 注册进 all()）、扩展 SafeAgentTool 安全层、实现 StreamingTool 流式、用 Skill 编复合动作。
- read 工具：并行✅、行号✅、offset/limit 分段✅；write 工具：增删行数回显❌（客户端缺口，靠 agent 写后 diff 摘要弥补）。

## 项目：UI 依赖与实现约定（2026-08-13 确立）
- **谨慎加依赖**：用户认可"先用项目已有能力，不轻易引三方库"。顶部上下文仪表盘（ContextMeterBar）纯 Compose 原生实现（Box.fillMaxWidth(ratio) 分段 + Modifier.shadow 霓虹发光 + DropdownMenu 仪表盘），**否决** SegmentedProgressBar/Gauge/Haze/ModalBottomSheet 三方方案（项目已具全量 Compose 能力，引库违反谨慎加依赖）。
- **弹窗形态偏好**：数据少、紧贴触发点的小弹层用 `DropdownMenu`（锚定在组件下方），不升 `ModalBottomSheet` 全宽抽屉（除非用户明确要底部抽屉式）。
- UI 视觉风格：霓虹科技风（primary 青 / 粉红危险态），状态色阈值 正常<60% / 警告 60-80% / 危险>80%。

## 项目：CS-Mem 记忆系统完善度基线（2026-08-13 核对代码）
- **主干全闭环（~90% 完善）**：采集(CsMemSessionManager 隐式自动) / 存储(MemoryGraphStore Room + MemoryWriterActor 无锁管道) / 修剪(UiTreePruner) / 差分(DifferentialIngestor) / 蒸馏(TraceDistiller→FSM宏，含防退化护栏) / 召回(CsMemRecallTools 三只读工具) / 免疫(MemoryImmuneSystem 悬浮窗+敏感词+隔离) / 旁路(BypassExecutionEngine FSM 95%阈值跳过LLM) / 熵遗忘(EntropyManager+decay/prune)。
- **真实缺口（已修 #11）**：手动整理入口——原 CsMemSessionManager 仅自动生命周期，缺"整理现成对话文本"API。2026-08-13 已加 `organizeText(goal, text)`：建 MANUAL Episode + 文本按行切 SemanticNode(role=TEXT, 稳定指纹去重) 经 writerActor 写入，可被 memory_search_nodes 召回；AgentChatViewModel.organizeToMemory 从占位改为调它；脑子图标 UI 已真接。
- **仍残留缺口**：#9 拓扑同胚迁移(VF2 跨版本记忆迁移)是占位(checkVersionMigration 仅查 WebView 版本)；#12 记忆可视化弱(仅底部 MEM chip，无独立管理页)。
- **Operit 对比说明**：项目内无 Operit 记忆系统实现资料，不妄编对比；以上为 cs-mem 自身代码能力自检。

## 项目：JitPack/EasyFloat 依赖供应链策略（2026-08-13 用户拍板）
- **根因**：CI 偶发 `unresolved reference: com.lzf.easyfloat.*` 是 JitPack 按需构建/网络超时导致 compile classpath 缺失，**非 `R` 资源损坏**（R 崩是连带符号表坍塌）。
- **用户决策（分级）**：
  - **P0 止血 + P1 A+（已执行）**：移除 Lottie、固定 EasyFloat 版本、仓库过滤、HTTP 超时、依赖预热重试、dependency locking；并**移除原 CI 对 `browser/` 的误报隔离**（原隔离只是藏问题）。
  - **P2 中期根治（待办，未做）**：把 EasyFloat 变可控工件——优先 Nexus/Artifactory 代理缓存 JitPack（C3），或 fork 发内部 Maven/GitHub Packages（C2）；可 POC Maven Central 的 `io.github.shenzhen2017:easyfloat`（C1，需 API 回归）。
  - **P3 长期防复发（待办，未做）**：抽 `FloatingBallOverlay` 接口，`browser-core` 不硬依赖 EasyFloat，未来换库不伤筋动骨。
  - **B 方案（WindowManager 重写去 EasyFloat）：暂缓**，除非产品明确要彻底移除浮窗三方库。用户明确反对"只加 `--refresh-dependencies` 重试"与"直接做 B"。
- **CI 约束**：不允许默认 `--refresh-dependencies`（破坏缓存、放大 JitPack 不稳）；lockfile 首次由 CI `--write-locks` 生成；本地无 Android SDK/gradlew，A+ 验证须靠 CI 跑。
