# 长任务中心 — 追踪 / 检查点 / 复制 / 续跑（v1.2）

> 对专属 agent 长任务复制任务的顶级优化：长任务自动留档、检查点快照、
> 复制任务（带上下文重跑）、从检查点续跑、运行对比、内置模板、档位
> 效能统计——全部在 Coding 屏「长任务」面板可用，引擎零改动。

## 一、总览

```
AgentEvent 流（VM collect）
      │  tracker.onEvent(event)
      ▼
LongTaskTracker（纯内存聚合）
      │  endRun：LongTaskDetector 判定规模
      │  ├─ 短任务 → null（静默丢弃）
      │  └─ 长任务 → LongTaskRecord
      │        │ fire-and-forget            │ ingest
      │        ▼                            ▼
      │  LongTaskStore                CodeThinkingEvolutionTracker
      │  （filesDir/longtask/，        （工作区×档位效能统计）
      │   原子写+内存缓存+保留策略）
      ▼
CodeLongTaskSheet（三页签：任务记录 / 任务模板 / 档位效能）
      │  复制 / 重跑 / 续跑 / 对比 / 删除
      ▼
TaskCopyEngine（复制语义 + 上下文摘要 + 续跑提示词 + 复制链）
      │  buildRelaunchPrompt / buildResumePrompt
      ▼
CodeViewModel.sendMessage（组装好的提示词直接进引擎）
```

## 二、长任务判定（LongTaskDetector）

纯函数，四个信号任一过阈值即留档，取满足的最高档：

| 规模 | 迭代 | 工具调用 | 时长 | 触碰文件 |
|------|------|----------|------|----------|
| MEDIUM | ≥8 | ≥12 | ≥120s | ≥3 |
| LONG | ≥15 | ≥25 | ≥300s | ≥8 |
| EPIC | ≥25 | ≥40 | ≥600s | ≥15 |

设计依据：8 轮迭代是一个「想清楚再做」的多步任务起点；两分钟是用户
「去干了别的再回来看」的分界；3 个文件开始形成「一次改动集」。
短任务不留档——长任务中心只收值得复盘的运行，列表的信噪比优先。

## 三、追踪与检查点（LongTaskTracker）

挂在 VM 的 collect 链上（`tracker.onEvent(event)` 一行接线，引擎零
改动），运行期间**纯内存聚合**（每事件 O(1) 或 O(200) 有界操作）：

| 事件 | 聚合动作 |
|------|----------|
| IterationStart | 迭代计数 +1（计数而非取序号：Plan 多步每步重新编号） |
| ToolCallStart | 工具计数 +1、toolsUsed[名] +1 |
| ToolCallComplete | 对话行「工具 名 ✓/✗」；**成功且 code_edit/code_write 时提取 path 入 filesTouched** |
| ResponseChunk | 追加进 200 字符尾环缓冲 |
| ResponseComplete | 尾巴定格，对话行「助手: …」，环复位 |

### 检查点双触发

- **迭代驱动**：累计迭代是 5 的倍数，且在**完成型事件**
  （ToolCallComplete / ResponseComplete）上触发——IterationStart 时
  第 N 轮工具还没跑，此时拍照会把计数少计一轮；
- **时间驱动**：距上个检查点 ≥60s 且期间有新事件——任意事件可触发
  （含 Start 型，长静默后的首个动作即拍照），防挂机空转刷检查点。

检查点持有 ≤10 个（超限丢最旧保最新），每个含：迭代号 / 时间戳 /
工具与文件计数 / 最近对话摘要行（≤6）/ todo 快照。

### 收尾防御

- beginRun 时若有未收尾的旧 run：自动按 ABORTED 走完整收尾判定
  （够格照样入库——「用户忘了收尾」不该丢掉一次 EPIC 运行的画像）；
- endRun 无活跃 run → null（幂等）；
- 收尾后晚到事件全部忽略。

## 四、复制任务（TaskCopyEngine）

### 复制语义（copy）

源记录 → 新副本：

- 新 id / 新时间戳；`parentTaskId = 源.id`（复制链可回溯）；
- 状态重置 RUNNING、iterations/toolCalls/duration 归零、错误与摘要清空；
- `title = 选项标题 ?: 源.title + "（副本 n）"`（n = 源 copyCount + 1，
  源的 copyCount 同步 +1）；
- goal 默认沿用（「复制任务再跑一遍」的语义）；
- 上下文/todo/文件清单/检查点按选项开关复制；
- 档位与工作区可覆盖（thinkingLevelOverride / targetWorkspaceId）。

### 重跑提示词（buildRelaunchPrompt）

「重跑任务：标题 + goal 原文 + ## 上次运行上下文（运行概览/对话摘要/
待办快照/涉及文件）+ 收尾指令」。FAILED 源的收尾指令带「注意上次的
错误与未竟事项」。总长 ≤3000 字符截断保头（标题与 goal 开头是模型
的定向锚点）。

### 续跑提示词（buildResumePrompt）——顶级优化核心

与重跑的语义差异：**重跑 = 换思路重新完成；续跑 = 从第 N 轮检查点
沿着原路线继续**。文本结构：

```
续跑任务：<title>

<goal 原文>

## 进度快照（第 N 轮检查点）
- 已进行：N 次迭代 · M 次工具调用 · 已触碰 K 个文件
- 最近进展：
  - <recentExchange 摘要行…>
- 待办状态：
  - <todoDigest 行…>

请从上述进度继续：先回看已触碰的关键文件与待办状态，已完成项不要
重做，接着未竟事项继续执行。
```

无检查点可续 → 空串，调用方（VM）自动回退到带上下文重跑并在对话里
说明——UI 上「续跑」按钮永远可用，不需要用户判断有没有检查点。

### 复制链（forkChain）

沿 parentTaskId 回溯到根（旧 → 新），防环（visited 集合）+ 链长硬
上限 64。UI 的「对比上次」按钮用 `LongTaskDiff.compare` 渲染两次
运行的文件交集/差集、迭代/工具/时长差，以系统消息进对话。

## 五、档位效能统计（CodeThinkingEvolutionTracker，coding 专属）

把长任务留档按「工作区 × 档位」聚合：runs / 状态三分计数 / 平均
迭代/工具/时长/文件数 / 成功率派生。

- **口径诚实**：只统计长任务（与长任务中心同源）；AUTO 聚合在 AUTO
  名下（「选 AUTO 这个决策的表现」，不冒领到逐轮实际档位）；
- 持久化 `filesDir/longtask/thinking-stats/stats_<ws>.json`，原子写 +
  损坏重置；
- `ingest` 在 VM 收尾链路 fire-and-forget，主线程零 IO；
- UI：长任务面板第三页签——用户用自己的历史数据选档，而非凭感觉。

## 六、内置模板（LongTaskTemplates）

8 个模板一键启动（应用推荐档位 + 预置 todo 骨架 + goal 模板发送）：

| key | 模板 | 推荐档位 |
|-----|------|----------|
| refactor | 模块重构 | DEEP |
| bugfix | 修 Bug | STANDARD |
| feature | 新功能开发 | DEEP |
| code-review | 代码评审 | ULTRACODE |
| test-gen | 补测试 | STANDARD |
| docs-gen | 文档生成 | LIGHT |
| perf-opt | 性能优化 | ULTRACODE |
| migrate | 依赖迁移 | MAXIMUM |

模板实例 `isTemplate = true`：留档豁免 prune、不计入档位效能统计
（不是真实运行）。

## 七、存储与保留策略（LongTaskStore）

- `filesDir/longtask/records/<id>.json`，kotlinx.serialization，
  `ignoreUnknownKeys`（字段演进的向后兼容）；
- **原子写**（tmp + renameTo，失败直写目标兜底）；
- 内存缓存 + Mutex 串行，`snapshotBlocking` 供主线程快速读；
- **保留策略**：启动时后台 prune（保留最新 50 条，`isTemplate` 豁免），
  失败仅 warn——prune 是优化不是正确性前提；
- 损坏 JSON 跳过并留痕，不影响其他记录。

## 八、UI（CodeLongTaskSheet）

Coding 屏工作区条「长任务」按钮唤起 ModalBottomSheet，三页签：

1. **任务记录**：状态过滤 chips（全部/已完成/失败/已中止）；卡片 =
   状态图标 + 标题 + 规模徽标（MED/LONG/EPIC，展示层重算——与入库
   判定同源保证一致）+ 档位/状态/时间 + 统计行；展开 = 目标/改动
   文件（横向滚动 chip）/todo 快照/检查点数与最近摘要/错误块；操作 =
   复制（选项对话框：上下文/todo/文件清单/档位覆盖/立即重跑）·
   重跑 · **续跑**（最后检查点 / 指定轮次）· 对比上次（有父任务时）·
   删除（确认）；
2. **任务模板**：8 模板卡片，一键开始；
3. **档位效能**：每档一行（runs + 成功率/平均迭代/工具/时长）。

## 九、相关文件索引

| 文件 | 职责 |
|------|------|
| `core/code-engine/.../code/longtask/LongTaskModels.kt` | Record/Checkpoint/CopyOptions 数据模型 |
| `core/code-engine/.../code/longtask/LongTaskDetector.kt` | 规模判定纯函数 |
| `core/code-engine/.../code/longtask/LongTaskStore.kt` | JSON 存储（原子写/缓存/prune） |
| `core/code-engine/.../code/longtask/LongTaskTracker.kt` | 事件流聚合 + 检查点 |
| `core/code-engine/.../code/longtask/TaskCopyEngine.kt` | 复制/重跑/续跑/摘要/复制链 |
| `core/code-engine/.../code/longtask/LongTaskTemplates.kt` | 8 内置模板 |
| `core/code-engine/.../code/longtask/LongTaskDiff.kt` | 运行对比 |
| `core/code-engine/.../code/thinking/CodeCodeThinkingEvolutionTracker.kt` | 档位效能统计 |
| `app/.../di/CodeLongTaskModule.kt` | DI 装配（含启动 prune） |
| `app/.../ui/screen/code/longtask/CodeLongTaskSheet.kt` | 三页签面板 |
| `app/.../ui/screen/code/CodeViewModel.kt` | 追踪接线 + 复制/重跑/续跑/对比 API |

## 十、设计取舍 FAQ

**Q: 为什么追踪在 VM 层而不是引擎层？**
引擎零改动 = agent 模式零风险；事件流对 VM 完全可见（collect 链），
聚合不需要引擎内部状态；将来 agent 模式要接入，同样一行
`tracker.onEvent(event)` 即可。

**Q: 为什么检查点不存消息全文？**
上下文全文在 CodeConversationMemory（引擎侧 per-workspace 持久化）
里已有；长任务记录的检查点只存**摘要行**（对话行 ≤120 字符 × 6），
保证记录文件小、留档多（保留 50 条）时存储仍然轻量。

**Q: 复制到另一个工作区时文件不带过去？**
记录里存的是路径清单（审计与重跑上下文用），不是文件本体。跨工作区
复制适用于「在别的项目里跑同类任务」——goal 与方法论迁移，文件本来
就该是新的。

**Q: 「续跑」和「重跑」该怎么选？**
上次跑歪了 / 想换个思路 → **重跑**（默认不带进度偏见）；被打断 /
卡在最后一步 / 只差一点 → **续跑**（保留已做工作，从检查点继续）。

## 十一、线程模型详解

| 状态 | 归属 | 访问模式 |
|------|------|----------|
| Tracker 聚合态（计数/环缓冲/检查点） | LongTaskTracker 实例字段 | VM 主线程串行（collect 链），单写者无竞争 |
| Store 内存缓存 + Mutex | LongTaskStore | suspend API 串行化；snapshotBlocking 主线程快速读 |
| 记录落盘 | persistScope（SupervisorJob+IO） | fire-and-forget，捕获不可变快照 |
| 效能统计 | CodeThinkingEvolutionTracker | 同上（共用 LongTaskPersistScope） |
| UI 状态 | CodeUiState（StateFlow） | VM 独占更新，Compose 收集 |

关键不变量：**VM 主线程绝不等磁盘**（所有 IO 都在 withContext(IO)
或 fire-and-forget scope）；**落盘协程与后续状态零共享**（组装完成的
不可变 data class 快照）；**单条落库失败不传染**（SupervisorJob）。
