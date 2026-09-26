# 消息分支与会话树 — MessageBranchNode 模型（P93）

> 状态：已实施 v1（Task 4-c）· 对比文档差距项 P93（[operit-rikkahub-comparison.md](operit-rikkahub-comparison.md) §4.4「本次对比最重要的单一特性」/§6.2）
> 前置：无（apex 此前 ChatHistoryMessage 线性扁平、无 parentId、恢复仅 user/agent 文本对）
> 业界对标：rikkahub `Conversation.messageNodes` + `MessageNode(messages[], selectIndex)` 全链路（ChatService 九路径）· apex 自家 FileTaskStore/LongTaskStore 持久化纪律

## 一、动机与模型选择

对比文档 §4.4 把 rikkahub 的消息分支评为**最值得复制的单一特性**：regenerate 的每次
结果、编辑的每个改写都是「同一槽位的候选」，UI 用 ◀ n/total ▶ 切换，原始消息永不丢失。
apex 现状是完全线性历史，regenerate = 覆盖，edit = 丢失。

### 为什么不是 parentId 树？

**设计本质：不是树，而是「节点列表 + 每节点候选数组 + 选中下标」**。比真正的
树（parentId 图）简单一个量级，且对话 UI 天然线性：

```
ConversationTree（"会话树"是历史叫法）
 nodes:
 ┌──────────────────────────────────────────────────────────────┐
 │ 节点 n0 (user)      candidates=[m1]               sel=0       │
 │ 节点 n1 (assistant) candidates=[m2, m3, m4]       sel=2 ◀▶    │
 │        │                └─再生成1    └─再生成2（当前）          │
 │        └─ 原始回复                                          │
 │ 节点 n2 (user)      candidates=[m5]               sel=0       │
 │ 节点 n3 (assistant) candidates=[m6, m7]           sel=0       │
 │        └─ 原始          └─ "{m6}_edit"（编辑=新分支）          │
 └──────────────────────────────────────────────────────────────┘
 currentMessages = nodes.mapNotNull { it.currentMessage }   ← 线性渲染免费
```

- **线性渲染免费**：`currentMessages` = 各节点选中消息按序拼接（空节点/坏下标节点
  跳过），LazyColumn 按节点渲染 `node.currentMessage`，分支切换只改 selectIndex；
- **regenerate/edit 有机成支**：再生成 = 同节点追加候选；编辑 = 新候选新 id——
  两者天然成为「◀ n/total ▶」的可切换分支，无需专门的树结构；
- 术语对位：rikkahub UIMessage→`BranchMessage`、MessageNode→`MessageBranchNode`、
  Conversation→`ConversationTree`。

## 二、数据模型（branch/BranchModels.kt，234 行）

| 类 | 关键设计 |
|----|----------|
| `MessageRole` | 四角色枚举：USER / ASSISTANT / SYSTEM / TOOL |
| `BranchMessage` | id/role/content 必填；createdAt/toolName/thinking/meta 全默认值（旧 JSON 安全）；`isEmptyPlaceholder()` 识别再生成空占位（assistant + 空 content） |
| `MessageBranchNode` | candidates 候选数组（顺序=产生顺序）+ selectIndex；`currentMessage` **守卫式空安全**（RikkaHub 抛 IllegalStateException vs apex 折叠 null）；`select(index)` 钳制；role 取首候选（空节点回退 USER）；`isSwitchable()`（>1 候选才显示选择器） |
| `ConversationTree` | 只读数据类 + 线性化 currentMessages / messageCount / assistantTurnCount / branchNodeCount / nodeOf / indexOfNode / findMessage；一切变更经 Ops 返回新树（写时复制，无锁读） |

与 apex 现有 ChatHistoryMessage 的差异：带稳定 `id`（分支槽位配对的关键——
「按 message.id 匹配则替换，否则 append」的幂等重试语义依赖它）；role 用类型安全
枚举而非字符串。

## 三、九个操作语义（branch/ConversationTreeOps.kt，453 行）

RikkaHub ChatService 全部分支变更路径的纯函数移植（全部返回新树，不可变纪律）：

| # | rikkahub 对位 | apex 函数 | 语义要点 |
|---|---------------|-----------|----------|
| 1 | 发送用户消息 | `appendUserMessage` | 新节点（单候选）；user 永远是新槽位（不可再生成）；节点 id "{treeId}-n{size}" 派生 |
| 2 | updateCurrentMessages（append 且选中） | `appendAssistantCandidate` | 尾节点是 assistant → 追加候选**并选中**；否则新 assistant 节点（回合定稿入口；流式请用 #4） |
| 3 | regenerateAtMessage（预创建空消息） | `regenerateSlot` | 追加空占位候选（content 空、createdAt 取注入 clock）并选中，返回 `RegenerateInfo(placeholderMessageId)` 回填句柄；**幂等重入**：选中候选已是空占位 → 直接复用其 id 返回原树——崩溃/重试不产生重复分支；调用方提供的 id 撞树内既有消息 → 防御回退自动派生 "{nodeId}_regen" |
| 4 | updateCurrentMessages（按 id 匹配替换） | `upsertCandidate` | 树中任意节点有同 id 候选 → **原位替换**（selectIndex 不动）；无 → 走 append。**流式分片/重试幂等落树的唯一入口**：首片 append 入树，后续分片全走原位替换 |
| 5 | editMessage | `editMessage` | keepHistory=true（默认）：新候选**全新 id** "{id}_edit"（撞车加序号；角色/createdAt/toolName/thinking/meta 继承，仅 content 换新）并选中——编辑即分支；false：同 id 原位改写不留历史 |
| 6 | buildConversationAfterMessageDelete | `deleteCandidate` | 候选删 + selectIndex 钳入缩小区间（删中的是选中分支 → 落到后继/末位）；节点清空 → 整节点移除 |
| 7 | selectMessageNode（◀ ▶ 切换） | `switchBranch` / `switchBranchRelative` | 绝对/相对（selectIndex += delta）切换；边界**不回绕**（守卫返回原实例） |
| 8 | forkConversationAtMessage | `forkAt` | 前缀深拷贝（含该消息节点及之前**全部候选**——分支历史完整带过去）；节点/消息全新 id（默认 "{old}_fork"，idGenerator 可注入，非单射生成器撞车加序号）；selectIndex 保留；标题 + 后缀；clock 盖 createdAt/updatedAt |
| 9 | checkInvalidMessages + 截断 | `sanitize` / `trimToMessage` | sanitize 四类修复（下）；trimToMessage 保留含该消息节点的前缀（再生成前上下文裁剪） |

### sanitize 四类修复（RikkaHub checkInvalidMessages 的泛化升级）

1. **空节点丢弃**（candidates 为空不占线性位置）；
2. **selectIndex 钳制**（负数/超上限 → coerceIn）；
3. **重复消息 id 去重**（全树首现保留——findMessage/upsert 的「首个命中」语义依赖唯一）；
4. **createdAt 乱序修复**：仅严格逆序时稳定排序（相等保原序）；已健全返回原实例
   （`===` 判定，避免无谓复制）。

失败路径纪律：找不到 id / 非法参数**不抛**——返回原实例（=== 可判定「无操作」）或
null（fork/regenerate 这类「必须命中才有产物」）。时间戳纪律：操作层不盖 updatedAt
（结构变更 ≠ 内容定稿），持久化层统一盖章；例外是 forkAt/regenerateSlot 显式注入 clock。

### 再生成流（RikkaHub「预创建消息 id」技巧的移植）

```
1. regenerateSlot(tree, nodeId) → 树上追加空占位并选中（返回占位 id）
2. LLM 流式生成 → 分片累计到 BranchMessage(id = 占位 id)
3. upsertCandidate(tree, partial)  → 按 id 原位替换（不新增候选）
4. 重复 3（重试/续流）              → 仍原位替换（幂等，无重复分支）
崩溃后重入：regenerateSlot 发现选中候选已是空占位 → 复用其 id（不追加第二个占位）
```

## 四、文件级持久化（branch/ConversationTreeStore.kt，362 行）

```
<dir>/                        DI 注入（App: filesDir/conversations；测试: 临时目录）
  ├── {treeId}.json           一个会话一棵树（encodeDefaults 完整快照）
  ├── {treeId}.json.tmp       写入中 temp（崩溃残留，首载清理）
  └── {treeId}.json.corrupt   损坏隔离（解析失败改名备份，不删除）
```

- **原子写**：tmp → flush → `fd.sync()` → 同目录 rename；rename 失败回退 copyTo +
  delete。进程任何时刻死亡，读侧只见完整旧文件或完整新文件；
- **防御式读**：损坏 JSON → 改名 .corrupt 隔离 + logger 留痕 + 当作不存在（null），
  绝不抛——一棵坏树不能让会话列表瘫痪；以**文件内容里的 tree.id** 为缓存键
  （文件名只用于初筛与校验）；
- **id 纪律**：treeId 只允许 `[a-zA-Z0-9_-]{1,64}`（挡路径穿越）——save 收到非法 id
  require 失败（我方 bug fail-fast）；load/delete 收到非法 id → null/false（外部输入
  查询语义）；
- **并发模型**（对齐 LongTaskStore/PromptTemplateRegistry）：@Volatile 不可变快照缓存 +
  协程 Mutex 串行读改写 + 惰性双检加载（首访扫一次目录）；`listIds()` / `snapshots()`
  同步快照读（TreeSummary：id/title/messageCount/updatedAt/branchCount=多候选节点数，
  updatedAt 降序同戳按 id 升序）；
- `save(tree)` 盖 clock 戳到 updatedAt 并**返回盖章后的树**（调用方应改用返回值——
  它是落盘的那份）；写失败留痕不抛（内存为准，下次保存全量重写追平）。

### fromLinearMessages 迁移（线性历史 → 会话树）

分组规则（**有意为之的迁移语义**）：USER/SYSTEM 各自独占新节点；**连续的
ASSISTANT/TOOL 消息（一个 agent 回合）合并进同一节点——回合首条消息为唯一候选**。
回合内的工具输出、后续 assistant 片段不生成候选：分支候选是「同一槽位的可切换
备选」，工具链消息是回合组成部分而非备选——塞成候选会制造假的「◀ 2/3 ▶」选择器。
apex 现有恢复路径本就只取 user/agent 文本对，口径一致；需要保留完整工具链的调用方
应在调用前自行拼接线性输入（meta 字段可携带附加信息）。

## 五、测试矩阵（81 用例全绿）

| 套件 | 数量 | 覆盖 |
|------|------|------|
| BranchModelsTest | 18 | select 钳制、currentMessage 空安全（空节点/坏下标→null）、role 取首候选与空回退、isSwitchable、roundtrip 字段逐项抽样、旧 JSON 缺字段前向兼容、线性化跳过空/坏节点 |
| ConversationTreeOpsTest | 48 | 九操作全语义：再生成→流式回填→重试三步全链路（「同 id 两次 upsert 单候选无重复」）、regenerateSlot 幂等重入复用占位 id、提供的 id 撞树回退派生、edit keepHistory 新 id 继承字段/原位改写、delete 钳制与整节点移除、fork 非单射生成器去重 + selectIndex 保留 + 全候选深拷贝、trim 前缀裁剪、sanitize 四类修复组合（空节点/越界下标/重复 id/严格逆序排序，相等保序）、**操作不改源树的不可变性**（=== 与字段比对） |
| ConversationTreeStoreTest | 15 | roundtrip 字段抽样、损坏 .corrupt 隔离且好树不受影响、删除、id 校验拒绝 `../evil` 与超 64 位、fromLinearMessages 分组（user 独占/回合合并/首条为候选）、runTest 5 并发 save 最终内存=磁盘一致、分支操作全链路 save-load 生存 |

验证：`:core:agent-engine:test` BUILD SUCCESSFUL——模块 627 tests / 0 failed / 1 skipped
（新增 81 个全绿，既有 546 个零回归）。首轮 3 处失败全为测试断言笔误
（assistantTurnCount 误算、currentMessages 误含未选中候选、meta.size 误写——均为
期望值错误非实现缺陷），修正后全绿。

## 六、app 层迁移路径（对比文档 §6.2 P93 + worklog 4-c 挂点）

### 6.1 UI 形态（消息卡 + 分支选择器）

```
┌─────────────────────────────────────────────┐
│ 用户：帮我重构这段代码                        │  ← 节点 n0（单候选，无选择器）
├─────────────────────────────────────────────┤
│ Apex Agent：方案 A……                         │  ← 节点 n1（3 候选）
│        ◀  2 / 3  ▶        ↻ 再生成  ✎ 编辑   │     selectIndex=1
├─────────────────────────────────────────────┤
│ 用户：第 2 步展开讲讲                         │  ← 节点 n2
└─────────────────────────────────────────────┘
   ◀ ▶ 边界不回绕；↻ → regenerateSlot + upsertCandidate 回填；
   ✎ → editMessage(keepHistory=true) 即新增候选；长按 → forkAt 从此分支
```

### 6.2 接线步骤

| 环节 | 落点 |
|------|------|
| 历史迁移 | ChatHistoryMessage(role/text/toolName/timestamp) → BranchMessage（id 自造如 "msg-{sessionId}-{index}"，role 字符串→MessageRole 枚举）→ `fromLinearMessages` 一行成树；存 dir=File(filesDir, "conversations") |
| 发送流 | 用户发送 appendUserMessage + store.save；流式回合 regenerateSlot 拿占位 id，每个分片/最终回填走 upsertCandidate（幂等可重试）；重试/崩溃恢复直接重入 regenerateSlot 自动复用占位 |
| UI | AgentMessageActions 加「从此处分支/再生成/编辑/删除候选」入口映射 switchBranchRelative/editMessage/deleteCandidate；消息卡内嵌「◀ {selectIndex+1}/{candidateCount} ▶」仅 `isSwitchable()` 时显示；**LazyColumn key = node.id**（同一节点换候选不重建卡片） |
| 引擎恢复 | 上下文恢复沿 currentMessages 回放 toLlmMessage（user/assistant 对）；分支切换即切换恢复路径 |
| 过渡策略 | SharedPrefsConversationMemory 与新 store 并行过渡；MAX_SESSIONS 上限裁剪改走 store.snapshots + delete |

## 七、设计取舍与常见问题

**Q: 为什么 currentMessage 折叠 null 而不是像 rikkahub 抛异常？**
apex 纪律是防御式折叠 + sanitize 修复：损坏数据（手改 JSON/崩溃残留）在线性化时
静默跳过，而不是让整条渲染链崩掉——修复交给 sanitize，读取永远是安全的。

**Q: upsertCandidate 为什么是流式落树的「唯一入口」？**
按 id 匹配则替换的语义把「首片 append、后续替换、重试幂等」三种流式形态统一成
一个操作——候选数恒等于「回合数 + 再生成次数」，永无重复分支。直接用
appendAssistantCandidate 落分片会每次都追加新候选。

**Q: fork 为什么连未选中的候选也复制？**
fork 是「从这句话开始的另一个世界」——用户之后随时可能 ◀▶ 切到别的候选继续；
丢掉候选的 fork 是半成品。全新 id 保证两棵树的 upsert/switch 互不串线。

**Q: 删除操作失败为什么返回原实例而不是 false？**
操作层是纯函数（无 IO），失败=无操作=原树原引用；`===` 判定即「无操作」信号，
Boolean 返回值留给有真实成败语义的 Store 层（delete）。

**Q: sanitize 为什么只在「严格逆序」时才排序？**
相等时间戳在合法数据里常见（同回合消息、假钟 0 默认值）；相等保原序
（TimSort 稳定 + 仅严格逆序触发）避免无害数据被重排——重排会改变线性化顺序，
代价大于收益。

**Q: store 删除失败为什么「复活比崩溃好」？**
delete 先移内存再删磁盘，磁盘删除失败只留痕——重启后该树可能复活。复活（用户
再删一次）是可恢复的小意外；崩溃（删除失败上抛）则中断整个会话列表操作。

## 八、后续路线

- app 层接线（§六五步）；ChatHistoryManager 全面切换后移除旧 SharedPrefs 路径；
- 摘要列表分页（对比文档 §4.4：rikkahub 用 Paging3 的「列表轻、详情重」——
  TreeSummary 已按此分层预留）；
- 工具链完整迁移（trimToMessage 前手动拼接工具消息，或 meta 携带回合工具摘要）。

## 附：文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/agent-engine/.../engine/branch/BranchModels.kt` | 234 | MessageRole/BranchMessage/MessageBranchNode/ConversationTree |
| `core/agent-engine/.../engine/branch/ConversationTreeOps.kt` | 453 | 九操作纯函数 + RegenerateInfo/RegenerateResult + sanitize + id 派生 |
| `core/agent-engine/.../engine/branch/ConversationTreeStore.kt` | 362 | 文件式仓库 + TreeSummary + fromLinearMessages |
| `core/agent-engine/.../engine/branch/BranchModelsTest.kt` | 327 | 18 用例 |
| `core/agent-engine/.../engine/branch/ConversationTreeOpsTest.kt` | 684 | 48 用例 |
| `core/agent-engine/.../engine/branch/ConversationTreeStoreTest.kt` | 418 | 15 用例 |
