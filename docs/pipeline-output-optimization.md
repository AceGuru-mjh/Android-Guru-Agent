# Agent 流水线输出优化（Pipeline Output Optimization）

> 目标：让 Agent 执行过程中的每一类输出（文字 / 代码 / 文件 / 选择 / 工具 / 思考 / Skill / 连接器 / 插件 / MCP）都以最适合的形态呈现，并补全此前被丢弃的收尾信息。

## 一、新增能力总览

| 能力 | 之前 | 现在 |
|------|------|------|
| 代码输出 | 纯文本等宽块 | `CodeOutputCard`：轻量语法高亮（关键字/字符串/数字/注释/注解）+ 语言标签 + 行数 + 复制 |
| 文件操作 | 一段 ✅ 文本 | `FileOpCard`：按扩展名图标 + 文件名/路径 + 操作徽章（新建/覆盖/追加/编辑/删除/移动）+ 大小变更（Old→New）+ 行数统计 + 逐条编辑明细 |
| Shell 执行 | 纯文本 | `ShellOutputCard`：`$ command` 命令头（可复制）+ 输出体，失败红色化 |
| JSON 输出 | 原始 JSON 文本 | `JsonOutputCard`：可折叠树形查看器（深度≤2 默认展开，30 项截断，值类型着色） |
| 连接器调用 | 无分类（表现为普通工具） | `ToolKind.CONNECTOR`（紫色 + Link 图标）：`connector*` / `http_request` / `github_*` 自动识别，`/connector:xxx` 路由专用横幅 |
| 插件调用 | 无分类 | `ToolKind.PLUGIN`（琥珀 + Extension 图标）：`plugin*` 前缀识别，`/plugin:xxx` 路由专用横幅 |
| Skill 调用 | 横幅永远"正在执行"脉冲 | `PipelineBannerCard`：运行中脉冲、结束后对勾 + 总耗时（修复永不停歇的动画） |
| Plan 步骤 | `StepStart` 事件被丢弃 | `StepMarkerCard` 步骤分隔卡（步骤 N + 描述） |
| 任务完成 | `Complete` 事件被丢弃（只复位 loading） | `RunSummaryCard`：总结文本 + 迭代数 + 工具调用数 + 总耗时 |
| 弹出选择（CHOICE） | 只有自由文本框 | 从 prompt 解析 `1. / • / -` 选项渲染单选卡 + 自定义答案兜底 |
| 确认（CONFIRMATION） | "提交/取消"语义模糊 | 明确"确认 / 拒绝"双按钮 |
| 工具输出细节 | 折叠时只有 8 行原始文本 | 折叠时智能摘要行（文件路径 / 命令 / URL / server）+ 统计（行数/字符数）+ 截断提示 + 复制按钮 |
| ANSI 转义序列 | 原样混入输出（shell 彩色码） | 事件入口统一 `stripAnsi` 清理 |
| 运行中工具卡 | 无耗时感知 | 实时耗时计时（秒级刷新）+ 参数智能摘要 |

## 二、架构与数据流

```
AgentEvent ──► AgentChatViewModel.handleEvent
                │  stripAnsi（chunk/output 统一清理）
                │  classifyTool(name, args, routeContextKind) → ToolKind
                │  StepStart  → AgentUiMessage.StepMarker
                │  Complete   → AgentUiMessage.RunSummary + finishActiveBanner()
                │  Error/Aborted/abort() → finishActiveBanner()
                ▼
          AgentUiMessage（新增 PipelineBanner / StepMarker / RunSummary）
                ▼
        AgentChatMessages.AgentMessageItem 分发
                ▼
   ToolCallCard ──► SmartToolOutput（detectOutputView 自动路由）
                      ├─ FILE_OP → FileOpCard(parseFileOp)
                      ├─ SHELL   → ShellOutputCard
                      ├─ CODE    → CodeOutputCard(highlightCode)
                      ├─ JSON    → JsonOutputCard(JsonNodeRow)
                      └─ PLAIN   → TextOutputBlock（复制 + 统计 + 截断提示）
```

### 关键决策

1. **输出视图路由是纯函数**（`detectOutputView` / `parseFileOp` / `classifyTool` / `smartToolSummary`）：
   全部为无副作用字符串处理，便于独立单测；不引入第三方语法高亮库（约 60 行正则扫描器实现，覆盖 C 族/Kotlin/Java/Python/Go/Rust/Swift/Bash 常用关键字）。
2. **路由上下文泛化**：`skillContext: String?` 升级为 `routeContextKind/Name`，`SlashCommandRoute` 新增 `routeKind/sourceName`，`/skill:` `/connector:` `/plugin:` 三类指令统一走 `PipelineBanner`，循环内工具调用带同源徽章（`classifyTool` 兜底规则）。
3. **横幅收尾不新增消息**：完成态通过 `finishActiveBanner()` 原地更新 `PipelineBanner.finishedAt`，消息流不被收尾噪音刷屏。
4. **ANSI 清理在事件入口**：ViewModel 在 chunk/output/fullOutput 进入缓冲前统一清理，UI 层零感知，时间线与卡片共用干净文本。

## 三、涉及文件

- `ui/screen/agent/AgentChatOutputCards.kt`（新增）：智能输出渲染器（代码高亮/文件卡/JSON 树/Shell 卡/RunSummary/StepMarker/ANSI 清理/格式化工具）
- `ui/screen/agent/AgentChatViewModel.kt`：事件处理增强（StepStart/Complete/横幅收尾/路由上下文/ANSI 清理）
- `ui/screen/agent/AgentUiModels.kt`：`ToolKind.CONNECTOR|PLUGIN`、`PipelineBanner`、`StepMarker`、`RunSummary`、`AgentToolCallUi.startedAt`
- `ui/screen/agent/AgentChatToolCards.kt`：CONNECTOR/PLUGIN 视觉规格、智能摘要行、SmartToolOutput 接线、运行卡实时耗时
- `ui/screen/agent/AgentChatPlanCards.kt`：`SkillBannerCard` → `PipelineBannerCard`（三态：Skill/连接器/插件 + 运行/完成）
- `ui/screen/agent/AgentChatDialogs.kt`：`UserInputDialog` 按输入类型差异化（CHOICE 单选卡/CONFIRMATION 语义化/TEXT）
- `ui/screen/agent/AgentChatMessages.kt`：新消息类型分发
- `ui/screen/agent/SlashCommands.kt` + `slash/SlashCommandRouter.kt`：路由类别（routeKind/sourceName）
