# Mode System v2 — 六档思考 × Plan 强化 × 终端主动性

> 分支 `feat/mode-system-v2`（里程碑 v1.4.0）。三个 issue 的完整落地记录：
> #168 六档思考模式（4-a）、#169 Plan 模式强化（4-b）、#170 终端主动性（4-b）。
> 三者共用同一套「引擎钩子 + 纯 Kotlin 顾问/规划器 + 提示词分层注入」架构。

## 1. 六档思考模式（#168，Task 4-a）

`ThinkingLevel` 从 5 档纯提示词差异升级为 6 档真实执行策略，新增 `AUTO`
自适应档。核心三件套位于 `core/agent-engine/.../engine/thinking/`：

### 1.1 六档执行画像（ThinkingProfile）

| 档位 | 思考预算 | 迭代倍率 | 压缩阈值 | 输出预算 | 附加策略 |
|------|---------|---------|---------|---------|---------|
| NONE | ×0.8（禁原生推理） | 0.8× | 1.2×（更早压缩） | 6000 | — |
| LIGHT | 256 | 0.9× | 1.1× | 7000 | — |
| STANDARD | 1024 | 1.0× | 1.0× | 8000 | — |
| DEEP | 4096 | 1.2× | 0.95× | 9000 | 工具自检回路 |
| MAXIMUM | 16384 | 1.5× | 0.9× | 10000 | 工具自检 + 终检清单 |
| AUTO | 选档器逐轮决定 | 同所选档 | 同所选档 | 同所选档 | 委托 AdaptiveThinkingSelector |

### 1.2 AUTO 评分维度（AdaptiveThinkingSelector）

复杂度评分（0-10+）→ 阈值映射：<3 LIGHT / 3-5 STANDARD / 6-8 DEEP / >8 MAXIMUM。
评分因子：文本长度、多步指示词、代码/命令信号、风险词（保底 DEEP）、
错误恢复（+2 且显式升一档）；规划期首轮与深水区（>15 次工具调用）保底
STANDARD。决策理由中文字符串全因子可解释（UI 直接展示）。

### 1.3 引擎三钩子（ThinkingModeController）

引擎主循环仅三处接线，策略全部下沉控制器：

1. **onIterationStart**（iteration++ 后）：解析本轮生效档位（AUTO → 选档）；
2. **postToolCheckPrompt**（工具结果写入后）：DEEP/MAXIMUM 档失败/高风险
   工具的自检提示注入（下一轮 LLM 可见）；
3. **finalSelfCheckPrompt / profileFor**（buildSystemPrompt）：MAXIMUM 档
   响应前自评清单经 system prompt Thinking 段下发。

## 2. Plan 模式强化（#169，Task 4-b）

### 2.1 计划锁定语义

```
Phase 1  生成   chatStream（无 tools）→ 规划期只读约束段（planningPhase=true）
Phase 2  解析   EngineResponseParsers.parseExecutionPlan（dependsOn 进入模型）
Phase 3  确认   awaitPlanConfirmationDecision(): PlanDecision（不再是 Boolean）
Phase 3.5 锁定  PlanGraph.lock(plan, enabledSteps, order) → locked（局部 val）
                addMessage(System("📋 计划已锁定：N 步（用户调整…）"))  ← 自动持久化
                emit(PlanConfirmed(locked.plan))
Phase 4  执行   按锁定顺序逐步 executeBuildLoop，StepStart 发锁定序号
Phase 5  反思   只读总结（planningPhase 约束同样生效）
```

**锁定**＝引擎侧 `locked` 为局部不可变快照 + UI 侧确认卡消失、锁定卡只读
（🔒 徽标）。执行期间任何用户操作都不再改变计划；锁定播报写入
ConversationMemory，后续每轮 LLM 请求都能看到这份不可变契约。

### 2.2 人控步骤（确认卡改造，AgentChatPlanCards.kt）

- 每步 **Checkbox**（默认勾选）+ **上移/下移 IconButton**（KeyboardArrowUp/Down）
  + 展示序号；未勾选步骤灰色 + 划线；
- 「执行」按钮保持在卡片**最末**；未勾选任何步骤时禁用；
- 顶部「规划中 · 只读」徽标（planningPhase 约束的 UI 映射）；
- 确认回调 `onConfirm(enabledSteps, order)` 传**原 step.index**；
- 确认后（PlanConfirmed）：PlanCard 显示 🔒 已锁定徽标 + 只读步骤列表，
  执行中当前步 ▶ 高亮、已过步骤暗淡（uiState.currentStepIndex ← StepStart）。

数据流：`AgentChatViewModel.confirmPlan(confirmed, enabledSteps, order)` →
`ApexAgentEngine.submitPlanConfirmation` 三参重载 →
`CompletableDeferred<PlanDecision>`。旧两参签名保留（兼容
ConfirmationSink/orchestrator，等价 `PlanDecision.legacy`）。

### 2.3 拓扑执行（PlanGraph，engine/plan/）

- **topoSort**：Kahn 算法 + 环检测。同层候选按声明顺序消解（无依赖计划
  恒等排列）；环（含自依赖/未知依赖过滤后）→ **整体回退声明顺序** + 警告；
- **applyAdjustments**：用户勾选筛选 + 顺序重排 + 重编号（index = 位置），
  dependsOn 同步重映射；被禁用步骤的依赖移除并附警告；空启用集防御性
  回退全量（附警告）；order 未覆盖的启用步骤按声明顺序追加；
- **lock**：apply → topo → 最终重编号（index = 执行位置，StepStart / 步骤
  提示词 / UI 高亮同源）→ 锁定播报消息（含用户调整摘要与警告）。

用户顺序与依赖冲突时拓扑自动纠正（如用户排 C,B,A 且 B 依赖 A → 执行
C,A,B），纠正属正常行为不算警告；环/禁用依赖才产生警告。

### 2.4 规划期只读显式化（EnginePrompts）

`buildSystemPrompt(..., planningPhase: Boolean = false)`：true 时追加
`## Planning Phase (READ-ONLY)` 段（仅产出计划 JSON、不执行任何工具/写
操作、只读推理）。executePlanMode 的计划生成与反思两处置 true。

## 3. 终端主动性（#170，Task 4-b）

用户反馈：Agent 不会主动用终端 —— 倾向一次性 shell，不建 PTY 会话、
不预备 Ubuntu。双层修复：

### 3.1 静态策略层（EnginePrompts：Terminal-Use Policy）

Tool-Use Policy 之后追加 MANDATORY 段，五条规则：

1. 一次性命令 → `terminal.exec`（结构化 stdout/stderr/exit_code）；
2. 连续 N 次一次性 shell → STOP，切换会话流
   `terminal.create → terminal.run(后台) → terminal.observe/terminal.wait`
   （会话保留 cwd/env/state）；
3. 交互式程序（gh auth login / python REPL / vim / apt 提示）必须
   `terminal.create + terminal.write`；
4. 工具链任务（apt/git clone/npm/pip/cargo/make/gcc）：先查
   `terminal.backends`，Ubuntu 未就绪先 `terminal.ubuntu.ensure` 并向用户
   汇报进度；
5. 失败的一次性命令不得盲目重跑超过两次 —— 检查输出 / 开会话 / 问用户。

### 3.2 动态提醒层（TerminalProactivityAdvisor，engine/terminal/）

轮次级状态机（纯 Kotlin 可测），引擎两钩子：

- `executeToolCallStreaming` 完成分支 → `onToolCallCompleted(toolId,
  success, args)`（滑窗计数）；
- `executeBuildLoop` 迭代开始 → `onIterationStart(lastUserPrompt)`，非空
  Advice 以 System 消息写入历史（下一轮 LLM 可见）。

触发条件（优先级 ENSURE_UBUNTU > SESSION_FLOW > FAILURE_RECOVERY）：

| 类别 | 条件 | 频控 |
|------|------|------|
| SESSION_FLOW | 连续 ≥3 次 shell_execute/terminal.exec（会话流工具出现即清零） | 每攒满 3 次提醒一次（3、6、9…），streak 停滞不重复 |
| ENSURE_UBUNTU | prompt 或近期一次性命令参数含工具链关键词（apt-get/apt install/git clone/npm/yarn/pip/cargo/make/gcc/python3/node/cmake）且本会话未调用过 ubuntu.ensure/install/status/backends | 每会话最多 1 次 |
| FAILURE_RECOVERY | 一次性命令连续失败 ≥2 次（成功即清零） | 连击期间持续，附注可拼进 SESSION_FLOW |

### 3.3 会话可见性与接管（UI 检查结论）

- **Agent 创建的 PTY 会话显示在终端页会话列表**：TerminalViewModel 的
  `refreshSessionsInternal` 直接拉 runtime 快照、不按 owner 过滤；非本 VM
  创建的会话 backendId 标记为 `"agent"`。4-b 补充：SessionChip 对
  backendId=="agent" 显示 **「Agent·」徽标**，与用户会话区分；
- **用户接管输入**：TerminalViewModel 所有写入（键盘/粘贴/Ctrl 组合/特殊键）
  均走 `InputOwner.USER`；Agent 工具侧由 TerminalSdk 统一注入
  `InputOwner.AGENT`（P60 §14：工具不可伪造 USER）—— 现有 owner 机制已
  支持，零改动。

## 4. 三块联动与配置入口

```
AgentChatDialogs（六档选择器）──► AgentChatViewModel.setThinkingLevel
                                        │ patchConfig(thinkingLevel)
                                        ▼
                   ApexAgentEngine ──► ThinkingModeController（#168 三钩子）
                     │  │                     ▲ 依赖 profileFor/planningPhase
                     │  │                     │
                     │  ├──► PlanGraph（#169 Phase 3.5 锁定/拓扑）
                     │  │       ▲ PlanDecision ← confirmPlan(enabledSteps, order)
                     │  │       │                （AgentChatPlanCards 人控）
                     │  └──► TerminalProactivityAdvisor（#170 两钩子）
                     │          ▲ 工具完成/迭代开始
                     ▼          │
                EnginePrompts.buildSystemPrompt
                （Tool-Use + Terminal-Use Policy + Thinking 段 + Planning Phase 段）
```

联动关系：

- **思考档位 × Plan**：PlanningMode 首轮保底 STANDARD（AUTO 选档器）；
  档位迭代倍率决定每步 executeBuildLoop 的实际上限；DEEP/MAXIMUM 的
  工具自检回路在计划步骤执行期照常生效；
- **Plan × 终端**：计划步骤的工具调用同样经过顾问滑窗 —— 执行期连续
  一次性 shell 会在下一步开始前收到会话流建议；工具链类计划（编译/安装）
  的首步前收到 Ubuntu 预备建议；
- **配置入口**：思考档位 = 聊天页对话框（AgentChatDialogs，持久化
  `AgentSettings.thinkingLevelOverride`）；Plan 人控 = 确认卡内联操作
  （无需设置）；终端策略 = 系统提示词常开 + 顾问自适应（无需设置）。

## 5. 文件索引

| 关注点 | 文件 |
|--------|------|
| 六档画像/选档器/控制器 | `core/agent-engine/.../engine/thinking/`（三件套，4-a） |
| 计划拓扑/调整/锁定 | `core/agent-engine/.../engine/plan/PlanGraph.kt` |
| 确认决策承载 | `core/agent-engine/.../engine/plan/PlanConfirmationRequest.kt` |
| 确认等待（迁出引擎） | `core/agent-engine/.../engine/plan/PlanExecutionSupport.kt` |
| 终端顾问 | `core/agent-engine/.../engine/terminal/TerminalProactivityAdvisor.kt` |
| 引擎接线（Phase 3.5/两钩子） | `core/agent-engine/.../engine/ApexAgentEngine.kt`（1200 行门禁内零净增） |
| 提示词（Terminal-Use/Planning Phase） | `core/agent-engine/.../engine/EnginePrompts.kt` |
| 确认卡/锁定卡 | `app/.../ui/screen/agent/AgentChatPlanCards.kt` |
| 事件归约/状态 | `app/.../ui/screen/agent/AgentChatEventApplier.kt` / `AgentUiModels.kt` |
| 会话 Agent 徽标 | `app/.../ui/screen/terminal/TerminalScreen.kt`（SessionChip） |
| 测试 | `plan/PlanGraphTest.kt`、`plan/PlanModeHumanControlTest.kt`、`terminal/TerminalProactivityAdvisorTest.kt` |

质量门禁：引擎 `wc -l` = 1200（≤ 上限）；`scripts/check_file_size.sh`、
`check_code_quality.sh`（无 printStackTrace/反射分发）、括号平衡全部通过；
agent-engine 全量 215 test 绿（kotlinc 2.0.21 单模块编译 + JUnitCore 实跑）。
