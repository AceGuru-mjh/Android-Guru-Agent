# Agent 输出 UI 审查报告

> 审查目标：`AgentChatScreen.kt` + `AgentChatViewModel.kt` 中 agent 回答过程中的
> 工具 / skill / MCP / 网络搜索调用与错误提示的 UI 设计是否完整。
> 审查日期：2026-08-11

## 一、当前已有的 UI 设计 ✅

| 能力 | 实现位置 | 说明 |
|------|---------|------|
| 工具调用卡片（运行中） | `AgentToolCallUi` + `LiveToolCallCard` | 脉冲动画 + 实时流式输出（尾窗 4000 字符）+ 进度条 |
| 工具调用卡片（完成/失败） | `ToolCallCard` | 标题 + 参数 + 可展开输出 + 耗时；失败时 `isError` 红边框 + ❌ |
| 思考过程 | `ThinkingMessage` + `ThinkingBlock` | 可折叠 "💡 思考过程" |
| Plan 模式 | `PlanMessage` + `PlanConfirmDialog` | 步骤列表 + 确认/驳回 |
| 系统事件 | `System` 消息 | 压缩提示、中止、Slash 指令反馈 |
| 附件上传 | 输入区 | 读取中占位 / 进度 / 读取失败 |
| 用户对焦提问 | `AgentQuestionSheet` | 选项卡片 |
| Abort 控制 | 顶部 + 底部 | 运行中显示"停止" |

**结论：骨架是完整的，但"分类呈现"和"错误突出"两块明显偏弱。**

---

## 二、关键缺口 ❌

### 缺口 1：工具类型未区分（tool / skill / MCP / Web Search）
`AgentToolCallUi` 和 `AgentUiMessage.ToolCall` 只有：
```kotlin
sealed interface AgentUiMessage {
    data class ToolCall(
        val toolName: String,   // ← 只有一个字符串，无类型字段
        ...
    ) : AgentUiMessage
}
```
引擎侧实际能区分来源：
- MCP 工具：`mcp_call`（带 server 参数）
- Web 搜索：`WebSearchTool`（`id="web_search"`）
- Web 抓取：`WebFetchTool`
- Skill：通过 Slash 路由进 agent 循环，但**没有独立的"skill 调用"事件/UI**

**结果**：用户看到的每一个工具卡片长得一模一样，无法一眼分辨"这是 MCP 调用 / 这是联网搜索 / 这是本地工具 / 这是 skill"，缺乏可观测性。

### 缺口 2：错误提示太弱
```kotlin
is AgentEvent.Error -> {
    _uiState.update { state ->
        state.copy(
            messages = state.messages + AgentUiMessage.System("❌ ${event.message}"),
            isLoading = false
        )
    }
}
```
错误被当作普通 `System` 消息渲染（灰色小字），**没有红色背景/红色边框/重试按钮**。在深色背景上 ❌ 很容易淹没在信息流里，用户可能错过关键失败。

### 缺口 3：Skill 调用无专门 UI
- Skill 通过 `/skill:xxx` Slash 指令进入主循环，UI 只显示一条 `System` 提示。
- agent 实际调用 skill 内部工具时仍按普通 `ToolCall` 渲染，用户**看不出"正在执行某个 skill"**。

### 缺口 4：MCP 连接的失败/超时无独立 UI
- MCP 连接失败、`mcp_call` 执行异常都只走通用 `AgentEvent.Error` → 灰色 `System`。
- 没有"MCP 服务器离线/超时"这类来源感知的提示。

### 缺口 5：Web 搜索结果无结构化呈现
- `WebSearchTool` 返回搜索结果列表，但 UI 只把 `output.take(500)` 当纯文本折叠显示，没有标题/链接/摘要的卡片化展示。

---

## 三、改进建议（按优先级）

### P0 — 错误突出（立刻能做，低风险）
1. 新增 `AgentUiMessage.Error(val message: String, val canRetry: Boolean = false)` 类型，
   配套 `ErrorBlock` 组件：红色背景、红色左边框、⚠ 图标、可选"重试"按钮。
2. `AgentEvent.Error` 映射到 `Error` 而非 `System`；`ToolCall` 失败态复用同一错误色。
3. 深色主题下用 `error`/`onError` 配对色，避免红色文字看不清。

### P1 — 工具类型分类（增强可观测性）
1. 给 `AgentUiMessage.ToolCall` 与 `AgentToolCallUi` 增加 `val kind: ToolKind`
   （`LOCAL` / `MCP` / `WEB_SEARCH` / `WEB_FETCH` / `SKILL`）。
2. 在 `ToolCallStart`/`ToolCallComplete` 处理中根据 `toolName` 前缀推断 kind
   （`mcp_call`→MCP，`web_search`→WEB_SEARCH，`web_fetch`→WEB_FETCH）。
3. `ToolCallCard` 顶部加一个 **来源标签 + 图标**：
   - 本地工具：🔧 ｜ MCP：🔌 + server 名 ｜ 搜索：🔍 ｜ 抓取：🌐 ｜ skill：✨
4. Skill 调用：在 Slash 路由信息里携带 skill 名，渲染时显示 "✨ 执行 Skill: xxx" 标题而非普通系统行。

### P2 — 结构化结果呈现
1. Web 搜索结果：解析 `SearchResult` 列表，渲染为可点击链接卡片（标题 + URL + 摘要）。
2. MCP 卡片显示 server 名称与工具路径，便于排查"是哪个服务器挂了"。

### P3 — 失败可恢复
1. 错误块/失败工具卡片提供"重试该步"入口（需引擎支持按 callId 重放，成本较高，可后置）。

---

## 四、一句话总结
UI 已覆盖工具调用、思考、Plan、系统事件的基础呈现，但 **(a) 所有调用类型外观雷同、无 skill/MCP/Web 区分，
(b) 错误处理只是灰色小字、缺乏红色高亮与重试**，这两块是最该补的设计缺口。
