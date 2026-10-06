# 增量更新全自动应用 + 跨版本补丁链（v1.4.5）

> 一句话：补丁下载完成后，App 内直接合成新 APK，弹出「立即安装」确认框，
> 点击即装 —— 零命令行；跨任意多个小版本自动解析补丁链逐段升级 ——
> 零全量回退。

## 动机

旧版增量更新的两大断点：

1. **命令行断点**：补丁（.vcdiff）下载完成后，App 只弹一个
   `xdelta3 -d -s …` 命令让用户自己去终端执行（还得先在内置 Ubuntu 里
   `apt install xdelta3`）。增量更新的省流优势被操作成本抵消殆尽。
2. **跨版本断点**：`version.json` 只携带「上一版 → 本版」单补丁档，
   `preferredPatch` 的 `fromTag` 精确匹配本地版本 —— 跨两个以上小版本
   （如 v1.4.4.3 → v1.4.4.5）直接判不可用，回退 300~900MB 全量包。

## 方案总览

```
检查更新（version.json）                      有新版
  └─ 拉取 patches.json 全量补丁索引 ────────┐
                                            ▼
                            resolveChain(本地版本 → 最新 tag)
                                            │
                 ┌──────────────────────────┼──────────────────────┐
                 ▼                          ▼                      ▼
           链可用（≤60% 全量）        索引缺失+单补丁匹配        无链
                 │                          │                      │
        PatchUpdateEngine            旧路径单补丁链              全量包
   逐段 DownloadManager 下载                │                      │
   逐段 SHA-256 校验 ←──────────────────────┘
                 │
   VcdiffDecoder 链式合成（乒乓临时文件）
                 │
   终局 SHA-256 对账 version.json
                 │
   State.Ready → 弹「立即安装」确认框（用户主导安装时机）
                 │
   launchInstaller() → 系统安装器确认 → 安装完成
```

## 关键组件

### 1. `update/VcdiffDecoder.kt` —— 纯 Kotlin VCDIFF（RFC 3284）解码器

- **零原生依赖**：不引 NDK / 不fork进程；LZMA 二级压缩段经
  `org.tukaani:xz`（纯 Java、公有领域、无传递依赖）解码。
- **支持面与 CI 对齐**：默认码表（s_near=4 / s_same=3）、VCD_SOURCE /
  VCD_TARGET 窗口、VCD_ADLER32 标准校验和、xdelta3 appheader 跳过、
  LZMA（VCD_LZMA_ID=2）二级压缩。VCD_CODETABLE / DJW / FGK 明确拒绝
  （CI 永不产生）。
- **xdelta3 LZMA 流拓扑（实现的核心洞察）**：xdelta3 为 DATA/INST/ADDR
  三类 section 各维护一条**跨窗口持续复用**的 .xz 流（编码侧
  sec_stream_d/i/a，逐段 SYNC_FLUSH，从不 FINISH）。因此窗口 N 的同类
  段是窗口 N-1 的**续流**（首段带魔数、后续段直接新块头开始、全流无
  index/footer）。解码侧两遍走：先全文解析段区间，再为每类构建零拷贝
  串联 `SectionFeedInputStream`，三个惰性 `XZInputStream` 跨窗口按序解码，
  每段恰好读出其 varint 声明的解压字节数。
- **防御式 IO**：恶意/损坏输入全部折叠为 `VcdiffFormatException`（长度
  校验先行）；窗口 Adler32 + 调用方终局 SHA-256 双保险；varint 溢出 /
  地址越界 / 段过大全量前置拒绝。
- **性能**：334MB arm64 APK + 11MB 补丁 → JVM 上 1.5s 合成完成
  （真实发布补丁实测，产物 SHA-256 与官方一致）。

### 2. `update/PatchIndex.kt` —— patches.json 全量补丁索引

- 发布仓库 main 分支的**累积**索引（CI 每次发布追加，老条目不清除），
  与 version.json 同走 raw CDN、零鉴权。
- `resolveChain(本地版本 → 目标 tag)`：fromTag→toTag 有向图逐跳推进，
  visited 防环（上限 64 跳），断链返回 null 回退全量。
- 前向兼容：全字段可选 + `ignoreUnknownKeys`；解析失败折叠 null。

### 3. `update/PatchUpdateEngine.kt` —— 全自动流水线编排

「逐段下载（DownloadManager，SHA-256 逐段校验）→ 链式合成（乒乓临时
文件，峰值磁盘 ≈ 2×APK + 补丁）→ 终局对账 → **就绪即停**」单状态机；
空间预检不足直接劝导全量；开工自清残留（含旧命令行方案遗留 .vcdiff），
成功后清补丁与中间产物。失败按 `FailKind`（SPACE/DOWNLOAD/VERIFY/
DECODE/INSTALLER）折叠，UI 出「重试增量 / 改用全量」双路。

**安装时机由用户主导**：流水线终点不是拉起安装器，而是上报
`State.Ready`（产物路径 + 体积）；UI 弹「更新包已就绪」确认框，主按钮
**「立即安装」**（次要「稍后安装」）。点击「立即安装」后经独立的
`launchInstaller()` 拉起系统安装器（Android 对侧载安装强制要求用户在
系统弹窗确认，App 无法代点）。「稍后安装」不丢产物：动作区常驻
「安装已就绪的 v{版本} 更新包」重入口，重新进入页面也会后台扫描磁盘
+ 指纹复核自动恢复该入口（全量包同理）。

### 4. UI（AboutUpdatePanel）与 CI

- 推荐卡改为链式语义（N 个补丁 · 总体积 · 节省百分比 ·「自动合成后弹
  「立即安装」确认框，无需命令行」说明）；下载/合成双阶段进度条；退役
  「复制 xdelta3 命令」弹窗。
- **下载完成 → 安装按钮弹窗**（用户明确的交互要求）：增量路径在
  `State.Ready` 弹「更新包已就绪」框（立即安装 / 稍后安装）；全量路径
  在下载完成后弹「安装包已就绪」框（立即安装 / 稍后安装）—— 两条路径
  同一交互范式，安装器拉起失败均给出手动安装路径提示。
- **就绪重入口**：清单就绪后后台扫描 `Download/ApexAgent/`，已合成
  （`ApexAgent-v*-patched.apk`）/已下载的全量包指纹复核通过即在动作区
  常驻安装按钮；非当前版本的陈旧合成产物自动清理。
- `release.yml`：补丁生成显式 `-S lzma`（不可用回退原生 VCDIFF）；新增
  `patches.json` 累积生成步骤；与 version.json 一并提交发布仓库 main。

## 多基底窗口 v2 + 补洞工作流（v1.4.8）

### 窗口从 3 扩到 10：跨十个版本内一步直达

`release.yml` 对语义序前 **10** 个版本各出一份单跳补丁（双变体 = 每
版 20 份）：窗口内任意版本 → 本版一步到位，不再走 N 段链式合成。
公开仓库 Actions 分钟免费 —— 批量补丁的生成成本可忽略。客户端零改动
即受益（`resolveChain` BFS 本就优先直达边）。

### 根因修复：基底选取改语义排序（本次断链事故）

旧实现取「Releases API 返回前 N」作基底窗口 —— 但该 API 按 release
对象创建时间排序，**不保证语义版本序**。实测：v1.4.7.10 的 release
（13:24 创建）被 API 排在列表第 7 位，v1.4.7.11 发布时窗口（前 3）
取到 {9,8,7}，相邻版 v1.4.7.10 被跳过 → 其出边为零 → 该版本用户永久
断链（v1.4.4.7 同因断链，连带更早版本全部不可达）。

修复：拉全量后 `sort -rV` 语义降序再取窗口 —— 相邻版本永远在首位，
窗口选取与 API 返回顺序彻底解耦；正则放宽到任意多段 tag
（`^v[0-9]+(\.[0-9]+)+$`，兼容自动构建序号版）。

### patch-backfill.yml：存量断链就地补洞

`release.yml` 只在发新版时前向覆盖 —— 历史留下的补丁洞（旧窗口 3、
API 顺序异常、体积守卫丢弃）会永久留在索引里。补洞工作流
（`workflow_dispatch`）对指定 release（默认最新）就地补齐缺失基底补丁：

- 基底选取与 release.yml 同源（语义降序窗口）；
- 幂等：索引已有该边且资产在 Release 上存在 → 跳过；
- 产物直接上传目标 Release + patches.json 累积提交发布仓库 main；
- 过发布仓库 validate-manifests 门禁；不动 version.json。

补洞后，断链版本（如 v1.4.7.10）的客户端**下次检查更新即自动拿到
直达补丁**（v1.4.5+ 内置链解析，无需升级 App 先）。

### NDK 半截安装加固（CI 修复）

sdkmanager 偶发半截安装（目录在、source.properties 缺失 → AGP
CXX1101 硬失败且被 `|| true` 静默吞掉）。三重防线：完整性校验先行 →
sdkmanager 安装后校验（坏了立即清掉重试）→ dl.google.com 官方 zip
直下兑底。绝不静默留下损坏目录 —— 那比没有更糟（AGP 按版本号命中
坏目录）。

## 安全与健壮性

| 层 | 机制 |
| --- | --- |
| 每段下载 | SHA-256 对 patches.json 指纹 |
| 每窗口 | 标准 Adler32 校验和 |
| 终局产物 | SHA-256 对 version.json download 指纹 |
| 尺寸 | 解码器三重长度守卫 + manifest 预期尺寸对账 |
| 恶意补丁 | 码表/模式/地址/段大小全量前置拒绝，绝不越界读写 |
| 磁盘 | 空间预检 + 中间产物失败即清 |

## 验证记录

- Python 参考实现（`scripts/vcdiff_ref.py` 同构移植）：
  9/9 合成用例（多窗口/RUN 重度/全同/全异/-9/小窗口）；
- **真实发布补丁**：v1.4.4.4 + patch(v4→v5) → 334,155,023 字节，
  SHA-256 与官方 v1.4.4.5 一致；
- **真实跨版本链**：v1.4.4.3 + patch(v3→v4) + patch(v4→v5) →
  SHA-256 一致（2.6s）；
- JUnit（`VcdiffDecoderTest` / `PatchIndexTest`）：21/21 通过，fixtures
  与 CI 补丁头部逐字节同构（`05 02`）；
- 发布仓库已补种 `patches.json`（v1.4.0 → v1.4.4.7 · 38 条，双变体
  全链连通），raw CDN 已生效。
