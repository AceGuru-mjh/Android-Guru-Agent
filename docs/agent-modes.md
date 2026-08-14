# Agent 执行模式

Agent 顶部模式栏提供 6 种执行模式（横向滚动切换）。模式通过 `AgentConfig.mode` 驱动
引擎行为，UI 侧仅在 `AgentChatScreen` 顶部栏切换。

| 模式 | Chip | 行为 | 适用场景 |
|------|------|------|---------|
| Build | `Build` | 边想边做，ReAct 实时循环 | 简单任务、快速响应 |
| Plan | `Plan` | 先产出执行计划（JSON 步骤）→ 用户确认 → 逐步执行 → 总结 | 复杂任务、多步骤操作 |
| Spec | `Spec` | 先产出需求规格（目标/需求/约束/验收标准/交付物）→ 用户确认 → 按交付物逐项执行 | 需求不明确、需要先对齐"做什么、做成什么样"的任务 |
| Reflect | `Reflect` | 反思模式：生成 → 评审 → 修正 循环（默认 1 轮，`reflectionRounds` 可调） | 代码生成、内容创作等对质量要求高的场景 |
| Assist | `Assist` | 人工协助：遇到多种选择/方案/目标/偏好时强制调用 `ask_user_choice` 弹出选项菜单，不擅自猜测 | 高风险操作、多目标歧义、需要人工决策 |
| Custom | `Custom` | 自定义：附加用户自定义指令（持久化），拼入 system prompt 的 `## Custom Instructions` | 固定输出格式/语言/行为约束 |

## 模式内部机制

### Spec 模式（`executeSpecMode`）
1. 流式生成规格 JSON（作为思考流呈现）
2. 解析为 `ExecutionSpec`（目标 / 需求 / 约束 / 验收标准 / 交付物 / 风险 / 推理）
3. 发射 `SpecGenerated` → `SpecAwaitingConfirmation`，UI 弹出"确认此规格并开始执行？"
4. 确认后按交付物（无则需求清单，再退化为目标）逐项进入 Build 循环执行
5. 最后生成总结（`ResponseComplete`）

事件：`SpecGenerated` / `SpecAwaitingConfirmation` / `SpecConfirmed`；UI 消息：`SpecMessage`（规格卡片）。

### Reflection 模式（生成 → 评审 → 修正）
- 最终纯文本轮次（无工具调用）时触发：
  1. **生成**：草稿按正常 `ResponseChunk` 流式呈现（UI 显示为一条 Agent 回复）
  2. **评审**：调用 LLM 严格审视草稿（事实错误/逻辑缺口/歧义/边界情况），
     完成后整段发射 `ReflectionReview`（UI 显示 REVIEW 评审卡片）
  3. **修正**：调用 LLM 依据评审意见重写，流式发射 `ResponseChunk`，`ResponseComplete` 为最终回复
- 轮数由 `AgentConfig.reflectionRounds` 控制（默认 1，可扩展多轮迭代）
- 草稿与修正稿均写入对话历史，保持上下文连贯

### HUMAN_ASSIST 模式
- 系统提示词声明：存在多个可行方案/目标/偏好或高风险动作时，**必须**先调用
  `ask_user_choice` 等待用户选择，禁止猜测
- 复用现有 `AgentQuestion` / `QuestionCard` 选项菜单 UI

### CUSTOM 模式
- 点击 `Custom` chip 弹出指令编辑对话框，输入持久化到 SharedPreferences
  （`custom_mode_instruction`），随 `setMode` 写入引擎配置
- 指令拼入 system prompt 的 `## Custom Instructions` 段落，优先级高于通用规则

## 人工选项菜单（多选支持）

`ask_user_choice` 工具新增 `multi_select` 参数：

```json
{
  "question": "请选择要执行的操作",
  "options": ["安装应用", "清理缓存", "导出日志"],
  "multi_select": true,
  "allow_custom": true
}
```

- `multi_select=true` 时 `QuestionCard` 渲染为 Checkbox 多选（UI 显示"可多选"徽章），
  回答经 `AgentAnswer.selectedOptionIds` 返回，工具输出 `User selected: A, B`
- 单选仍走 `selectedOptionId`（兼容旧字段），自定义输入与多选互斥
