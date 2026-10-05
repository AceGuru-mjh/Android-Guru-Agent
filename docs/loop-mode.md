# Loop 循环与 Cron 模式（v3）

> 一个特性一篇：LOOP 模式的设计、数据流与验证。GOAL 目标模式见
> [goal-mode.md](goal-mode.md)；作用域隔离见 [scope-isolation.md](scope-isolation.md)。

## 动机

用户诉求：「在会话内重复运行提示词、轮询状态或设置一次性提醒，适合需要
周期性检查的本地任务。」—— 类比 Claude Code 的 /loop：**会话内调度器**，
不是系统级 AlarmManager 任务（那是后续演进方向）。

## 数据流

```
LoopSetupSheet（Agent 屏，LOOP 模式首条消息自动拦截转入）
  │ prompt + kind(INTERVAL|CRON|ONCE) + intervalMs/cronExpr/triggerAt + maxRuns
  ▼
LoopStore（filesDir/loops/loops.json，原子写 tmp+renameTo；runLog 每循环 ≤20 条）
  ▼
LoopScheduler（@Singleton，15s tick 轮询 + Mutex 防重入）
  │ 到期判定：nextRunAt <= now 且 runsDone < maxRuns
  │   INTERVAL → lastRunAt + intervalMs（最小 30s 钳制）
  │   CRON     → VixieCron.nextAfter（复用 time 工具的位图解析器，5 字段）
  │   ONCE     → triggerAt（过期未跑 → 立即补跑）
  ├─ tryEmit(dueEvents) 有收集者（VM 活着）→ 会话注入
  └─ tryEmit 失败（无人消费）→ ApexNotifications（apex_general 渠道）
  ▼
AgentChatLoopController（VM 扩展）
  │ collect dueEvents 且 mode==LOOP 且上一轮已收尾
  ▼
"[loop 第 N 轮] <prompt>" → 正常发送管线（引擎单次执行）
  ▼
ApexAgentEngine（Mode: LOOP 提示词段：单轮自包含/幂等/状态简报）
```

## 关键设计决策

1. **调度在 VM/服务层，不在引擎循环内**——引擎只跑单次执行（`executeBuildLoop`
   复用，工具面同 AGENT：剔除编码工具 + 作用域隔离）。模式枚举只是「会话处于
   周期任务中」的提示词标记；
2. **收集者存活语义**判前后台：`dueEvents.tryEmit` 失败 = 会话屏没开 →
   ONCE 类型必发系统通知（提醒主用途）；INTERVAL/CRON 记 runLog + 按
   notifyOnRun 补通知，下轮会话打开时接续；
3. **循环独立于模式存活**：切走 LOOP 不停止（activeLoop 与 mode 解耦），
   只有 maxRuns 耗尽 / ONCE 到点跑完 / 用户显式停止才结束；
4. **幂等纪律进提示词**：重复运行不得重复副作用（先查状态再动手）；
5. **cron 复用不复制**：`CronSchedule` 薄适配 core 的 `VixieCron` 位图解析器
   （time 八合一工具同源），非法表达式折叠 null（红字提示 + 下次时间预览）。

## 状态卡与入口

- **LoopSetupSheet**：三型选择（固定间隔 chips 1m/5m/30m/1h/6h + 自定义分钟 /
  Cron 表达式 + 实时下次触发预览 + 常用预设 `*/5 * * * *` `0 * * * *` `0 9 * * *`
  `0 9 * * 1` / 一次性 5m/30m/1h/明天此刻）+ maxRuns；
- **运行状态卡**（输入栏上方）：`Loop 运行中 · 第 N/M 轮 · 下次 X 后` 倒计时
  （1s 刷新）+ 停止 + 立即触发一次；
- **首条消息拦截**：LOOP 模式下第一条非斜杠输入自动带入配置 Sheet 草稿
  （`maybeInterceptLoopFirstSend`）。

## 设置项（设置 → Agent → Loop）

| 字段 | 默认 | 说明 |
|---|---|---|
| `loopDefaultIntervalMs` | 300_000 | 新循环默认间隔（预填） |
| `loopMaxRunsDefault` | 10 | 新循环默认最大执行次数 |
| `loopNotifyOnRun` | true | 后台触发/完成通知（前台静音） |
| `loopCatchUpMissed` | false | 会话重开时补跑错过的轮（ONCE 恒即时补发） |

## 行为验证（JVM 实测，见 PR 描述）

`*/5 * * * *` 下次 +5min / `0 * * * *` 下个整点 / `0 9 * * *` 当日 09:00 /
`0 9 * * 1` 周一 09:00（Asia/Shanghai 时区）/ 非法表达式（`not a cron`、
`99 * * * *`、空串）全部折叠 null —— 7 例全过。

## 已知边界

- 会话屏关闭时 INTERVAL/CRON 轮次不执行（记录 + 通知），不做跨进程后台执行
  （需要时开 Keep Alive 前台服务，ApexCoreService 宿主已接线）；
- Cron 表达式为标准 5 字段（Vixie cron），不支持 @daily 等别名。
