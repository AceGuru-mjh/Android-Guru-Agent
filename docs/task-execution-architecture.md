# Task Execution Architecture (T76)

> Agent 长任务执行体系：可规划、可持久执行、可恢复、可压缩上下文、可追踪进度、可从异常恢复。
> 关联：`docs/T76_ARCHITECTURE_AUDIT.md`（前置审计）· `docs/T76_ADR.md`（架构决策 D-1~D-4）· `docs/T76_FINAL_REPORT.md`（交付报告）

---

## 1. 总览

T76 在**既有引擎之上**叠加任务运行时层（D-2 方案 B），不重写引擎、不替换 `AgentEngine` DI 绑定：

```
┌──────────────────────────────────────────────────────────────┐
│ UI层                                                          │
│  AgentChatScreen ─ TaskStatusCard / TaskRecoveryBanner       │
│  AgentChatViewModel ─ AgentTaskStatusController（新文件）     │
│        │ execute/abort 改走 controller（3 处一行改动）        │
├────────┼─────────────────────────────────────────────────────┤
│ TaskRuntime（core:agent-engine/task/，纯 JVM）                │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │ TaskStatus  │  │ FileTaskStore│  │ ToolExecutionPolicy │  │
│  │ Machine     │  │ (原子写+隔离) │  │ (80 工具幂等分类)    │  │
│  └─────────────┘  └──────────────┘  └─────────────────────┘  │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │ TaskModels  │  │ Recovery     │  │ DanglingToolCall    │  │
│  │ (序列化)    │  │ Policy       │  │ Repair (既有bug修复) │  │
│  └─────────────┘  └──────────────┘  └─────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│ ApexAgentEngine（最小挂钩：injectSystemContext +              │
│   setLlmExecutionTags，共 +45 行）                            │
│   AgentEvent 流（冻结）→ TaskRuntime 镜像消费 → checkpoint    │
├──────────────────────────────────────────────────────────────┤
│ ConversationMemory（不动）· TaskStore 文件（新）              │
└──────────────────────────────────────────────────────────────┘
```

**执行路径**：`VM.sendMessage → controller.execute → TaskRuntime.execute →（镜像 collector 消费）engine.execute → AgentEvent 流 → 生命周期边界落盘 → Channel 重放给 UI`。

镜像 collector 运行在 TaskRuntime 自己的 scope（与 UI collect 生命周期解耦）：VM 取消它的 job 不会中断 checkpoint 记录；abort/pause 的收尾事件即使 UI 已停止消费，落盘也完整执行。

---

## 2. 分层模型

| 层 | 类型 | 落点 | 持久化 | 说明 |
|---|---|---|---|---|
| Task | `AgentTask` | `task/TaskModels.kt` | ✅ 单文件 | 顶层载体：状态/步骤/journal/时间戳/配置快照 |
| Execution | 执行流（Channel + 镜像协程） | `task/TaskRuntime.kt` | 状态承载于 Task | 一次 `execute` = 一次执行；单活跃互斥 |
| Step | `TaskStepModel` | Task.steps | ✅ | Plan/Spec 步骤（BUILD 模式无步骤粒度） |
| Operation | `ToolOperationRecord` | Task.operations（journal） | ✅ | 工具调用（NOT_STARTED/RUNNING/SUCCEEDED/FAILED/UNKNOWN） |

**journal 物理上限**：最近 200 条滚动保留（`TaskStoreLimits.MAX_JOURNAL_SIZE`），防文件膨胀。operationId 本地生成（`<taskId>-op<seq>`），与 LLM callId 建立映射——恢复时用 operationId 判幂等、用 callId 修补悬空历史。

---

## 3. 两层状态机（审计 R-8）

**持久层**（`TaskStatusMachine`，显式迁移表 + `IllegalTaskTransitionException`）：

```
PENDING ──→ PLANNING ──→ RUNNING ──→ COMPLETED
   │           │      ↗  │  ↖
   │           │     ┌───┤    └──→ FAILED ──→ RETRYING ──┐
   │           │     │   ↓                               │
   └───────────┴──── │ WAITING_USER ──→ PAUSED ──→ RUNNING(恢复)
                     │   │                │
                     ↓   ↓                ↓
              CANCELLING ──→ CANCELLED ←──┘（绝对终态）
                     ↑
              RECOVERING（崩溃恢复中）
```

完整迁移表（v1）：

| from | to |
|---|---|
| PENDING | PLANNING · RUNNING · FAILED · CANCELLED |
| PLANNING | RUNNING · WAITING_USER · PAUSED · FAILED · CANCELLED |
| RUNNING | PLANNING · WAITING_USER · PAUSED · CANCELLING · RECOVERING · COMPLETED · FAILED |
| WAITING_USER | RUNNING · PAUSED · CANCELLING · RECOVERING · FAILED |
| PAUSED | RUNNING · CANCELLING · RECOVERING · FAILED |
| CANCELLING | CANCELLED · FAILED |
| RECOVERING | RUNNING · CANCELLING · FAILED |
| RETRYING | RUNNING · CANCELLING · FAILED |
| FAILED | RETRYING · CANCELLED（放弃） |
| COMPLETED / CANCELLED | （无出边，绝对终态） |

- **自环一律非法**（`isLegal(s, s) == false` 全集测试）。
- **RUNNING→CANCELLED 直达非法**：取消必须经 CANCELLING 瞬态（abort 是协作式的——请求取消到执行流收尾之间存在窗口）。例外：FAILED→CANCELLED 是表内直达边（放弃失败任务）。
- **PAUSED ≠ CANCELLED**：PAUSED 有到 RUNNING 的出边（resume）；CANCELLED 绝对终态，重启后不自动继续。

**运行时层**（A68 `orchestrator.TaskState`：Planning/Acting/Observing/Responding…）不变——单次进程内瞬态细化，不落盘。映射关系：`Planning→PLANNING`、`Acting/Observing/Responding→RUNNING`、`AwaitingUserInput/AwaitingPlanConfirmation/AwaitingSpecConfirmation→WAITING_USER`、`Finished.Completed→COMPLETED`、`Finished.Failed→FAILED`、`Finished.Aborted→PAUSED|CANCELLED`（按用户意图裁决）。

---

## 4. Checkpoint 策略（N-4）

**只在生命周期边界落盘，绝不按 token**（`CheckpointBoundary` 14 类）：

| 边界 | 触发事件 | 落盘内容 |
|---|---|---|
| TASK_CREATED | execute 入口 | 新任务（含配置快照） |
| PLAN_CONFIRMED | PlanConfirmed/SpecConfirmed | 步骤持久化 + PLANNING→RUNNING |
| STEP_STARTED | StepStart | 步骤 RUNNING + 前序步骤 DONE（步骤切换推导） |
| TOOL_CALL_STARTED | ToolCallStart | journal 追加 RUNNING（含幂等分类快照） |
| TOOL_CALL_FINISHED | ToolCallComplete | journal 更新 SUCCEEDED/FAILED + 输出摘要 |
| STEP_FINISHED | 推导（步骤切换/Complete） | 最后步骤完成化 |
| CONTEXT_COMPRESSED | ContextCompressed | 压缩计数 + historyAnchor + 任务状态重注入 |
| WAITING_USER | UserInputRequired | WAITING_USER |
| ERROR | Error(不可恢复) | FAILED + 错误摘要 |
| PAUSED / CANCELLED / COMPLETED | finalizeStream 裁决 | 终态（pause > cancel > failed > completed） |
| RECOVERED / RETRY_STARTED | 恢复/重试入口 | RECOVERING / RETRYING |

**终态仲裁**：引擎的 `finally` 无条件发 `Complete` 事件（含失败场景）——TaskRuntime 不信它，由 `finalizeStream` 按标志统一裁决（pauseRequested / cancelRequested / 已 FAILED 优先于"完成"）。镜像 collector 的 finally 异常安全：finalize 失败不吞 `done.complete`/`tap.close`（否则 pause/cancel 永久挂起）。

**原子写**（D-1）：`<taskId>.json.tmp` 写入 → flush → **fsync**（`FileDescriptor.sync()`）→ 同目录 rename（原子）。崩溃时磁盘上要么旧 checkpoint 要么完整新文件；半写 temp 在下次扫描时清理。损坏文件移 `corrupt/`（保留不删除）。schema v1 + `ignoreUnknownKeys` 宽容解析（未来字段前向兼容）。

---

## 5. Pause / Resume / Cancel / Retry（N-6）

| 操作 | 语义 | 状态链 | 重启行为 |
|---|---|---|---|
| **pause()** | 可续暂停：置 pauseRequested → `engine.abort()`（协作式）→ 等流收尾 → PAUSED 落盘。对话历史**不动**（引擎 history + memory 均保留） | RUNNING→PAUSED | 发现为 PAUSED，**等用户显式续**（不自动跑） |
| **resume()** | 注入 `[RESUME]` 提示（告知中断点进度，勿重复已完成操作）→ execute 续跑（上下文天然连续——同一引擎、同一 history） | PAUSED→RUNNING | — |
| **cancel()** | 终止：置 cancelRequested → abort → 流收尾 CANCELLING→CANCELLED 落盘 | RUNNING→CANCELLING→CANCELLED | **绝不自动继续**（isActive=false，发现扫描排除） |
| **retry()** | 失败重试：FAILED→RETRYING→RUNNING，retryCount+1，上限 3（`DEFAULT_RETRY_LIMIT`）；注入 `[RETRY]` 提示（成功操作勿重做） | FAILED→RETRYING→RUNNING | — |

**并发互斥**（N-8）：`AtomicBoolean.compareAndSet` 占位——并发两次 resume/retry/execute **只有一个成功**（另一个立即拒绝），无检查-占位竞态窗口。跨进程互斥由 TaskStore 文件状态承担。

---

## 6. 崩溃恢复（N-5，D-3）

```
App 启动 → VM init → TaskRuntime.discoverRecoverableTasks()
  ├─ store.loadActiveTasks()（顺带：temp 清理 + 损坏隔离）
  ├─ RUNNING/WAITING/PLANNING/RECOVERING → RECOVERING（迁移落盘）
  ├─ PAUSED → 保持（用户明确暂停过，等用户决定）
  └─ 修补悬空历史（DanglingToolCallRepair）：见 §7
→ UI 横幅：任务标题 + 中断步骤 → 用户选择
  ├─ 继续 → resumeFromCrash(taskId)
  │    ├─ RecoveryPolicy.planForTask：UNKNOWN 操作决策（见 §8）
  │    ├─ 构造恢复提示（先验证 UNKNOWN 操作，勿重做已成功操作）
  │    └─ executeAsTask（RECOVERING→RUNNING，注入提示续跑）
  └─ 取消 → CANCELLED 终态（重启不再出现）
```

**测试语义**（任务书 §23 如实声明）：进程死亡用**确定性模拟**——`TaskRuntime.simulateCrash()`（SIGKILL 语义：短路 finally 收尾，store 停留在最后 checkpoint）+ 新 Runtime 实例共享 store/memory 模拟重启。真机 E2E 未做（CI 无法复现真机进程死亡，不伪造）。

---

## 7. 悬空 toolCall 修复（既有缺陷，审计 R-5）

**缺陷**（本任务之前已存在）：进程死于工具执行中 → memory 里 `Assistant.toolCalls=[...]` 之后无配对 `ToolResult` → 重启后引擎 load 该历史 → 下一次 LLM 请求带不完整 tool_calls 序列 → OpenAI 兼容 API 400 → 整条对话历史不可用。

**修复**：恢复发现时扫描历史——每个"有 callId 无 ToolResult"的悬空调用，追加合成 `ToolResult("⚠ Interrupted: outcome UNKNOWN ... Verify ...")`。该文本同时满足 API 配对校验**和**告知 LLM 结果未知应先验证（与 RecoveryPolicy 的 VERIFY 决策呼应）。幂等（重复修补无副作用），配对不变量有专项测试。

---

## 8. 工具幂等性与恢复决策（N-7）

**分类表**（`ToolExecutionPolicy`，覆盖 App 实际注册的 80 个工具，未列入默认 UNKNOWN）：

| 类别 | 语义 | 代表工具 | 恢复动作 |
|---|---|---|---|
| READ_ONLY | 重放无副作用 | read_file / list_files / web_search / ui_dump / logcat / browser_snapshot | RETRY |
| IDEMPOTENT_WRITE | 重放等价 | write_file（整覆盖）/ app_install / create_directory / browser_navigate / github_write_file | RETRY |
| NON_IDEMPOTENT | 重放重复副作用 | app_uninstall / ui_tap / input_text / browser_click / terminal_send / alarm | VERIFY |
| UNKNOWN | 行为不可判定 | shell_execute / http_request / mcp_call / terminal_exec / code_runner | VERIFY |

**决策矩阵**（`RecoveryPolicy.decideForOperation`）：

```
SUCCEEDED   → SKIP（绝不重复执行已成功落盘的操作——幂等性验收核心）
FAILED      → SKIP（错误已在历史，LLM 自行重规划）
NOT_STARTED → SKIP（从未执行）
UNKNOWN/中断:
  ask_user 族        → RETRY（重新提问）
  READ_ONLY          → RETRY
  IDEMPOTENT_WRITE   → RETRY
  NON_IDEMPOTENT     → VERIFY（提示 LLM 先验证再决定）
  UNKNOWN 分类       → VERIFY（保守路径）
```

**VERIFY 语义**：不重放操作，向 LLM 注入"操作 X 可能已在中断前执行，先用只读工具验证其实际效果再决定是否重做"。

---

## 9. 上下文压缩兼容（N-9）

P7 三层压缩（Layer1 工具输出截断 / Layer2 滑动窗口 / Layer3 LLM 摘要）的 Layer2 会把中间消息整段替换——任务状态若只存在于对话消息中会被压缩丢失。

**T76 方案**：
1. **Task 状态不在对话历史里**——它住在 TaskStore（独立文件），压缩天然不影响；
2. 压缩边界（ContextCompressed 事件）触发**重注入**：`contextInjector` 钩子向引擎 history 追加一条 `[TASK STATE]` system 消息（含标题/步骤进度/未完成步骤），LLM 压缩后仍知道任务进行到哪；
3. checkpoint 记录 `historyAnchor`（压缩后持久化消息数 = contextRef）与 `compressionCount`——恢复时校验依据。

---

## 10. Session 与 Task 的关系（任务书 §13）

**现状**：仓库无 Session 模型——全局单条对话历史（ConversationMemory，SharedPrefs）。T76 **不引入第二套 Session 系统**（任务书 §29 禁令）：

- `AgentTask.conversationRef = "global"`：引用语义（预留多 Session 扩展，不动 schema）；
- `historyAnchor`：任务创建/压缩时刻的历史锚点；
- 一个屏幕 = 一条对话 = 0..N 个历史 Task 记录（D-4：taskId 一等公民，v1 单活跃执行 + 任务历史查询 `loadTaskHistory()`）。

---

## 11. 可观测性（N-12）

- `LlmRequestContext.taskId/stepId`（T72 已预留、此前恒 null）现在**真实填充**：TaskRuntime 在 execute 入口设 taskId、StepStart 时设 stepId、finalize 清空。引擎全部 7 个 LlmRequestContext 构造点经 `tagged()` 包裹。未接线时行为与 T76 之前完全一致（既有测试不受影响）。
- `TaskRuntimeEvent`（低频通道，对应 A68 TaskLifecycleEvent 模式）：StatusChanged / CheckpointSaved / Finished / RetryExhausted / RecoverableDiscovered。**AgentEvent sealed 层级冻结不扩展**（A68 设计原则）。
- 日志脱敏：journal 只存参数摘要（512 字符截断）与输出摘要（256 字符截断），不落 prompt 全文。

---

## 12. 配置快照（任务书 §20）

`TaskConfigSnapshot` 在任务创建时刻从引擎当前配置快照（mode/thinkingLevel/maxIterations/maxContextTokens/压缩参数/temperature/reflectionRounds/enabledToolIds）——执行中途改设置不影响运行中任务（复用 A68 TaskOrchestratorConfig 的快照语义）。恢复/重试时以快照为参照。

---

## 13. 禁区遵守（任务书 §29）

以下**零改动**：platform:terminal 全模块、terminal-emulator、TerminalModule(DI)、Terminal v2 工具注册、core:llm-adapter 的 SSE 解析与 runtime/ 内部、platform:cs-mem 内部、plugin-sdk/plugins、AgentEvent sealed 层级、ConversationMemory 接口、UI 整体架构、MainActivity/ApexApp 启动链、platform:persistence 死代码（未激活、未基于它建恢复）。

引擎净改动 `ApexAgentEngine.kt` +45 行（两个挂钩 + tagged 包裹），1057→1102 行（1200 门禁内）。VM 1123→1189 行（门禁内）。

---

## 14. 测试矩阵覆盖（任务书 §18 A–K）

| 项 | 测试 | 位置 |
|---|---|---|
| A 状态机合法/非法迁移 | 121 组合全矩阵 + 自环 + 终态无出边 + 异常信息 | TaskStatusMachineTest |
| B 持久化 roundtrip/损坏/版本 | 全字段 roundtrip + 未知字段宽容 + 隔离不阻塞 + temp 清理 + 路径穿越 | FileTaskStoreTest |
| C 生命周期 checkpoint | 工具 journal（成功/失败）+ 落盘读回 + 步骤推导 | TaskRuntimeTest |
| D pause-restart-resume | PAUSED 落盘 → resume 续跑 → journal 连续 → 完成 | TaskRuntimeTest |
| E UNKNOWN 工具操作 | RUNNING 态中断 → 恢复计划 UNKNOWN + RETRY/VERIFY 决策 | TaskRuntimeTest + RecoveryPolicy |
| F 崩溃恢复（确定性模拟） | simulateCrash → 新实例发现 → 恢复续跑 → 完成 | TaskRuntimeTest |
| G cancel-restart 不自愈 | CANCELLED 终态 → 发现扫描排除 | TaskRuntimeTest |
| H 并发 resume 互斥 | 并发两次 resume 一个成功 + 并发 execute 拒绝 | TaskRuntimeTest |
| I 幂等 | 已成功操作绝不重放（未注册工具重放即失败的反证设计） | TaskRuntimeTest |
| J 压缩前后状态一致 | 重注入 + compressionCount + 步骤跨压缩保留 + 压缩后崩溃恢复 | CompressionCompatTest |
| K Fake 全链集成 | FakeLlmClient+FakeToolExecutor+真实 FileTaskStore 完整闭环 | TaskRuntimeTest 全部 |

补充：retry 上限链、FAILED→CANCELLED 放弃、悬空修补（单/并行/中段/重复/配对不变量）、四元 ID 贯通（BUILD+PLAN）与清理。

**全量 121 tests 绿**（73 A68 既有 + 48 T76 新增）；本地 kotlinc 复刻 CI 编译链验证，CI 用 `:core:agent-engine:test`（Gradle）执行。
