## 改动摘要

本 PR 全面升级 Agent 回答过程中的输出 UI 设计，补全此前缺失的工具分类与错误提示。

### 新增能力
- **工具来源分类**：新增 ToolKind 枚举（LOCAL/MCP/WEB_SEARCH/WEB_FETCH/SKILL）+ classifyTool() 自动识别；每张工具卡片显示类型图标 + 状态徽章（完成/失败/运行）+ 来源标签（MCP·server / Skill:xxx）。
- **错误红色高亮**：AgentEvent.Error 升级为 ErrorBlock 红色卡片（描边 + 图标 + 可选重试），替代原灰色 System 小字。
- **联网搜索结构化卡片**：解析 WebSearchTool 输出为标题/域名/摘要/外链卡片，点击打开浏览器。
- **Skill 专门视觉**：Slash Skill 路由携带 skillName，循环内工具标记为 SKILL 来源。

### 涉及文件
- AgentChatViewModel.kt：ToolKind / classifyTool / AgentUiMessage.Error / retry / skillContext
- AgentChatScreen.kt：ErrorBlock / ToolKindBadge / WebSearchResultsCard / 卡片精细化
- SlashCommandRouter.kt：SlashCommandRoute.skillName

### 验证
- 已通过 IDE lint（0 错误）+ 静态复核。
- CI 将自动编译 app 模块（Android SDK + Gradle）验证改动。

详见 docs/agent-ui-audit.md。
