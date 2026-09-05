# T76 CURRENT ARCHITECTURE AUDIT
## Android-Guru-Agent (apex-agent) — Agent 长任务执行系统前置源码审计

- **审计对象**：`AceGuru-mjh/Android-Guru-Agent`，分支 `main`，HEAD `1849ca8`（Merge PR #90 feat/market-coding-principles），工作树干净、与 origin/main 同步
- **审计方式**：全量源码阅读（core/agent-engine 6600+ 行逐文件、app 层 ViewModel/DI/Service、platform 层）+ 两个并行探索代理交叉验证；所有结论均附文件路径证据；未运行任何生产代码改动
- **审计边界**：按任务书要求，本次只做 Phase A 审计，不写生产代码
- **重要前置发现**：远端已存在已合并分支 `t76/linux-environment-productionization`（Linux 环境生产化，T76 编号已被占用过）。本任务（Agent Task Runtime）建分支时应使用任务书指定的 `t76/agent-task-runtime`，并在 PR 描述中说明与旧 T76 编号无关，避免历史混淆

---

## 0. 执行摘要（十条最关键事实）

1. **仓库已存在一套完整的"任务编排器"（A68 系），但它是纯内存、单次运行的**：`core/agent-engine/.../orchestrator/` 共 17 个文件（TaskOrchestrator、DefaultTaskOrchestrator 860 行、TaskState、TaskStateMachine、TaskProgress、TaskLifecycleEvent、RetryPolicy、FailureClassifier、LoopDetector、RecoveryPlanner、ToolCallGraph、BatchExecutionEngine、ToolCallRunner、UserInteractionGate 等）。它有状态机、重试、退避、失败分类、循环检测、恢复提示注入、并行工具执行——**但没有持久化、没有 Pause/Resume、没有 Checkpoint、没有崩溃恢复**。T76 绝不能重造这套东西，必须在其上扩展。
2. **A68 orchestrator 在 App 层完全没有被消费**：`app/.../di/AgentModule.kt` 中 `provideTaskOrchestrator`（L157-185）绑定了 `DefaultTaskOrchestrator`，但全仓库没有任何注入点使用 `TaskOrchestrator` 类型——`AgentChatViewModel` 注入并消费的是 `AgentEngine`（实际绑定 `ApexAgentEngine`，`AgentModule.kt` L113-140）。orchestrator 连同其 73 个测试处于"已接线但未通电"状态。
3. **不存在 Session 模型**：没有 session id、没有多会话。全仓库只有一条全局对话历史，存于 SharedPreferences `"apex_memory"`（key `conversation_history`），`newChat()` = `engine.clearHistory()` 直接清空。任务书 §13"Session != Task"的设计在当前代码里要从"引入 Task 引用对话"这一侧起步（Task 记录其关联的 conversation 引用即可，无需先造 Session 系统）。
4. **持久化现状薄弱且不原子**：`SharedPrefsConversationMemory`（`app/.../di/SharedPrefsConversationMemory.kt`）append 是 O(n) 读-改-写全量重写；无 schema 版本号；解码失败即清空历史；`apply()` 异步落盘，进程被杀时最后若干条 append 可能丢失。
5. **发现一个与 T76 崩溃恢复直接相关的现存缺陷（关键）**：`ApexAgentEngine.executeBuildLoop` 的写入顺序是"先 `addMessage(Assistant(toolCalls))`（立即持久化）→ 再逐个执行工具 → 工具完成后 `addMessage(ToolResult)`"（ApexAgentEngine.kt L656→L691→L883）。若进程在工具执行期间被杀，持久化历史中会留下**悬空 toolCall（有 Assistant.toolCalls 无对应 ToolResult）**，重启后下一次 LLM 请求会被 OpenAI 兼容 API 以 400 拒绝（dangling tool_call_id）。T76 的恢复逻辑必须检测并修补这种历史。
6. **abort() 是协作式标志位，无任何持久化**：`ApexAgentEngine.abort()`（L972-980）只做 `isRunning=false` + complete 三个 Deferred。没有 Pause 语义，没有 Cancel 持久化，App 重启后无从知晓上次任务是被中止还是崩溃。
7. **工具系统无幂等性/危险度元数据**：`AgentTool` 接口只有 id/name/description/parametersSchema/execute；callId 完全来自 LLM 返回的 `ToolCall.id`（无本地稳定 ID 生成）；超时不在 executor 层（在 orchestrator 的 ToolCallRunner 里，`DefaultToolExecutor` 无超时）。T76 要求的 `ToolExecutionPolicy`（READ_ONLY/IDEMPOTENT/NON_IDEMPOTENT/UNKNOWN）需要从零建立，但可挂靠现有 `CommandPermissionGate`（高危 shell 命令正则确认）与 `RiskLevel`（计划层风险枚举）的概念。
8. **实际注册工具约 80 个，不是 README 写的 35 个，也不是 ToolModule 注释写的 53 个**：73 个无条件注册 + 7 个 GitHub 条件注册（含 browser 15 个、terminal v2 16 个、terminal legacy 4 个、cs-mem 3 个）。审计一切以代码为准。
9. **质量门禁会约束 T76 的实现方式**：`scripts/check_file_size.sh` 限制 main 代码单文件 ≤1200 行（test ≤1600 行）。当前 `ApexAgentEngine.kt` 已 1057 行、`AgentChatViewModel.kt` 已 1123 行——**T76 几乎不能向这两个文件加代码**，新逻辑必须放新文件（VM 侧大概率要拆出独立的 Task 状态控制器）。另外 `check_code_quality.sh` 禁止 `javaClass.getMethod` 反射分发与 `printStackTrace()`。
10. **CI 存在测试盲区**：`ci.yml` 只跑 `:platform:terminal:testDebugUnitTest`、`:core:agent-engine:test`、`:core:tool-registry:test` + `:app:compileDebugKotlin`（continue-on-error）+ `:app:assembleDebug`。**`:core:llm-adapter:test`（69 个测试）与 `:app:test`（37 个测试）不在 CI 内**，app 模块无 androidTest。任务书验收标准 20"CI 全绿"需要先把新测试接入 CI，并如实说明现有盲区。

---

## 1. 当前 Agent Engine 架构

### 1.1 模块与主类

| 文件 | 类/接口 | 职责 | 当前行为 | 问题 | T76 复用判定 |
|---|---|---|---|---|---|
| `core/agent-engine/.../AgentEngine.kt` | `interface AgentEngine` | 引擎抽象：`execute(UserInput): Flow<AgentEvent>`、`abort()`、`submitUserInput`/`cancelUserInput` | 全部执行模式共用一个入口 | 无 pause/resume/retry 入口 | **保留不动**，TaskRuntime 叠加在其上 |
| `.../ApexAgentEngine.kt`（1057 行） | `class ApexAgentEngine : AgentEngine, ConfirmationSink` | 生产引擎：6 种模式（BUILD/PLAN/SPEC/REFLECTION/HUMAN_ASSIST/CUSTOM），流式 ReAct 循环 | 见下文分解 | 见下文分解 | **保留主循环，做最小挂钩扩展** |
| `.../AgentConfig.kt` | `AgentConfig`/`AgentMode`/`ThinkingLevel` | 运行时配置（mode/thinkingLevel/maxIterations/maxContextTokens/temperature/compressionThreshold/enabledToolIds/reflectionRounds...）+ 预设 QUICK/STANDARD/CAREFUL | DI 单例一次性快照（设置页改动需重启 App 生效） | 无任务级配置快照概念（任务书 §20 要求） | 复用；Task 记录 config snapshot |
| `.../AgentEvent.kt` | `sealed interface AgentEvent`（21 个事件）+ `ExecutionPlan`/`PlanStep`/`ExecutionSpec`/`RiskLevel`/`InputType` | 流式事件 + Plan/Spec 产物模型 | UI 唯一消费通道 | 事件无 taskId/stepId 关联字段（StepStart 只有 index+description） | **冻结扩展**（A68 设计原则），Task 关联放 TaskLifecycleEvent 侧 |
| `.../ConversationMemory.kt` | `interface ConversationMemory` | load/append/save/clear/count 五个方法 | 每条消息入历史即 `append()`；构造时 `load()` 恢复 | 接口无事务/原子语义 | 复用接口；Task 通过引用关联 |
| `.../EnginePrompts.kt`（343 行）/`EngineResponseParsers.kt` | 纯函数 | system prompt 组装、Plan/Spec JSON 解析（含 fallbackPlan） | — | — | 不动 |
| `.../CommandPermissionGate.kt` | 高危 shell 命令确认门 | 正则命中高危命令时要求用户确认 | 只覆盖 shell_execute | 工具级危险度缺失 | T76 的 ToolExecutionPolicy 与之互补 |
| `.../StreamingToolCallAccumulator.kt` | 流式工具调用累加器 | 以 `id || _idx_N` 复合键拼接并行工具调用参数 | — | id 依赖 LLM 返回 | 不动 |
| `.../ExecutionMemoryObserver.kt` | 隐式记忆观察者接口 | onTaskStart/onActionExecuted/onTaskFinish/tryBypass（cs-mem 肌肉记忆旁路） | 引擎四个钩子调用 | — | 不动 |
| `.../UserInput.kt`/`UserQuestion.kt`/`ConfirmationSink.kt` | 多模态输入/提问桥/确认下沉接口 | 类型安全桥接 | — | — | 不动 |

### 1.2 主循环行为分解（以 BUILD 为例，`executeBuildLoop` L557-763）

- 循环条件：`while (isRunning && iteration < config.maxIterations)` —— **协作式取消**，`abort()` 置 `isRunning=false` 后，当前工具/当前 LLM 流不会被立即打断，只在边界退出（工具执行内部 `CancellationException` 会重抛传播，依赖调用方 job cancel）。
- 每轮迭代：`IterationStart` 事件 → `maybeCompressContext`（P7 压缩检查点）→ 肌肉记忆旁路尝试 → 构造 messages（System + 全部 conversationHistory）→ `runtime.chatStream`（T72 多模型路由，含 VISION 分流）→ 流式累计 content/reasoning/toolCalls → 分支处理（工具调用串行执行 / 纯文本收尾 / 空响应报错）。
- 工具执行（`executeToolCallStreaming` L786-894）：`ToolCallStart` → 逐 chunk `ToolOutputChunk`/`ToolProgress` → P7 Layer1 截断 → `ToolCallComplete(success)` → `addMessage(ToolResult)` → memoryObserver 钩子。**成败判定 = 流式 Error 事件信号 + "Error" 前缀兜底**。
- 异常分类（`execute` 外层 catch）：`CancellationException`→`Aborted`；`TimeoutCancellationException`（确认超时）→不可恢复 Error；`ModelRuntimeException`（T72 分类）→按 `isFallbackEligible` 标记 recoverable；其他→不可恢复 Error。**finally 中无条件补发 `Complete` 事件**（即使失败也发"Task completed"——语义上是个弱点，T76 的 TaskRuntime 事件层要区分 Failed/Completed）。
- PLAN 模式：Think → 流式生成 plan JSON → 解析 → `PlanAwaitingConfirmation` → 挂起 `awaitPlanConfirmation()`（CompletableDeferred，5 分钟超时，`abort()` 自动 complete(false)）→ 确认后逐 `PlanStep` 执行（每个 step 一次 Build 循环，`StepStart` 事件）→ 最终 reflection。SPEC 模式同构（deliverables 代替 steps）。
- **关键缺口（对 T76）**：
  - Plan/Spec 的步骤循环在引擎**内部**，外部无法按 step 粒度做 checkpoint/暂停/恢复；
  - `StepStart` 只有 index/description，无 stepId、无完成状态持久化；
  - 每条消息持久化了，但"任务执行到哪"这个语义没有持久化；
  - 会话级 `anyActionFailed` 实例字段跨 execute 累计，非任务级语义。

### 1.3 T72 多模型运行时（`core/llm-adapter/.../runtime/`）

`ModelRuntime` 统一执行入口（`SingleClientModelRuntime` 兼容回退），`DefaultModelRuntime` 做角色路由→候选尝试→跨模型降级（maxAttempts=4、防环、流式仅首 chunk 前可降级），`ModelRoleRouter`+`CapabilityResolver` 做能力校验，`LlmRequestContext` **已含 `taskId`/`stepId` 字段**（诊断用，当前引擎调用时全部传 null）。→ T76 可观测性要求的 taskId/stepId 贯通已有现成挂点，只需在 TaskRuntime 驱动时填入。

---

## 2. 当前 Session 架构

**结论：不存在 Session 架构。**

- 全仓库无 session id 概念。仅有的"session"字样：`CsMemSessionManager`（长期记忆会话，无关）、`ChatToolkitStore.buildSessionContext()`（system prompt 段落构造，无关）、Terminal 模块的 `SessionMetadataStore`（终端会话元数据，与聊天无关——但其**文件式元数据存储模式可作为 TaskStore 的参照**）。
- 对话连续性完全靠 `ConversationMemory` 的单条全局历史：App 重启 → 引擎构造 → `memory.load()` 恢复全部消息（`ApexAgentEngine.kt` L88-90）。
- "新会话"按钮：`AgentChatViewModel.newChat()`（L928-956）→ cancel job → flush 流式缓冲 → `(engine as? ApexAgentEngine)?.clearHistory()` → UI 复位。**清空即销毁，无会话存档/切换/列表**。
- 多任务并发：无。引擎是 `@Singleton`，`isRunning` 单标志，同一时刻只可能有一个 `execute` 在跑（但没有互斥保护——理论上并发 `execute` 会交错写同一条 history）。

**对 T76 的含义**：任务书 §13"Session != Task、Task 引用 Session"在当前现实下应落地为——**Task 持有 `conversationRef`（指向全局单条历史的引用语义）+ Task 创建时刻的 `historyAnchor`（当时持久化消息数或最后消息指纹）**，避免为 T76 先造一套平行 Session 系统（任务书 §29 明令禁止第二套 Session 系统）。UI 层面"一个屏幕 = 一条对话 = 0..N 个历史 Task 记录"。

---

## 3. 当前 Persistence

### 3.1 现有持久化机制清单

| 机制 | 位置 | 用途 | 原子性 | Schema 演进 | T76 相关性 |
|---|---|---|---|---|---|
| SharedPreferences `"apex_memory"` | `app/.../di/SharedPrefsConversationMemory.kt`（114 行） | 对话历史（`StoredMessage`/`StoredToolCall` JSON 列表） | ❌ apply() 异步；append 为 O(n) 读-改-写 | ❌ 无版本号；解码失败清空重来 | Task 必须引用但**不要**把 Task State 塞进这里 |
| SharedPreferences `"apex_settings"` | `app/.../data/SettingsRepository.kt` | LLM 配置/模型档案/Agent 设置（`model_profiles_v2` 等 5 个版本化 key + legacy 迁移） | apply() | ✅ key 带 `_v2` + `migrateLegacyConfig` | **模式参照**：版本化 key+迁移是好实践，TaskStore 应仿此并做得更严（原子写） |
| 文件 JSON `filesDir/agent_memory/<category>/<key>.json` | `core/tool-registry/.../MemoryTools.kt` + FileMemoryStore | memorize/recall/forget 长期事实记忆 | — | — | 无关 |
| Room `cs_mem_graph.db` | `platform/cs-mem/di/CsMemModule.kt` | 隐式记忆图 | ✅ | ✅ | 无关（不要为 Task 引入第二个 Room DB） |
| 文件式终端会话元数据 | Terminal 模块 `SessionMetadataStore` | 终端会话 | — | — | **存储模式参照** |
| `platform:persistence` 整模块 | `PersistenceEngine.kt` + `WatchdogWorker` | 6 层保活 + 15 分钟看门狗 | — | — | **死代码**：`PersistenceEngine.activate()` 全仓库零调用；WatchdogWorker 非 HiltWorker、仅 BootReceiver 拉起 ApexCoreService。与 Agent 执行零关系，T76 **不要**基于它建恢复触发 |

### 3.2 关键判定

- **TaskStore 不能用 SharedPreferences**：checkpoint 频率高于消息追加（每个 ToolCall 边界），O(n) 全量重写不可接受；且无原子写。
- **推荐路线**（与任务书 §21/§25 一致）：`filesDir/taskstore/` 目录 + 单 Task 单 JSON 文件 + **temp 文件写入 → fsync → 原子 rename**（Android 上 `File.renameTo` 同分区原子）+ 顶层 `index.json` 或按状态分目录（`active/`、`done/`）发现未完成任务。schema 带 `version` 字段 + `ignoreUnknownKeys` 宽容策略 + 损坏文件隔离（移入 `corrupt/` 而非删除）。
- **ConversationMemory 接口不动**：Task 恢复时通过它取回对话；但 T76 需新增"修补悬空 toolCall"的辅助逻辑（见 §14 风险 R-5）。

---

## 4. 当前 Tool 系统

### 4.1 执行链

```
LLM ToolCall(id来自模型) → ApexAgentEngine.executeToolCallStreaming
  → ToolExecutor.executeStream(toolId, args): Flow<ToolStreamEvent>   (DefaultToolExecutor, flowOn(IO))
    → registry.getTool(id) → SafeAgentTool 包装（保证永不抛业务异常）
      → AgentTool.execute(args): String   或   StreamingAgentTool.executeStream(args): Flow<ToolStreamEvent>
```

- `ToolRegistry`（`core/tool-registry/.../ToolRegistry.kt`）：register/unregister/getTool/getAllTools/getToolDefinitions；`DefaultToolRegistry` 简单 map。
- `DefaultToolExecutor`：**无超时**（orchestrator 的 `ToolCallRunner` 用 `withTimeout(toolTimeoutMs=60s)` 包裹才有时限）；未知工具返回带可用工具列表的 Error 字符串；`CancellationException` 重抛。
- `SafeAgentTool`：装饰器，关键设计是它实现 `StreamingAgentTool` 以保住 delegate 的流式能力。
- `ToolStreamEvent`：Output/Progress/Complete/Error 四态。

### 4.2 与 T76 直接相关的缺口

1. **无 callId 本地生成**：id 全部来自 LLM；恢复场景需要稳定 `operationId`（建议 `executionId + stepId + 序号` 本地生成，与 LLM callId 做映射记录）。
2. **无幂等性分类**：80 个工具需要逐一归类 READ_ONLY（read_file/list_files/web_fetch/app_list/ui_dump/logcat/get_device_info...）/ IDEMPOTENT_WRITE（write_file 整覆盖、app_install、settings put）/ NON_IDEMPOTENT（app_uninstall、delete_file、ui_tap、input_text、clipboard write、terminal.run）/ UNKNOWN（shell_execute、http_request POST、plugin 工具）。**分类表是 T76 的新增交付物**，挂靠方式建议：`AgentTool` 不改（冻结），新建 `ToolExecutionPolicy` 注册表（toolId → 类别 + 恢复策略默认值），Terminal 类工具默认 UNKNOWN。
3. **实际注册工具清单**（`app/.../di/ToolModule.kt`）：73 无条件 + 7 GitHub 条件 = 80 个。其中 terminal 16+4 个、browser 15 个、cs-mem 3 个、memory 3 个。README 的 35 与注释的 53 均过期。
4. **断链现状（记录，不顺手修）**：`SkillToolAdapter` 零实例化（skill→工具链路不存在）；`McpTools`/`SkillTools`/`FileEditTool` 定义未注册；`PluginManager.registerPluginTools` 是 TODO 空壳；`OpenAiCompatibleClient`/app 侧流式 `DownloadFileTool`/`StreamingShellExecuteTool` 为死代码（后者与已注册工具 id 冲突）。

---

## 5. 当前 Context 管理（P7 压缩）

- **触发**：`ApexAgentEngine.maybeCompressContext`（L1012-1051）——每个迭代边界检查 `TokenEstimator.estimateHistory > maxContextTokens × compressionThreshold(默认0.8×128k)`，触发则 `HybridCompressor.compress(history, preserveRecent=5)`。
- **三层策略**（`.../compression/`）：Layer1 工具输出智能截断（`ToolOutputTruncator`，且在**每个工具结果入历史前**就内联执行——这是第一道防线）；Layer2 滑动窗口（保 System + 最近 5 轮，中间替换为规则摘要 system 消息）；Layer3 LLM 摘要（失败回退规则摘要）。
- **持久化同步**：压缩后 `memory.save(conversationHistory)` 全量回写 → 重启加载的已是压缩态。
- **保护规则**：System prompt、最近用户原始任务描述、最近 preserveRecentTurns 条、执行中工具调用。
- **T76 关键交互点**：压缩后历史被替换，**Task 状态不在对话历史里**——如果 TaskRuntime 只把任务状态写进对话消息，压缩（尤其 Layer2）会丢掉它。方案：TaskRuntime 在每次压缩后**重新注入**一条受保护的 Task State system 消息（或在 HybridCompressor 增加 preserve 钩子保留该消息）；同时 Checkpoint 里保存 `contextRef`（压缩后首消息指纹），Resume 时校验。**这是任务书 §8/验收 J 的核心实现点。**

---

## 6. 当前已有 Task / Todo / Plan 能力（T76 最重要的复用盘点）

### 6.1 A68 Orchestrator 全家桶（`core/agent-engine/.../orchestrator/`，17 文件）

| 文件 | 核心类型 | 已实现语义 | 缺什么（对照 T76 任务书） | T76 判定 |
|---|---|---|---|---|
| `TaskState.kt`（145 行） | `sealed interface TaskState`：Idle / Planning(iter) / Acting(callId,tool) / Observing(success) / Responding / AwaitingUserInput / AwaitingPlanConfirmation / AwaitingSpecConfirmation / Finished{Completed,Failed,Aborted} | 单次运行内完整生命周期表达 | **无 PAUSED / CANCELLING / RECOVERING / RETRYING**；无 persisted 标记；终态不可复活（设计如此） | **扩展**：新增状态 + 增加 `taskId` 关联 |
| `TaskStateMachine.kt`（142 行） | 状态机宿主：StateFlow<TaskState>+StateFlow<TaskProgress>+SharedFlow<TaskLifecycleEvent>，synchronized 守护，emit 在锁外 | 并发安全的转移 + 进度刷新 + 生命周期事件 | **transitionTo 不校验合法性**——任意状态可跳任意状态（任务书 §2 要求禁止非法迁移并测试） | **扩展**：加转移合法性表 + `IllegalTransitionException` |
| `TaskProgress.kt`（84 行） | 进度快照：goal/currentObjective/completedIterations/completedToolCalls/failedToolCalls/attemptCount/retriedToolCalls/recoveryCount/elapsedMs | 每次 transition 原子刷新 | 无 step 级进度（任务书要求 currentStep/totalSteps，"Step 3/7"优于假百分比） | **扩展**：加 step 维度字段 |
| `TaskLifecycleEvent.kt`（159 行） | 11 类低频事件：Started/StateChanged/ToolCallScheduled/ToolCallFinished/Timeout(PER_TOOL/TASK_LEVEL)/Cancelled/ToolCallRetried/LoopDetected/RecoveryTriggered/ParallelBatchFinished/Finished | 与 AgentEvent 双通道分离（高频流式 vs 低频生命周期）——**这正是任务书 §16 要求的事件模型** | 缺 TaskPaused/TaskResumed/CheckpointSaved/TaskRecovered/StepStarted/StepCompleted | **扩展**（事件模型已就绪，加事件即可） |
| `RetryPolicy.kt`（129 行） | 指数退避+抖动+任务级 RetryBudget（默认 maxRetries=2/budget=6/退避 500ms~8s/±20% 抖动）；DEFAULT/DISABLED/FAST 三档 | 任务书 §7 的"最大次数/退避/保存 retryCount/不允许无限 retry"**语义上已完整实现**（纯计算无 IO，可测） | retryCount 只在内存 TaskProgress；未持久化；无 transient/permanent 之外的 user-cancel/context-failure 分类细分 | **复用+持久化包装** |
| `FailureClassifier.kt`（116 行） | TRANSIENT / TIMEOUT / PERMISSION / FATAL 四分类（PERMISSION/FATAL 永不重试） | 任务书 §7 的错误分类基础 | 缺 INFRASTRUCTURE（进程死亡/取消）与 UNKNOWN（中断不明）类 | **扩展枚举** |
| `LoopDetector.kt`（136 行） | 重复调用检测 + 周期振荡检测（窗口滑动、acknowledge） | 智能体打转检测 | — | **原样复用** |
| `RecoveryPlanner.kt`（78 行） | 循环触发时注入恢复提示词（有界 maxRecoveries） | "恢复提示"语义（注意：这是**策略性恢复**，不是**崩溃恢复**——两者在 T76 要分开） | 与崩溃恢复无关 | **原样复用**，命名注意区分 |
| `ToolCallRunner.kt`（237 行） | 单次工具调用核心：per-attempt withTimeout + 分类 + 重试决策 + 退避 | 工具级重试执行单元 | 无 operationId、无结果落盘 | **复用**，外接 journal |
| `ToolCallGraph.kt`（212 行）+`BatchExecutionEngine.kt`（454 行）+`ToolCallOutcome.kt` | depends_on 依赖分层 + 有界并行 + 部分失败隔离（依赖失败→SKIPPED）+ 聚合 | A68.3 并行批次 | — | **原样复用** |
| `UserInteractionGate.kt`（61 行） | 单一 pending ask_user 挂起门 | 人工介入 | — | **原样复用** |
| `DefaultTaskOrchestrator.kt`（860 行） | 总装：BUILD 模式自跑 ReAct 循环（**自持一条独立 conversationHistory**），其余模式委托 delegate（ApexAgentEngine）并从事件流推导状态 | 见 §1 | 无持久化/无 pause/单任务假设/`execute` 入口清空 history 重新开始 | **核心改造对象**（或其上层包 TaskRuntime） |
| `TaskOrchestratorConfig.kt`（195 行） | toolTimeoutMs=60s/taskTimeoutMs=0(禁用)/retryPolicy/loop 参数/maxRecoveries/enableParallelToolExecution/maxParallelToolCalls/emitLifecycleEvents | 配置快照语义已实现（任务开始时快照，中途 updateConfig 不影响运行中任务——**任务书 §20 的 snapshot 语义已有先例**） | — | **复用模式** |
| `TaskResilienceRuntime.kt`（52 行） | 每任务重建的 runner+detector+planner 组合 | 所有权规则明确 | — | **原样复用** |
| `OrchestratorPrompts.kt`/`OrchestratorLog.kt` | 提示词构造/AppLogger 门面 | — | — | 复用 |

### 6.2 Plan / Spec 能力（引擎内）

- `ExecutionPlan`/`PlanStep`（`AgentEvent.kt` L178-192）：goal/steps[index,description,toolName,estimatedArgs,dependsOn]/estimatedToolCalls/riskLevel/reasoning。**PlanStep 有 dependsOn！** 与 ToolCallGraph 的语义呼应——T76 的 Step 模型可以直接以 PlanStep 为基础加 status 字段。
- `ExecutionSpec`：goal/requirements/constraints/acceptanceCriteria/deliverables。
- 解析容错：`EngineResponseParsers.parseExecutionPlan` 失败回退 `fallbackPlan()`（单步计划）。
- **缺口**：Plan 步骤执行状态（已完成/失败/跳过）不持久化、不可恢复；`StepStart` 事件无 stepId；用户拒绝 Plan = `Aborted`（无"重新规划"路径）。

### 6.3 Todo 工具

**不存在**任何 todo 工具（对照 hermes-agent 的 todo_tool 概念）。T76 若需要"任务内待办"语义，应建在 Task/Step 模型上而不是新造一个 todo 工具（避免平行系统）。

---

## 7. 当前 UI（Agent 聊天界面）

| 文件 | 规模 | 职责 | 与 T76 相关现状 |
|---|---|---|---|
| `app/.../ui/screen/agent/AgentChatViewModel.kt` | **1123 行（逼近 1200 门禁！）** | 引擎驱动 + 全部 UI 状态 | 裸 `collect` 消费 `AgentEvent`；`handleEvent` 500-789 行覆盖 21 种事件；节流刷新（工具 16ms/流式 33ms）；`abort()` 有部分产物打捞逻辑（快照部分回复落为 isPartial 消息 + "⏹ 已中止"）——**说明 VM 层已在补偿 abort 的事件送达问题** |
| `.../AgentChatScreen.kt` | 565 行 | 聊天界面 | 有：消息列表、流式气泡、运行中工具卡（含进度条）、Plan/Spec 确认卡、用户输入对话框、错误块（可重试=重发上一条用户消息）、Stop 按钮（isLoading 时）、顶部上下文仪表盘（ContextMeterBar 触发 compressNow） |
| `.../AgentChatMessages.kt`/`AgentChatToolCards.kt`/`AgentChatPlanCards.kt`/`AgentChatDialogs.kt`/`AgentUiModels.kt` | — | 消息/卡片/对话框组件化拆分 | 组件拆分习惯良好，T76 的 TaskStatusCard 照此模式新增文件即可 |

**无**：暂停/恢复按钮、任务进度条（step x/y）、崩溃恢复入口（重启后发现未完成任务）、任务历史列表、retry 失败任务入口。
**关键约束**：VM 已 1123/1200 行——T76 的 VM 改动必须以**新增独立文件**（如 `AgentTaskStatusController.kt` 或把 Task 状态做成独立 StateFlow 源由 VM 聚合）方式做，不能往 VM 里继续堆。

---

## 8. 当前 DI（Hilt）

全部 module（`app/.../di/`）：`AgentModule`、`LlmModule`、`ToolModule`、`AppModule`、`AttachmentModule`、`GithubModule`、`CsMemObserverModule`(@Binds)、`SkillModule`、`McpModule`、`TerminalModule`（360 行，**T76 禁区**）。

T76 相关绑定现状：
- `AgentEngine → ApexAgentEngine`（AgentModule L113-140）——**app 实际执行路径**
- `TaskOrchestrator → DefaultTaskOrchestrator`（L157-185，delegate=上述引擎）——**零消费者**
- `ConversationMemory → SharedPrefsConversationMemory`（L78-84）
- `ContextCompressor → HybridCompressor`（L90）
- `AgentConfig ← SettingsRepository 一次性快照`（L39-76，KDoc 自述"设置变更需重启生效"）
- `LlmClient → DynamicLlmClient`（LlmModule L31，模型配置热更新）
- `ExecutionMemoryObserver → CsMemSessionObserver`（@Binds）

**T76 接线结论**：`AgentModule` 是新增 `TaskRuntime`/`TaskStore` 绑定的落点；是否把 `AgentEngine` 绑定换成 TaskRuntime 包装（对外仍实现 AgentEngine 接口保持 VM 兼容）是核心架构决策（见 §10 D-2）。

---

## 9. 当前测试

| 模块 | 测试数 | 覆盖 | CI 是否运行 |
|---|---|---|---|
| core:agent-engine | **76**（OrchestratorTestSuite 29 + OrchestratorResilienceTestSuite 44 + RoleRoutingGoldenTest 3 含 1 @Ignore） | 状态机迁移、成功路径、失败传播、取消、超时、进度/事件、失败分类、重试策略、循环检测、重试集成、循环恢复集成、依赖图、并行执行 | ✅ `:core:agent-engine:test` |
| core:tool-registry | 31 | executor 流式包装、SafeAgentTool、浏览器脚本/DOM 解析 | ✅ |
| core:llm-adapter | 69 | **仅 runtime/ 包**（DefaultModelRuntime/Validator/ErrorClassifier/RoleRouter/CapabilityResolver/Registry）；`StreamingOpenAiClient` SSE 解析零单测 | ❌ **不在 CI** |
| app | 37 | slash 解析/路由、browser tracer/RetryPolicy | ❌ **不在 CI**；无 VM 测试 |
| app androidTest | **不存在** | — | — |
| platform:terminal | ~961 JVM + 5 个 instrumentation 文件（39 test） | Terminal/PRoot/Ubuntu 全链（含真实 rootfs E2E） | ✅（单测）；instrumentation 仅编译 |
| 其他（platform:persistence、cs-mem、plugin-sdk、plugins） | 0 | — | — |

**现成 Fakes**（`core/agent-engine/src/test/.../orchestrator/`）：`FakeLlmClient`（脚本化响应序列+callLog）、`FakeToolExecutor`（脚本化工具+注册成功/流式/错误/抛异常/延迟+callLog）、`FakeToolRegistry`、`FakeConversationMemory`（内存 store+计数器+snapshot）。**T76 测试矩阵的 Fake 基建已就绪**——任务书 §18K 要求的 FakeModelClient+FakeToolRegistry 完整跑通，现有 73 个 orchestrator 测试就是模板。

**缺口**：真实持久化集成测试不存在（`SharedPrefsConversationMemory` 零测试）；进程死亡只能用确定性测试 harness 模拟（任务书 §23 已预期，不可伪造）。

**CI 工作流**（`.github/workflows/`）：`ci.yml`（static-analysis 手工 kotlinc 编译 core 四模块 + 工具 id 唯一性 + 括号平衡 + PRoot sha256；app-compile 含 terminal 测试与 agent-engine/tool-registry 测试；build-apk）；`quality-gate.yml`（文件行数 1200/1600 门禁 + 反反射/printStackTrace 检查）；`pr-labeler.yml`。**仓库无 gradlew wrapper**（CI 现场生成 8.10）；无 ktlint/detekt。

---

## 10. T76 需要新增什么（缺口清单 → 新增交付物）

按任务书语义逐条对照，**当前代码完全缺失、必须新建**的能力：

| # | 缺口 | 新增交付物（建议） | 落点 |
|---|---|---|---|
| N-1 | Task/Execution/Step/Operation 四级持久化模型 + 稳定 ID | `TaskModels.kt`：`AgentTask`（taskId/title/userInput/mode/configSnapshot/status/timestamps/retryCount/error/conversationRef）、`TaskExecution`（executionId/taskId/状态/当前 step）、`TaskStep`（stepId/status：PENDING/RUNNING/DONE/FAILED/SKIPPED）、`ToolOperation`（operationId/executionId/stepId/toolName/args/状态：NOT_STARTED/RUNNING/SUCCEEDED/FAILED/UNKNOWN） | `core/agent-engine/.../task/` 新包（纯 JVM） |
| N-2 | 持久化 TaskStore（原子写 + schema 版本 + 损坏隔离） | `TaskStore` 接口（纯 JVM，saveCheckpoint/loadActiveTasks/loadTask/markCorrupt）+ `FileTaskStore` 实现（temp→fsync→rename；`filesDir/taskstore/<taskId>.json`；schemaVersion=1；损坏移 `corrupt/`） | 接口在 core，实现在 app |
| N-3 | 状态机合法迁移校验 | `TaskStatusMachine`：状态枚举扩展（PENDING/PLANNING/RUNNING/WAITING/PAUSED/RECOVERING/COMPLETED/FAILED/CANCELLED）+ 显式迁移表 + `IllegalTaskTransitionException`；**注意与 A68 TaskState（运行时瞬时态）是两层**：持久层状态（粗粒度、可恢复）vs 运行时状态（细粒度、A68 已有），映射关系要显式定义 | `core/agent-engine/.../task/` |
| N-4 | Checkpoint（生命周期边界持久化） | `TaskCheckpoint`（task state/execution state/当前 step/已完成 steps/操作 journal 摘要/retryCount/latestError/model turn info/contextRef/timestamps）+ 保存边界常量表（Task 创建/Plan 完成/Step 开始/ToolCall 开始/ToolCall 完成/Step 完成/turn 完成/压缩/错误/恢复/完成——**不按 token**） | 同上 |
| N-5 | Crash Recovery + Recovery Policy | 启动时 `discoverRecoverable()`（找 RUNNING/WAITING 态 Task）→ 对每个 UNKNOWN 操作按策略决策：retry/verify/skip/fail/ask-user；**修补悬空 toolCall 历史**（见 R-5）后重建引擎上下文 | `task/RecoveryPolicy.kt` + app 层启动发现 |
| N-6 | Pause / Resume / Cancel 持久语义 | Pause=安全边界后落盘 PAUSED；Resume=加载 checkpoint→重建→继续当前 step（不从头）；Cancel=落盘 CANCELLED 且重启不自动继续（与 Pause 的关键区别写入状态机迁移表） | `task/TaskRuntime.kt` |
| N-7 | Tool 幂等性分类表 | `ToolExecutionPolicy`（toolId→READ_ONLY/IDEMPOTENT_WRITE/NON_IDEMPOTENT/UNKNOWN + 默认恢复动作），覆盖实际注册的 80 个工具逐一归类，UNKNOWN 默认 ask-user/verify | `task/ToolExecutionPolicy.kt` |
| N-8 | 单活跃执行互斥 | `single active execution per task`：执行权获取（原子标记 execution.state=RUNNING 于 store，或进程内 Mutex+store 校验双保险）；"并发两次 Resume 只允许一个成功"测试 | `TaskRuntime` |
| N-9 | 上下文压缩兼容 | 压缩后重注入 Task State system 消息（受保护位）；checkpoint 记录 contextRef；压缩不丢 Task/Step/Execution 状态 | 扩展 `HybridCompressor` 或引擎压缩后钩子 |
| N-10 | 引擎层最小挂钩 | ApexAgentEngine 增加（a）事件回调/拦截点供 TaskRuntime 记录 checkpoint（ToolCallStart/Complete、StepStart 等）；(b) 恢复入口（以 checkpoint 上下文续跑，含悬空修补）；**不重写主循环** | 修改 `ApexAgentEngine`（控制在门禁内，见 R-2） |
| N-11 | UI：任务状态卡 + 恢复入口 | `TaskStatusCard.kt`（title/状态/当前 step/进度 Step x/y/retry/error/操作按钮 Pause/Resume/Cancel/Retry）+ 重启后"发现未完成任务"横幅（继续/取消/查看） | `app/.../ui/screen/agent/` 新文件 + VM 新控制器文件 |
| N-12 | 可观测性贯通 | 把 `LlmRequestContext.taskId/stepId`（字段已存在，当前全传 null）真正填上；OrchestratorLog 结构化日志带四元 ID；日志脱敏（不落 prompt 全文/API key） | TaskRuntime 填参 |
| N-13 | 测试矩阵（任务书 §18 A-K 全项） | 状态机合法/非法迁移、持久化 roundtrip、崩溃恢复 harness（确定性模拟：写 checkpoint 到"Step2 RUNNING"后新建 runtime 恢复）、UNKNOWN 工具操作、retry 边界、pause-restart-resume、cancel-restart 不自愈、幂等（重复 complete step 结果一致）、并发 resume、压缩前后状态一致、FakeModel+FakeTools+**真实 FileTaskStore** 全链集成测试 | 各模块 test 目录 |
| N-14 | CI 接入 | 新测试任务进 `ci.yml`；顺带补 `:core:llm-adapter:test` 与 `:app:test` 的既有盲区（PR 描述中如实说明哪些是本任务新增覆盖、哪些是既有盲区补救） | `.github/workflows/ci.yml` |
| N-15 | 架构文档 | `docs/task-execution-architecture.md`（Task/Execution/Step/Operation/Checkpoint/状态机迁移图/Recovery/Retry/Pause/Cancel/压缩兼容/Session 关系） | docs/ |

## 11. T76 可以复用什么（严禁重造清单）

1. **A68 orchestrator 全套**（§6.1 表）：状态机宿主、进度、生命周期事件通道、RetryPolicy（含预算/退避/抖动）、FailureClassifier、LoopDetector、RecoveryPlanner、ToolCallRunner（超时+重试单元）、ToolCallGraph/BatchExecutionEngine（并行）、UserInteractionGate、TaskOrchestratorConfig（快照语义）。
2. **测试 Fake 四件套** + 73 个 orchestrator 测试模式（任务书 §18K 的直接模板）。
3. `ConversationMemory` 接口与现有实现（Task 引用它，不改）。
4. `HybridCompressor` 三层压缩（只加任务状态保护，不重写）。
5. `LlmRequestContext.taskId/stepId`（已预留）。
6. `PlanStep.dependsOn` + `ToolCallGraph`（Step 依赖语义已有先例）。
7. `SettingsRepository` 的版本化 key+迁移模式（TaskStore schema 演进参照）。
8. `AppLogger`/`OrchestratorLog`/`EngineLogExtensions`（结构化日志基建已存在）。
9. `AgentEvent` 21 事件体系（**冻结不扩展**，任务关联走 TaskLifecycleEvent）。
10. UI 组件化拆分模式（AgentChatToolCards 等文件即模板）。
11. `quality-gate` 行数门禁（作为实现纪律的强制约束来用）。

## 12. T76 不应该碰什么（禁区清单）

- **`platform:terminal` 全模块 + `terminal-emulator` + `TerminalModule`（DI）+ Terminal v2 的 16+4 个工具注册**——Terminal/PTY/PRoot/Ubuntu/LinuxRuntime/ExecutionBackend 一律不动（任务书 §29）。
- `core/llm-adapter` 的 `StreamingOpenAiClient` SSE 解析与 `runtime/` 多模型路由内部（只在调用侧填 taskId/stepId 参数）。
- `platform/cs-mem` 内部（MemoryGraphStore/BypassEngine）；`plugin-sdk`/`plugins`（桥接 TODO 是既知断链，记录不顺手修）。
- `AgentEvent` sealed 层级（A68 冻结原则）；`ConversationMemory` 接口签名。
- UI 整体架构（drawer 导航、屏幕结构）；`MainActivity`/`ApexApp` 初始化链（恢复发现入口放 Agent 界面 ViewModel init 或 ApexApp 的轻量检查，不改启动时序）。
- `platform:persistence` 死代码（PersistenceEngine/WatchdogWorker）——**不激活、不基于它建恢复**（避免复活第二套后台框架）；如需后台触发恢复，用 WorkManager 一次性 expedited work 独立评估，v1 可以只做界面入口发现。
- 既知但与本任务无关的断链（Skill 链路、Plugin 桥接、死代码文件）——只记录，不顺手修。

## 13. 预计修改文件（实施蓝图）

**新增（纯 JVM，core/agent-engine/src/main/kotlin/com/apex/agent/core/engine/task/）**
`TaskModels.kt`（Task/Execution/Step/Operation+枚举）、`TaskStatusMachine.kt`（迁移表+校验）、`TaskStore.kt`（接口+Checkpoint 数据类）、`RecoveryPolicy.kt`、`ToolExecutionPolicy.kt`（80 工具分类表）、`TaskRuntime.kt`（总装：驱动引擎/存 checkpoint/处理 pause-resume-cancel-retry/单执行互斥/事件桥接 TaskLifecycleEvent）。

**新增（app）**
`di/FileTaskStore.kt`（原子文件实现）、`ui/screen/agent/AgentTaskStatusController.kt`（VM 侧任务状态控制器，避免 VM 超行数）、`ui/screen/agent/TaskStatusCard.kt`（含 Pause/Resume/Cancel/Retry）、`ui/screen/agent/TaskRecoveryBanner.kt`（重启发现未完成任务）。

**修改（最小化）**
`ApexAgentEngine.kt`（事件挂钩+恢复入口，**净增控制在 ~140 行内以避开 1200 门禁**，必要时把 prompt 构造再外移）；`AgentModule.kt`（+TaskRuntime/TaskStore 绑定）；`AgentChatViewModel.kt`（改为经控制器消费任务状态，**行数只减不增**）；`AgentChatScreen.kt`（挂两个新组件）；`HybridCompressor.kt`（任务状态保护位）；`.github/workflows/ci.yml`（测试任务）；`docs/task-execution-architecture.md`（新文档）。

**测试新增**
core：`TaskStatusMachineTest`（合法+非法迁移矩阵）、`TaskStoreRoundtripTest`（真实文件 IO roundtrip+损坏+版本）、`CrashRecoveryTest`（确定性 harness）、`ToolExecutionPolicyTest`、`TaskRuntimeIntegrationTest`（FakeModelClient+FakeToolExecutor+**真实 FileTaskStore(tmpdir)** 全链：plan→tool→checkpoint→模拟死亡→恢复→完成）、`ConcurrentResumeTest`、`PauseResumeCancelTest`、`CompressionCompatTest`、`IdempotencyTest`。

## 14. 风险

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R-1 | **架构分叉**：app 走 ApexAgentEngine 直连路径，A68 orchestrator（含 BUILD 自有 history）未通电。若 TaskRuntime 建在 orchestrator 上而 app 不切换，就造出第三套平行系统 | 高 | 决策 D-2（见下）二选一后严格执行；无论哪条路，`AgentEngine` 接口与 VM 消费方式保持兼容 |
| R-2 | **行数门禁**：ApexAgentEngine 1057/1200、AgentChatViewModel 1123/1200，几乎没有加码空间 | 高 | 新逻辑全部进新文件；引擎只加挂钩；VM 拆控制器；PR 前本地跑 `check_file_size.sh` |
| R-3 | **T76 编号冲突**：`t76/linux-environment-productionization` 已存在且已合并 | 中 | 分支用 `t76/agent-task-runtime`；PR 标题与描述明确区分；文档注明历史编号占用 |
| R-4 | **SharedPrefsConversationMemory O(n) append + 非原子**：checkpoint 若叠加消息写入会放大写放大 | 中 | TaskStore 独立文件存储、按边界批写；不动 ConversationMemory |
| R-5 | **悬空 toolCall 缺陷**（现存）：进程死于工具执行中 → 持久历史 Assistant.toolCalls 无配对 ToolResult → 重启后 LLM 请求 400 | 高 | Recovery 必含"历史修补"步骤：扫描 dangling toolCallId → 合成 `ToolResult("interrupted, outcome UNKNOWN")`；该项单独写测试；文档记录为"发现并修复的既有缺陷" |
| R-6 | **CI 盲区**：llm-adapter/app 测试不在 CI；app-compile continue-on-error | 中 | T76 PR 中把新测试接入；PR 描述如实区分"新增覆盖"与"既有盲区"，不把 skipped/compile 写成 passed |
| R-7 | **无 gradlew wrapper**：本地复现 CI 需自装 Gradle 8.10 | 低 | 审计环境已验证克隆可行；实施时按 ci.yml 方式生成 wrapper |
| R-8 | **TaskState 双层语义混淆**：A68 运行时态（Planning/Acting...）与 T76 持久态（PAUSED/RECOVERING...）若混在一层会把状态机搅乱 | 高 | 文档显式定义两层映射（持久态=粗粒度聚合，运行态=瞬时细化）；迁移合法性各自独立校验 |
| R-9 | **压缩丢任务状态**：Layer2 滑窗会把中间消息全换掉 | 高 | 压缩后重注入受保护 Task State 消息 + checkpoint 存 contextRef（§5/N-9） |
| R-10 | **真机进程死亡不可在 CI 复现** | 中 | 用确定性 harness（构造"死亡现场"快照→新 Runtime 实例恢复）验证恢复逻辑；文档如实声明"模拟死亡"，不声称真机 E2E |
| R-11 | **A68 orchestrator 的 BUILD history 与引擎 history 分叉**：若走切换路线，两套 history 追加行为不一致（orchestrator 按"任务完成时持久化"） | 中 | 决策记录进 ADR；切换路线需统一 memory 追加点 |

## 15. 最终实施顺序（9 个逻辑提交，对应任务书 §25）

1. **audit/architecture**：本审计报告入库（`docs/`）+ ADR（含 D-1~D-4 四个决策的最终选择与理由）。
2. **task state model**：TaskModels + TaskStatusMachine（含非法迁移异常）+ 单测（迁移矩阵全测）。
3. **persistence**：TaskStore 接口 + FileTaskStore（原子写/版本/损坏隔离）+ ConversationMemory 悬空修补工具 + roundtrip/损坏/版本单测。
4. **execution/checkpoint**：TaskRuntime 骨架 + Checkpoint 边界常量表 + 引擎事件挂钩 + journal 记录 + 单测。
5. **recovery/retry/pause/cancel**：RecoveryPolicy + UNKNOWN 处理 + RetryPolicy 持久化包装 + Pause/Resume/Cancel 语义 + 并发互斥 + 状态机/恢复/幂等/并发全测。
6. **agent loop integration**：TaskRuntime 正式驱动引擎执行（含恢复入口、LlmRequestContext 四元 ID 填充、压缩兼容注入）+ Fake 全链集成测试（真实 FileTaskStore）。
7. **ui**：TaskStatusCard + RecoveryBanner + VM 控制器接线（行数门禁自检）。
8. **tests 补全 + CI**：任务书 §18 A-K 矩阵查漏 + ci.yml 接入 + 全量回归（terminal 961 测试不回归）。
9. **documentation**：`docs/task-execution-architecture.md`（状态迁移图）+ T76_FINAL_REPORT.md + PR。

---

## 附：四个必须先拍板的架构决策（提交给任务发起人确认）

- **D-1 存储技术**：推荐文件式 JSON + 原子 rename（`filesDir/taskstore/`），不用 SharedPreferences（O(n) 非原子）、不引入第二个 Room DB（符合任务书 §21"优先当前架构、勿引入大型数据库"）。备选：DataStore——多一层依赖收益有限。
- **D-2 执行路径**（最关键）：**推荐方案 B**——TaskRuntime 作为独立层叠加在 `AgentEngine` 之上（组合而非继承），DI 把 `AgentEngine` 绑定换成 TaskRuntime（其实现 AgentEngine 接口，VM 零改动即可获得任务能力），内部委托 ApexAgentEngine。理由：避免推倒 A68 orchestrator 与引擎的 history 分叉（R-11）、VM/接口全兼容、引擎改动最小。备选方案 A（app 切换到 DefaultTaskOrchestrator 再加持久化）——要统一两套 BUILD 循环与 memory 行为，改动面与回归风险大得多。
- **D-3 恢复触发**：v1 用 Agent 界面 ViewModel init 时的确定性发现（可测、零后台依赖）；WorkManager 一次性 expedited 恢复触发作为 v1.1 可选增强（不复活 PersistenceEngine 死代码）。
- **D-4 多任务**：v1 支持持久化任务历史 + **单活跃任务**（与现有单引擎单 history 现实一致），模型上 taskId 一等公民、不加单任务假设到存储 schema（未来扩展不动 schema）——符合任务书 §17 的"第一版单活跃+历史"定位。

> 本报告基于 main@1849ca8 全量源码审计。所有"不存在"结论均经全仓 grep 验证。下一步：等待任务发起人对 D-1~D-4 的确认（或按推荐默认值），随后进入 Phase B。
