# API Key Pool — 多 Key 轮换与健康管理（P90）

> 状态：已实施 v1（Task 4-a）· 对比文档差距项 P90（[operit-rikkahub-comparison.md](operit-rikkahub-comparison.md) §6.1/§6.2）
> 前置：`ProviderConfig.apiKeys: List<String>` 与 `KeyRotationMode` 枚举早已预埋（ModelProfile.kt 数据层），无实现
> 业界对标：operit `MultiApiKeyProvider` + `ApiKeyPoolAvailabilityTester`（三态可用性 + 持久化游标）· rikkahub `LruKeyRoulette`（未用优先 + LRU 负载分摊）

## 一、动机

对比文档 §3.4 把「Key 管理」总结为同一功能的三种工程形态：operit 是**池 + 测试器**、
rikkahub 是**轮盘**、apex 此前是**预埋的数据层**——`LlmConfig.kt:183` 只取
`provider?.apiKeys?.firstOrNull()`，多填的 key 永远沉默。差距直接指向长任务场景：

- **429 限流打断长任务**：apex 引擎一次任务可发起几十通 LLM 请求，撞上配额墙时
  只能靠 `LlmClientFactory` 的 RetryInterceptor 原地退避重试——同一把 key、同一条墙；
- **坏 key 无健康档案**：401/403 与 429/5xx 对 key 生命周期的含义完全不同（前者
  是「key 死了」，后者是「key 还活着但被限流」），此前无任何区分；
- **对比文档结论**（§6.2 P90）：轮换必须落在**请求层包装器**——`ModelRuntimeRegistry`
  以 `LlmConfig` 相等为缓存键，改 config 换 key 会击穿缓存语义。

本包补上池逻辑本体（纯 JVM、不碰网络）+ 请求层编排（`KeyPoolLlmClient`），
合计 3 个 main 文件 962 行、2 个测试文件 1112 行。

## 二、数据模型（keypool/ApiKeyPoolModels.kt，251 行）

### 2.1 Key 状态机：三态 + 冷却

| 状态 | 含义 | 进入方式 | 退出方式 |
|------|------|----------|----------|
| UNTESTED | 从未测试/使用 | 新增 key | 首次成功 → AVAILABLE；失败上报分流 |
| AVAILABLE | 已确认可用 | 成功调用 / 测试器标记通过 | 401/403 失败 → UNAVAILABLE |
| UNAVAILABLE | 鉴权死 key | 401/403 失败、人工标记 | 仅 markTested / resetKey 显式复活 |
| COOLDOWN | 限流冷却中 | 429/5xx/网络失败 → 指数退避 | cooldownUntilMs 到点自动回归（选中时自愈翻回 AVAILABLE） |

与 operit 的差异（刻意）：增加 COOLDOWN 态——operit「测一次失败就出局」的严格三态
会让线上流量标记（而非人工测试）把新鲜池直接判死。

### 2.2 核心数据类

| 类 | 职责 | 关键字段 |
|----|------|----------|
| `ApiKeyEntry` | 单把 key 的健康档案 | id/label/key/enabled/status/usageCount/totalFailures/consecutiveFailures/lastUsedAt/lastErrorCode/lastErrorMessage/cooldownUntilMs |
| `KeyPoolState` | 可序列化持久化唯一载体 | entries（保配置序）/cursorIndex/rotationMode/preferUnusedKeys |
| `KeySelection` | 单次 acquire 结果 | entry（可空）/outcome/skippedCount/reason |
| `KeyPoolSnapshot` | 脱敏只读快照（UI/诊断） | keys: List<KeyHealthInfo> + 各状态计数 + summary() |

- `lastErrorCode` 约定：**0 = 网络层错误哨兵**（无状态码但值得冷却）、**-1 = 无码错误**
  （仅计数）、正值为真实 HTTP 码；
- `maskKey()`：长度 ≤ 8 完全打码 `••••`；否则保留前 3 + `…` + 后 4——快照/异常/日志里
  唯一的 key 呈现形态（对齐 ModelRuntimeErrors.kt「绝不携带 API Key」纪律）；
- 所有 `@Serializable` 字段全带默认值：旧 JSON 无损加载（ModelProfile.kt 演进纪律）。

## 三、池本体（keypool/ApiKeyPool.kt，443 行）

### 3.1 两档候选门控（防死锁的关键设计）

```
全部 entries
    │ 过滤 enabled == false（一律排除）
    ▼
┌─ 一档候选（Operit 三态门）──────────────────────────┐
│  AVAILABLE + 冷却到点的 COOLDOWN（自愈）            │
│  一档非空 → UNTESTED 被门住（结论优先于没测）        │
└────────────────────┬────────────────────────────────┘
                     │ 一档全灭（全死/全冷却）
                     ▼
┌─ 回退档（apex 防死锁回退）──────────────────────────┐
│  UNTESTED —— 严格三态门会让"新鲜池第一次限流"即死锁  │
│  （试过的进冷却、没测的被门住、永远选不出 key）      │
└─────────────────────────────────────────────────────┘
    │ 两档皆空：有冷却中的 key → ALL_COOLING_DOWN（有救，等）
    │           否则               → ALL_UNAVAILABLE（重测/重置）
    ▼ UNAVAILABLE 永远出局，需 markTested/resetKey 复活
```

### 3.2 四模式语义（acquire 按模式选 key）

| 模式 | 取 key 规则 | 游标副作用 | 适用 |
|------|-------------|-----------|------|
| DISABLED | 永远取候选列表第一个（sticky） | 不动 | 单 key / 用户明说不轮换 |
| SEQUENTIAL | 从游标起向后找第一个候选，绕回开头 | 选中后游标 = 下标+1 | 均匀轮询（operit currentKeyIndex） |
| ON_ERROR | 粘性：preferUnusedKeys=true（默认）时未用优先 → LRU → 列表序；false 时同扫描式粘住 | 回写选中下标（rotateCursor 前进） | 失败才换 |
| ON_RATE_LIMIT | 同 ON_ERROR | 同上 | 仅 429 才换（严格按模式名） |

- RikkaHub `LruKeyRoulette` 语义即 `preferUnusedKeys=true`：从未用过（usageCount==0）
  的桶优先、桶内按 lastUsedAt 升序（LRU）、并列取列表序——**无 Random**（RikkaHub 的
  Default 轮盘用随机，这里为可测试性改确定性 LRU）；裸 acquire 不改统计，重复调用结果稳定；
- 选中「冷却已到点」的 key 时顺带把状态翻回 AVAILABLE（自愈双保险：
  `isCoolingDown` 同时校验状态与时间）。

### 3.3 失败分类（reportFailure 池内纯状态机）

| 上报码 | 判定 | 效果 |
|--------|------|------|
| 401 / 403 | key 大概率已死 | → UNAVAILABLE，不设冷却，需显式复活 |
| 429 / 5xx / code==0（网络哨兵） | 暂时性失败 | → COOLDOWN + 指数退避 `now + min(30s·2^(连续失败-1), 30min)` |
| 其它（400/404/-1 无码） | 请求级错误 | 只累计统计，不动状态（换 key 也一样错，不惩罚 key） |

退避序列 30s / 60s / 120s / 240s … 封顶 30min（`BASE_COOLDOWN_MS=30_000` /
`MAX_COOLDOWN_MS=1_800_000`）；倍增用循环实现，触顶即停防 Long 溢出。
错误消息截 200 字符（`MAX_ERROR_MESSAGE_LENGTH`）防巨响应体撑爆持久化。

### 3.4 线程模型与池管理

- 所有变更走 `Mutex` 串行 + **不可变克隆**（copy + 新 List）——同一时刻只有一次选 key，
  游标不被并发抢跑（operit getApiKey 同款）；`snapshot()` 无锁直读 @Volatile 副本；
- `reportSuccess`：usageCount++ / lastUsedAt=now / 连续失败清零 / 翻 AVAILABLE——
  **UNAVAILABLE 例外**：鉴权死 key 不因一次偶发成功复活；
- `markTested(available)`：测试器回写口，只翻状态清冷却，不动计数（两本账）；
- `addKey`（upsert，只追加队尾保 DISABLED 首位语义）/ `removeKey`（游标补偿：删游标
  之前的条目则游标减一）/ `setEnabled` / `resetKey`（全归零）/ `rotateCursor`（+1 绕回）；
- `currentState()` / `replaceState()`：持久化层读写口（app 落盘/恢复）。

## 四、请求层编排（keypool/KeyPoolLlmClient.kt，268 行）

实现 `LlmClient` 接口的包装器：每次调用 acquire → delegateFactory 现建真实 client
（如 `StreamingOpenAiClient(config.copy(apiKey = key))`）→ 失败按模式换 key 重试。
**必须在请求层做**（对比文档 §6.2 P90 风险项）：ModelRuntimeRegistry 缓存以
LlmConfig 相等为键（`runtime/ModelRuntimeRegistry.kt:34-35` 的 `clientFactory` 是
app 层装配点），轮换若发生在 config 层会击穿缓存。

```
调用方                        KeyPoolLlmClient                ApiKeyPool
  │ chat()/chatStream() ─────────► │                                │
  │                                │ acquire(mode) ────────────────► │
  │                                │◄──── KeySelection(entry) ───────│
  │                                │ delegateFactory(entry.key)      │
  │                                │ delegate.chat(...)              │
  │              失败(401/403/429) │ reportFailure(id, code) ──────► │ 记账+冷却/判死
  │                                │ rotateCursor()（非 SEQUENTIAL）► │ 粘性换下一把
  │                                │ triedIds 去重 + maxKeyRetries 闸 │
  │                                │ ……环：直到成功 / 耗尽 / 不可重试 │
  │◄── 成功结果 / 重抛最后原始错误 │ reportSuccess(id) ────────────► │
  │     （开局即空池才抛 KeyPoolExhaustedException）
```

- **值得换 key 的矩阵**（isRotationWorthy）：ON_ERROR/SEQUENTIAL = 401/403/429
  （5xx 需 `rotateOnServerErrors=true`，默认关——多为端点问题换 key 无益）；
  ON_RATE_LIMIT = **仅 429**；DISABLED = 不换（坏 key 在下一通调用被候选过滤自然跳过——
  跨调用兜底而非调用内轮换）；
- 每通调用最多尝试 `maxKeyRetries`（默认 3）把**不同** key（`triedIds` 去重防绕回打转）；
  耗尽**重抛最后一个原始错误**（保真上层 ErrorClassifier 分类）；一把都没试成才抛
  `KeyPoolExhaustedException`（携带 outcome/mode/attemptedKeyIds，引导用户去测试页/等冷却，
  绝不携带 key 材料）；
- **流式只在首块之前可重试**：`emitted` 标记——已吐过块（半途失败）绝不重放
  （重复输出），直接上传播同时照常 reportFailure 让下一通自然换 key；
- CancellationException 原样上抛（协作取消绝不吞）；Error（OOM）不做池记账直接上抛；
- 秘钥卫生：key 材料只流向 delegateFactory；写入池的消息先剥除 key 子串
  （`[redacted]` 占位）再截断。

## 五、测试矩阵（52 用例全绿）

| 套件 | 数量 | 覆盖 |
|------|------|------|
| ApiKeyPoolTest | 34 | 假钟退避数学（30s→60s→…→30min 封顶）、两档候选门（三态门/UNTESTED 回退/死锁防御）、四模式取 key（游标前进/绕回/粘性回写）、LRU 排序（未用优先/lastUsedAt 升序/列表序并列）、自愈（冷却到点选中翻回 AVAILABLE）、reportSuccess 不复活 UNAVAILABLE、markTested/resetKey/addKey（upsert）/removeKey（游标补偿）/setEnabled/rotateCursor、maskKey 脱敏、快照计数 |
| KeyPoolLlmClientTest | 18 | 换 key 重试全流程（acquire→失败→reportFailure→rotateCursor→下一把）、模式矩阵（DISABLED 不换/ON_RATE_LIMIT 仅 429/ON_ERROR 含 401/403）、maxKeyRetries 耗尽重抛原始错误、开局空池抛 KeyPoolExhaustedException、流式首块前可重试/吐块后不重试、CancellationException 透传、错误消息脱敏（key 子串被 [redacted]） |

验证：`:core:llm-adapter:test` 强制重跑 BUILD SUCCESSFUL——模块 184 tests / 0 failed
（新增 52 个，既有 132 个零回归）；假钟 `() -> Long` 注入驱动全部时间分支，零墙钟睡眠。

## 六、app 层接入指南

| 环节 | 落点 |
|------|------|
| 多 Key 持久化 | SettingsRepository / securePrefs 改多键存储（`provider_api_key_{id}_{idx}`）或直接序列化 `KeyPoolState`（全字段默认值 JSON 安全），hydrate 全列表后 `replaceState` |
| clientFactory 包装 | DI 装配 `ModelRuntimeRegistry(clientFactory = { config -> KeyPoolLlmClient(pool, mode, { key -> LlmClientFactory.create(config.copy(apiKey = key)) }) })`——池与测试器共享同一实例 |
| 模式来源 | `ProviderConfig.keyRotationMode`（ModelProfile.kt:77）已有枚举直读 |
| 健康面板 | `pool.snapshot()` 即数据源：KeyHealthInfo 列表 + 各状态计数 + cooldownRemainingMs（「等待 N 秒冷却结束」提示） |
| 测试器回写 | 对齐 operit ApiKeyPoolAvailabilityTester：并发探测后 `markTested(id, available)`（只翻状态不动计数） |

## 七、常见问题

**Q: 为什么失败先 reportFailure 再决定换不换 key？**
池记账与重试决策解耦：即使本模式不重试（如 DISABLED），401 也要记进健康档案，
让**下一通调用**的候选过滤跳过坏 key——跨调用兜底与调用内轮换是两层独立的防线。

**Q: ON_RATE_LIMIT 遇到 401 为什么不换 key？**
严格按模式名语义：限流/配额才换。鉴权失败的 key 已被判 UNAVAILABLE 出局，
下一通 acquire 候选自然不含它——失败分类交给池，重试时机交给模式。

**Q: 同一通调用会不会两连试同一把 key？**
不会。`triedIds` 环内不变量保证同一把绝不二连试；且可换级失败（401/403/429/5xx）
都会把 key 踢出候选（判死或冷却），池理论给不出同一把——双保险防御。

**Q: 流式中途断流怎么办？**
已发块后失败不重放（内容重复比重试失败更糟）：原样上抛 + reportFailure 记账，
下一通调用自然换 key。与引擎「已流式输出的轮次不重试」纪律同源。

## 八、不落地项与后续路线

- **并发可用性测试器 UI**：operit 的 ApiKeyPoolAvailabilityTester 有可暂停/并发语义，
  本期只留了 `markTested` 回写口，UI 层测试页后续批次；
- 退避未消费 HTTP `Retry-After` 头（与 LlmClientFactory RetryInterceptor 的
  指数退避并存，未来可在 KeyPoolLlmClient 解析后透传给池）；
- 池持久化格式未定稿为 DataStore（`KeyPoolState` 已可序列化，app 层任选介质）。

## 附：文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/llm-adapter/.../llm/keypool/ApiKeyPoolModels.kt` | 251 | KeyStatus/ApiKeyEntry/KeyPoolState/KeySelection/KeyHealthInfo/KeyPoolSnapshot/maskKey |
| `core/llm-adapter/.../llm/keypool/ApiKeyPool.kt` | 443 | Mutex + 不可变克隆池：acquire/reportFailure/reportSuccess/markTested/resetKey/addKey/removeKey/setEnabled/rotateCursor/snapshot |
| `core/llm-adapter/.../llm/keypool/KeyPoolLlmClient.kt` | 268 | LlmClient 请求层包装器 + withKeyRetry 重试环 + KeyPoolExhaustedException |
| `core/llm-adapter/.../llm/keypool/ApiKeyPoolTest.kt` | 598 | 34 用例 |
| `core/llm-adapter/.../llm/keypool/KeyPoolLlmClientTest.kt` | 514 | 18 用例 |
