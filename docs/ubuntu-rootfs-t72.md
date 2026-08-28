# T72 — Ubuntu RootFS Productionization

> Branch: `t72/ubuntu-rootfs-productionization` · 依据：以当前 main（含 P70 #76 / P71 #77 / P78）真实源码逐文件审查后定义的范围。

## 1. 审查结论（动手前的真实状态）

对 `ubuntu/`（8 文件）、`proot/`、`runtime/`、CI 的系统审查 + 对**真实** ubuntu-base-24.04.4 tarball 的字节级分析，确认以下断层：

| # | 级别 | 发现 | 证据 |
|---|------|------|------|
| A1 | P0 | **Extractor 解析真实 Ubuntu tarball 必然产出损坏 rootfs**：typeflag `'2'`(symlink) 落入 regular-file 分支被写成空文件（194 个全毁，含 `bin -> usr/bin` → `/bin/sh` 不存在）；typeflag `'1'`(hardlink) 被误当 symlink；USTAR prefix/GNU `'L'/'K'`/PAX `'x'/'g'` 全不支持（PAX 头会被物化成文件）；mode 只恢复 exec 位（`/etc/.pwd.lock` 0600→0644）；tar header checksum 不校验；**截断的 archive 被静默当作正常 EOF** | `python tarfile` 分析真实包：3413 条目 = 2562 file + 655 dir + 194 symlink + 2 hardlink；P69 测试自写的 tar 只有 `'0'/'5'` 类型——恰好是旧 parser 能处理的形状（测试盲区） |
| A2 | P0 | **官方源 SHA-256 全是 `0000…0000` placeholder**，URL 是 cdimage 上已不存在的无 point-release 路径 → 真实下载从未可用（checksum 必 mismatch） | `OfficialUbuntuRootfsSource.kt`（P69 版） |
| A3 | P0 | **Ubuntu 基础配置缺失**：真实包的 `/etc/resolv.conf`、`/etc/hosts` 为空文件（guest DNS 全灭 → apt 必败）；apt 工作目录未验证；CA 缺失未标注 | tarball 解压实测 |
| A4 | P1 | 健康检查只查目录名（bin/etc/usr/home/tmp + sh/bash 存在），且结果不进 metadata；merged-usr 下甚至无法区分"symlink 完好"与"bin 是被写坏的空文件" | `validateRootfsLayout` |
| A5 | P1 | 状态链（DOWNLOADED→…→READY）无 per-stage 证据持久化；metadata 只在最终 READY 写一次 | `doInstall` |
| A6 | P1 | **原子激活有数据丢失窗口**：先 `deleteRecursively` 旧版本再 rename staging；rename 失败 = 旧 READY rootfs 已删、全损 | `doInstall` ATIVATING 段 |
| A7 | P1 | `remove()` 返回 `Ready(id="removed")`（语义错误）；单实例内存锁，无跨实例互斥 | `RootfsProvisionerImpl` |
| A8 | P2 | Downloader 的 "resume" 不发 `Range` 头，直接 append 新流到旧 `.part` → 重试必损坏；`repair()` 注释声称复用缓存 archive，实现里没有 | `attemptDownload` |
| A9 | P1 | **CI 的 proot smoke/integration 测试自 P71 起从未真跑**：预检用了 Termux proot 的 `--` 分隔符，upstream proot 5.4（CI apt 版）不认 → 预检恒 false → SKIPPED 被误读为 "ptrace 受限"（实测 GH runner ptrace 可用） | 本地 proot 5.4 复现 `unknown option '--'` |
| A10 | — | 旧 Ubuntu 栈（P64 `UbuntuDistributionProvider`）是 simulated download 假实现，与 P69 未接线；app 层无任何 provisioner 引用（接线属 T73） | grep 全仓库 |

## 2. 真实发布信息（写死以保证可复现）

来源：`https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS`（2026-02 实测，与本地下载文件逐一复核）：

| artifact | URL | SHA-256 | size |
|---|---|---|---|
| ubuntu-24.04.4-arm64 | `.../ubuntu-base-24.04.4-base-arm64.tar.gz` | `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2` | 29,870,567 |
| ubuntu-24.04.4-amd64 | `.../ubuntu-base-24.04.4-base-amd64.tar.gz` | `c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58` | 29,989,394 |

版本策略：**锁 point release**。point 升级 = 显式改表 + 改测试，不做运行时自动跟随（镜像变而 checksum 不变 = 静默不可复现）。

格式事实（arm64 包实测）：ustar magic（`ustar\0 00`）、无 PAX/GNU longname（max path 87）、2 个 hardlink（`usr/bin/perl5.38.2 → usr/bin/perl`、`usr/bin/uncompress → gunzip`）、无 setuid、`/etc/apt/sources.list.d/ubuntu.sources` 为 deb822 格式 **http** ports 源（arm64 用 `ports.ubuntu.com`，amd64 包自带 `archive.ubuntu.com`）。

## 3. T72 交付

### 3.1 `OfficialUbuntuRootfsSource` — 真实 artifact 源
- 真实 URL/SHA-256/size（上表）；`resolve()` 拒绝 placeholder checksum（全零/非 64 hex）
- `RootfsArtifact.isVerifiable` 同步收紧（placeholder 不算 verifiable）
- `open(artifact, offset)` 支持 HTTP Range（206 续传 / 200 标记为 `RangeNotSupportedInputStream` / 416 报坏档）

### 3.2 `RootfsDownloader` — 真断点续传
- `.part` 存在 → 发 `Range: bytes=<len>-`；206 → 追加；200 → 丢弃 `.part` 重下；416 → 删 `.part` 重下（P69 的 append 必损坏 bug 修复）
- SHA-256 单次 hash（.part 上，rename 前）→ mismatch 删除 `.part` **和**同名旧档（绝不留坏档）
- retry + cancellation 语义保持

### 3.3 `RootfsExtractor` — 生产级 tar 解析（全重写）
| typeflag | 语义 | 处理 |
|---|---|---|
| `'0'`/`'\0'`/未知 | regular file | 写文件 + **完整 POSIX mode 恢复**（owner/group/other × rwx） |
| `'5'` | directory | mkdirs + mode（目录恒保 owner rwx 防中途锁死） |
| `'2'` | **symlink** | `Files.createSymbolicLink`（P69 写成空文件）；parent 自动创建；失败**计数**不静默 |
| `'1'` | **hardlink** | `Files.createLink`（P69 误当 symlink）；目标缺失/失败 → symlink fallback 并记录 `linkFallbacks` |
| `'L'`/`'K'` | GNU longname/longlink | 内容覆盖下一条目的 name/linkname |
| `'x'`/`'g'` | PAX 扩展头 | 解析 `path`/`linkpath`/`size` record（`'x'` 下一条目生效，`'g'` 全局生效） |
| `'3'/'4'/'6'/'D'/'S'` | 设备/dumpdir/旧 sparse | skip + 计数（不误当文件数据） |

结构完整性：
- **header checksum 校验**（unsigned + legacy signed 两种算法；全零 checksum 容忍）
- **截断检测**：header 半块 / entry 数据中途 EOF → `ARCHIVE_INVALID`（P69 静默当 EOF）
- USTAR `prefix` 字段拼接（>100 字符路径）
- GNU base-256 size 解析
- 安全（§10 保持并加固）：`../`/绝对路径拒绝；canonical 越界判定改**分隔符边界**（P69 的 `startsWith` 会放行 `/target-evil`）；symlink 链逃逸拦截；hardlink 目标限定 rootfs 内
- `ExtractResult` 携带诚实统计（files/dirs/symlinks/hardlinks/pax/rejected/symlinkFailures/linkFallbacks/skippedSpecials）

### 3.4 `RootfsConfigurator` — 把"刚解压的 Ubuntu Base"配置成"能跑的 Ubuntu"
- `/etc/resolv.conf`：注入 DNS（Android 生产 DI）> 复制 host `/etc/resolv.conf`（CI/Linux）> 公共 DNS fallback（**带 warning**，绝不无声）
- `/etc/hosts`（localhost + hostname）、`/etc/hostname`、`/etc/default/locale`（C.UTF-8）
- apt/dpkg 工作目录幂等确保（lists/partial、dpkg info/updates/triggers、archives/partial、log/apt）
- **CA certificates 诚实策略**：host 有 bundle（CI/Linux）→ 真实复制进 rootfs；没有（Android）→ 保留缺失 + warning（http apt 源不受影响）。**绝不伪造空 bundle**
- `/tmp` world-writable；`/etc/localtime` 存在性检查
- 全部动作/警告进 `ConfigureReport`（可断言，不靠 println）

### 3.5 `RootfsHealthInspector` — READY 的证据
FAIL 级（阻断 READY）：`/bin/sh`、`/bin/bash`（经 symlink 链解析）、`/usr/bin/env`、os-release（内容含 ubuntu）、`/etc/apt`、apt sources（one-line 或 deb822）、`/usr/bin/apt`、`/usr/bin/dpkg`、`/var/lib/dpkg/status`、resolver（非空 nameserver）、**arch**（`/usr/bin/env` ELF e_machine 字节级比对设备架构——arm32/arm64 装错在 PRoot 报 exec 错前拦截）、基础目录。
WARN 级（如实入档不阻断）：CA 缺失、timezone 缺失。

### 3.6 `RootfsProvisionerImpl` — 生命周期强化
- **顺序修正**：EXTRACTED → **CONFIGURED** → **VALIDATED** → ACTIVATED → READY（resolv.conf 是 configure 写的，先验后配会把真 tarball 判死）
- **阶段证据持久化**：每阶段完成即写 metadata（`stageEvidence: {"DOWNLOADED": ts, …}`）；中途被杀后 reconcile 能看到死在哪一步（`interruptedInstall` → 清 staging + FRESH_INSTALL_REQUIRED）
- **原子激活修复**：旧版本先 rename 成 `<id>.replaced-<ts>`；staging 就位成功才清理；失败**回滚**旧版本（旧 READY 永不丢失）
- **archive 缓存复用**：`archives/` 已有 checksum 匹配的包 → 跳过网络（repair/重装提速；坏缓存删除重下）
- **跨实例文件锁**：`<base>/.provision.lock`（OS FileLock，进程崩溃内核自动释放）—— 多 provisioner 实例互斥
- `remove()` → `ProvisioningResult.Removed`（语义修复）；`invalidate(reason)`：停用（current()==null）但留档供 repair；`install(force=true)`：重装/版本迁移
- **版本迁移修复**：AlreadyReady 短路只在 current 与 target 同分布/架构/版本前缀时生效（装 26.04 不再被 24.04 挡）
- metadata schema v2（stageEvidence/health/entryCount），v1 文件向后兼容读取

### 3.7 测试
新增/重写（全部本地真实运行通过）：
- `RootfsExtractorTest`（14）：symlink/hardlink/prefix/GNU L·K/PAX path 覆盖/截断/checksum 损坏/gzip 损坏/mode 恢复/设备 skip/symlink 链逃逸/base-256
- `RootfsConfiguratorTest`（9）+ `RootfsHealthInspectorTest`（10）+ `RootfsDownloaderTest`（4）
- `RootfsProvisioningTest`（50，重写）：真实 Ubuntu 形状 fixture（merged-usr symlink！）、缓存复用（open 计数=0）、跨实例文件锁、invalidate 语义、force 重装、版本迁移、中断恢复、健康门禁（无 apt → ROOTFS_INVALID + 证据消息）
- **`UbuntuRootfsEndToEndIntegrationTest`（10）—— T72 衔接验证**：真实下载 cdimage amd64（30MB，真 SHA-256）→ provisioner 全链 → READY（metadata 证据链 + entryCount>3000 + 真实 merged-usr/hardlink/mode 断言）→ `LinuxPRootBackend.prepare()` SpawnSpec → **真实 proot 进程跑 Ubuntu**：`/etc/os-release`（"Ubuntu 24.04.4 LTS"）、`apt --version`、`test -L /bin`、workspace bind + guest env 注入、**`apt-get update` 真实网络成功**
- androidTest `RootfsProvisioningInstrumentationTest`（4）：真机 arm64 版全链（Termux proot 原始 `-E/--` argv 契约 + 设备 DNS 注入）
- 修复 `ProotExecutorProotSmokeTest`：预检去掉 `--`（A9 根因）→ CI 上**真跑**；执行加 host-proot 适配层（`-E`→pb env、去 `--`、`PROOT_NO_SECCOMP=1`）
- `PRootRuntimeIntegrationTest`：skip 消息更正（历史 skip 的真实原因是 `--` 语法，非 ptrace）

## 4. proot 版本差异（重要架构事实）

| | Termux proot 5.1.107.92（APK 生产目标） | upstream proot 5.4（CI apt 版） |
|---|---|---|
| `-E KEY=VALUE` | ✅（Termux 扩展，guest env 注入） | ❌ 不存在 |
| `--` 分隔符 | ✅ | ❌ `unknown option '--'` |
| Ubuntu 24.04 guest（glibc 2.39） | ✅（Termux 维护补丁） | ⚠️ 需 `PROOT_NO_SECCOMP=1`（seccomp 加速与 glibc 2.39 冲突 → SIGBUS） |

含义：`PRootCommandBuilder` 的 argv 契约（`-E`/`--`）**绑定 Termux 构建**——由真机 androidTest 锁定；JVM/CI 测试用语义等价的适配层（env 继承 + 无 `--`）。E2E 测试的适配逻辑只存在于测试代码。

## 5. 测试矩阵（真实结果）

| 环境 | 结果 |
|---|---|
| 本地 kotlinc 全闭包编译（main+test，183 文件） | ✅ 0 errors（1492 classes） |
| 本地 androidTest stub 编译 | ✅ 17 classes |
| 本地 JUnit 全量（121 类分 8 批） | ✅ **776 tests / 0 failures** |
| 本地 E2E（真实下载 + 真实 proot + apt-get update） | ✅ 10/10（110s） |
| 本地 proot smoke（修复后真跑） | ✅ 5/5 |
| Android 真机 instrumentation | ⏳ NOT AVAILABLE（无设备；CI 编译，真机运行需 connectedDebugAndroidTest） |
| GitHub Actions | ⏳ PR CI 验证中 |

## 6. 已知限制（诚实清单）
- 真机 androidTest 未运行（无设备）——T79 的范围
- E2E 的 Level 2/3 在 CI 依赖 runner 的网络与 proot 5.4 + `PROOT_NO_SECCOMP` 行为；本地已验证同版本组合可行
- CA bundle 在 Android 生产上缺失（Ubuntu Base 基线如此；http apt 源不受影响，`apt install ca-certificates` 可补）
- `UbuntuDistributionProvider`（P64 旧栈）保留为 @Deprecated 测试 stub——移除/适配属 T73 接线范围
