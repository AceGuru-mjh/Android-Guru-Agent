# T76: Agent Task Runtime and Recovery

> 分支 `t76/agent-task-runtime` ← main@1849ca8 · 9 commits · 121 tests green (73 A68 existing + 48 T76 new)
> 注：编号 T76 曾用于已合并的 `t76/linux-environment-productionization`（Linux 环境生产化）；本 PR 为任务书指定的 **Agent Task Runtime** 工作线，与历史编号无关。

## 概述

将 Agent 从「一次性 execute() → ReAct 循环」升级为**可持久执行、可恢复、可压缩上下文、可追踪进度**的长任务系统。前置 Phase A 全量源码审计（`docs/T76_ARCHITECTURE_AUDIT.md`，6600+ 行逐文件 + A68 17 文件语义核实），四个架构决策见 `docs/T76_ADR.md`。

**叠加层设计**（不重写引擎、不替换 AgentEngine 绑定、AgentEvent 冻结不扩展）：

- **Task/Execution/Step/Operation 四级持久化模型**（kotlinx.serialization，单任务单文件）
- **11 态显式状态机**（迁移表全矩阵测试，非法迁移抛异常；与 A68 运行时态两层独立）
- **Checkpoint 只在生命周期边界落盘**（14 类边界，绝不按 token）；原子写 = tmp → fsync → rename；损坏隔离 `corrupt/`；schema v1 宽容解析
- **Pause/Resume/Cancel/Retry** 持久语义（PAUSED 可续 vs CANCELLED 绝对终态重启不复活；retry 上限 3 + RETRYING 瞬态；CANCELLING 协作式瞬态）
- **崩溃恢复**：启动发现 → 悬空 toolCall 修补 → UNKNOWN 操作幂等决策（80 工具逐一分类）→ 恢复提示注入续跑
- **压缩兼容**：压缩边界重注入受保护任务状态消息 + historyAnchor 锚点（Layer2 滑窗不丢任务进度）
- **四元 ID 贯通**：taskId/stepId 填入 LlmRequestContext 全部 7 个构造点（T72 预留字段此前恒 null）
- **单活跃执行互斥**：AtomicBoolean CAS，并发 resume/retry/execute 只许一个
- **UI 最小改动**：TaskStatusCard（Step x/y 真实进度 + 上下文按钮）、TaskRecoveryBanner（重启恢复入口）、AgentTaskStatusController（VM 1189/1200 行门禁内）

## 发现并修复的既有缺陷

1. **悬空 toolCall 致历史不可用**：进程死于工具执行中 → 重启后 LLM 请求 400（OpenAI tool_calls 配对校验）→ 整条对话不可用。修复：恢复时合成 UNKNOWN ToolResult（配对不变量 + 幂等，8 个专项测试）。
2. **CI 编译缺口**：static-analysis 的 kotlinc 未加载 kotlinx-serialization 编译插件——本 PR 的 `serializer()` 调用会挂 CI，已补 `-Xplugin`。

## 测试矩阵（任务书 §18 A–K 全项）

| 项 | 覆盖 |
|---|---|
| A 状态机 | 121 组合全矩阵 + 自环拒绝 + 终态无出边 |
| B 持久化 | roundtrip / 未知字段宽容 / 损坏隔离不阻塞 / temp 清理 / 路径穿越 |
| C checkpoint | 工具 journal 成功失败 / 步骤推导 / 落盘读回 |
| D pause-restart-resume | PAUSED 落盘 → resume 续跑 → journal 连续 |
| E UNKNOWN 操作 | RUNNING 中断 → RETRY/VERIFY 决策矩阵 |
| F 崩溃恢复 | simulateCrash（SIGKILL 语义）→ 新实例发现 → 恢复完成 |
| G cancel 不自愈 | CANCELLED 终态 → 发现扫描排除 |
| H 并发互斥 | 双 resume 单胜 / 并发 execute 拒绝 |
| I 幂等 | 已成功操作绝不重放（反证设计） |
| J 压缩兼容 | 重注入 + 步骤跨压缩保留 + 压缩后崩溃恢复 |
| K Fake 全链 | FakeLlmClient + FakeToolExecutor + **真实 FileTaskStore** 完整闭环 |

**如实声明**：
- 崩溃恢复为**确定性模拟**（`simulateCrash()` = SIGKILL 语义短路 finally + 新实例共享 store 模拟重启；任务书 §23 预期此方式），未做真机 E2E。
- app 层（Compose/DI/VM）本地无 Android SDK 未编译——按 CI app-compile job 验证；**既有盲区不变，未新增盲区**（llm-adapter/app 测试不在 CI 是历史现状，PR 不顺手扩大范围）。
- 121 tests 为本地 kotlinc 2.0.21 复刻 CI 编译链验证；CI 用 Gradle `:core:agent-engine:test` 执行（新测试类自动纳入）。

## 禁区遵守（任务书 §29 零改动清单）

platform:terminal 全模块 · terminal-emulator · TerminalModule · Terminal v2 工具 · llm-adapter SSE/runtime 内部 · cs-mem 内部 · plugin-sdk/plugins · AgentEvent sealed 层级 · ConversationMemory 接口 · UI 整体架构 · MainActivity/ApexApp 启动链 · platform:persistence 死代码（未激活）。引擎净 +45 行（1200 门禁内）。

## 文档

- `docs/T76_ARCHITECTURE_AUDIT.md` — Phase A 全量审计
- `docs/T76_ADR.md` — D-1~D-4 架构决策（含 D-2 审计后修正：不改 AgentEngine 绑定）
- `docs/task-execution-architecture.md` — 状态机迁移图 / 恢复流程 / 幂等决策矩阵 / 压缩兼容 / 测试映射
- `docs/T76_FINAL_REPORT.md` — 18 项交付清单 + 已知限制

## 提交序列

1. `d77b49d` docs: Phase A audit + ADR
2. `2ebb24f` feat: task state model + status machine (N-1/N-3)
3. `366b412` feat: TaskStore + atomic writes + dangling repair (N-2/R-5)
4. `b94a930` feat: TaskRuntime + recovery/pause/cancel/retry (N-4..N-12)
5. `3523a2f` feat: compression compat + LLM context tags (N-9/N-12)
6. `0fb143a` feat: UI wiring (N-11)
7. `167e5c3` ci: serialization plugin in static-analysis (N-14)
8. `94a198c` docs: architecture + final report (N-15)
9. `d0c96d9` fix: brace-balance compliance for CI gate
