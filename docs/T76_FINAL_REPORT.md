# T76 Final Report — Agent Task Runtime / 长任务执行体系

> 分支：`t76/agent-task-runtime`（自 main@1849ca8）
> 前置：`docs/T76_ARCHITECTURE_AUDIT.md`（Phase A 全量源码审计）· `docs/T76_ADR.md`（D-1~D-4 决策）
> 架构：`docs/task-execution-architecture.md`（状态机/恢复/压缩兼容全景）

## 交付清单

| # | 交付物 | 文件 | 状态 |
|---|---|---|---|
| 1 | 持久化任务模型（Task/Step/Operation + 配置快照 + journal 限制） | `core/.../task/TaskModels.kt` | ✅ |
| 2 | 状态机（11 态显式迁移表 + 非法迁移异常 + 崩溃入口降级） | `task/TaskStatusMachine.kt` | ✅ 121 组合全测 |
| 3 | TaskStore（原子写 fsync+rename / schema v1 宽容 / 损坏隔离 / temp 清理 / 路径穿越防护） | `task/TaskStore.kt` + `task/FileTaskStore.kt` | ✅ 真实文件 IO 测试 |
| 4 | Checkpoint（14 类生命周期边界，绝不按 token） | `task/TaskRuntime.kt` | ✅ |
| 5 | Pause/Resume/Cancel/Retry（持久语义 + retry 上限 + CANCELLING 瞬态） | `task/TaskRuntime.kt` | ✅ D/G 场景测试 |
| 6 | 崩溃恢复（发现 + 悬空修补 + 幂等决策 + 恢复提示注入） | `task/RecoveryPolicy.kt` + TaskRuntime | ✅ F/E/I 测试 |
| 7 | 工具幂等分类表（80 工具逐一归类，未知默认 UNKNOWN） | `task/ToolExecutionPolicy.kt` | ✅ |
| 8 | 悬空 toolCall 修复（**既有缺陷** R-5：OpenAI 400 防线） | `task/DanglingToolCallRepair.kt` | ✅ 配对不变量测试 |
| 9 | 单活跃执行互斥（AtomicBoolean CAS，并发 resume 只许一个） | TaskRuntime | ✅ H 测试 |
| 10 | 压缩兼容（重注入 + historyAnchor + 压缩后崩溃恢复） | TaskRuntime + 引擎挂钩 | ✅ J 测试 |
| 11 | 引擎最小挂钩（injectSystemContext + setLlmExecutionTags，净 +45 行） | `ApexAgentEngine.kt` | ✅ 既有测试零回归 |
| 12 | 四元 ID 贯通（taskId/stepId → LlmRequestContext 全部 7 个构造点） | 引擎 + TaskRuntime | ✅ N-12 测试 |
| 13 | DI 接线（AgentEngine 绑定不动，TaskRuntime 独立注入） | `di/AgentModule.kt` | ✅ |
| 14 | VM 控制器（新文件，VM 1189/1200 行门禁内） | `ui/.../AgentTaskStatusController.kt` | ✅ |
| 15 | 任务状态卡（步骤进度 Step x/y + 状态显隐按钮） | `ui/.../TaskStatusCard.kt` | ✅ |
| 16 | 崩溃恢复横幅（继续/取消，CANCELLED 不复活） | `ui/.../TaskRecoveryBanner.kt` | ✅ |
| 17 | CI 接入（static-analysis 补 serialization 插件——否则必红） | `.github/workflows/ci.yml` | ✅ |
| 18 | 架构文档（迁移图 + 恢复流程 + 禁区清单 + 测试矩阵） | `docs/task-execution-architecture.md` | ✅ 本文档体系 |

## 测试

- **core:agent-engine：121 tests 全绿**（A68 既有 73 + T76 新增 48）：
  - TaskStatusMachineTest 8 · FileTaskStoreTest 11 · DanglingToolCallRepairTest 8
  - TaskRuntimeTest 12（全链：真实 FileTaskStore + FakeLlmClient/FakeToolExecutor）
  - CompressionCompatTest 3 · LlmContextTagIntegrationTest 3
- 本地验证方式：kotlinc 2.0.21 复刻 CI 编译链（含 `-Xplugin=serialization` 与 `-Xfriend-paths`）+ JUnitCore 全量执行；CI 侧 `:core:agent-engine:test`（Gradle）自动纳入新测试类。
- **如实声明**：进程死亡为确定性模拟（`simulateCrash()` = SIGKILL 语义短路 finally + 新实例共享 store 模拟重启），**未做真机 E2E**；app 层（Compose UI/DI/VM）本地无 Android SDK 未编译，按 CI app-compile 验证（既有既知盲区不变，未新增盲区）。

## 禁区遵守（零改动清单）

platform:terminal 全模块 · terminal-emulator · TerminalModule · Terminal v2 工具 · llm-adapter SSE/runtime 内部 · cs-mem 内部 · plugin-sdk/plugins · AgentEvent sealed 层级 · ConversationMemory 接口 · UI 整体架构 · MainActivity/ApexApp 启动链 · platform:persistence 死代码（未激活）。

## 发现并修复的既有缺陷

1. **悬空 toolCall 致历史不可用**（审计 R-5）：进程死于工具执行中 → 重启后 LLM 请求 400。修复：恢复时合成 UNKNOWN ToolResult（配对不变量 + 幂等）。
2. **迁移表缺失**（A68 TaskStateMachine.transitionTo 不校验合法性）：T76 持久层独立建表并全矩阵测试，A68 运行时层行为保持不变（其测试零回归）。

## 提交序列（9 个逻辑提交）

1. `docs(t76)` Phase A 审计 + ADR（D-2 含审计后修正：不改 AgentEngine 绑定）
2. `feat(t76)` 任务状态模型 + 状态机（N-1/N-3）
3. `feat(t76)` TaskStore 持久化 + 原子写 + 悬空修补（N-2/R-5）
4. `feat(t76)` TaskRuntime + 恢复/暂停/取消/重试（N-4~N-12）
5. `feat(t76)` 压缩兼容 + 四元 ID 集成（N-9/N-12，18J）
6. `feat(t76)` UI 接线（N-11）
7. `ci(t76)` static-analysis serialization 插件（N-14）
8. `docs(t76)` 架构文档 + 本报告
9. PR: 「T76: Agent Task Runtime and Recovery」

## 已知限制（v1 边界）

- 单活跃任务（D-4 决策）：并发多任务执行留待 v2（存储 schema 已是一等 taskId，扩容不动 schema）。
- 恢复触发为 VM init 确定性发现（D-3）：WorkManager 一次性 expedited 触发列为 v1.1 可选增强。
- v1 恢复交互收敛为横幅级"继续/取消"；逐 UNKNOWN 操作的 ASK_USER 弹窗未做（决策交 LLM + VERIFY 提示，RecoveryAction 枚举已预留）。
- Plan 模式的步骤执行状态推导依赖 StepStart 切换与 Complete 兜底（AgentEvent 无 StepComplete 事件——冻结原则下不扩展）。
