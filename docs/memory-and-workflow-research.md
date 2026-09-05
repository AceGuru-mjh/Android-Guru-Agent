# 记忆系统与工作流对外调研报告（2026-09）

> 目的：对照业界有代表性的 Agent 记忆系统与工作流框架，审计本仓库
> `platform/cs-mem`（认知记忆）与 `core/agent-engine`（编排/工作流）的现状，
> 给出"已对齐 / 已修复 / 待建设"三层结论，并沉淀本 PR 落地的优化项。
>
> 方法：以论文与官方文档为准（MemGPT/Letta、Mem0、A-MEM、Zep/Graphiti、
> Voyager、Claude Code、OpenHands、SWE-agent），逐条映射到本仓库实际代码
> ——本文所有"现状"描述均可在代码中找到对应文件与符号，不做臆测。

---

## 一、调研对象速览

| 项目 | 领域 | 核心机制 | 对本项目的启示 |
|------|------|---------|--------------|
| **MemGPT / Letta** | 记忆 | 分层记忆（main context ↔ recall/archival external context）、自编辑记忆、sleep-time compute | 上下文分页、梦境巩固 |
| **Mem0** | 记忆 | 两阶段管线（提取 → 增量更新：ADD/UPDATE/DELETE/NOOP）、异步写入 | 差分摄取、Actor 异步写 |
| **A-MEM** (arXiv:2502.12110) | 记忆 | Zettelkasten 笔记网络：笔记构建 → 链接生成 → 记忆演化 | 语义边、拓扑同胚迁移 |
| **Zep / Graphiti** | 记忆 | 双时间线时序知识图谱，事实级失效（valid time vs transaction time） | Episode 审计；未来引入边失效 |
| **Voyager** | 记忆/技能 | 技能库（代码即技能）+ 迭代提示 + 自动验证 + 嵌入索引检索 | FSM 宏 ≈ 技能库；晶化 ≈ 永久技能 |
| **Claude Code** | 工作流 | 编排者 + 子代理扇出、并行工具调用、上下文压缩（compaction） | 压缩对齐、复合键、工具白名单 |
| **OpenHands** | 工作流 | 事件流架构（全动作皆事件、可回放）、沙箱化执行运行时 | TerminalEventBus/EventLog、PRoot 沙箱 |
| **SWE-agent** | 工作流 | ACI（agent-computer interface）：为模型设计的工具面 + 护栏 + 历史处理器 | 观察引擎、输出截断、循环检测 |

---

## 二、记忆系统对比

### 2.1 本项目现状（基线）

`platform/cs-mem` 是一条完整的"感知 → 降维 → 差分 → 存储 → 蒸馏 → 回放"管线：

```
PrivilegeManager.getUiTree()          感知：无障碍 UI 树（Root→Shizuku→普通三通道）
        │
        ▼
UiTreePruner.prune()                  降维：剔除不可见/装饰节点，产出 SemanticNode
        │                             语义图；节点指纹 = SHA-256(cls|res|text|role|parent)
        ▼
DifferentialIngestor.computeDelta()   差分：DTS 只存状态跃迁（95%+ 体积压缩）
        │
        ▼
MemoryWriterActor                     写入：Actor 单协程串行化（防 SQLite 锁），
        │                             有界 Channel(256) 背压 + 批量 + 紧急刷盘
        ▼
MemoryGraphDatabase (Room, v3)        存储：nodes/edges/episodes/fsm_macros/migration_map
        │
        ├── TraceDistiller.distill()  蒸馏：ReAct Trace → DFA 宏技能（锚点提取→动作压缩→FSM 编译）
        ├── BypassExecutionEngine     回放：指纹命中宏 → 绕过 LLM 直接下发无障碍动作（0 token）
        ├── EntropyManager            遗忘：能量衰减（艾宾浩斯曲线）+ 低能坍缩 + 高频晶化
        ├── DreamRenderer             梦境：息屏+充电时记忆保鲜/拓扑同胚迁移/全局熵衰减
        ├── TopologyMigrator          迁移：旧版本指纹 → 新版本指纹别名桥（migration_map）
        └── MemoryImmuneSystem        免疫：悬浮窗/劫持/记忆中毒防御 + 可疑指纹隔离
```

### 2.2 逐项对比

#### MemGPT / Letta —— 分层与"操作系统式"记忆

- **Letta 的做法**：main context（对话窗口内）+ external context（recall storage 检索、archival storage 归档），由模型通过函数调用在层级间搬运记忆，并可**自编辑**记忆块；新版 Letta 提供 sleep-time compute（空闲期后台整理记忆）。
- **本项目对应**：
  - 分层 ✓：`ConversationMemory`（工作记忆，SharedPreferences 持久化）↔ cs-mem 图存储（长期记忆），三层压缩（`HybridCompressor`：工具输出截断 → 滑窗 → LLM 摘要）扮演"换页"角色；
  - 睡眠整理 ✓：`DreamRenderer`（息屏+充电+WiFi 触发）与 sleep-time compute 思想同源；
  - 自编辑 ✗（有意取舍）：cs-mem 的写入全部走确定性管线（差分/蒸馏），不允许 LLM 直接改写长期记忆。收益是**记忆可信**（配合 `MemoryImmuneSystem` 防中毒），代价是缺少 Letta 式"模型自主归纳"。这是刻意的安全取舍——本项目的记忆驱动 0-token 的 FSM 回放，错误记忆的代价远高于一般 chatbot。

#### Mem0 —— 两阶段提取-更新管线

- **Mem0 的做法**：对话先"提取"候选记忆，再经"更新"阶段做 ADD / UPDATE / DELETE / NOOP 决策；存储异步化，不阻塞主循环。
- **本项目对应**：
  - 增量决策 ✓（形式不同）：`DifferentialIngestor` 按拓扑差分决定节点 ADD（新增指纹）/更新 lastSeen（等价 UPDATE）/移除边（等价 DELETE）——是"结构化版"的 Mem0 管线；
  - 异步写入 ✓：`MemoryWriterActor`（单 Actor 串行 + 有界邮箱背压 + 批量刷盘）与 Mem0 的异步 memory ops 目标一致，且额外解决了 Android Room 的 "Database is locked"；
  - 冲突消解 ✗：Mem0 对"同义事实冲突"有 LLM 仲裁；本项目节点按内容指纹天然去重，但**语义冲突**（同一控件换文案）依赖跨版本迁移而非 LLM 仲裁。可作后续方向。

#### A-MEM —— Zettelkasten 记忆网络

- **A-MEM 的做法**：新记忆入网时生成笔记（结构化），动态生成到既有记忆的链接，且**新记忆会触发旧记忆的演化**（旧笔记的链接/标签随之更新）。
- **本项目对应**：
  - 链接生成 ✓：`UiTreePruner.generateSpatialEdges`（空间边）+ `TraceDistiller.compileFSM`（因果边，动作 → 状态跃迁）；
  - 记忆演化 ✓（受限形态）：`TopologyMigrator` 在 App 升级后为旧节点计算新版本等价节点并写入 `migration_map` 别名桥，等价于"旧记忆被新事实修正"；
  - 演化的触发面 ✗：A-MEM 每次写入都可能演化旧记忆；本项目只在"梦境"（`DreamRenderer`）批量做，属节流取舍（移动端电量预算）。

#### Zep / Graphiti —— 双时间线时序知识图谱

- **Zep 的做法**：每条事实同时记录 valid time（现实世界生效时间）与 transaction time（入库时间）；事实变化时旧行不删除而是标记失效，支持"当时认为"类查询。
- **本项目对应**：
  - 事务时间 ✓：Episode（`startedAt/finishedAt`）、节点 `lastSeen`、宏 `lastExecutedAt` 都是 transaction-time 形态；
  - 有效时间 ✗：边与节点是"现状快照"，无 `invalid_at` 概念，低能记忆直接物理删除（熵增坍缩）。这是与 Zep 最大的理念差异：本项目选择**遗忘换体积**（移动端存储预算），Zep 选择**永不遗忘换审计**。未来若要支持"上周这个按钮在哪"类查询，需要给 `edges` 表加失效列并把 `pruneLowEnergy` 改为软删除——已列入 backlog。

#### Voyager —— 终身学习技能库

- **Voyager 的做法**：技能以可执行代码形式入库，配迭代提示生成、环境自动验证、嵌入向量索引检索，相似任务直接复用技能而非重推理。
- **本项目对应**：
  - 技能库 ✓：`FSMMacro` 就是"UI 自动化版技能"——把成功的 ReAct 轨迹蒸馏为确定性状态机；
  - 自动验证 ✓（三处）：蒸馏期 `extractAnchors` 只采纳"UI 发生重大变化"的有效步；回放期 `BypassExecutionEngine.executeMacro` 每步校验屏幕到达预期 `toState`（偏离即失败交还 LLM）；保鲜期 `DreamRenderer` 梦境中随机抽验低能宏；
  - 永久技能 ✓：`EntropyManager.shouldCrystallize`（能量≥8、成功≥10 次、成功率≥90%）→ `crystallizeMacro` 固化，免疫衰减/剪枝/删除——对应 Voyager "稳定技能进库不再验证"的思想；
  - 嵌入检索 ✗：当前宏召回按指纹精确匹配 + 迁移别名桥，无语义近邻检索（相似但不同 App 的流程无法复用）。backlog：给宏描述建嵌入索引（纯 JVM 可用 sentencepiece/onnx 小模型，或复用 LLM 生成关键词）。

### 2.3 本 PR 落地的记忆修复（对应上述差距）

> 完整 diff 见 PR 描述。以下按"调研结论 → 本仓库缺陷 → 修复"组织。

| # | 调研结论 | 本仓库缺陷（修复前） | 本 PR 修复 |
|---|---------|--------------------|-----------|
| M1 | A-MEM/Zep：边的身份必须跨快照稳定，差分才有语义 | 边 ID 是**帧内自增计数器**（`e_0,e_1…` 每帧重置）：帧 N 的 `e_3` 与帧 N+1 的 `e_3` 可连接完全不同节点 → 差分"新增边"随边数波动误报；`deleteByLabels` 全局按标签删除会跨 Episode 误删 | `UiTreePruner.stableEdgeId`：边 ID = SHA-256(源指纹\|目标指纹\|关系) 前 16 位，同一条拓扑边任意两帧同 ID；Room v2→v3 迁移清理历史重复行并建 `(episode_id, edge_label)` 唯一索引；`deleteByLabelsInEpisode` 增加 episode 作用域 |
| M2 | Voyager：技能回放必须可验证、参数必须纯净 | 蒸馏 `input_text` 时存**完整原始描述**（`input_text("hello")`），回放把整串字面量输入进输入框 | 蒸馏期 `TraceDistiller.extractActionParams` 提取纯参数；回放期 `BypassExecutionEngine.extractInputText` 双保险兼容三种历史形态（新纯参数 / 旧完整描述 / 无引号） |
| M3 | Zep/A-MEM：版本演化后旧事实应能解析到新事实 | `migration_map` 只写不读（DreamRenderer 写入别名桥，召回侧从不消费）→ App 升级后旧宏集体失效（有炉无米）；且迁移回退的复验用宏的**旧初始指纹**做匹配率检查——旧指纹必然不在当前屏幕，任何阈值下迁移宏都被误杀 | `MemoryGraphStore.findMacrosViaMigration`（当前指纹 → 旧别名 → 旧宏）+ 旁路引擎改用**正向别名桥精确校验**（`resolveMigration(旧指纹) == 探测指纹`），闭环缺口 #9 §4 |
| M4 | Letta/Voyager：高价值记忆应免于遗忘 | 无晶化落库通道（`shouldCrystallize` 判定存在但无 `crystallizeMacro`） | `EdgeDao.crystallize` / `FSMMacroDao.crystallize`：置 `is_crystallized=1` 并保底能量，衰减/剪枝/删除三处跳过 |

**新增测试**（cs-mem 模块从 0 → 3 个测试类，接入 CI）：
`UiTreePrunerStableEdgeIdTest`（稳定 ID 纯函数性质 + 帧稳定性 + 差分语义）、
`TraceDistillerParamExtractionTest`（参数提纯 + 端到端蒸馏）、
`BypassExecutionEngineTest`（迁移回退闭环 + 精确匹配 + 状态偏离防护 + 参数双保险）。

---

## 三、工作流对比

### 3.1 本项目现状（基线）

双引擎工作流：

- **直连路径**：`ApexAgentEngine`（Plan/Build 双模式 ReAct 循环，流式事件）
- **编排路径**：`DefaultTaskOrchestrator`（A68：状态机 + 批量工具执行 + 用户交互门 + 恢复规划器 + 多模型运行时路由），供复杂长任务使用
- **终端运行时**：`platform/terminal`（Ubuntu 24.04 rootfs + PRoot 用户态沙箱 + 原生 PTY + VT100/ANSI 终端模拟器 + 16 个 `terminal.*` 工具 + 观察引擎/等待引擎/作业管理）

### 3.2 逐项对比

#### Claude Code —— 编排者 + 压缩 + 并行工具

- **Claude Code 的做法**：主代理负责理解与拆解，子代理独立上下文执行子任务（隔离上下文爆炸）；上下文逼近上限时自动 compaction（摘要替换历史）；并行工具调用（一次 assistant 消息多个 tool_use 分片流式到达，按 index 合并）。
- **本项目对应与本 PR 修复**：
  - 压缩对齐（W1）：此前**只有 AgentEngine 有三级压缩**，经编排器执行的长任务上下文无界增长，工具输出直接入历史最终撞模型窗口。现在 `DefaultTaskOrchestrator` 注入同一 `ContextCompressor`，每轮迭代前检查 token 水位（默认 128k×80%），超限压缩并发射 `AgentEvent.ContextCompressed`——与 Claude Code compaction 同触发时机；
  - 历史处理器（W2，SWE-agent ACI 思想）：`BatchExecutionEngine` 工具结果入历史前经 `ToolOutputTruncator.smartTruncate`（JSON 保头尾 / 列表保首尾 / 代码保头），事件流仍携带完整输出，仅对话历史收窄——"给模型的视图"与"给用户的视图"分离；
  - 并行工具分片合并（W4）：OpenAI 并行工具调用的流式分片"首片带 id+index、续片只带 index"，累加器键从"id 优先"改为 **index 优先**——id 优先时首片键 `call_1` 与续片键 `_idx_0` 不一致，同一调用撕裂成两个累加器、参数 JSON 被裁断（该缺陷同时存在于 AgentEngine 与编排器，本 PR 一并修复并以回归测试锁定）；
  - 工具白名单（W5）：编排器向模型暴露的工具集按 `AgentConfig.enabledToolIds` 过滤，与 AgentEngine 同一语义，避免模型幻觉调用未启用工具。

#### OpenHands —— 事件流与沙箱运行时

- **OpenHands 的做法**：所有动作/观察皆事件、事件流可回放（审计 + 恢复）；执行在隔离沙箱；代理与模型解耦（多 LLM 路由）。
- **本项目对应**：
  - 事件流 ✓：`TerminalEventBus`/`TerminalEventLog`（终端域事件持久化）+ `TaskLifecycleEvent`（编排域）+ `AgentEvent`（引擎域，`ContextCompressed` 等事件 UI 可见）；
  - 沙箱 ✓（更进一步）：PRoot 用户态 Linux 沙箱（Ubuntu 24.04 官方 rootfs、sha256 锁定预编译二进制、工作区隔离、进程组信号、作业策略），比 OpenHands 的容器沙箱更贴 Android 无 root 约束；
  - 多模型路由 ✓：T72 `ModelRoleRouter`（含图片走 VISION 角色 + 能力校验降级）。

#### SWE-agent —— ACI（为模型设计的接口）

- **SWE-agent 的做法**：工具面（ACI）专门为模型设计——搜索/编辑/执行带护栏（文件路径安全、输出限幅）、配"历史处理器"裁剪对模型无益的冗长输出、循环检测防止模型空转。
- **本项目对应**：
  - 观察引擎 ✓（比 SWE-agent 更重）：`ObservationEngine2`/`SemanticStateReducer` 把终端原始 ANSI 输出降维成语义状态（等待输入/运行中/完成/错误分类），`InputWaitingDetector` 识别提示符——模型看到的不是乱码流而是结构化观察；
  - 护栏 ✓：`CommandPolicy`（危险命令策略）、`TerminalInputController`、`TimeoutController`、`LoopDetector`（编排器循环检测 + `PromptDetector`）；
  - 输出限幅 ✓：W2 的截断器即 SWE-agent"历史处理器"的对应物。

### 3.3 本 PR 落地的工作流修复汇总

| # | 调研结论 | 本仓库缺陷（修复前） | 本 PR 修复 |
|---|---------|--------------------|-----------|
| W1 | Claude Code compaction：上下文逼近上限自动摘要 | 编排器路径无压缩，长任务上下文无界增长 | `DefaultTaskOrchestrator` 注入 `ContextCompressor`（与 AgentEngine 共享 HybridCompressor），每轮水位检查 + `ContextCompressed` 事件 |
| W2 | SWE-agent 历史处理器：超长工具输出对模型无增益 | 编排器把工具输出全文写入对话历史（一条 shell dump 可耗尽窗口） | `BatchExecutionEngine.truncateForHistory`：入历史前智能截断（事件流仍完整） |
| W3 | OpenHands 事件流语义：正文与思考是两种事件 | 编排器把 LLM **正文**整段缓存后误当 `ThinkingChunk` 发射（UI 无法逐字渲染）；原生 `reasoning_content`（R1/Qwen3-thinking）被丢弃 | 正文流式阶段逐段转发 `ResponseChunk`；`reasoningContent` 透传 `ThinkingChunk` 并以 `ThinkingComplete` 收尾；终止信号只发一次、不重复全文 |
| W4 | OpenAI 并行工具调用分片协议（index 合并） | 累加器键 id 优先 → 首片/续片键不一致，参数 JSON 被裁断（两引擎同病） | 键改为 **index 优先**（`_idx_N`），index<0 回退 id；AgentEngine + 编排器双修，回归测试锁定分片合并 |
| W5 | Claude Code 工具可见性控制 | 编排器把全部工具定义暴露给模型（未按启用清单过滤） | `enabledToolIds` 白名单过滤，与 AgentEngine 同一语义 |

**新增测试**（`OrchestratorTestSuite` 追加 3 个回归用例）：正文流式分段、
reasoning/正文不混流、并行工具分片合并（断言合并后参数为完整 JSON）。

---

## 四、结论与 Backlog

### 已对齐（业界同水平）
分层记忆 + 异步写入 + 确定性回放 + 梦境巩固 + 免疫防御 + 沙箱运行时 + 事件流 + 观察引擎。
其中**确定性 FSM 旁路（0 token 回放）+ 免疫系统（记忆中毒防御）**在调研对象中
没有直接对应物，属于本项目的差异化设计。

### 本 PR 补齐
M1–M5（记忆：稳定边 ID/参数提纯/迁移闭环/晶化落库/作用域删除）、
W1–W5（工作流：压缩对齐/历史截断/流式语义/分片合并/工具白名单）。

### Backlog（按收益排序）
1. **宏技能语义检索**（Voyager 启发）：给 FSMMacro 描述建嵌入/关键词索引，支持跨 App 近邻复用；
2. **软删除与双时间线**（Zep 启发）：edges 增加 `invalid_at`，`pruneLowEnergy` 改标记失效，支持历史查询；
3. **子代理扇出**（Claude Code 启发）：TaskOrchestrator 支持并行子任务（子上下文隔离 + 结果汇聚），复用现有状态机；
4. **LLM 记忆仲裁**（Mem0 启发）：语义冲突场景引入轻量 LLM 仲裁（受免疫白名单约束）；
5. **Letta 式记忆块自编辑**：远期——需先完成免疫系统的 OCR 视觉比对（MLKit）作为前置防线。

---

## 参考文献与项目

- MemGPT: *Towards LLMs as Operating Systems*（Packer et al., 2023, arXiv:2310.08560）；Letta 文档（分层记忆、sleep-time compute）
- Mem0：两阶段提取-更新管线（官方文档与 MCP 实现）
- A-MEM: *Agentic Memory for LLM Agents*（arXiv:2502.12110, 2025）——Zettelkasten 式动态记忆组织
- Zep: *A Temporal Knowledge Graph Architecture for Agent Memory*（Rasmussen et al., 2025）；Graphiti（getzep/graphiti）
- Voyager: *An Open-Ended Embodied Agent with Large Language Models*（Wang et al., 2023）——技能库 + 迭代提示 + 自动验证
- Claude Code 官方文档（sub-agents、compaction、并行工具调用协议）
- OpenHands: *An Open Platform for AI Software Developers*（Wang et al.）；事件流架构文档
- SWE-agent: *Agent-Computer Interfaces Enable Automated Software Engineering*（Yang et al., 2024）——ACI 设计原则
