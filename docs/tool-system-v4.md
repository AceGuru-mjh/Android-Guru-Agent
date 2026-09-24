# Tool System v4 — Default-On, Progressive, Provider-Adaptive

> 状态：已实施（Wave 1）· 分支 `feat/tool-system-v4-default-on`
> 前置：[tool-system-v3.md](tool-system-v3.md)（执行硬化）· [agent-execution-overhaul.md](agent-execution-overhaul.md)
> 业界对标：operit（AAswordman/Operit，渐进激活/use_package）· rikkahub-agent（rikkahub/rikkahub-agent，ToolCatalog 渐进披露 + tool_choice + Provider schema 清洗）

---

## 0. 一句话

v2 定义了工具是什么，v3 让工具在循环里不死，v4 让**工具默认就可用**：
直接发送对话不再报错、~110 个能力全部可达、圈选的函数**必须**被调用。

## 1. 用户报告的根因（这次真正修掉）

> “直接发送对话会直接报错，还需要选择调用函数的东西……这些都是默认启动的，
> 你好像都没有做工具系统”

| # | 根因 | 证据 |
|---|------|------|
| 1 | **非法函数名直接 400**：~25 个 terminal 工具 id 带点（`terminal.exec`、`terminal.linux.bootstrap`…），OpenAI 函数名文法 `^[a-zA-Z0-9_-]{1,64}$` 不允许点。不圈选 = 全量 110 个 schema（带点名字）一起发 → 严格端点整单拒绝；手动圈选（无点号工具）恰好避开 → “必须选函数才能发” |
| 2 | **请求体爆炸**：110 工具 × (schema 387B + 描述 260B) ≈ 70–100KB + 系统提示词 110 行清单 → 小上下文/严格网关直接拒 |
| 3 | **没有渐进披露**：全量或白名单二选一——白名单语义（enabledToolIds）就是“不选=发全部”这个雷的载体 |
| 4 | **MCP 是三个代理工具**：模型只能 `mcp_call(server, tool, args)` 盲调（无 schema、无名字发现）——“GitHub 连接/MCP 没什么用” |
| 5 | **无请求级 tool_choice**：圈选函数无法表达“必须调用” |

## 2. 架构总览

```
                    ┌── ToolRegistry（单源真相：id/description/schema/元数据）──┐
                    │   builtin · plugin · skill · MCP(first-class) · catalog  │
                    └────────────────────────┬─────────────────────────────────┘
                                             │ 每轮迭代
   AgentConfig.forcedToolIds ──┐             ▼
   (「调用函数」圈选=强制)      │   ToolRequestBudget.planForced / planDefault
   exposeAllTools(电源用户) ────┤   · CORE 集（~45）∪ 会话激活(tool_open) ∪ 全量?
                                │   · ToolNameSanitizer（点号→下划线 + 冲突消解 + 64 字符）
                                │   · ToolSchemaSanitizer（修复/过滤 required/限长）
                                │   · 预算钳制（≤64 个 / ≤48KB），名称稳定排序
                                ▼
   Engine BUILD 循环 ── RequestToolPlan{tools, providerNameToId, visibleIds}
        │                        │
        │ tool_choice=Required/  │ system prompt: Available Tools(provider 名)
        │ Function(强制) ────────┤ + Tool Catalog 段 + Forced 段
        ▼                        ▼
   StreamingOpenAiClient（v4: 描述 1024 钳制 + Gemini 关键字剥离 + P0 骨架兜底）
        │
        └─ 400/413/422 或工具类报错 → 降级重试同一轮：1=纯 CORE → 2=无工具纯对话
           （“直接发送”永远有响应；已流式输出的轮次不重试，避免内容重复）
```

## 3. 组件明细（core/tool-registry `catalog/` 包）

### 3.1 ToolTierPolicy
- **CORE_TOOL_IDS（~45）**：交互(ask_user*) + 文件(8) + Web(4) + terminal.exec/create/run/observe/wait/snapshot/backends + 记忆(3) + 应用/设备(8) + MCP(3) + skill/connector(3) + 目录元工具(3)。每请求全 schema 常驻 —— **agent 默认就是能干活的**。
- **LEGACY_ALIAS_IDS**：6 个弃用别名永不入请求（`terminal_exec` 等老名字让位给现代 `terminal.exec` 的清洗后名）。

### 3.2 ToolNameSanitizer（400 根因的直接修复）
- 注册表 id → provider 合法名：`terminal.linux.bootstrap` → `terminal_linux_bootstrap`；
- 冲突消解确定性（非 legacy 优先 → 长 id 优先 → 字典序 → `_2` 后缀）；
- **双向映射随计划下发**：引擎执行时把模型回显名映射回注册表 id（`executeToolCallStreaming` / 编排器 `ToolCall` 重映射）；无映射时原样直查（模型直呼 registry id 也通）。

### 3.3 ToolSchemaSanitizer
- 永不出抛：非法 JSON → `{"type":"object","properties":{}}` 骨架；
- `required` ∩ 真实 `properties`（幽灵 required 是严格校验器杀手）；
- 只保留 OpenAI 兼容关键字（$schema/title/examples/patternProperties/allOf… 全删）；
- 单 schema ≤ 8KB；描述 ≤ 1024 字符（截断带标记）。

### 3.4 ToolRequestBudget（请求装配唯一入口）
- `planDefault(registry, activation, exposeAll, coreOnly)`：CORE ∪ 激活 ∪（全量?）；legacy 永不出；
- `planForced(registry, forcedIds)`：只带选中集，未知 id 记入 dropped 供诊断；
- 钳制：≤64 工具 / ≤48KB；**按 provider 名稳定排序**（rikkahub 教训：工具数组顺序变化会打爆 provider 前缀缓存）；
- 产出 `RequestToolPlan{tools, providerNameToId, visibleRegistryIds, totalBytes, droppedByBudget}`。

### 3.5 ToolActivationStore + 目录元工具（渐进披露闭环）
- 会话级激活（FIFO 上限 24；引擎 `execute()` 入口 reset，不跨任务泄漏）；
- `tool_search(query)` 关键词检索全目录；`tool_open(tool_name)` 返回完整描述+参数 schema（作为**工具结果**注入——operit use_package 模式的原生函数调用版），下一轮请求自动带上；`tool_list()` 类别总览；
- 接受 registry id **或**清洗名（模型两种叫法都能打开）。

### 3.6 MCP 一等化
- `McpAgentTool`：每台已连接服务器的每个远程工具 → 真函数
  `mcp__{server}__{tool}`（McpToolNaming 归一化 slug，provider 合法名，跨服务器防撞）；
  schema 经清洗器修复；结果 16KB 钳制；MEDIUM 风险 + 非幂等（v3 策略不盲重试）；
- `McpToolRegistrar`：挂 `McpManager.SessionListener`（v4 新增）——connect → 发现 → 注册（REPLACE，重连刷新）/ disconnect → 精确注销（服务端工具列表变化不留幽灵）；
- **McpManager**：新增 `listServerTools(name)` + 会话事件监听（connect/disconnect/remove/disable/断开全部 全路径触发）；
- 启动自动连接：ApexCoreService 对 `getEnabledConfigs()` 逐台 connect（“配置即生效”，与插件同一哲学；失败不阻断，模型可 `mcp_connect` 重试）；
- 旧三个代理工具（mcp_list/mcp_connect/mcp_call）保留为兜底路径。

### 3.7 强制函数调用（「调用函数」菜单的 v4 语义）
- `ToolChoiceSpec`（llm-adapter）：Auto / Required / **Function(name)** / None —— 每请求级，新增 5 参重载贯通 `LlmClient → ModelRuntime → DefaultModelRuntime → StreamingOpenAiClient`（带默认实现，全部既有实现与测试零改动；SingleClientModelRuntime 覆写透传，legacy 路径不丢 tool_choice）；
- 圈选 1 个 → `tool_choice={"type":"function","function":{"name":…}}`；多个 → `"required"`；**且请求只暴露选中工具**；
- 语义与 UI 文案同步：ChatToolkitStore 同一持久化 key 平滑升级（旧数据=空集=默认模式）；
- ChatViewModel `patchConfig` → `AgentConfig.forcedToolIds` + `exposeAllTools`。

### 3.8 请求自愈（降级重试）
- 引擎 BUILD 循环捕获“工具相关拒绝”（LlmException.Http 400/413/422，或报错文案含 tool/function/schema/parameters；401/403 鉴权排除）；
- 本轮**零输出**时逐级降级重试：1=纯 CORE（去强制、去激活、去全量）→ 2=无工具纯对话（系统提示词带 "Tools Unavailable This Turn" 说明）；
- 已流式输出不重试（防内容重复）；最多消耗 2 次迭代，然后正常上抛；
- ErrorClassifier 升级：400 响应体摘要（200 字符）进异常消息（诊断+关键词判定双受益）。

### 3.9 提示词（EnginePrompts）
- Tool-Use Policy 第 8 条：目录用法（tool_search → tool_open）；
- "## Available Tools" 列 **provider 名**（模型看到什么名字就调什么名字，与 schema 零歧义）；
- "## Tool Catalog (N more available)"：未预载能力计数 + 类别概览 + 三条发现路径；
- "## Forced Function Calls"：圈选集 + 必须调用声明；
- "## Tools Unavailable This Turn"（降级 2 级时）。

## 4. 接线（app 模块）
- ToolModule：`provideToolActivationStore` + `provideMcpToolRegistrar`（IO Supervisor 作用域）+ 目录三工具注册（第 12 节）；
- AgentModule：engine 与 orchestrator 注入**同一** ToolActivationStore（tool_open 激活对两条执行路径同时生效）；任务快照 `forcedToolIds/exposeAllTools`；
- ChatToolkitStore：`forcedToolIds()`/`exposeAllToolsEnabled()` 取代 `effectiveToolWhitelist()`；会话上下文新增“强制函数调用”段；
- ToolkitRingButton：「函数调用」→「**强制函数调用**」（含语义提示行）+ 全量工具开关；i18n 双语（values/values-zh）；
- AgentChatViewModel：patchConfig 携带 v4 参数。

## 5. 测试（新增 44 个，全绿；既有 516 个回归全绿；总 560）

| 套件 | 覆盖 |
|------|------|
| ToolSystemV4CatalogTest (27) | 名字清洗（点号/钳制/冲突/回环）、schema 修复（非法 JSON/幽灵 required/坏关键字/超长）、激活（幂等/FIFO/reset）、计划（CORE-only/激活并入/全量/强制/coreOnly 降级/名称稳定排序/数量钳制）、目录元工具（search/open 含清洗名/open 未知引导/list）、MCP 命名（防撞/provider 合法） |
| ToolChoiceSpecRequestTest (11) | required/none/auto/Function 序列化、Profile 回退、无函数工具时不发 tool_choice、Gemini 关键字剥离、非 Gemini 保留 enum、描述钳制、非法 schema 骨架 |
| EngineToolSystemV4Test (6) | 默认请求=CORE+清洗名+目录段、系统提示词 provider 名+目录、强制单选=Function/多选=Required、**tool_open → 下一轮请求带上被激活工具（端到端）**、400→coreOnly→无工具三级降级自愈、鉴权错误不降级 |

验证方式：Kotlin 2.0.21 官方编译器 + catalog 同版本依赖（coroutines 1.9.0 / serialization 1.7.3 / okhttp 4.12.0），三个核心模块 main+test 全量编译 exit 0，JUnit 全量 560 绿。

## 6. 显式不做（Wave 2 范围）
- **MCP 模型自管理**（mcp_add/update/test 控制工具，rikkahub-agent NO_ALWAYS_ALLOW 门控模式）；
- **循环守卫**（同参重复调用 ≥3 阻断）与**终答回收**（空终稿时去工具重问）；
- 技能自动激活（SKILL.md frontmatter 触发词）与技能经验摘要；
- MCP OAuth / stdio-over-proot 桥（operit 的 Node 桥模式）；
- 目录“经验摘要”（schema 指纹门控的学习型工具说明）。

## 7. 升级兼容
- ChatToolkitStore 沿用旧持久化 key（`toolkit_functions`）：旧“白名单圈选”数据自然解释为“强制圈选”（语义变化已在 UI 文案标明）；
- TaskConfigSnapshot：`enabledToolIds` → `forcedToolIds`+`exposeAllTools`（旧快照反序列化兼容：字段缺失取默认）；
- LlmClient/ModelRuntime 新方法全部带默认实现：DynamicLlmClient/NoOpLlmClient/全部测试 Fake 零改动编译通过；
- 既有引擎/编排器测试 131 个全部通过（未映射工具名回退原样直查，旧路径语义不变）。

---

## 8. Wave 2（v4.1）— 全面完善

在 Wave 1（默认可用 + 渐进披露 + 强制调用 + MCP 一等化 + 降级自愈）之上：

| 能力 | 实现 | 对标 |
|------|------|------|
| **循环守卫** | 同工具+同参数第 3 次起拦截（不执行），返回 `loop_detected` 自修复指引（重读结果/改参数/换方法）；不同参数不受影响 | rikkahub-agent LoopGuard（实测灾难样本：27 步 141K token 全是同参重复） |
| **终答回收** | 空终稿（无内容无工具调用）≠ 失败：去工具 + FinalAnswerReminder 重试（≤2 次），仍空才报 "Empty response" | rikkahub-agent FinalAnswerRecovery |
| **MCP 生命周期** | `mcp_remove_server`（HIGH 风险过确认门——配置即提权面）+ `mcp_toggle_server`（可逆启停，重启自动连接联动）；mcp_connect 描述更新为 v4 一等注册语义 | rikkahub-agent mcp_* 控制工具 + NO_ALWAYS_ALLOW 门控 |
| **技能建议** | 用户输入分词 → 未启用已安装技能 top-3 匹配 → system prompt "Skill Suggestions" 段（启用技能不重复建议——其 promptInjection 已在 Active Skills） | operit 技能目录 + 模型自主 use_package |
| **SRP 拆分** | EngineToolExecution / EngineLoopGuard / EngineFinalAnswer 同包扩展文件（引擎回落 1082 行） | 仓库既有 God-file 拆分模式 |

Wave 2 测试 +9（循环守卫 2 / 终答回收 2 / 技能建议 3 / MCP 生命周期 2），
总 578 全绿（K2 + JUnit）。
