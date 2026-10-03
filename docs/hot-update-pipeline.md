# 热更新体系（hot update pipeline v1）

> 一句话：data-only 版本不再重装 APK —— App 下载热更包（MB 级 ZIP），
> 校验 → 原子落位 → 技能/目录**即时生效**。全程零安装器、零签名校验，
> **签名冲突在这条通道上从根上不存在**。

## 动机：签名冲突的根因

现状（v1.4.5 增量链路）：补丁（vcdiff）→ App 内合成新 APK → 拉起系统
安装器 → **覆盖安装**。这条链有个绕不开的坎：

```
发布 APK 以 debug 密钥签名（app/build.gradle.kts：signingConfig = debug）
        │
        ├─ debug.keystore 是机器本地的 —— CI runner 与开发者本机必然不同
        └─ 本地构建 / 分叉构建（fork）用户的已装 APK 签名 ≠ CI 产物签名
                        │
                        ▼
            系统安装器：INSTALL_FAILED_UPDATE_INCOMPATIBLE（签名冲突）
```

对官方渠道用户（装的就是 CI 产物），只要签名密钥稳定，增量链可用；
但对**本地构建 / 分叉构建**用户（含同源 fork 自编译），补丁合成的 APK
永远装不上 —— 下载 300MB 合成 40 分钟后在安装器里撞墙。

而 App 的高频变更（技能 / MCP 目录）本就是**数据驱动 + 运行时加载**：

| 层 | 载体 | 加载时机 |
| --- | --- | --- |
| 技能 | `filesDir/skills/*.json`（SkillRegistry，版本化幂等安装） | 安装即生效（SkillHotReloader 增量同步工具注册） |
| MCP 精选目录 | `assets/mcp_catalog/*.json`（市场页读取） | 进市场页加载 |

于是热更新通道 = **把数据层从 APK 里解耦**：CI 判定本版只改了热载路径
时，额外产出一个热更包；App 下载后落位 overlay，无需触碰 APK。

## 方案总览

```
检查更新（version.json）── effective = max(包 versionCode, 已应用热更目标)
  └─ 远端 versionCode > effective？
        │
        ├─ 是 ── version.json 带 hot 档且过 HotUpdatePolicy 五重门？
        │        │
        │        ├─ 是 ──► 热更新（主推）：下载 ZIP → SHA-256 → 解包
        │        │          → 逐文件指纹 → 原子落位 → 技能即时注入
        │        │          （State.Applied → 自动重检 → UpToDate）
        │        │
        │        └─ 否（本版含代码变更 / 基底过老）──► 原增量链 / 全量
        │             └─ 签名预检：本地指纹 ≠ manifest.signingCertSha256
        │                → 提前警告（不再下载几百 MB 后才撞墙）
        │
        └─ 否 ──► UpToDate（数据层已热更到清单版本的设备也落在这里）
```

## 热更包（apex-hot-v1）

ZIP 布局与 APK 热载路径同构：

```
hot_v1.4.5.3.zip
├── hotmanifest.json        ← 包内清单（schema/target/逐文件 SHA-256）
├── skills/*.json           ← 技能清单（与 assets/skills 同构，累积快照）
└── mcp_catalog/*.json      ← MCP 目录分类文件（累积快照）
```

`hotmanifest.json`（schema `apex-hot-v1`）：

```json
{
  "schema": "apex-hot-v1",
  "targetVersionCode": 46,
  "targetVersionName": "1.4.5.3",
  "entries": [
    {"path": "skills/api-design.json", "sha256": "…"},
    {"path": "mcp_catalog/browser.json", "sha256": "…"}
  ]
}
```

version.json 新增字段（老客户端 `ignoreUnknownKeys` 自动忽略）：

```json
{
  "versionName": "1.4.7",
  "versionCode": 49,
  "hot": {
    "baseVersionCode": 49,
    "targetVersionCode": 49,
    "targetVersionName": "1.4.7",
    "url": "https://github.com/…/hot_v1.4.7.zip",
    "sizeBytes": 1048576,
    "sha256": "…"
  },
  "commitSha": "<本版完整 commit —— 下一版 data-only 判定基准 / 应急热修白名单比对基准>",
  "signingCertSha256": "<发布 APK 签名证书指纹 —— 客户端签名预检>"
}
```

### 基底取能力地板，不随版本推进

`hot.baseVersionCode` 固定为**热通道能力地板**（首个携带热更客户端的
versionCode，v1.4.7 = 49），而非上一版 versionCode：

- 热更包是**累积快照**（skills + 目录全量、自包含），对任何热通道
  客户端皆可安全应用，基底不需要随版本收缩；
- 签名冲突用户（本地/分叉构建，本通道的主要服务对象）**永远无法重装
  APK** —— 若基底随版本推进，他们只要跳过一次发版就会被永久锁在数据
  通道之外；
- 地板以下的老客户端本就不认识 `hot` 字段（ignoreUnknownKeys），
  不受影响。

### 应急热修（同版本重发）

数据层发布后发现坏内容（如某个技能清单写坏）时，发布仓库可用
`hotfix` 工作流（见 Android-Guru-Agent-Release/HOT_UPDATE.md）**同版本
重发**热更包：`targetVersionCode` 不变、新 ZIP（`hot_v1.4.7.r2.zip`）+
新指纹。客户端门控凭**包指纹判重**：

```
同 target 且 sha256 == 已应用指纹 → 不推（幂等）
同 target 且 sha256 != 已应用指纹 → 重新应用（应急修订生效）
target < 已应用 → 永不回退
```

## 版本口径（关键语义）

- `packageVersionCode`：PackageManager 报告的已装 APK versionCode（二进制层）；
- `appliedTargetVersionCode`：已应用热更包的目标 versionCode（数据层）；
- **effective = max(两者)** —— 更新检查比对口径：
  - 数据层已热更到 N 的设备，对远端 N 版判 UpToDate（不再重复推）；
  - 远端 N+1 版无 hot 档（含代码变更）时照常推增量/全量。
- **APK 追平退役**：`packageVersionCode >= appliedTarget` 时 overlay 自动
  清理（新 APK 的 assets 已携带 ≥ 热更内容），记账归零。

## 关键组件

### 1. `update/HotUpdateModels.kt` —— 模型与纯函数门控

- `HotAsset`：version.json `hot` 档模型；
- `HotPackage`：包内清单解析（schema 校验 + 路径合法性第一道闸）；
- `HotUpdatePolicy.resolve` 五重门（全过才放行）：
  1. `hot.targetVersionCode == manifest.versionCode`（清单自洽）；
  2. 未应用过：`target > appliedTarget`（全新包），或
     `target == appliedTarget` 且包指纹不同（应急重发）；
  3. `effective >= hot.baseVersionCode`（数据层基底兼容，基底=能力地板）；
  4. URL 非空；
  5. 同版本重发时清单必须携带指纹（无指纹无法判重，保守不推）。

### 2. `update/HotContentStore.kt` —— overlay 落位/读取/退役

- 目录布局：`filesDir/hot/{active, staging-*, active-old}`；
- **原子换位三步舞**（仓库纪律：tmp + renameTo，失败直写兜底）：
  `active → active-old`（让位）→ `staging → active`（瞬时换入）→ 清理；
  换入失败旧内容原样换回；
- 读取入口（技能清单 / id 集 / 目录文件）先过**退役检查**；
- prefs 抽接口（`HotPrefs`）—— 纯 JVM 单测注入内存 fake。

### 3. `update/HotUpdateEngine.kt` —— 流水线

断点复用（已验证 ZIP 跳过下载）→ DownloadManager 下载 → ZIP 终局
SHA-256 → `SafeZipExtractor` 解包（路径穿越 + zip bomb 防御，复用
core 既有件）→ `hotmanifest.json` 解析 + target 对账 → **逐文件
SHA-256 复核**（第二道防线）→ 原子落位 → 技能即时注入
（`installBundled` 版本化幂等升级，保持用户启停态）。

### 4. 接线

- `UpdateCenter`：`hotState` StateFlow（与 patchState 同生命周期语义）、
  `checkForUpdate` 改用 effective 口径、生效后自动重检、
  `signatureMismatch` 签名预检；
- `SkillModule`：pruneStaleBundled 白名单 = assets id ∪ 热更 id
  （防热更技能被旧 APK assets 快照反向清理）+ 每次启动重放热更技能（幂等）；
- `MarketViewModelMcpCatalog`：存在 overlay 时目录层**整体替换**
  （支持上游删除分类文件），overlay 颗粒无收时回退 assets；
- `AboutUpdatePanel`：热更卡片（主推）→ 增量 → 全量；签名不一致时
  顶部警告条。

## CI（release.yml）

```
resolve：拉上一版 version.json → prevSha / prevVcode 输出
        │
build： Extract signing certificate digest（apksigner → meta/cert_sha256.txt）
        │
        Generate hot update package：
        │   compare(prevSha…HEAD) 变更集
        │   ├─ 全部在白名单 assets/{skills,mcp_catalog}/*.json → 打包
        │   │   hotmanifest.json + 数据文件 → dist/hot_v*.zip
        │   │   + meta/hot_meta.json（base=热通道能力地板 49）
        │   ├─ 白名单外变更 / compare 截断 → 不出热更包（必须重装）
        │   └─ prevSha 为空（旧清单无 commitSha）→ 跳过（通道自举起点）
        │
        version.json 注入：hot / commitSha / signingCertSha256
```

- 热更包只进发布仓库 Release（`files: dist/*` 自动带走；主仓库镜像
  仍只收 APK + SHA + version.json，与 vcdiff 补丁同策略）；
- 通道自举：本 PR 合入后的第一个版本携带 `commitSha`，**下一个**
  data-only 版本起才有热更包。

## 双仓库分工

| 仓库 | 职责 |
| --- | --- |
| 开发仓库（Android-Guru-Agent） | 热更客户端 / release.yml 热更包生成与发布 / 本文档 |
| 发布仓库（Android-Guru-Agent-Release） | version.json + patches.json 清单维护 / 热更包与 vcdiff 补丁的资产宿主 / **清单校验门禁**（validate-manifests）/ **应急热修发布**（hotfix 工作流，同版本重发热更包） |

发布仓库是补丁与热更包的**发布主体**：所有更新资产（APK / vcdiff /
热更 ZIP）与两份清单都在它名下发布；开发仓库 CI 通过跨仓库 PAT
写入。应急热修不需要开发仓库发版 —— 发布仓库的 hotfix 工作流直接
打包数据层重发（操作手册见发布仓库 HOT_UPDATE.md）。

## 安全与健壮性

| 层 | 机制 |
| --- | --- |
| ZIP 整包 | SHA-256 对 version.json `hot` 档指纹 |
| 包内清单 | schema 校验 + 路径合法性（绝对路径 / `..` / 反斜杠拒绝） |
| 逐文件 | SHA-256 对 hotmanifest 条目（外层对账后内容再篡改的极端场景） |
| 解压 | SafeZipExtractor：zip-slip canonical 校验 + zip bomb 三重上限 |
| 落位 | 原子换位 + 失败回滚 + 暂存残留自清理 |
| 技能 | installBundled 版本化幂等（降级跳过、保持用户启停态） |
| 退役 | APK 追平自动清理（防 overlay 虚高掩盖真正的安装更新） |

## 与既有通道的关系

| 通道 | 适用 | 安装 | 签名冲突 |
| --- | --- | --- | --- |
| **热更（新增）** | data-only 版本 | **零安装**，即时生效 | 不存在（不碰 APK） |
| 增量（vcdiff） | 官方渠道 + 签名一致 | 合成后系统安装器 | 签名一致时无；不一致被预检提前拦截 |
| 全量 | 兜底 | 系统安装器 | 同上 |

**已知边界**（诚实声明，非缺陷）：
1. 含代码 / 资源 / manifest 变更的版本无法热更（Android 无 root 不允许
   换自身 APK）—— 走增量/全量 + 签名预检提前告知；
2. 热更通道只做技能/目录的**增量升级**，不做内置技能移除（移除需 APK
   更新后由 pruneStaleBundled 收敛）；
3. MCP 目录为整体替换语义（快照覆盖），市场页下次加载生效。

## 验证记录

- `HotUpdateModelsTest`（JUnit4 纯 JVM）：门控五重门逐项 / 应急重发
  判重三态（重推 / 幂等跳过 / 陈旧拒绝）/ 有效版本口径 / 退役判定 /
  包清单解析（合法 / 未知字段 / schema 拒绝 / zip-slip 路径拒绝 /
  畸形折叠）；
- `HotContentStoreTest`（TemporaryFolder + 内存 fake prefs）：原子换位
  与记账（含包指纹）/ 应急重发指纹覆盖 / 整体替换 / 防御式读取
  （损坏 JSON 跳过）/ APK 追平退役 / 换位失败回滚（staging 消失不丢
  旧内容）/ 暂存清理；
- CI 侧 data-only 判定 / 指纹注入由 release.yml 干跑（workflow_dispatch）
  验证 —— Job Summary 展示热更包体积与基底窗口。
