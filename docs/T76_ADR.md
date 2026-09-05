# ADR: T76 Agent Task Runtime — 架构决策记录

> 状态：已决（按审计推荐默认值执行）
> 关联：`docs/T76_ARCHITECTURE_AUDIT.md`（Phase A 全量源码审计，main@1849ca8）
> 任务：T76 — Agent Task Runtime / 长任务执行体系

## 背景

Phase A 审计（见关联文档）确认：仓库已有 A68 orchestrator 全家桶（纯内存、
单次运行、零消费者）、ApexAgentEngine 单例引擎（全局单条对话历史、
SharedPrefs 持久化非原子）、P7 三层上下文压缩。完全缺失：持久化 Task 模型、
Checkpoint、崩溃恢复、Pause/Resume/Cancel/Retry 持久语义、工具幂等性分类。
四个必须先拍板的架构决策如下。

## D-1 存储：文件式 JSON + 原子 rename（已决）

- **选择**：`filesDir/taskstore/<taskId>.json`，temp 文件写入 → fsync →
  原子 rename；schema `version` 字段 + `ignoreUnknownKeys` 宽容解析；
  损坏文件移入 `corrupt/` 隔离（不删除）。
- **理由**：checkpoint 频率（每个 ToolCall 边界）远高于 SharedPreferences
  O(n) 读-改-写可承受范围；不引入第二个 Room DB（符合任务书 §21"优先
  当前架构"）；`SettingsRepository` 的版本化 key 模式是先例。
- **否决**：SharedPreferences（非原子、O(n)）、Room（引入第二数据库）、
  DataStore（多一层依赖收益有限）。

## D-2 执行路径：TaskRuntime 叠加层（方案 B，已决，含一处修正）

- **选择**：`TaskRuntime` 作为独立服务组合在 `AgentEngine` 之上（组合非
  继承），从引擎事件流推导 checkpoint 边界并落盘。
- **审计后修正**：审计原建议"DI 把 AgentEngine 绑定换成 TaskRuntime"，
  但 VM 存在 15 处 `(agentEngine as? ApexAgentEngine)` cast——替换绑定会使
  全部 cast 静默失效（updateConfig/compressNow/clearHistory/plan 确认桥全断）。
  **最终方案：AgentEngine 绑定保持 ApexAgentEngine 不动；TaskRuntime 作为
  独立 @Singleton 注入 VM 侧新增的 AgentTaskStatusController**。VM 的
  execute/abort 调用点改经 controller 转发（3 处一行改动），事件消费零改动。
- **理由**：避免 A68 BUILD 自有 history 与引擎 history 分叉（R-11）；
  VM 15 处 cast 全兼容；引擎主循环不重写；未来若引入多 Session 也不受影响。

## D-3 恢复触发：ViewModel init 确定性发现（已决）

- **选择**：v1 在 Agent 界面 ViewModel 初始化时调
  `TaskRuntime.discoverRecoverableTasks()`（纯文件扫描，确定性、可测、
  零后台依赖），UI 横幅呈现"继续/取消"。
- **理由**：不复活 `platform:persistence` 死代码（PersistenceEngine/
  WatchdogWorker 全仓库零调用，审计 §3.1）；WorkManager 一次性 expedited
  恢复触发列为 v1.1 可选增强，不在本任务范围。

## D-4 多任务：模型一等 + v1 单活跃（已决）

- **选择**：taskId 为一等公民（存储 schema 不含单任务假设）；v1 运行时
  一次只允许一个活跃执行（single active execution per task × 单活跃任务，
  与现有单引擎单 history 现实一致），提供持久化任务历史查询。
- **理由**：符合任务书 §17"第一版单活跃 + 历史记录"定位；未来扩展多任务
  不动存储 schema。

## 悬空 toolCall 修复（审计 R-5，随本任务交付）

进程死于工具执行中 → 持久历史 Assistant.toolCalls 无配对 ToolResult →
重启后 LLM 请求 400（OpenAI 兼容 API 校验失败）。恢复流程必含"历史修补"
步骤：扫描 dangling toolCallId → 合成 `ToolResult("interrupted, outcome
UNKNOWN")`。此为**发现并修复的既有缺陷**，单测覆盖（DanglingToolCallRepairTest）。

## 两层状态模型（审计 R-8）

- **持久层**（本任务新增 `task/TaskStatusMachine`）：PENDING/PLANNING/
  RUNNING/WAITING_USER/PAUSED/CANCELLING/RECOVERING/RETRYING/COMPLETED/
  FAILED/CANCELLED——显式迁移表，非法迁移抛异常。
- **运行时层**（A68 既有 `orchestrator.TaskStateMachine`）：Planning/Acting/
  Observing…——单次进程内瞬态细化，不落盘，行为不变。
- 两层各自独立校验，文档显式给出映射表（见
  `docs/task-execution-architecture.md`）。

## 事件模型冻结

`AgentEvent` sealed 层级冻结不扩展（A68 设计原则）。任务关联走
`TaskRuntimeEvent`（低频生命周期通道，对应 A68 TaskLifecycleEvent 模式）。
UI 的 AgentEvent 消费逻辑零改动。
