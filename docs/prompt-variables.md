# 提示词变量引擎 — {{name}} 展开与 19 内置变量（P91）

> 状态：已实施 v1（Task 4-b）· 对比文档差距项 P91（[operit-rikkahub-comparison.md](operit-rikkahub-comparison.md) §4.5/§6.2）
> 前置：无（apex 此前提示词变量零支持；EnginePrompts 纯字符串拼装）
> 业界对标：rikkahub `PlaceholderTransformer` + `DefaultPlaceholderProvider`（内置变量、ignoreCase、@Composable displayName 扩展点）· operit prompt/config 全阶段钩子

## 一、动机

对比文档 §4.5 的结论：rikkahub 的提示词工程有「变量 + 模板 + 注入位置」三件套，
apex 的 `EnginePrompts.buildSystemPrompt` 是分段拼装但**没有变量引擎**——用户无法在
自定义提示词里写「现在是 {{date}}、你在 {{device}} 上」这类自引用文本。

落地纪律（对比文档 §6.2 P91 明确）：**核心层保持纯 JVM 纯字符串**——设备型号、电量、
网络等运行时数据由 app 层组装成字符串快照注入，core 不感知 `Build.BRAND` 或
`BatteryManager`（镜像 rikkahub 的 PlaceholderCtx 但剥离 Android Context 依赖）。

三件套位于 `core/agent-engine` 纯 JVM 包 `com.apex.agent.core.engine.promptvars`：

```
PromptVariableContext   一次求值的不可变上下文（设备/会话/模型快照，app 层注入）
PromptVariableDef       变量元数据（名称/描述/作用域/示例）→ 设置页可渲染
PromptVariableRegistry  内置 19 变量 + 用户自定义变量（会话级注册）
PromptVariableExpander  {{name}} / {{name|default}} 语法展开器（递归/转义/环安全）
```

### 展开数据流

```
app 层组装                       core 层（纯 JVM）
┌──────────────────┐   ┌───────────────────────────────────────────┐
│ AgentConfig      │   │  PromptVariableRegistry                    │
│ ModelProfile     ├──►│   custom ──► 注册自定义 ──► 内置 19         │
│ EnvironmentInfo  │   │        ▲                                  │
└──────────────────┘   │        │ resolveOrNull(name, context)       │
      context 快照 ───►│  PromptVariableExpander                    │
                       │   TOKEN_REGEX 扫描 ──► 递归(≤maxDepth) ──►  │
                       │   环检测 / 转义 / 默认值 / UnknownPolicy     │
                       └───────────────────────────────────────────┘
                          输出：展开后的纯文本（发 LLM / 预览）
```

## 二、语法（PromptVariableExpander.kt，179 行）

| 语法 | 语义 | 示例 |
|------|------|------|
| `{{model_id}}` | 基本展开，**大小写不敏感**（{{MODEL_ID}} 等价，对齐 rikkahub ignoreCase） | → `gemini-2.0-flash` |
| `{{ model_id }}` | 前后空白容忍 | → 同上 |
| `{{focus\|全面审查}}` | 变量缺失时用默认值（**显式默认值永远优先于未知策略**，首尾空白剔除） | focus 未知 → `全面审查` |
| `\{{model_id}}` | 转义：输出字面 `{{model_id}}`（不展开） | → `{{model_id}}` |

- 词法预编译为顶层 `TOKEN_REGEX`（对齐 WebTools.kt v2 顶层 Regex 先例，全实例共享）：
  转义分支 + 名称分支（`[a-zA-Z][a-zA-Z0-9_]*` + 可选 `\|默认值`，默认值内**禁止花括号**
  防与闭合定界符歧义）；
- 非法形态（空名、数字开头、未闭合、默认值含花括号）整体不匹配、**原样保留**——
  「畸形花括号不吞文本」；
- **递归**：解析值本身可含占位符（自定义变量 greeting = "你好 {{user_name}}"），逐层
  展开至 `maxDepth`（默认 3，构造期 require >= 0），超层原样保留防失控；
- **环安全**：展开栈记录正在展开的变量链（A→B→C），回到链上变量时原样保留该占位符
  ——A↔B 互指在有限步内终止；分支独立，**菱形引用（A→B、A→C、B/C→D）不误伤**；
- `expand`（策略 THROW 时抛 UnknownPromptVariableException）/ `expandOrNull`（异常折叠
  null，防御式调用方）/ `findVariables`（扫描引用的变量名：规范小写、首现序去重、
  转义不计——模板引擎缺失提示与输入框高亮用）/ `isResolvable`（委托注册表走完整
  优先级链）。

### UnknownPolicy 三态（无显式默认值时生效）

| 策略 | 行为 | 适用 |
|------|------|------|
| KEEP（默认） | 原样保留占位符 | 用户能在输出里看到未填的坑 |
| EMPTY | 替换为空串 | 静默清理 |
| THROW | 抛 UnknownPromptVariableException | 模板必填校验 fail-fast |

### 与 rikkahub 语法的差异（刻意）

rikkahub 的 PlaceholderTransformer 同时替换 `{{key}}` 与 `{key}` 双语法（rikkahub
源码 replace 两次）；apex **只认双花括号**——单花括号在自由文本里过于常见
（JSON 示意、Shell 变量、正则量词 `{2,}`），吞掉它们的误伤率远高于收益。
缺省值 `{{name|default}}` 与转义 `\{{` 是 apex 新增（rikkahub 无）。

## 三、内置 19 变量全表（PromptVariableRegistry.kt，242 行）

| # | 变量 | 作用域 | 来源 | 示例值 |
|---|------|--------|------|--------|
| 1 | `model_id` | SESSION | context.modelId | `gemini-2.0-flash` |
| 2 | `model_name` | SESSION | context.modelName | `Gemini 2.0 Flash` |
| 3 | `mode` | SESSION | context.modeName | `BUILD` |
| 4 | `thinking_level` | SESSION | context.thinkingLevelName | `DEEP` |
| 5 | `tool_count` | SESSION | context.toolCount | `42` |
| 6 | `session_id` | SESSION | context.sessionId | `session-8f3k` |
| 7 | `agent_name` | USER | context.agentName（空回退 "Apex Agent"） | `Apex Agent` |
| 8 | `user_name` | USER | context.userTitle（空回退 "user"） | `Boss` |
| 9 | `time` | SYSTEM | SimpleDateFormat "HH:mm" | `14:30` |
| 10 | `date` | SYSTEM | "yyyy-MM-dd" | `2025-01-01` |
| 11 | `datetime` | SYSTEM | "yyyy-MM-dd HH:mm" | `2025-01-01 14:30` |
| 12 | `weekday` | SYSTEM | "EEE"（按注册表 locale 本地化缩写） | `Wed` |
| 13 | `locale` | SYSTEM | context.locale（空回退注入 locale） | `zh-CN` |
| 14 | `timezone` | SYSTEM | context.timeZoneId（空回退默认时区） | `Asia/Shanghai` |
| 15 | `platform` | SYSTEM | context.platform | `Android` |
| 16 | `device` | SYSTEM | context.deviceSummary（app 注入） | `Pixel 9 Pro` |
| 17 | `network` | SYSTEM | context.networkSummary | `Wi-Fi` |
| 18 | `battery` | SYSTEM | context.batterySummary | `85%` |
| 19 | `os` | SYSTEM | platform + deviceSummary 组合 | `Android Pixel 9 Pro` |

### 与 rikkahub 内置变量的对照（DefaultPlaceholderProvider 实测源码）

| rikkahub 变量 | apex 对位 | 口径差异 |
|---------------|-----------|----------|
| `cur_date` | `date` | 同为本地化日期 |
| `model_id` / `model_name` | `model_id` / `model_name` | 同名同义 |
| `locale` / `timezone` | `locale` / `timezone` | 同名同义 |
| `system_version`（"Android SDK v36 (16)"） | `platform` / `os` | apex 拍平为平台串，SDK 版本由 app 注入 deviceSummary 承载 |
| `device_info`（Build.BRAND + MODEL） | `device` | app 层组装注入（纯 JVM 纪律） |
| `battery_level`（BatteryManager int） | `battery` | 摘要串（如 "85%"），电量采集留在 app |
| `nickname` / `user`（同名别名对） | `user_name` | 对齐 AgentConfig.userTitle 口径 |
| `char`（助手名） | `agent_name` | 对齐 AgentConfig.agentName 口径 |
| —— | `time` / `datetime` / `weekday` | apex 时间族细化 |
| —— | `mode` / `thinking_level` / `tool_count` / `session_id` | **agent 特有**：执行形态变量（rikkahub 是聊天客户端无此概念） |
| —— | `network` / `os` | 环境快照细化 |

### 注册表机制

- **解析优先级链**（高 → 低）：`context.custom`（本次求值临时覆盖，模板引擎注入
  overrides 走这层）> `registerCustom` 注册的自定义变量（可覆盖同名内置，如把
  {{time}} 固定为演示值）> 内置变量；
- **命名规范**：规范名小写 `[a-z][a-z0-9_]*`（`NAME_PATTERN`）；查找大小写不敏感
  （trim + lowercase 后规范化）；非法名注册抛 IllegalArgumentException（调用方 bug
  fail-fast），解析时非法名返回 null 不抛；
- `registerCustom`（后写胜出）/ `unregisterCustom`（注销后内置解析自然恢复）/
  `customVariables()`（防御性拷贝）/ `definitions()`（内置在前按声明序 + 自定义在后，
  设置页变量清单直接消费）/ `isBuiltIn`；
- **时间确定性**：SimpleDateFormat 按构造注入的 locale + 上下文 timeZoneId + nowMs
  假钟格式化；SimpleDateFormat 非线程安全，每次求值新建实例。

## 四、纯 JVM 上下文（PromptVariableModels.kt，133 行）

`PromptVariableContext` 一次求值的不可变快照：nowMs 时钟函数（假钟注入测试 /
`System.currentTimeMillis` 真实时钟）+ 模型/人设/会话/环境 14 个字符串字段 + custom
临时覆盖层。`PromptVariable.DEFAULT` 提供全字段非空的占位快照（epoch 0 =
1970-01-01 周四 UTC）供测试与预览。

`VariableScope` 三作用域决定设置页分组与生命周期：SYSTEM（环境快照，随环境变化）、
SESSION（会话数据）、USER（用户人设字段）。

## 五、测试矩阵（54 用例全绿）

| 套件 | 数量 | 覆盖 |
|------|------|------|
| PromptVariableRegistryTest | 22 | 19 内置变量逐个求值（DEFAULT 上下文精确断言）、优先级链（custom > 注册自定义 > 内置）、同名覆盖与注销恢复、大小写不敏感、非法名注册抛/解析返回 null、definitions 顺序与 isBuiltIn、时间类 UTC/Asia_Shanghai 时区切换精确字符串、weekday 本地化 |
| PromptVariableExpanderTest | 32 | 四语法形态、空白容忍、默认值优先于策略、转义输出字面、递归至 maxDepth + 超层保留、环（A↔B）终止、菱形不误伤、KEEP/EMPTY/THROW 三策略、expandOrNull 折叠、findVariables 首现序去重且转义不计、畸形花括号原样保留 |

确定性策略：`Locale.US` 注册表 + 固定 epoch `1735689600000`（2025-01-01 周三 UTC）
假钟 + UTC/Asia/Shanghai 切换断言精确字符串。验证：
`:core:agent-engine:test` BUILD SUCCESSFUL——模块 546 tests / 0 failed / 1 skipped
（新增 97 个全过，既有 449 个零回归；skipped 为既有 RoleRoutingGoldenTest 预存跳过）。

## 六、app 层接入指南

| 环节 | 落点 |
|------|------|
| 展开点 | `AgentChatViewModel.sendMessage` 发送前对用户输入/附加上下文调 `expander.expand` |
| 上下文组装 | 从 AgentConfig（agentName/userTitle）+ ModelProfile（modelId/modelName）+ EnvironmentInfoProvider（device/network/battery 拍平字符串）构造 PromptVariableContext |
| 会话级注入 | `AgentConfig.additionalSystemContext` 组装点 + `EnginePrompts.buildSystemPrompt` 系统级终点 |
| 自定义变量存储 | AgentSettings 加 promptVariables 字段（仿 customModePresets 模式），启动恢复 registerCustom |
| 变量清单 UI | 设置页直接消费 `registry.definitions()`（name/description/scope/example 全在元数据里） |

## 七、设计取舍与常见问题

**Q: 为什么 context.custom 的优先级高于注册表自定义变量？**
custom 是「本次求值」的临时覆盖层——模板引擎用它注入 overrides 与模板默认值
（见 [prompt-template-library.md](prompt-template-library.md) §四），必须压过持久层
的自定义变量；但它低于模板正文里的显式 `{{var|default}}`（合并序见模板引擎）。

**Q: 递归深度 3 会不会不够？**
maxDepth 是构造参数（require >= 0）可调；3 层覆盖「文本 → 自定义变量 →
内置变量」的常规链路，超层原样保留而非报错——宁可少展开也不失控膨胀。

**Q: 为什么 rikkahub 的 displayName 是 @Composable 而 apex 用字符串元数据？**
rikkahub 把 UI（设置页本地化变量名）写进数据层；apex 的 PromptVariableDef 携带
description/example 字符串，UI 层自行本地化——core 零 Compose 依赖的防腐纪律。

**Q: 非法形态为什么原样保留而不是报错？**
提示词正文是自由文本：正则、代码片段、JSON 示意都可能含双花括号。畸形形态
不吞文本（KEEP 语义的默认延伸）让用户在输出里看到并自行修正；需要严格校验的
调用方用 UnknownPolicy.THROW。

## 八、后续路线

- 对比文档纪律项「时间类变量绑定消息 createdAt 或会话级缓存一次（不破坏前缀缓存）」
  由 app 层落实：会话内复用同一 context 快照即可（引擎侧不自动注入时间变量）；
- 后续：`{{workspace}}/{{sandbox}}/{{mcp_servers}}` 等 agent 环境变量（对比文档 §4.5
  建议）挂 ConnectedServicesProvider/工作区状态注入；
- 输入框变量高亮可用 `findVariables` 实时扫描；缺变量提示复用模板引擎的
  missingRequired 通道。

## 附：文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/agent-engine/.../engine/promptvars/PromptVariableModels.kt` | 133 | VariableScope/PromptVariableDef/PromptVariableContext（含 DEFAULT） |
| `core/agent-engine/.../engine/promptvars/PromptVariableRegistry.kt` | 242 | 19 内置变量 + 自定义注册 + 优先级链 + 时间格式化 |
| `core/agent-engine/.../engine/promptvars/PromptVariableExpander.kt` | 179 | TOKEN_REGEX 词法 + expand/expandOrNull/findVariables + UnknownPolicy |
| `core/agent-engine/.../engine/promptvars/PromptVariableRegistryTest.kt` | 256 | 22 用例 |
| `core/agent-engine/.../engine/promptvars/PromptVariableExpanderTest.kt` | 286 | 32 用例 |
