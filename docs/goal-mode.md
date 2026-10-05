# Goal 目标模式（v3）

> 一个特性一篇：GOAL 模式的设计、数据流与验证。LOOP 循环模式见
> [loop-mode.md](loop-mode.md)；作用域隔离见 [scope-isolation.md](scope-isolation.md)。

## 动机

用户诉求：「设定一个**可验证的完成条件**（如"所有测试通过"），Agent 持续工作，
每轮结束后由**快速模型**检查条件是否满足，不满足则自动继续，直到目标达成。」

既有模式都缺这一环：BUILD 的循环以「模型不再调工具」为终止信号——弱模型
叙述计划就收工；PLAN/SPEC 只解决"先想后做"；承诺催促（PromiseDetector）每任务
只救一次。GOAL 把**终止权从主模型手里拿走**：每轮自然收尾时由独立的快速模型
对照验收判据判定，未达标自动注入差距说明续跑。

## 数据流

```
GoalSetupSheet（Coding 屏）
  │ statement + acceptanceCriteria + maxRounds
  ▼
GoalModeCoordinator（@Singleton，单活动目标状态机）
  │ startGoal(spec) → ACTIVE
  ▼
ApexAgentEngine.executeBuildLoop（纯文本轮收尾钩子，GOAL 分支）
  │ goalCoordinator.onAgentTurn(本轮工作汇报)
  ├─ GoalDecision.Continue → addMessage(System(差距说明)) + continue
  │    （验收通知 ThinkingChunk [goal] 标记 → CodeViewModel 改道
  │      appendSystemMessage：时间轴 SystemLine 常驻 + 会话落盘）
  ├─ GoalDecision.Stop(achieved) → 照常 ResponseComplete（状态 ACHIEVED）
  └─ null（无目标/已停止）→ 默认收尾
  ▼
FastModelGoalVerifier（LlmRequestContext.fast("goal_verify")）
  │ 非流式低温调用，JSON 协议 {"achieved","reason","evidence"}
  │ 围栏剥离 + 首个 JSON 对象提取；失败重试 1 次后 unknown 放行
  ▼
ModelRoleRouter：fast → primary → default 解析链
  （用户可在 模型 → Roles 为验收器配便宜小模型）
```

## 关键设计决策

1. **验收判据独立成提示词**（`GoalPrompts.verifierSystem`）：主模型自评不可信
   ——验收器只认证据（命令输出/文件内容/测试结果），"应该没问题"不算数；
2. **薄包装纪律**：协调器不持有引擎引用、不改消息时间线——续跑注入由引擎按
   HUMAN_ASSIST/REFLECTION 同款拦截惯例执行（引擎仅 15 行接线，1198/1200 预算内）；
3. **GOAL 恒走深潜线**（CodeGoalController 三点保障：切入/启动/重启时
   `switchLogic(DEEP_DIVE)`）——验收钩子只装配在深潜线引擎上，标准线无此逻辑；
4. **轮次预算双闸**：GoalSpec.maxRounds（验收轮）与 AgentConfig.maxIterations
   （引擎迭代）独立计数，任一耗尽即停（防无限循环）；
5. **验收不可用防御**：快速模型连续失败 → `GoalCheckResult.unknown`（按达成
   放行）——验收器故障绝不拖垮主循环；
6. **验收关闭模式**（设置 → Goal）：跳过快速模型，按主模型自评「完成报告」
   关键词弱判定放行（省 token 的降级路径）。

## 状态机

```
ACTIVE ──验收通过──▶ ACHIEVED（activeSpec()=null，状态卡显示 ✅）
   │  ──轮次耗尽──▶ STOPPED（状态卡显示 ⏛ + 「重启目标」= 原 spec 重放）
   │  ──用户停止──▶ STOPPED
   └──startGoal(新)─▶ ACTIVE（覆盖旧目标：单任务使命语义）
```

## UI

- **GoalSetupSheet**：陈述 + 可验证条件双输入（非空校验）+ 轮次 chips（3/5/8/12/20）
  + 帮助折叠；首条消息拦截（GOAL 模式下第一条输入自动带入草稿）；
- **GoalStatusCard**（输入栏上方）：进度徽标（第 N/max 轮）+ 条件展开 + 最近验收
  ✅/❌ 与理由 + 停止/重启操作；只要存在目标（任何状态）就显示，跨模式存活；
- **验收通知**：`[goal]` 前缀 ThinkingChunk → 时间轴 SystemLine 常驻 + 落盘
  （思考卡会被 ThinkingComplete 覆写，不可靠——见 CodeGoalController）。

## 设置项（设置 → Agent → Goal）

| 字段 | 默认 | 说明 |
|---|---|---|
| `goalVerifierEnabled` | true | 快速模型验收开关（关闭=主模型自评） |
| `goalMaxRounds` | 8 | 新目标默认轮次上限 |
| `goalAutoResume` | true | 会话恢复时提示续跑未完成目标 |

## 行为验证（JVM 实测，见 PR 描述）

continue-on-fail 轮次计数 / stop-on-achieved 状态迁移 / exhaust-rounds → STOPPED /
manual-stop → null / 围栏 JSON 解析 / 垃圾输出 unknown 放行 / 验收关闭自评路径 ——
7 例全过。
