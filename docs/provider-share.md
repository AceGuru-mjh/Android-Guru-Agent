# Provider Share Codec — 供应商分享码与 QR 载荷（P96）

> 状态：已实施 v1（Task 4-f）· 对比文档差距项 P96（[operit-rikkahub-comparison.md](operit-rikkahub-comparison.md) §6.1/§6.2）
> 前置：`ProviderConfig`（含 apiKeys 多 key 数据）+ `ModelProfile` 全字段默认值序列化纪律
> 业界对标：rikkahub `ProviderSetting.encodeForShare`（"ai-provider:v1:" + Base64(JSON)，无压缩无信封、解码 require 直接抛异常）· apex 增强：GZIP 压缩 + 信封层 + 密钥脱敏开关 + 防御式解码

## 一、动机

对比文档 2-c 差距⑦：apex 无任何 QR 库与分享码协议——供应商配置只能手抄
baseUrl/key。rikkahub 的做法是「前缀 + Base64(JSON(Provider))」：剥离 models 只导
连接配置、无压缩、解码失败 require 直接抛异常。

apex 侧四点适配（learn from 但不照抄）：

1. **分享单元更大**：apex 的 Profile 是一等公民（采样/推理/超时全在档位上），
   只分享 Provider 等于只分享一半——分享单元 = Provider + 挂载的 ModelProfile 档位
   列表，载荷可达数 KB；
2. **载荷大 → GZIP 压缩**（信封层承载「是否压缩」标志）；
3. **密钥红线**：默认导出即脱敏（apiKeys 清空），带 key 必须显式 opt-in；
4. **解码全程防御式**：任何形状的坏输入折叠为分型错误值，绝不向上抛
   （RikkaHub 是 require 抛异常——扫错码即 crash）。

落地于纯 JVM 模块 `core/llm-adapter` 新包 `com.apex.agent.core.llm.share`
（2 个 main 文件 455 行 + 1 个测试文件 392 行）。**codec 不依赖 QR 库**——
zxing 是 app 层后续接线（见 §八），本包只产出/消费单行 URI 文本。

## 二、URI 四层洋葱（ProviderShareCodec.kt，295 行）

```
┌────────────────────────────────────────────────────────────────┐
│ URI   = "apex-provider:v1:" + B64(信封 JSON)          外层 Base64 │
├────────────────────────────────────────────────────────────────┤
│ 信封  = { format, version, compressed, data }    恒小，永不压缩  │
├────────────────────────────────────────────────────────────────┤
│ data  = B64(gzip(载荷 JSON)) 或 B64(载荷 JSON)      中层 Base64  │
├────────────────────────────────────────────────────────────────┤
│ 载荷  = ProviderSharePayload JSON                              │
│         Provider + Profiles，唯一可能大的层（压缩只发生在这里）  │
└────────────────────────────────────────────────────────────────┘
```

- **为什么需要信封**：一旦引入 GZIP，「是否压缩过」这个标志必须**带外**传输——
  它不可能写进被压缩的 JSON 里（先有鸡还是先有蛋）。信封就是这层带外元数据，
  同时承载 format/version 供格式演进：解不开就明确报错，绝不瞎猜格式；
- **三处版本号各司其职**：URI 前缀的 v1（scheme 语义，只增不改）、信封
  `ShareEnvelope.version`（包装格式）、载荷 `ProviderSharePayload.version`（内容
  格式）——当前均为 1；解码时信封版本与载荷版本都**严格校验**（`!=` 而非 `>` 容忍
  ——未来 v2 解不开就明确报 UnsupportedVersion）；
- **压缩决策**：载荷 JSON **原始字节数严格大于 512**（`COMPRESS_THRESHOLD_BYTES`）
  才 gzip。小于此值压缩反而变大（gzip 头 10 字节 + trailer 8 字节的固定开销），
  且小载荷本就进得了低版本 QR；
- 编解码器用 `encodeDefaults=false` 的紧凑 Json——等于默认值的字段不进载荷
  （QR 容量友好），解码 `ignoreUnknownKeys`（向前兼容）。

### 编码/解码往返

```
encode(provider, profiles, includeKeys=false)
   │ includeKeys=false → provider.copy(apiKeys = emptyList())
   │ payload = ProviderSharePayload(provider, profiles, exportedAt, includesKeys)
   │ payloadBytes = 紧凑 JSON 字节
   │ payloadBytes.size > 512 ──► data = B64(gzip(payloadBytes))     compressed=true
   │            else          ──► data = B64(payloadBytes)          compressed=false
   │ envelope = ShareEnvelope("apex-share", 1, compressed, data)
   └──► "apex-provider:v1:" + B64(envelope JSON)

decode(uri)
   │ trim → startsWith 前缀？──否──► Malformed("missing prefix …")
   │ 剔白 → 外层 B64 → 信封 JSON → format/version 严格校验
   │ data B64 → compressed? gunzip(2 MiB 上限) : 原样
   │ 载荷 JSON → schema("apex-provider") / version 校验
   └──► Result.success(ProviderSharePayload)  或  Result.failure(ShareDecodeException(error))
```

## 三、数据模型（ProviderShareModels.kt，160 行）

| 类 | 字段 | 说明 |
|----|------|------|
| `ProviderSharePayload` | schema("apex-provider") / version(1) / provider / profiles / exportedAt / includesKeys | 除 provider 外全默认值——旧 JSON 缺字段无损加载；`hasProfiles` 供导入端提示「将创建 N 个模型档案」 |
| `ShareEnvelope` | format("apex-share") / version / compressed / data | **四字段全必填（无默认值）**——私有小协议，缺字段即非法信封（Malformed），不做宽容填充 |
| `ShareCodecError` | Malformed / CorruptPayload / WrongSchema / UnsupportedVersion | plain sealed class（值语义，可 is 断言/拷贝比较）；每个 message 是可直接展示的人读句（绝不含密钥材料） |
| `ShareDecodeException` | error: ShareCodecError | Result failure 只能装 Throwable 的**纯管道桥**——error 原样携带分型结果，绝不附加堆栈语义；调用方经 `shareCodecErrorOrNull()` 扩展取回分型错误，不直接碰桥 |

## 四、编码语义（encode）

- **密钥处理（红线规则）**：`includeKeys=false`（默认）→ `apiKeys` 置空 +
  `includesKeys=false`；`includeKeys=true` 全量保留且 includesKeys=true——导入端
  必须提示「此码含密钥」。**defaultHeaders / Profile customHeaders 刻意保留**
  （文档化取舍：请求头绝大多数是路由信息（X-Region / anthropic-version 等）无密钥
  语义；真正的密钥材料只认 apiKeys 列表；需要连头彻底脱敏的调用方自行先清头再传入）；
- profiles 原样保留（档位是分享价值主体）；
- `nowMs` 写入 exportedAt（默认 0 = 不记录）；
- object 单例、无状态纯函数（json 与 Base64 codec 线程安全），任意线程直接调用。

## 五、解码语义（decode）——防御式逐层剥

```
trim → 去前缀 → Base64 段内白字符剔除（QR 文本拷贝夹带换行/空格，无损剔除）
  → 外层 Base64 → 信封 JSON → format/version 严格校验
  → data Base64 → gunzip（2 MiB 解压上限）→ 载荷 JSON → schema/version 校验
```

| 输入症状 | 错误分型 |
|----------|----------|
| 空串 / 缺前缀 / 前缀后无内容 | Malformed |
| 信封 Base64 非法（乱码字符） | Malformed |
| 信封 JSON 解不开 / format 不认识 | Malformed |
| 信封 version != 当前版 | UnsupportedVersion |
| data Base64 非法 / gzip 流损坏 / **解压超 2 MiB** | CorruptPayload |
| 载荷 JSON 解不开 | CorruptPayload |
| 载荷 schema 不是 "apex-provider" | WrongSchema |
| 载荷 version != 当前版 | UnsupportedVersion |

- **gzip 炸弹防御**：解出的载荷超过 2 MiB（`MAX_DECOMPRESSED_BYTES`）视为损坏——
  抛 IOException 折叠 CorruptPayload，绝不让恶意码把内存打爆（正常 Provider +
  Profiles 载荷远小于此）；
- 全部失败经 kotlin.runCatching + getOrElse 非局部 return 折叠，绝不向上抛；
  `decodeOrNull` 快速路径（UI 预检/批量过滤）；`isShareUri` 容忍首尾空白；
- **Base64 字母表**：标准 RFC 4648 含填充（对齐 rikkahub 默认字母表）；解码端
  严格拒收 URL_SAFE 的 `-` 和 `_`——可预测性优先于宽容度（白字符剔除已覆盖 QR
  现实噪音）；
- 错误描述截 120 字符（错误句有界，防超长异常消息刷屏）。

### 与 rikkahub `ai-provider:v1:` 互不干扰（刻意决策）

RikkaHub 码（"ai-provider:v1:" 前缀）**不是** apex 分享码：跨应用前缀刻意不同，
误扫别家的码不会被本 codec 吞掉（isShareUri false / decode Malformed）。其 JSON
无信封无压缩，与 apex 四层结构不兼容——**不做自动识别**；如需互通另写独立适配器。

## 六、QR 版本估算（estimateQrVersion，纯启发式）

| URI 字符数（原样，含首尾空白） | 估算 QR 版本 |
|-------------------------------|--------------|
| ≤ 126 | v7 |
| ≤ 285 | v14 |
| ≤ 550 | v20 |
| ≤ 976 | v27 |
| ≤ 1852 | v40 |
| 超出 | -1（过大，建议改文本/文件分享） |

数字按高纠错档的字节容量再加保守余量拍定——真实容量随 ECC 等级/编码模式/分段
结构变化，拿不准就**估大不估小**。KDoc 明示：仅供 UI 提示「二维码密度」，**不参与
编解码决策**。测试对边界九点采样（126/127/285/286/550/551/976/977/1852/1853）。

## 七、测试矩阵（20 用例全绿）

| 分组 | 覆盖 |
|------|------|
| 小载荷往返 | 明文路径白盒断言（信封 compressed=false + 字段采样 id/baseUrl/modelId/customHeaders/temperature/exportedAt/includesKeys） |
| 大载荷往返 | 3 档位各 700 字符 systemPromptPrefix → compressed=true 白盒断言 + gzip 往返 |
| 密钥红线 | 默认脱敏（apiKeys 清空）+ **URI 文本不含明文 key 材料** + includeKeys=true 全量保留 + 非密钥请求头幸存 |
| 前缀与噪音 | 缺前缀 Malformed、**RikkaHub 前缀不误吞**、首尾空白与 Base64 段内换行容忍 |
| 防御式 | 空串/纯前缀/乱码/中文垃圾全折叠 Malformed 不 crash、截断 gzip 流 CorruptPayload（dropLast 8 字符保 Base64 合法）、伪 gzip 魔数 CorruptPayload、坏 schema WrongSchema、载荷版本 99 与信封版本 99 UnsupportedVersion、陌生信封 format Malformed |
| 边界 | decodeOrNull 折叠 null、四分型 message 人读可展示、QR 边界九点采样、真实载荷 QR 版本预算冒烟 |

白盒约定：`envelopeOf` 复刻 decode 外两层断言压缩路径；`payloadUri`/`uriOf` 手工造
明文信封 URI 构造坏 schema/坏版本场景（不走被测 encode——它只产合法载荷）。
验证：`:core:llm-adapter:test` 一次通过 BUILD SUCCESSFUL——模块 184 tests / 0 failed
（新增 20 个全绿，兄弟 keypool 52 个零回归）。

## 八、app 层接入指南

| 环节 | 落点 |
|------|------|
| QR 生成 | 引入 `com.google.zxing:core`（纯 Java，Maven Central，符合依赖白名单）→ QRCodeWriter 直接吃 `codec.encode()` 输出 |
| 分享页 | `estimateQrVersion` 预判二维码密度，-1 时给出「过大改文本分享」建议 |
| 导入流程 | `isShareUri` 预检 → decode → `shareCodecErrorOrNull()` 分型提示（四类人读句直接展示）→ includesKeys=true 时弹密钥警告 → SettingsRepository.upsertProvider + Profile 落库 |
| RikkaHub 互通 | 如需支持 ai-provider:v1 码，另写独立适配器（其 JSON 无信封无压缩） |

## 九、设计取舍与常见问题

**Q: ShareCodecError 为什么是 plain sealed class 而不是异常子类？**
值语义可 is 断言/拷贝比较，UI 按 `Malformed/CorruptPayload/WrongSchema/UnsupportedVersion`
四分型给出精确提示（「扫错码了」vs「格式版本太新」是完全不同的用户动作）。但
kotlin Result failure 只能装 Throwable——所以加 ShareDecodeException 纯管道桥 +
shareCodecErrorOrNull() 扩展，调用方永远不直接碰桥。

**Q: 信封版本为什么 `!=` 严格校验而不是 `>` 容忍未来小版本？**
信封是我们的私有小协议，没有「小版本」语义；未来 v2 解不开就明确报
UnsupportedVersion，绝不瞎猜格式——错得可解释比碰运气兼容更安全。

**Q: 为什么不缓存压缩决策的结果？**
encode 是纯函数无状态：同一输入永远同一输出，缓存只会引入失效问题。

**Q: 载荷里带 apiKeys 的码被转发给第三人会怎样？**
这正是 includesKeys 显式记录的原因：解码端从载荷读 `includesKeys=true` 时必须
弹「此码含密钥」警告——码本身无加密（QR 是明文介质），红线靠「默认剥离 +
显式 opt-in + 导入端提示」三层约定，而非密码学。

## 十、后续路线

- zxing 接线与设置页导出/导入 UI（§八流程）；
- 分享码签名/校验和（当前依赖 QR 自身纠错——恶意篡改的合法结构码无法识别）；
- Web 端分享页（分享码贴文本框解析预览——codec 纯 JVM 已可直接复用）。

## 附：文件索引

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/llm-adapter/.../llm/share/ProviderShareModels.kt` | 160 | ProviderSharePayload/ShareEnvelope/ShareCodecError/ShareDecodeException/shareCodecErrorOrNull |
| `core/llm-adapter/.../llm/share/ProviderShareCodec.kt` | 295 | encode/decode/decodeOrNull/isShareUri/estimateQrVersion + gzip/gunzip（2 MiB 上限） |
| `core/llm-adapter/.../llm/share/ProviderShareCodecTest.kt` | 392 | 20 用例 |
