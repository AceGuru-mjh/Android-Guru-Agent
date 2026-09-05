# Tool System v2 — 工具系统升级

> 分支 `feat/tool-system-v2` · 基于 main @ `026c5be`
> 范围：core:tool-registry 基础设施 + 15 个新工具 + engine/app/UI 接线 + 202 项单元测试

## 1. 问题（为什么改）

v1 工具系统有五个结构性缺口，每个都在真实使用中反复出现：

| # | 缺口 | 表现 |
|---|------|------|
| 1 | **schema 只是文档** | `parametersSchema` 手写 JSON 字符串，没有任何运行时校验——schema 与代码漂移，模型靠猜参数 |
| 2 | **失败没有形状** | 工具返回裸字符串，失败靠 `"Error: "` 前缀识别——引擎分不清"参数错了改了再试"和"权限拒绝永远别试" |
| 3 | **未知工具错误倾倒全量清单** | 打错工具名 → 错误信息塞进 40+ 个 id 的墙，模型继续猜 |
| 4 | **风险不分级** | `shell_execute` 有命令级确认，`app_uninstall` / `delete_file` 什么都不问——同类破坏性操作体验不一致 |
| 5 | **元数据不存在** | prompt 里的工具清单是字母序长列表（40+ 行噪音）；函数菜单同样是平铺列表，找不到工具；没有任何"哪个工具在被用/总在失败"的观测面 |

另外两个具体缺口：McpManager 的三个工具（mcp_call/mcp_list/mcp_connect）已建成但从未注册进 ToolRegistry；app 层 `tools/StreamingShellExecuteTool.kt` 与 `tools/DownloadFileTool.kt` 是撞 id 的死代码副本（注册的其实是 core 的同名类）。

## 2. 方案（改了什么）

### 2.1 核心基建（core:tool-registry，9 个文件）

```
com.apex.agent.core.tools/
├── ToolMetadata.kt       类别(16)/风险(3)/标签 —— id 推断，v1 工具零迁移
├── ToolResult.kt         ToolErrorCode(9) + 结构化结果 + StructuredAgentTool 契约
├── ToolSchema.kt         schema DSL：声明即校验（单一真源）+ v1 JSON 宽松导入
├── ToolArguments.kt      强类型参数读取器：缺参/类型错 → 带字段名的结构化错误
├── ToolExecutionGate.kt  执行门接口 + ToolPermissionManager 会话状态机
├── ToolUsageTracker.kt   每工具 次数/成败/耗时/最近错误（无锁统计）
├── ToolSuggester.kt      Levenshtein 相近建议（修复"倾倒全量 id"）
├── ToolRegistry.kt       注册表加固 + DefaultToolExecutor v2 管线
└── SafeAgentTool.kt      元数据透传
```

**执行管线**（`execute` / `executeStream` 共用）：

```
查找 ──未命中──▶ 带相近建议的错误（不再倾倒全量 id 清单）
  │
门控 ──拒绝──▶ PERMISSION_DENIED（含模型可执行指引："换方案，勿重试"）
  │
校验 ──违规──▶ INVALID_ARGUMENT（字段名 + 违规原因 + "Fix the arguments and retry"）
  │
执行（原有 Safe/流式路径不变）
  │
统计（成败/耗时/最近错误）
```

关键设计决策：

- **字符串协议保持一等公民**。`ToolResult.render()` 产出引擎已在解析的 `"Error: …"` 字符串——下游零改动；结构化信息是增量而非替代。
- **接口新增全部带默认实现**。`AgentTool.metadata` 默认按 id 推断；`ToolRegistry.register(tool, policy)` 默认 REPLACE（v1 语义）。agent-engine 测试里的 `FakeToolRegistry`/`StubAgentTool` 一行未改仍然编译（CI 会跑这些测试）。
- **DSL 声明即校验**。`toolSchema { string("path", required = true) }` 同时是渲染源（`parametersSchema` 由它渲染）和校验规则（executor 运行时执行）——结构上不可能漂移。v1 手写 schema 经 `fromRendered` 宽松导入：解析失败的静默跳过（绝不误伤），解析成功的获得同等校验（required/类型/枚举/数值边界）。
- **校验对未知字段宽容**。模型爱塞垃圾字段；拒绝它们只会造成重试循环。只有声明过的约束被强制。
- **selfGated 跳过双重弹窗**。`shell_execute` 自带命令级确认（CommandPermissionGate），工具级再弹一次是骚扰——`ToolPermissionManager.selfGatedToolIds` 预置放行。
- **交互闭环留在 app 层**。core:tool-registry 不依赖引擎问题桥（依赖方向约束）；app 的 `RiskAwareToolGate` 注入 UserQuestionGateway 回调驱动同一状态机。headless/测试注入自己的 confirm 即可复用。

### 2.2 新工具（15 个，全部纯 JVM / 离线 / 确定性）

| 工具 | 一句话 | 为什么值得加 |
|------|--------|--------------|
| `json_path` | JSONPath 子集查询（递归/切片/union/通配/过滤器 `&&`/`\|\|`） | 模型提取 JSON 字段不再"重打印整文档再肉眼找" |
| `regex_extract` | 正则抽取（首匹配/全量 JSON 数组/命名组） | 纯文本工作不再绕 shell 管道过命令门 |
| `regex_replace` | 正则替换（`$1`/`${name}` 组引用/上限/忽略大小写） | 同上，写路径 |
| `text_diff` | Myers O((N+M)D) 行级 diff（unified/stat/json 三格式） | "草稿 vs 修订"对比一次调用；比 shell diff 少一次门禁往返 |
| `datetime` | now/format/add/diff/parse/convert（宽进解析：ISO/epoch 秒毫秒/常见格式） | LLM 日期算术出了名的不可靠——确定性替代 |
| `uuid_generate` | v4(SecureRandom)/v7(时间有序) | 模型无法"发明" UUID——工作流要 id 时唯一正确来源 |
| `file_hash` | md5/sha1/sha256/sha512 流式(64KB)+沙箱 | 完整性校验不需要 shell out 到 md5sum |
| `csv_query` | RFC4180 解析+select/where/sort/limit | 结构化表格查询（引号内逗号/换行都对） |
| `base_convert` | 2..36 任意进制（BigInteger + 0x/0b 前缀探测） | 数字系统转换，模型口算会错 |
| `string_distance` | levenshtein/damerau/jaro-winkler | 去重/模糊匹配决策依据 |
| `random_generate` | int/float/string/pick（SecureRandom 默认，seed 可复现） | 测试数据/随机选择不再"编造" |
| `cron_next` | Vixie cron 解析+下 N 次+人话解释（4 年不可能调度上限） | "这个 cron 下次什么时候跑"是真实高频问题 |
| `duration_convert` | "1h30m"↔秒↔ISO8601↔人类可读 + 比较 | 人类时长解析是 LLM 弱项 |
| `unit_convert` | 长度/质量/数据(1000/1024)/温度(公式)/速度 | 单位换算确定性来源 |
| `xml_extract` | XML 路径抽取（[N]/[*]/[@attr='v']/命名空间无关）+ XXE 防护 | RSS/配置解析，javax.xml 安全配置 |

全部基于 `BaseTool`：schema DSL 声明、参数异常→结构化错误、崩溃兜底（EXECUTION_FAILED 而不是冒泡炸掉 agent 循环）、v1 字符串协议自动桥接。

### 2.3 接线

- **engine**：`EnginePrompts` 工具清单按类别分组 + `⚠️HIGH-RISK` 标记 + 高风险行为规则段——字母序长列表换成模型可导航的结构。
- **app (DI)**：`ToolModule` 注册 15 个新工具 + 补接 MCP 三工具（mcpManager 注入）；`DefaultToolExecutor` 全站过门（主执行器与 skill 复合步骤执行器共享）；`RiskAwareToolGate`（仅一次/本会话/拒绝 三选项）+ `ToolUsageTracker` 单例。
- **UI**：函数调用菜单按类别分组 + 高风险 ⚠ 徽标（`ToolkitRingButton`）；`classifyTool` 元数据优先（新工具零改动获得正确来源徽章）；`availableTools()` 携带元数据。
- **CI**：tool-ID 唯一性 grep 扩展到 `app/src/main`（顺带删除两个撞 id 的死代码文件）。
- **测试**：`core:tool-registry` 新增 202 项单元测试（CI 的 `:core:tool-registry:test` 全绿执行）——schema DSL/校验、注册表行为（重复策略/版本/事件/搜索/分类）、门控状态机、统计、建议器、执行管线、15 个工具行为。

## 3. 兼容性清单

| 调用方 | 影响 | 迁移 |
|--------|------|------|
| 现有 AgentTool 实现 | 无（metadata 默认推断） | 0 行 |
| 现有 ToolRegistry 实现 | 无（新增成员全带默认实现；`FakeToolRegistry` 未改已编译） | 0 行 |
| `DefaultToolExecutor` 构造 | gate/tracker/schemaValidation 全部可选默认 | 0 行（注入即生效） |
| 字符串协议消费方（engine 等） | 无（render 输出同协议） | 0 行 |
| 未注册工具错误消费方 | 文本变化：不再含全量 id 清单，含相近建议 | 无代码依赖 |
| `ToolkitRingButton` ToolRef | 新增字段带默认值 | 调用点更新为直接消费 ViewModel 的 ToolRef |

## 4. 验证

本地与 CI 同口径（kotlinc 2.0.21 + 序列化插件 + 同版本依赖 jar）：

- `core:logging` / `core:llm-adapter` / `core:tool-registry` / `core:agent-engine` 全量编译 **0 错误**
- `core:tool-registry` 测试 **202/202 通过**（含 31 项存量回归）
- `core:agent-engine` 测试 **73/73 通过**（orchestrator 全套 + FakeToolRegistry 兼容性验证）
- 质量门 `check_code_quality.sh`（无反射分发/无 printStackTrace）✅
- 文件预算 `check_file_size.sh`（main ≤1200 / test ≤1600）✅
- 括号/花括号平衡（CI 静态检查同款脚本）✅
- tool-ID 唯一性（含 app/src/main，含类型注解形式排查）✅
- app 变更文件单文件编译错误指纹与 baseline 一致（仅存量 Android 依赖类 unresolved，无新增语法/类型错误）
- PRoot 二进制 sha256 校验 ✅

## 5. 已知边界与后续

- `csv_query.select` / `random_generate.items` 数组参数未进 DSL schema（DSL 暂无数组构造器）——经 `optionalStringList` 读取并带类型错误，描述文档已注明。后续可给 DSL 加 `stringArray(...)`。
- schema 校验在 executor 层执行（惰性解析 + 进程内缓存），未做注册期预检日志——tool-registry 模块按依赖方向无 logger 可用；如果需要注册期诊断，接线点在 app 层构建 registry 后遍历 `ToolSchemaCache`。
- 风险推断按 id 前缀族（`ToolMetadata.infer`）——新工具显式声明 metadata 即可覆盖；未来可考虑从 schema 声明推导（如参数含 `path` + `overwrite=true` → HIGH）。
- `ToolUsageTracker` 目前只在进程内聚合；接入设置页/诊断报告的 UI 展示是后续 PR 的事（数据已就绪：`stats()` / `report()`）。
