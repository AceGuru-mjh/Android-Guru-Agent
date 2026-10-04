# 双思考逻辑引擎 — Coding 模式「深潜 / 标准」切换

> **已实施 v1**（PR #254 + 后续增强提交）。Coding 屏右上角新增「思考逻辑」
> 选择器：**深潜**（自研七档思考，v1.2 既有行为，默认）与**标准**
> （业界标准 Agent 任务循环：画像 / 权限门 / 子代理 / 上下文压缩）。
> 两线经 `DualLogicCodeEngine` 门面并联，事件协议 100% 复用 `AgentEvent`
> ——胶囊时间轴、长任务追踪、权限问答、计划确认卡零改动两线通用。
> 切换入口三通道：右上角选择器（Compose 下拉）/ `/logic:<mode>` 斜杠
> 命令 / 重启自动恢复（`AgentSettings.codeThinkingLogic` 持久化）。
>
> **标准线 v2 全面完善**（env 块 / 行为规范 / 工具纪律 / 权限链闭环 /
> read-before-edit 硬约束 / 模型感知窗口 / 增量压缩）见
> [standard-mode-v2.md](standard-mode-v2.md)。

## 一、动机与定位

「怎么完成任务」存在两种被验证过的思路：

- **深潜**：一个引擎吃透全部状态，靠**深度档位**（七档思考阶梯 ×
  自适应预检 × 深水区升级，见 [thinking-levels.md](thinking-levels.md)）
  调节投入强度——强在**纵深推理**，一次任务内部多轮穷举；
- **标准**：把任务循环拆成**角色画像 × 权限门 × 子代理委派 × 上下文
  压缩**的标准件——强在**编排纪律**：每步有权限审计、可委派隔离
  的子任务、长会话不膨胀。

用户在不同任务形态下各有最优解，因此做成**运行时可切的并联双引擎**
而非二选一的替换：现场隔离（各自独立记忆通道），切回来继续。

## 二、总体架构

```
CodeViewModel（Coding 屏）
   │ setLogicMode / restoreLogicMode / /logic:<mode>
   ▼
DualLogicCodeEngine（路由门面，volatile 单引用切换）
   ├──► CodeAgentEngine        深潜线（自研七档思考）
   │      └─ 记忆：code_memory/
   └──► StandardModeEngine     标准线（标准任务循环）
          ├─ 记忆：code_memory_standard/
          ├─ StandardPermissionEngine（allow/ask/deny 三态门）
          ├─ StandardToolSurface（别名归一 + 合成 task 工具）
          ├─ StandardSubAgentDispatcher（隔离子代理，并发 3）
          ├─ StandardCompactor（LLM 摘述 + 滑窗降级）
          └─ StandardTextKernel（JNI C++17 / 纯 Kotlin 回退）
```

### 门面路由表（`DualLogicCodeEngine.kt`）

| 调用 | 路由目标 |
|---|---|
| execute / abort / submitUserInput / cancelUserInput | 当前激活引擎 |
| setActiveWorkspace / clearConversation | **两条线同步**（各自独立记忆通道） |
| updateGlobalRules / updateSessionExtras / updateForcedTools / updateMode | 两条线同步（廉价缓存写入） |
| prepareForTask / updateThinkingLevel / 仪表读数 | 当前激活引擎 |
| submitPlanConfirmation | 当前激活引擎（两线各自的人控门互不串扰） |

`switchLogic` 仅赋一个 volatile 引用 + 同步双线轻量缓存；运行中切换
被拒绝（`isStandardEngineRunning` 由 VM 在发送前判断，UI 弹提示）。

## 三、标准线：任务循环内核（`StandardModeEngine.kt`）

### 3.1 回合循环

```
用户输入 ──► 理解(UNDERSTANDING) ──► 规划(PLANNING) ──► 执行(EXECUTING) ⇄ 验证(VERIFYING)
                ▲                                        │        │
                │        压缩暂态(COMPACTING)◄─接近上限────┘        │
                │        权限问答(AWAITING_INPUT)◄─ask 门挂起──────┤
                └────────── 无工具调用 / 预算耗尽 / 用户中止 ◄───────┘
                                    ▼
                              总结(SUMMARIZING) ──► DONE
```

- **LLM 流式 → 工具批处理 → 权限门 → 结果回填**，直到无工具调用 /
  回合预算耗尽；批处理事件经 `AgentEvent` 外送（id 前缀 `sub_` 标记
  子代理来源）；
- **PLAN 档人控门**：规划师产出结构化计划 → `PlanConfirmationRequested`
  事件 → 用户勾选/重排 → `submitPlanConfirmation` → 切构建者执行；
- **防循环守卫**：同指纹工具调用 3 次警告、4 次强制收敛（注入纠偏提示）；
- **回合预算** = 画像基数 `defaultMaxTurns` × 七档思考倍率（档位语义
  在标准线退化为「预算倍率」——ULTRACODE ×2.0 / APEXCODE ×3.0 照常
  生效，AUTO 预检与深水区观察器**仅深潜线**参与，标准线不出现无关的
  自适应消息）；
- **usage 校准**：provider 返回 token 用量回写预算核算；悬挂 toolCall
  修复（流式中断的半截调用补齐失败结果，防下轮死锁）。

### 3.2 五画像（`StandardModels.kt` / `StandardAgents.kt`）

| 画像 | key | 角色 | 工具面 | 写权限 | 用途 |
|---|---|---|---|---|---|
| 构建者 | build | 主代理 | 完整 + task 合成 | ✅ | BUILD 档默认 |
| 规划师 | plan | 主代理 | 只读 + task 合成 | ❌ | PLAN 档（人控门） |
| 通用 | general | 主代理兜底 | 完整 + task 合成 | ✅ | 显式 general 指定 |
| 探索 | explore | 子代理 | 只读代码探索 | ❌ | 返回 `path:line` 结论 |
| 调研 | research | 子代理 | 联网检索 | ❌ | 返回带来源结论 |

`task` 合成工具仅主代理可见（子代理不能再派子代理，防递归爆炸）。

### 3.3 权限三态门（`StandardPermissionEngine.kt`，v2 完整裁决序）

每次写类/执行类工具调用先过门：

```
DENY 规则短路 ──► PLAN 硬门（planGate）──► 会话记忆（本轮已放行的同命令）
──► 命令级通配（shell 首词前缀）──► 工具级规则 ──► 敏感文件保护
（.env 族/密钥/凭据 → ask）──► 模式兜底
```

- 模式四档：BYPASS（全放）/ DEFAULT（写类 ask）/ ACCEPT_EDITS（编辑
  放行、shell ask）/ PLAN（**硬拒写**，只读循环）；
- 设置层接线（v2）：`StandardPermissionSource` 快照源每轮任务前拉取
  （模式 + 规则三元组，改设置即时生效）；PLAN 档另叠加引擎级硬门
  （与设置层模式正交）；
- `ask` → `AgentQuestion` 事件挂起 → 用户选项回传（允许一次 / 本次会话
  总是允许 / 拒绝——拒绝时的非空指示原文回传模型，可改道）；空/超时
  （5 分钟）= 拒绝；
- 子代理的 ASK 折叠为 DENY（无 UI 通道，宁可保守）；
- 引擎级 read-before-edit 硬约束在权限门之前拦截（未读先编直接拒绝
  并引导，新文件免检，详见 [standard-mode-v2.md](standard-mode-v2.md)）。

### 3.4 工具面编排（`StandardToolSurface.kt`）

别名归一表保证胶囊分族与深潜线一致：`read→code_read`、
`bash/shell→shell_execute`、`edit→code_edit`、`write→code_write`、
`grep→code_grep`…；最终工具面 = 画像白名单 ∩ 注册表 ∪ 合成工具。
`task` 工具（子代理唯一入口）参数含 `subagent_type` / 任务描述 /
超时，产物折叠为一条工具结果回填主循环。

### 3.5 子代理派发（`StandardSubAgentDispatcher.kt`）

- 全新同构引擎实例（独立会话/预算/权限门），事件 id 前缀 `sub_` 外送
  （UI 时间轴可见但正文与思考链不进父流——上下文隔离）；
- 常量：并发 3（`DEFAULT_MAX_CONCURRENT`）、超时 4 分钟
  （`DEFAULT_TIMEOUT_MS = 240_000L`）、产出截断 8000 字符
  （`MAX_OUTPUT_CHARS`）。

### 3.6 上下文压缩（`StandardCompactor.kt`，v2 结构化模板）

- 接近上限时：旧消息段 → LLM 摘述（≤1000 token，`SUMMARY_MAX_TOKENS`）
  带 `[SESSION SUMMARY — earlier context was compacted]` 头替换；摘要
  按固定五段模板（Objective / Important Details / Work State / Next
  Move / Relevant Files），二次压缩增量合并旧摘要，替换后尾部追加
  继续指令（详见 [standard-mode-v2.md](standard-mode-v2.md)）；
- LLM 摘述失败 → **滑窗降级**（滑窗摘要 + 最多 20 条摘要结果，
  `MAX_DIGEST_RESULTS`），压缩永不成为单点故障；
- 压缩预算消费模型真实上下文窗口（`ModelProfile.contextWindow`，
  DI 注入 `modelInfoProvider`），静态 128K 兜底。

### 3.7 文本核（`StandardTextKernel.kt` + `core/code-native`）

token 估算 / 行级 diff 统计 / 模糊定位三个热路径算子，JNI C++17 实现
（`code_kernel.cpp` 162 行 + `code_native_jni.cpp` 110 行），加载失败
或宿主不支持时**纯 Kotlin 同语义回退**（`PureKotlin` 内嵌对象）——
功能永不因 native 缺位而降级，只是快慢之别。

## 四、App 层接线

| 文件 | 职责 |
|---|---|
| `di/CodeModule.kt` | `@Named("code")` 装配为 `DualLogicCodeEngine`（深潜 = 既有 CodeAgentEngine，标准 = StandardModeEngine + 独立记忆 `code_memory_standard/` + NativeTextKernel） |
| `ui/screen/code/CodeViewModel.kt` | `setLogicMode`（持久化 + 门面切换 + 回执消息 + 双线 mode/档位同步）/ `restoreLogicMode`（init 恢复）/ `/logic` 命令截获（`handleLogicCommand`） |
| `ui/screen/code/CodeLogicModeSelector.kt` | 右上角 28dp 胶囊 + 300dp 结构化下拉（两模式名/徽标/描述/能力清单），运行中切换拒绝提示 |
| `AgentSettings.codeThinkingLogic` | 持久化字段（空/未知 → 深潜兜底，防脏值切线） |
| `strings_code.xml`（en/zh） | 13+ 键：胶囊/标题/模式名/徽标/描述/能力/切换回执/运行中拒绝/未知模式引导 |

### 切换三通道

1. **右上角选择器**：结构化下拉，切换回执进对话流；
2. **`/logic:<mode>` 斜杠命令**（本地路由命令）：`standard/std` →
   标准，`deep_dive/deep/apex` → 深潜（`StandardLogicMode.fromName`
   容错别名）；未知 id 发引导消息；运行中拒绝与选择器同口径。命令在
   `handleSlashCommand` 中**先于通用路由截获**——纯 VM 状态操作不进
   引擎；Agent 屏键入则由路由层空转（引导消息 + 空 agentPrompt，
   与 `/mcp:<id>` 未连接的拦截语义一致）；
3. **重启恢复**：init 读 `codeThinkingLogic` → 门面预热切换。

### 现场隔离语义

切到另一条线时旧线**会话现场保留**（独立记忆通道），切回即恢复；
「清空对话」清**两条线**（意图 = 全新开始）。

## 五、实现清单

| 模块 | 文件 | 行数 |
|---|---|---|
| core/code-engine standard 包 | 12 个 Kotlin 文件 | 3,394 |
| ├─ 主循环 | `StandardModeEngine.kt` | 1,145 |
| ├─ 模型/画像/阶段 | `StandardModels.kt` | 313 |
| ├─ 权限门 | `StandardPermissionEngine.kt` | 338 |
| ├─ 提示词 | `StandardPrompts.kt` | 316 |
| ├─ 工具面 | `StandardToolSurface.kt` | 224 |
| ├─ 压缩器 | `StandardCompactor.kt` | 221 |
| ├─ 子代理 | `StandardSubAgentDispatcher.kt` | 177 |
| ├─ 门面 | `DualLogicCodeEngine.kt` | 159 |
| └─ 其余 | Session/Agents/TextKernel/LogicMode | 501 |
| core/code-native | C++17 JNI（kernel 162 + jni 110 + 头文件 + host 测试） | ~424 |
| app | CodeModule / CodeViewModel / CodeLogicModeSelector / strings | ~400 |
| 斜杠命令（v1.1 增强） | `SlashCommand.Logic` + 路由引导分支 + VM 截获 + 7 测试 | ~150 |

## 六、测试矩阵

| 测试文件 | 覆盖 |
|---|---|
| `standard/` 10 个测试类（108 用例） | 权限矩阵（规则/记忆/通配/模式兜底/子代理折叠）、会话 fork 与统计、工具面别名归一与白名单、压缩链（LLM 摘述/滑窗降级/失败路径）、主循环端到端（含子代理派发、PLAN 人控门、防循环、预算倍率、usage 校准、悬挂修复）、提示词渲染、双引擎路由 |
| `slash/SlashCommandParserTest`（+4） | `/logic:standard` / `deep_dive` / 大小写 / 尾随文本 |
| `slash/SlashCommandRouterTest`（+3） | Logic 路由空转（无 agentPrompt）、未知 id 引导、无来源元数据 |

本地验证：kotlinc 2.0.21 全量类型检查零错误（七模块真实编译 +
app 层联合编译）；JUnit 39/39（slash）；C++ host 测试 ALL PASS。

## 七、设计取舍 FAQ

**Q：为什么不把深潜引擎重写成标准循环？**
A：两线交互面与事件协议完全一致（`AgentEvent`），重写只会把已验证的
七档思考/长任务/胶囊时间轴耦合重打一遍。门面并联让两线各自演化，
风险面最小。

**Q：标准线的思考档位是什么语义？**
A：档位退化为「回合预算倍率 + 工具输出预算」（ULTRACODE/APEXCODE
预算照常放大），AUTO 自适应与深水区升级是深潜线专属机制——标准线
有自己的预算自洽，注入自适应消息反而干扰循环。

**Q：子代理为什么 ASK 折叠 DENY？**
A：子代理没有 UI 问答通道（父循环不等它）。保守拒绝 + 结果里说明
  权限不足，主代理可换只读路径或向用户转述。

**Q：C++ 核必须吗？**
A：不是必须——纯 Kotlin 回退同语义。C++ 覆盖三个热路径（token 估算 /
diff 统计 / 模糊定位），长会话高频调用下省主线程开销；加载失败自动
回退，无功能损失。

**Q：`/logic` 在 Agent 屏会怎样？**
A：路由层空转（引导消息 + 空 agentPrompt），不进引擎不误执行——
与 `/mcp:<id>` 未连接的拦截同语义。

## 八、后续路线

- 标准线运行报告（`StandardRunReport`）接入长任务中心统计页签
  （画像维度聚合，与档位效能页签并列）；
- 会话快照（`code_sessions/`）按逻辑线分文件，切线恢复 UI 现场
  （引擎侧记忆已隔离，UI 快照目前共用）；
- 子代理并发画像配置化（探索/调研配比随任务形态自适应）。
