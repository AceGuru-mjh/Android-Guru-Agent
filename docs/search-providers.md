# 多供应商网络搜索框架 — 六家供应商 + 三相编排（P94）

> 状态：已实施 v1（Task 4-d）· 对比文档差距项 P94（[operit-rikkahub-comparison.md](operit-rikkahub-comparison.md) §4.6/§6.2）
> 前置：apex WebTools.kt 三级免 key 爬虫回退（DDG-HTML→DDG-Lite→Bing），无任何 API-key 型供应商
> 业界对标：rikkahub `SearchService` 19 家供应商统一接口 + 参数 schema（本框架取其抽象，裁剪 Composable UI 与 scrape 通道）

## 一、动机

对比文档 §4.6 的结论：rikkahub 有 19 家搜索服务（Tavily/Exa/Brave/SearXNG 等），
参数带 JSON schema 且引用协议进工具描述；apex 只有爬虫三级回退——**质量天花板被
免 key 供应商锁死**（无相关性分、无时间过滤、无域名过滤），反爬封禁即断粮。

落地于纯 JVM 模块 `core/tool-registry` 新包 `com.apex.agent.core.tools.search`
（11 个 main 文件 1670 行 + 5 个测试文件 1356 行），并**手术式接入 WebSearchTool
（仅 +26/-1 行）**：`searchRegistry` 是可选构造参数，`registry == null` 路径
**逐字节不变**——既有 487 个测试零迁移。

## 二、统一模型（SearchProviderModels.kt，181 行）

| 类 | 关键设计 |
|----|----------|
| `SearchQuery` | 供应商无关入参：query/maxResults（钳 1..20）/timeoutMs/safeSearch/timeRange(day\|week\|month\|year)/includeDomains/excludeDomains/language；`sanitized()` 去空白+钳制——缓存与限流键都以 sanitized 形态为准（" kotlin " 与 "kotlin" 同键）；@Serializable 全默认值，可直接从 LLM 工具参数反序列化 |
| `SearchResultItem` | title/url/snippet/publishedAt/score（Tavily/Exa 相关性分）/faviconUrl/provider（来源回溯 + 去重诊断）——对照 rikkahub SearchResultItem，去掉 LLM 消费不到的 highlights |
| `SearchProviderError` | 折叠式错误值（防御式 IO）：正码=HTTP 状态码、负码=哨兵（-1 网络 / -2 解析 / -3 配置 / -4 缺 key / -5 零结果 / -6 本地限流 / -7 全败聚合）；`retryable` 由 `http()` 推导（429 与 5xx 可重试） |
| `SearchResponse` | 单一返回类型三态合一：成功（items 非空且 error==null）/ 失败 / 缓存命中（cached=true，tookMs 保留首次真实耗时） |
| `SearchProviderConfig` | 供应商运行时配置（providerId/apiKey/enabled/baseUrl/extraParams/priority 数值小者优先）；@Serializable 全默认值可直接 JSON 持久化；`normalizedBaseUrl()` 去尾斜杠 |

`timeoutMs` 是上层预算**建议值**不强制应用——为每次搜索 newBuilder 复制 OkHttpClient
的复杂度远大于收益（传输层超时由共享客户端 connect 10s / read 20s 决定）。

## 三、供应商接口与基类（SearchProvider.kt，217 行）

`SearchProvider` 接口五属性：id（缓存/限流桶键）/displayName/requiresApiKey/
supportsAdvancedParams/**isScrapeFallback**（additive 默认 false——区分「兜底爬虫相」，
注册表据此分相调度）+ `search(query, config)`。配置不进接口（rikkahub 把 apiKey
揉进 sealed options；这里调用时传入——注册表才能热更新 key/优先级/开关而不动供应商实现）。

`HttpSearchProvider` 基类：`executeJson`（可取消 awaitOk：enqueue +
suspendCancellableCoroutine，协程取消同步 call.cancel()——WebTools.kt 的 awaitOk
是文件私有，独立维护一份；非 2xx 折叠为带状态码的 SearchProviderHttpException）+
包内宽松 JSON 帮助（parseJsonObject/stringField 等，垃圾输入折叠 null）+
`toSearchProviderError()` 统一映射。**实现纪律：search 永不抛（CancellationException
除外），一切失败折叠进 error；解析逻辑抽成 internal 纯函数（parseXxxResponse），
夹具单测不触网。**

## 四、六家供应商

| 供应商 | id | 鉴权 | 端点 | 参数映射 | 高级参数 |
|--------|----|------|------|----------|----------|
| Tavily | `tavily` | POST body `api_key`（兼容自建网关透传） | `https://api.tavily.com/search` | max_results 直映射；include/exclude_domains；`search_depth` 走 extraParams（默认 basic 省配额）；score/published_date 容忍缺失 | ✓ |
| Brave | `brave` | 头 `X-Subscription-Token` | `https://api.search.brave.com/res/v1/web/search` | count；safesearch strict/off；freshness day→pd/week→pw/month→pm/year→py；search_lang；age（相对时间）原样进 publishedAt | ✓ |
| Exa | `exa` | 头 `x-api-key` | `https://api.exa.ai/search` | numResults；type 走 extraParams（默认 auto）；**startPublishedDate**：timeRange → "now - N" ISO-8601 下界（`startPublishedDateFor` 纯函数假钟可测；month=30d、year=365d 近似） | ✓ |
| SearXNG | `searxng` | 无 key（HTTP Basic 可选，凭据走 extraParams username/password） | `{baseUrl}/search?format=json` | **baseUrl 必填否则本地早退 CONFIG 错误**（不猜默认公网实例——避免把查询发给不受控第三方）；safesearch 0/1；time_range 原生同口径；language | ✓ |
| DuckDuckGo | `duckduckgo-html` | 无 key（`isScrapeFallback=true`） | `https://html.duckduckgo.com/html/?q=` | 仅 query+maxResults（其余静默忽略）；result__a/result__snippet 成对正则；`unwrapDdgRedirect` 解 uddg= 跟踪重定向；实体解码 | ✗ |
| Bing | `bing-web` | 无 key（`isScrapeFallback=true`） | `https://www.bing.com/search?q=&count=` | 仅 query+maxResults；b_algo/h2 锚 + 段落摘要；**decodeBingRedirect**：ck 路径 u 参数 a1 前缀后为 URL-safe base64 目标 URL（还原 - → +、_ → /、按 4 对齐补 = 再解码；非 http 丢弃） | ✗ |

DDG/Bing 解析资产自包含移植自 WebTools.kt（同源独立维护避免跨文件私有冲突），
差异点：产出 SearchResponse（error 折叠）而非抛异常；解析抽成纯函数可夹具单测；
支持 config.baseUrl 覆盖。

## 五、注册表三相编排（SearchProviderRegistry.kt，346 行）

```
search(query)
   │ sanitized + 空查询拒绝（CONFIG）
   │ availableProviders()：enabled 且（免 key 或 apiKey 非空），priority 升序
   ▼
┌─ 相 1：缓存 ──────────────────────────────────────────────┐
│ 按候选优先级逐个查 SearchResultCache，命中即返回           │
│ （cached=true，不打供应商）                                │
└───────────────────────────┬───────────────────────────────┘
                            ▼
┌─ 相 2：API 供应商（优先级序）──────────────────────────────┐
│ runProvider：先过 SearchRateLimiter 令牌闸                 │
│   耗尽 → 记 CODE_RATE_LIMITED 跳过（不烧配额）              │
│ 成功（有结果）→ dedupeByUrl → 写缓存 → 返回                │
│ 失败/零结果 → 记 failures 换下一个                          │
└───────────────────────────┬───────────────────────────────┘
                            ▼
┌─ 相 3：兜底爬虫（DDG → Bing）──────────────────────────────┐
│ isScrapeFallback 供应商同规则执行                          │
└───────────────────────────┬───────────────────────────────┘
                            ▼
   聚合错误（providerId="search-registry"）：任一 retryable
   则整体 retryable，永不抛（CancellationException 除外）
```

- `register`（同 id 替换语义，位置保持首注槽位；无配置时落默认——API 供应商
  priority=100、爬虫 priority=900，开箱即用「API 优先、爬虫兜底」）；
  `updateConfig`（未注册 id 也接受先配后注册）/ `configs()`（设置页快照）；
  `registerBuiltinProviders()` 一键注入六家（共享 client 连接池）；
- `testProvider(providerId)`：设置页连通性探针——**绕缓存绕限流**（用户显式点击
  允许跳过令牌闸），发起真实探测查询（maxResults=1）；null=健康，非 null=具体错误
  （缺 key/未注册/可达但零结果/网络）。

### 缓存（SearchResultCache.kt，103 行）

LRU（LinkedHashMap accessOrder=true，访问即刷新新鲜度，超容量从最久未用端逐出）
+ TTL 惰性清理（默认 5 分钟 `DEFAULT_TTL_MS=300_000`，假钟注入测试不睡眠；容量
64 `DEFAULT_MAX_ENTRIES`）。**只缓存成功响应（error==null），失败不占容量**。
缓存键 = providerId + 全部语义参数规范串（域名列表**排序后**参与——"a,b" 与 "b,a"
同键；maxResults 参与键——不同条数视为不同请求，宁可多打一次也不返回缺斤短两）。

### 限流（SearchRateLimiter.kt，69 行）

每供应商一个令牌桶：容量 5（`DEFAULT_CAPACITY`，5 次突发）、每分钟回填 10
（`DEFAULT_REFILL_PER_MINUTE`，6 秒 1 个）、回填封顶 capacity；**时钟回拨防御**
（elapsed <= 0 不扣不加——假钟测试可任意推进）；全同步块线程安全。目的：API 型
供应商按次计费有 QPS 配额，本地先兜住突发；免 key 爬虫也各自计桶防反爬封禁。

### URL 去重规范化（dedupeByUrl + canonicalUrl）

刻意保守只做三件事：**去尾斜杠**（根路径与路径末尾归一）、**剥 utm_ 开头跟踪参数**
（大小写不敏感，含裸 `utm`）、**scheme 小写**；解析失败退化为纯去尾斜杠。
path/query/fragment 原样保留（raw 形态，不做百分号解码避免歧义）。去重保首现。

## 六、WebSearchTool 注入点（builtin/WebTools.kt，669→694 行）

```
class WebSearchTool(httpClient, searchRegistry: SearchProviderRegistry? = null)
                              │
   searchWithFallback(query, maxResults)
       ├── searchRegistry?.let { registry.search(SearchQuery(...)) }
       │       成功（items 非空）→ formatResults 直接返回
       │       空/错 → 回落旧链
       └── 旧三级链（DDG-HTML → DDG-Lite → Bing）逐字节不变
```

`registry == null`（默认构造）路径完全不变——既有测试零迁移。集成测试用离线
OkHttp 拦截器 fake 保证确定性（不触网）。

## 七、测试矩阵（70 用例全绿）

| 套件 | 数量 | 覆盖 |
|------|------|------|
| SearchProviderParsingTest | 21 | 六家解析纯函数夹具（含真实 base64 of `https://example.com/x` 的 Bing ck 重定向、截断/伪 gzip 场景、空 url 丢弃、可选字段容忍、Brave freshness 映射、Exa startPublishedDate 假钟） |
| SearchProviderRegistryTest | 26 | 三相编排（缓存命中计数/优先级顺序/限流跳过不烧配额/爬虫兜底/聚合错误 retryable 传播）、空查询与无供应商拒绝、去重（规范化三规则）、取消传播、testProvider 探针语义 |
| SearchResultCacheTest | 11 | TTL 过期/假钟推进、LRU 逐出、容量钳制、失败响应不缓存、语义参数键（域名排序/maxResults 维度） |
| SearchRateLimiterTest | 8 | 突发容量、匀速回填、封顶、时钟回拨不扣不加、桶隔离 |
| WebSearchToolRegistryIntegrationTest | 4 | registry 成功短路旧链、空结果回落旧链、null registry 字节不变、离线拦截器 fake 确定性 |

验证：`:core:tool-registry:test` BUILD SUCCESSFUL——模块 557 tests / 0 failed
（新增 70 个，既有 487 个零回归）。首轮 1 失败为测试断言笔误（equals(4, list)
应为 .size），修正后全绿。门禁：kotlin_balance.py 17 文件 OK；check_file_size.sh
695 main + 200 test 预算内；无反射派发/无 printStackTrace。

## 八、app 层接入指南

| 环节 | 落点 |
|------|------|
| 装配 | ToolModule / BuiltinSearchMcpTransport 构造 WebSearchTool 时注入 registry：先 `registerBuiltinProviders()` + `updateConfig` 填 key |
| Key 来源 | vault / securePrefs（对比文档 §6.2 P94：VaultStore/EncryptedPrefsVaultStore） |
| 设置页 | 供应商管理 section：`configs()` 快照渲染 + `updateConfig` 保存 + `testProvider` 连通性探测按钮 |
| 共享客户端 | 注入全局单例 OkHttpClient 共享连接池（`defaultSearchClient()` 仅供测试/默认） |

## 九、设计取舍与常见问题

**Q: 为什么配置调用时传入而不进接口（rikkahub 是 sealed options）？**
配置与实现解耦：注册表 `updateConfig` 能在不重建供应商实例的前提下热更新
key/优先级/开关——六家实现不感知配置存储，测试用假 config 即可驱动全部分支。

**Q: 缓存相为什么按「候选优先级逐个查」而不是全局键？**
缓存是每供应商独立维度（providerId 进键）：A 家缓存的结果不拦 B 家的更新——
同一查询切供应商能拿到各家口径，而不是永远命中先注册那家的旧结果。

**Q: 限流耗尽为什么跳过而不是等待？**
搜索在 agent 工具调用链上（等待会拖死整轮迭代）：跳过换下一家，本次请求
仍然有结果；令牌桶在后台回填，下次请求该供应商自然回归。

**Q: canonicalUrl 为什么不做百分号解码？**
去重口径刻意保守：解码歧义（`%2F` 与 `/`、大小写百分号）会让「相同 URL」的
判定本身变得不稳定；三件规范化已覆盖真实搜索结果的主要噪音形态（utm 跟踪、
尾斜杠、scheme 大小写），误判代价为零。

**Q: 不学 rikkahub 的参数 schema 进工具描述？**
rikkahub 每家自带 Composable schema UI（19 家 × 参数披露）；apex 先收敛到统一
SearchQuery 字段面——工具描述只有 `web_search` 一个入口，schema 披露等 app 层
真的需要分家参数时再加。

## 十、后续路线

- LinkUp/Zhipu/Doubao 等其余 rikkahub 供应商按同一骨架追加（每家一个文件 +
  internal 纯函数解析）；
- 缓存键未来可加「结果新鲜度感知」（同查询不同 timeRange 已天然分键）；
- BuiltinSearchMcpTransport（MCP 搜索通道）自动受益于 registry 注入；
- 失败响应的局部缓存（当前完全不缓存失败——若某家「间歇性返回零结果」可考虑
  短 TTL 负缓存，防止连续打同一家的空结果）。

## 附：文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/tool-registry/.../tools/search/SearchProviderModels.kt` | 181 | SearchQuery/SearchResultItem/SearchProviderError/SearchResponse/SearchProviderConfig |
| `core/tool-registry/.../tools/search/SearchProvider.kt` | 217 | 接口 + HttpSearchProvider 基类 + 可取消 awaitOk + 宽松 JSON 帮助 |
| `core/tool-registry/.../tools/search/TavilySearchProvider.kt` | 113 | Tavily + parseTavilyResponse |
| `core/tool-registry/.../tools/search/BraveSearchProvider.kt` | 117 | Brave + freshnessFor + parseBraveResponse |
| `core/tool-registry/.../tools/search/ExaSearchProvider.kt` | 135 | Exa + startPublishedDateFor + parseExaResponse |
| `core/tool-registry/.../tools/search/SearXngSearchProvider.kt` | 119 | SearXNG + parseSearxngResponse |
| `core/tool-registry/.../tools/search/DuckDuckGoScrapeProvider.kt` | 130 | DDG HTML 抓取 + uddg 解包 + 实体解码 |
| `core/tool-registry/.../tools/search/BingScrapeProvider.kt` | 140 | Bing Web 抓取 + ck base64 重定向解码 |
| `core/tool-registry/.../tools/search/SearchResultCache.kt` | 103 | LRU + TTL 缓存 |
| `core/tool-registry/.../tools/search/SearchRateLimiter.kt` | 69 | 每供应商令牌桶 |
| `core/tool-registry/.../tools/search/SearchProviderRegistry.kt` | 346 | 三相编排 + dedupeByUrl/canonicalUrl |
| `core/tool-registry/.../tools/search/`（tests 5 文件） | 1356 | 70 用例（424/496/124/207/105） |
