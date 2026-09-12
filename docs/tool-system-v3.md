# Tool System v3 — Execution Hardening & Observability

> 状态：已实施 · 分支 `feat/tool-system-v3-hardening`
> 前置：[tool-system-v2.md](tool-system-v2.md)（结构化工具契约 / Schema DSL / 风险门控）
> 业界对标：MCP spec 2025-06-18 · LangGraph ToolNode · Anthropic computer-use (2026-08 toolset) ·
> Mobile-Agent-E (Shortcut 自进化) · AutoGPT Block (sensitive/HITL) · AndroidWorld

---

## 0. 一句话

v2 回答了"工具是什么"（结构化 schema / 元数据 / 风险）；v3 回答"工具在真实
Agent 循环里如何不死、不炸、可观测"——**超时、重试、限流、熔断、逐调用追踪、
批量首错即停、组合动作、环境态门控**，八层执行硬化，全部对齐业界成熟方案，
全部带测试。

## 1. 动机（调研驱动）

对 7 个业界方案做源码级调研（MobileAgent / Anthropic CU / OpenAI CU / MCP /
AutoGPT / LangGraph / AndroidWorld）后，本项目 v2 工具系统存在以下结构性缺口：

| # | 缺口 | 业界基准 | 本项目 v2 现状 |
|---|------|---------|---------------|
| 1 | **工具级超时** | MCP 规范 MUST；AndroidWorld 全工具 30s | 无 —— `http_request` 挂死 = Agent 循环挂死 |
| 2 | **瞬态失败重试** | LangGraph wrap_tool_call 指数退避 | 无 —— 一次网络抖动 = 一步失败 |
| 3 | **速率限制** | 成熟平台标配 | 无 —— 模型失败循环可刷 50 次/分钟 |
| 4 | **熔断器** | Hystrix 形态 | 无 —— 已损坏工具持续烧 token |
| 5 | **逐调用追踪** | LangSmith / NodeExecutionStats | 仅聚合计数（ToolUsageTracker） |
| 6 | **批量顺序执行** | Anthropic CU actions[]，首错即停 + 统一跳过文案 | 无 —— 每步一个 LLM 往返 |
| 7 | **组合动作（自进化）** | Mobile-Agent-E Shortcut | 无 |
| 8 | **环境态门控** | Mobile-Agent 键盘门控 | 无 —— 键盘未弹出时 input_text 必败 |
| 9 | **MCP 注解词汇** | readOnly/destructive/idempotent/openWorld 四 hints | 仅 LOW/MEDIUM/HIGH 粗粒度 |

另有存量 bug：`ToolModule` 中 MCP 三工具被重复注册**三次**（REPLACE 静默互踩）。

## 2. 架构总览

```
            ┌────────────────────────── EnhancedToolExecutor（v3 管线）──────────────────────────┐
            │                                                                                      │
 模型调用 ──▶ 查找(建议) ─▶ 环境门 ─▶ 风险门 ─▶ schema校验 ─▶ 限流(令牌桶) ─▶ 熔断器 ─▶ 超时+重试  │
            │   ToolSuggester   ToolEnvironmentGate  RiskAware   ToolSchema     ToolRateLimiter  ToolCircuitBreaker │
            │                    (fail-open)        ToolGate    (v2)                            + ToolRunPolicy │
            │                                                                                      │
            │        每次尝试一个 ToolTraceSpan（成败/耗时/attempt/args摘要）──▶ ToolTraceRecorder │
            │        聚合计数（v2 ToolUsageTracker）继续生效                                      │
            └──────────────────────────────────────────────────────────────────────────────────┘
                                        │
              ToolBatchRunner（首错即停 + {n} 引用）   ShortcutTool（组合动作，经同一执行器）
```

**设计原则**（继承 v2）：
- **全部可选注入**——不接 v3 组件时行为与 v2 逐字节一致，既有测试与调用点零迁移；
- **错误即内容**——所有拒绝（限流/熔断/环境/超时）都返回模型可读的修复指引，而非
  中断循环；
- **fail-open**——环境遥测缺失/过期时放行，绝不让推断性门控硬禁用工具。

## 3. 组件明细

### 3.1 ToolAnnotations（MCP 四 hints + sensitiveAction）

`ToolAnnotations(readOnlyHint, destructiveHint, idempotentHint, openWorldHint,
sensitiveAction)`：

- **retrySafe 派生**：`readOnly || idempotent` —— 自动重试同一 payload 的安全充要
  条件（重放已成功的破坏性操作是数据丢失）；
- **零迁移推断**：`infer(id, risk)` 覆盖全部存量 id 族（与引擎 T76 幂等注册表同源
  口径），显式声明优先；
- 挂在 `ToolMetadata.annotations`（带默认值，data class 向后兼容）。

### 3.2 ToolRunPolicy + DefaultToolRunPolicyResolver + RetryClassifier + ToolRateLimiter

- `ToolRunPolicy(timeoutMs, maxRetries, baseRetryDelayMs, rateLimitPerMinute)`；
- 预设：`quickRead`（30s/1retry/120rpm）· `network`（90s/2retry/30rpm）·
  `mutating`（60s/不盲重试）· `legacy`（v1/v2 语义）；
- **全抖动指数退避**（AWS 风格）：`delay = random(0 .. base * 2^attempt)`，避免
  恢复中后端的惊群；
- RetryClassifier：权限/沙箱/参数类错误终态（重发同 payload 无意义）；超时/IO/
  执行错误可重试 —— 与 LangGraph 的"调用错误自纠、执行错误上抛"同构；
- ToolRateLimiter：连续回填令牌桶（capacity = 每分钟配额，tokens/ms 回填），
  超限**立即拒绝**而非排队——失控循环要立刻看见错误。

### 3.3 ToolCircuitBreaker

CLOSED →（N 连败）→ OPEN →（冷却）→ HALF_OPEN（单探测）→ 成功闭合 / 失败
按 2× 加宽冷却重开（上限 maxCooldownMs）。纯惰性状态机（无定时器线程），
`check()` 即求值。Deny 文案带"上次错误 + 探测倒计时 + 勿立即重试"指引。

### 3.4 ToolTraceRecorder + ToolCallIds

- 每次尝试一个 `ToolTraceSpan(callId, toolId, durationMs, outcome, attempt,
  argsDigest, errorSlug)`；
- **args 摘要而非原文**（长度/可打印字符计数）——诊断导出不含用户粘贴的机密；
- 环形有界缓冲（默认 200，app 配 300）+ 监听器分发 + JSON 导出 + 直方图报告；
- 幂等完成：同一 handle 二次 complete 不产生幻影 span；
- `ToolUsageTracker`（聚合计数）继续并存 —— tracker 答"多常/多快"，tracer 答
  "具体哪次、什么参数、第几轮重试"。

### 3.5 ToolBatchRunner（Anthropic CU 批量语义）

- 严格顺序、**首错即停**、未执行步骤统一返回契约原文
  `"Not executed: an earlier action in this turn failed."`；
- `{n}` 步间引用：整引用 / 模板内插值，头截断 4KB 防爆炸，前向/越界引用在
  执行前拒绝（调用计数为零）；
- 整批墙钟预算（默认 120s，上限 600s）；步骤上限 16；
- 模型入口 `tool_batch_run`（steps 支持内联数组或字符串编码两种形态）。

### 3.6 Shortcut 组合动作（Mobile-Agent-E）

- `ShortcutDefinition`：id（`shortcut.*` 命名空间）/ 前置条件 / 声明参数 /
  步骤模板（`{param}` 占位符 + `{0}` 步间引用）；
- 解析期全量校验：未知工具、未声明占位符、死参数（声明未使用）、超步数全部
  字段级拒绝；
- **JSON 感知替换**：字符串参数按 JSON string content 转义，数值/布尔裸插入
  —— 渲染出的步骤参数永远是合法 JSON；
- **风险继承**：编译工具的风险 = 所含步骤最坏风险（含 `app_uninstall` 的
  快捷方式必为 HIGH）；
- 模型入口三件套：`shortcut_define`（定义 + 热注册为一等工具）、
  `shortcut_list`（清单 + 挖掘建议）、`shortcut_run`（显式执行兜底）；
- `ShortcutSuggester`：从 ToolTraceRecorder 的成功相邻 bigram 挖掘 ≥3 次的
  序列，产出可编辑的定义骨架 —— Mobile-Agent-E 自进化闭环的落地。

### 3.7 ToolEnvironmentState + ToolEnvironmentGate（Mobile-Agent 门控范式）

- 三态语义：`true` / `false` / **unknown（缺失或过期）**——门控只对显式 false
  拒绝，unknown 放行（fail-open，零回归）；
- **TTL 过期回 unknown**：事件驱动信号（可编辑焦点 → keyboard_active）天然会
  陈旧，过期绝不误报 false；
- 9 个标准旗标：keyboard_active / accessibility_ready / network_available /
  root_available / shizuku_available / ubuntu_ready / terminal_session_open /
  browser_attached / power_suitable；
- Deny 文案带每个旗标的**修复动作**（"先 ui_tap 输入框"）；
- **同源双消费**：`EnvironmentInfoProvider` 把 `summary()` 注入 system prompt
  的 Live Environment 段 —— 模型看到的与门控执行的是同一份真值；
- app 层 `EnvironmentStateUpdater` 桥：收集 accessibilityAvailable StateFlow +
  注册 TYPE_VIEW_FOCUSED 可编辑焦点事件（45s TTL）；
- `CompositeToolGate`：门控链组合（环境门在前——便宜且模型可自修，风险审批
  在后——不白烧用户会话授权）；
- `input_text` 声明 KEYBOARD_ACTIVE 硬前置（遥测未接入时行为不变）。

### 3.8 新内置工具（3 + 4 编排入口）

| 工具 | 来源语义 | 要点 |
|------|---------|------|
| `wait` | Anthropic CU `wait` | ≤300s 有界可取消等待；报告实际 elapsed |
| `json_transform` | jq 精神 | pick/omit/rename/flatten/map_pick/wrap/values 七操作管线，工具间数据形状对齐零 token 燃烧 |
| `version_compare` | SemVer §10-11 | `1.10.0 > 1.9.0`、预发布阶梯、v 前缀、build 元数据忽略；回显解析元组供模型自检 |
| `tool_batch_run` | Anthropic CU actions[] | 批量执行模型入口（steps 双形态） |
| `shortcut_define/list/run` | Mobile-Agent-E | 组合动作定义/清单+挖掘/执行 |

## 4. 接线（app 模块）

- `ToolModule`：v3 单例四件（EnvironmentState / TraceRecorder / CircuitBreaker /
  ShortcutRegistry）+ `buildV3Executor()` 统一装配（registry 内部步骤执行器与
  引擎主执行器共用同一装配，避免行为漂移）+ **修复 MCP 三重注册**；
- `ApexApp.onCreate`：启动 `EnvironmentStateUpdater` 遥测桥；
- `AgentModule`：`AndroidEnvironmentInfoProvider` → 引擎 system prompt；
- `AgentChatViewModel` / 引擎 / 编排器零改动（ToolExecutor 接口不变）。

## 5. 测试

新增 88 个 JVM 测试（总计 202 → 290，全绿）：

| 套件 | 覆盖 |
|------|------|
| ToolV3InfraTest | 注解推断/工厂、退避全抖动、解析器优先级、重试分类、令牌桶（容量/回填/按工具/禁用）、熔断状态机（开/合/半开/加宽/复位）、环境三态/TTL/摘要、复合门控短路 |
| ToolTraceRecorderTest | span 幂等完成、环形驱逐、监听器、args 摘要不泄机密、JSON 导出、直方图 |
| ToolBatchRunnerTest | 顺序执行、首错即停+契约文案、{n} 整引用/插值/越界预拒、头截断、整批预算超时、16 步上限 |
| ToolV3ExecutorTest | 策略超时（虚拟时间）、零超时=v2 语义、retrySafe 重试至成功、破坏性不盲重试、权限终态、重试耗尽、熔断短路+探测恢复、限流拒绝、追踪 attempt 编号、统计一次逻辑调用、schema 校验不回归、建议错误不回归、流式（包装/超时/透传）、Builder 装配 |
| ShortcutSystemTest | 定义解析（合法/非法 id/未知工具/死参数/未知占位符/步数上限）、注册表 upsert/导出、风险继承、JSON 安全替换、数值裸替换、缺参字段级错误、步骤失败传播、挖掘建议（成功 bigram + 骨架可解析） |
| NewToolsV3Test | wait 边界/成功、json_transform 七操作+管线+形状错误、version_compare 数值序/预发布阶梯/前缀/元数据、tool_batch_run 双形态、shortcut_* 模型入口 |

## 6. 显式不做（并说明理由）

- **流式工具自动重试**：已发部分输出的流无法重放（副作用对模型可见），与
  Anthropic 批量执行器同样保持不对称；
- **引擎编排器（DefaultTaskOrchestrator）的 prompt 环境注入**：编排器 prompt
  独立装配，属后续增量（引擎主路径已接入）；
- **Debian 版本号全序比较**：`version_compare` 是 SemVer + best-effort 容错
  （`1.2.3ubuntu1` 类），非 dpkg --compare-versions 等价物。

## 7. 验证

- 本地 CI 镜像（kotlinc 2.0.21 + 同版本依赖 jar，与 ci.yml 逐字节同构）：
  4 核心模块编译零错误 + 290 测试全绿；
- `check_file_size.sh` / `check_code_quality.sh` / 括号平衡 / 工具 ID 唯一性 /
  重复类名检查全部通过。
