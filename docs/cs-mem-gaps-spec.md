# CS-Mem 未完项补全 Spec

> 范围：基于 `platform/cs-mem` 与 `app/.../ui` 的实际代码现状，补全两处已知缺口。
> 原则：最小改造量、不引三方库、复用项目已有能力（Room / Hilt / Compose Material3）。

## 现状基线（已核对代码）

- `DreamRenderer.checkVersionMigration()` 仅读取 WebView 版本号并返回字符串，**无任何图结构迁移逻辑**（占位，原注释 "完整 VF2 实现见 Phase 5 后续"）。
- `MemoryGraphStore` / `MemoryGraphStoreImpl` 无版本记录、无迁移 API。
- `NodeEntity` 无 `app_version` 字段，无法标识节点所属 App 版本。
- 抽屉导航 `DrawerDestination` 无 `Memory` 项，`ApexDrawerContent` 第 123 行显式注释移除，`ApexRoot` 路由 switch 无 Memory 分支。
- 记忆仅能在底部 `MEM` chip 间接窥见（historyDepth 条数），无独立管理页。

---

## 缺口 #9：拓扑同胚迁移（跨版本记忆保鲜）

### 问题
App 升级后 UI 拓扑变化（resourceId / className / 布局）会使旧 `SemanticNode` 指纹失效，
导致基于指纹的 FSM 宏匹配（`BypassExecutionEngine.findBestMacro`）与节点召回失准。
当前 `checkVersionMigration()` 是空壳，未做任何迁移。

### 目标
在"梦境渲染"周期内，检测 App（宿主自身 + 关键前台 App）版本变化，将旧版本节点拓扑
**映射**到新版本等价节点，使长期记忆与宏技能在版本演进后仍能复用，而非整体失效。

### 约束 / 取舍
- **不做完整 VF2 子图同构**（计算重、易误匹配）。采用**轻量属性相似度映射**：
  旧节点 → 新节点的一对一候选，依据 `role + normalizedResourceId + textHint 相似度` 打分，
  阈值以上才认定"同胚"，避免盲目重写指纹。
- 迁移**不改写旧指纹本身**（指纹是 stable key，改了会破坏去重与宏的初始/终止指纹），
  而是建立 `migration_map` 表（old_fp → new_fp + score + from_version → to_version），
  供召回/宏匹配层在解析时做"指纹别名解析"。
- 失败/低置信度时安全跳过，写入 `DreamResult.migrationNotes` 审计。

### 规格

#### 1. 存储层（Room）
- 新增实体 `MigrationMapEntity`（表 `migration_map`）：
  - `id` 自增主键
  - `oldFingerprint: String`（唯一索引）
  - `newFingerprint: String`
  - `matchScore: Float`
  - `fromVersion: String`
  - `toVersion: String`
  - `createdAt: Long`
- 新增 DAO `MigrationDao`：`upsert` / `getByOldFingerprint` / `getAll` / `deleteByOldFingerprint`。
- 现有 `NodeEntity` 增加 `@ColumnInfo(name = "app_version") val appVersion: String?`，
  `ingestNodes` 写入时从 `appPackage` 解析当前 App 版本（由 `DreamRenderer`/`CsMemSessionManager` 注入，
  避免 Store 直接依赖 `PackageManager`——Store 保持纯净）。
- `MemoryGraphStore` 接口新增：
  - `suspend fun recordMigration(map: MigrationMap)`
  - `suspend fun resolveMigration(oldFingerprint: String): String?`（返回 newFingerprint 或 null）
  - `suspend fun getMigrationMaps(): List<MigrationMap>`
- `MemoryGraphDatabase` `entities` 加入 `MigrationMapEntity::class`，`version` 升为 `2`，
  提供 `autoMigration`（Room 2.x）或 `fallbackToDestructiveMigration(false)` + 显式 `Migration` 对象
  （保留旧数据，仅加表/加列）。

#### 2. 映射算法（新建 `dream/TopologyMigrator.kt`）
- 输入：旧版本节点集（按 `appVersion` 分组、`appPackage` 过滤）、新版本节点集。
- 对每个旧节点，在**同 appPackage + 同 role** 的新节点中按以下打分选最佳候选：
  - `resourceId` 归一化（去包名前缀、转小写）相等：+0.5
  - `textHint` 包含/被包含或 Levenshtein 相似度 > 0.8：+0.4
  - `className` 末级类名相等：+0.1
  - 总分 ≥ 0.7 视为同胚映射。
- 输出 `List<MigrationMap>`，经 `store.recordMigration` 落库。

#### 3. 接入点
- `DreamRenderer.checkVersionMigration()` 改为：
  - 读取宿主 `context.packageName` 的 `versionName`（替换原 WebView 占位逻辑）；
  - 取 store 中记录的"上一次已知版本"（落 `MigrationMapEntity` 的 `toVersion` 或新增 `meta` 表，
    优先用 `MigrationMapEntity` 最近一条 `toVersion`）；
  - 若版本不同 → 调 `TopologyMigrator` 生成映射并落库，返回变更摘要（迁移了多少对、覆盖率）；
  - 返回 `String?`（审计用）。
- `CsMemSessionManager.ingestNodes` 路径透传 `appVersion`（在 `afterAction`/`captureInitialState`
  中用 `privilegeManager` 或 `context` 取版本，写入节点）。

#### 4. 别名解析（召回增强，可选但推荐）
- `CsMemRecallTools` 的 `MemorySearchNodesTool` / `MemoryRecallMacroTool` 在拿到指纹后，
  先经 `store.resolveMigration(fp)` 做别名解析，使旧宏在迁移后仍可被召回/匹配。

### 验收
- [ ] Room 升级到 version 2，旧库数据存活（加列/加表，非 destructive）。
- [ ] App 版本变化触发后，`getMigrationMaps()` 返回非空且 `matchScore≥0.7`。
- [ ] 同版本重复运行 `checkVersionMigration` 不产生重复映射（幂等 upsert）。
- [ ] `DreamResult.migrationNotes` 含可读摘要（如 "migrated 12 nodes app=com.x v1.2→v1.3"）。

---

## 缺口 #12：记忆可视化独立页（MemoryScreen）

### 问题
CS-Mem 作为底层引擎运行，但用户无法查看/管理已沉淀的记忆（Episode / 节点 / 宏技能），
只能从抽屉底部 `MEM` chip 看到粗略条数。

### 目标
新增抽屉导航项 `Memory`，进入一个独立管理页，列出记忆概览（Episode 数 / 节点数 / 宏数）、
近期 Episode、高频宏技能，支持按关键词搜索节点、删除单条 Episode（带确认）。

### 约束 / 取舍
- 复用 `MemoryGraphStore` 已有只读 API（`getRecentEpisodes` / `getTopMacros` / `searchNodesByText` / `getNodesByRole`）
  与 `EpisodeDao.delete`，**不加新查询接口**（除非确实缺失）。
- 纯 Compose Material3，霓虹风格与现有 `SkillScreen` / `ContextMeterBar` 一致，**不引三方库**。
- 页面只读为主，删除 Episode 为破坏性操作，需 `AlertDialog` 二次确认。

### 规格

#### 1. 路由与导航
- `ApexRoot.kt` `DrawerDestination` 新增 `data object Memory : DrawerDestination("memory", "记忆", Icons.Default.Storage)`。
- `ApexDrawerContent.kt` destinations 列表取消第 123 行注释，加入 `DrawerDestination.Memory`
  （位置：放在 `Skill` 之后、`Model` 之前或抽屉中部，视觉分组合理即可）。
- `ApexRoot.kt` `when(currentDestination)` 新增 `DrawerDestination.Memory -> MemoryScreen()`。
- 图标：`Icons.Default.Storage`（Material3 内置，无需新增资源）。

#### 2. ViewModel（`MemoryViewModel`）
- 注入 `MemoryGraphStore`。
- `uiState`：`episodes: List<EpisodeSummary>`、`macros: List<FSMMacro>`、`searchQuery: String`、
  `searchResults: List<SemanticNode>`、`stats: MemoryStats(episodeCount, nodeCount, macroCount)`。
- 方法：`refresh()`（拉取 episodes/macros/stats）、`onSearch(query)`（调 `searchNodesByText`）、
  `deleteEpisode(id)`（调 `store` 的删除路径 → 实现见下）、`clearSearch()`。

#### 3. 存储删除能力（补齐）
- `MemoryGraphStore` 接口新增 `suspend fun deleteEpisode(episodeId: String)`（含其关联 edges 清理）。
- `MemoryGraphStoreImpl` 实现：事务内 `episodeDao().delete(id)` + `edgeDao().deleteByEpisode(id)`
  （edges 已在 `EdgeDao`，确认有 `deleteByEpisode`；若无则用 `getEdgesByEpisode` 批量删）。
- 注意：nodes 为跨 Episode 共享字典，**不随 Episode 删除而硬删**（仅降能，交由熵遗忘回收）。

#### 4. UI（`MemoryScreen.kt`，新建 `app/.../ui/screen/memory/`）
- 顶部 `TopAppBar` 标题"记忆"，右侧搜索 `IconButton`（展开 `OutlinedTextField` 或跳转搜索区）。
- 概览卡片行：三张小卡显示 `stats`（Episode / Node / Macro 计数），霓虹描边风格。
- 搜索区：`OutlinedTextField` 输入关键词 → 实时 `searchNodesByText`，结果以 `LazyColumn` 列出
  （textHint + role + appPackage，点击可复制或仅展示）。
- 近期 Episode 列表：`LazyColumn` 卡片显示 goal / status / 时间 / 动作数，右侧删除 `IconButton`
  → `AlertDialog` 确认 → `deleteEpisode`。
- 高频宏列表：折叠/分区显示 `getTopMacros`，展示 name / successCount / appPackage（只读，不编辑）。
- 空态：与 `SkillScreen` 一致的居中提示。

### 验收
- [ ] 抽屉出现"记忆"项，点击进入 `MemoryScreen`，无崩溃。
- [ ] 概览计数与 store 实际数据一致。
- [ ] 搜索关键词能召回 `searchNodesByText` 结果。
- [ ] 删除 Episode 后有确认弹窗，确认后列表即时刷新且 store 中对应 Episode 消失。
- [ ] 所有改动文件 `0` lint 错误。

---

## 实施顺序
1. spec 文档（本文件）。
2. #9 存储层：实体 + DAO + 接口 + 数据库升级 + 节点 appVersion 字段。
3. #9 映射：`TopologyMigrator` + `DreamRenderer.checkVersionMigration` 实装 + 召回别名解析。
4. #12 存储删除能力 + `MemoryViewModel` + `MemoryScreen` + 导航接线。
5. lint 全检 + diff 摘要。
