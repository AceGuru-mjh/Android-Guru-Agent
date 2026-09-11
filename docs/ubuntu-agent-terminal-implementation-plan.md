# Android-Guru-Agent — Ubuntu Agent Terminal 完整实施方案

> **状态**：规划文档（本 PR 不修改任何生产代码）
> **基线**：`main @ 729b69a`（"feat(terminal): PR #69 — Ubuntu RootFS Provisioning layer (#73)"）
> **作者**：Agent（基于对 main 全量源码的重新审读）
> **日期**：2026-08-28
> **目标**：让 Agent 真正可以通过 `terminal.run` 在真实 Ubuntu 24.04 userspace 中执行命令，并拥有长期 PTY/session、stdout/stderr streaming、write、signal、resize、wait、observe、workspace、environment 与 recovery 能力。

---

## 0. 结论摘要（Executive Summary）

1. **此前审计报告的核心结论全部成立，但有三处需要修正精度**：
   - `PRootRuntime.processProvider() → Fake`：只在 *未配置 rootfsProvider* 时才回退 Fake（`PRootBackend.kt:402-405`）。PR #68 确实接入了基于 `ProcessBuilder` 的真实 provider，但它有四个致命缺口（见 §1.2-G1~G4），且**生产路径从未配置 rootfsProvider**，所以"生产路径上等价于 Fake"的结论成立。
   - `install() → 不解压`：对 PR #64 的 `UbuntuDistributionProvider` 成立（`UbuntuEnvironment.kt:246-262` 直接返回 descriptor）。但 PR #69 新增的 `RootfsProvisionerImpl` **确实执行了真实下载与解压**——只是它有 placeholder 校验和、USTAR 解析器缺陷，并且**没有接入任何 DI / Runtime**（全仓库 0 处生产引用）。
   - `TerminalRuntimeImpl → 完全不知道 Linux/PRoot 存在`：**完全成立**。`TerminalRuntimeImpl` 构造参数只有 `NativePty / TerminalPolicy / VirtualTerminalFactory / persistenceStore`（`TerminalRuntimeImpl.kt:65-74`）；Hilt `TerminalModule` 只提供 `JniNativePty + TerminalPolicyImpl + SessionMetadataStore + TerminalRuntimeImpl`（`TerminalModule.kt:26-52`）。
2. **审计报告遗漏了比"链路未接通"更严重的问题**：当前生产 JNI 路径上存在 **4 个只在真机才会触发的 I/O 级 bug**（idle-read 误判 -1 杀死输出泵、`NewStringUTF` 在 NUL 处截断、LINE 模式双换行、write 使用错误的 native sessionId），它们同样会杀死未来的 Ubuntu PTY 会话。**若不先修复本地核心，Ubuntu 闭环在真机上第一天就会失败。**
3. **架构裁决**：采用 **PTY-Spawn 级 Backend Seam**（方案 C，A 的精化）：把"会话如何被 spawn"抽象为 `ExecutionBackend`，在**现有** `SessionManager` 创建点注入 `LocalShellBackend` / `LinuxPRootBackend`；`forkpty` 的 child 从 `execl(shell)` 泛化为 `execv(argv)`，Ubuntu 会话即 `proot -r <rootfs> … -- /bin/bash -i`。**100% 复用成熟的 Session/Job/IO/Wait/Observation/Persistence 核心，零平行实现。**（§3）
4. **PR Roadmap**：P70（本地核心加固）→ P71（生产 PRoot 执行）→ P72（Ubuntu RootFS 生产化）→ P73（Linux ExecutionBackend 接入 TerminalRuntime）→ P74（Ubuntu 长生命周期 PTY 会话 + Agent E2E）→ P75（Workspace）→ P76（apt）→ P77（Environment）→ P78（Linux Recovery）→ P79（Job/Process 收编与死代码清理）→ P80（性能基准）。编号依据当前 git 历史（内部 P 序列已用到 P69；GitHub PR 序列已用到 #74，#70 仍处于 open 状态）。（§24）

---

## 1. 当前架构问题

### 1.1 审计结论复核表

| 审计结论 | 复核结果 | 证据 |
|---|---|---|
| `PRootRuntime.processProvider() → Fake` | **部分成立**（生产等价成立） | `PRootBackend.kt:402-405`：`realProcessProvider ?: FakeLinuxProcessProvider()`；生产无任何调用方传入 `rootfsProvider` |
| `UbuntuDistributionProvider.acquire() → Simulated download` | **成立** | `UbuntuEnvironment.kt:238-239`：注释原文 `// Simulated download` |
| `install() → 不解压` | **对 P64 成立，对 P69 不成立** | `UbuntuEnvironment.kt:246-262` vs `RootfsProvisionerImpl.kt:140-175`（真实 download + extract 调用链） |
| `TerminalRuntimeImpl → 不知道 Linux/PRoot` | **完全成立** | `TerminalRuntimeImpl.kt:65-74`、`TerminalModule.kt:26-52`、全仓库 grep：`linux/`、`proot/`、`ubuntu/` 包在 `app/` 模块 0 引用 |
| Fake 进入不了 production 却存在 | **成立且更严重** | 8 个 Fake 类在 `FakeLinuxRuntime.kt`；`FakeRootfsSource` 甚至位于 **main** source set（`OfficialUbuntuRootfsSource.kt:124-145`） |
| 双 Job/Process 体系 | **成立且是三套** | `job/`（活跃）+ `process/`（半活跃）+ `process2/`（纯死代码）；另有 `control/`、`api/TerminalApi.kt`、`observation2/`、`input2/` 四个"平行宇宙"（§1.2-D） |

### 1.2 审计遗漏的架构问题（新发现，按严重度排序）

**P0-A：生产 JNI 路径的 4 个致命 I/O bug（Fake 测试全部掩盖）**

| # | Bug | 位置 | 后果 |
|---|---|---|---|
| A1 | **idle-read 误判**：`JniNativePty.nativeRead` 把"空读 + hasData()==false"映射为 `-1`；而真实 JNI 的 idle 读返回 `""`（`pty_session.cpp:159-183` EAGAIN→break→空串） | `JniNativePty.kt:56-71` | 真机上**第一次空闲读就返回 -1，输出泵立即自杀**（`PtyOutputPumpImpl.kt:71-76` emitError + break）。`FakeNativePty` idle 返回 0（`FakeNativePty.kt:90`），所以 JVM 测试全绿 |
| A2 | **NUL 截断 + UTF-8 破坏**：`NewStringUTF(raw.c_str())` 在首个 `\0` 截断，且非 UTF-8 字节被替换 | `jni_bridge.cpp:65` | 任何含 NUL/非 UTF-8 的输出（gzip、二进制 `cat`）静默损坏 |
| A3 | **双换行**：LINE 模式 Kotlin 侧追加 `\n`（`TerminalInput.kt:32-33`），C++ `PtySession::writeLine` 再追加 `\n`（`pty_session.cpp:154-157`） | 两层 | `terminal.run` 的命令变成 `cmd\n\n`（空行执行一次）；RAW/KEY 写入也带多余回车（方向键变 `\x1b[A\n`） |
| A4 | **native id 错位**：`InputManagerImpl.doWrite` 用 `sessionId.toInt()`，而 pump/resize 用 `assembly.nativeSessionId`（两个独立计数器：Kotlin AtomicLong vs C++ `nextId_`） | `InputManagerImpl.kt:97` vs `SessionManagerImpl.kt:103` | 当前仅靠"两个计数器恰好同步"的巧合工作；Linux 后端接入后（创建顺序不同）立即错位 |

**P0-B：Ubuntu RootFS 下载在真实镜像上必然失败**
- `OfficialUbuntuRootfsSource.kt:54,61`：两个 artifact 的 sha256 均为 `"0000…0"` placeholder。`isVerifiable` 只检查 `length==64`（`RootfsProvisioning.kt:45`），所以下载器会真的去和全零比对 → `CHECKSUM_MISMATCH`。**今天对真实 cdimage.ubuntu.com 跑 install() 100% 失败。**
- 无 SHA256SUMS 拉取逻辑，无 pin 表。

**P0-C：USTAR 解析器缺 prefix 字段 / GNU / PAX 支持**
- `RootfsExtractor.kt:86-91` 只读 name[0..100]，不读 USTAR prefix（345..500），不认 `L`/`K`（GNU longname）、`x`（PAX）。ubuntu-base 中大量 `/usr/lib/...` 路径 >100 字符 → **路径截断/错乱，解压出的 rootfs 损坏**。
- 硬链接（typeflag `2`）、char/block device、fifo 完全未处理（ubuntu-base 基本没有 device node，风险低，但需显式声明策略）。

**P0-D：apt 无法工作的三个根因（都在 Android 侧，不在 apt 侧）**
1. **DNS**：Android 无 `/etc/resolv.conf`；ubuntu-base 里也没有 → guest 内 `apt-get update` 直接 DNS 解析失败。必须在 configure 阶段从 Android `ConnectivityManager`/系统属性生成 rootfs 内 `/etc/resolv.conf`。
2. **arm64 源错误**：ubuntu-base 24.04 的 `/etc/apt/sources.list.d/ubuntu.sources` 指向 `archive.ubuntu.com`——它**不托管 arm64 deb**。arm64 必须改写为 `ports.ubuntu.com/ubuntu-ports`（amd64 不用改）。这是 proot-distro 等所有先行者都踩过的坑。
3. **CA 证书**：guest 无 `ca-certificates` 时 HTTPS apt 失败；v1 用 HTTP 镜像 + 首次 `apt-get install ca-certificates` 解决。

**P1-E：平行宇宙死代码（约半个 platform/terminal）**
以下包/类**零生产引用**（仅自测试）：`process2/`（JobManager2/ProcessControl/BoundedJobRegistry——后者恰好修了活跃代码的无界 job 泄漏）、`control/`（TerminalController 三号门面）、`api/TerminalApi.kt` + `ApiHardening`（三号公共 API，无实现）、`observation/Observation2`、`input/InputLayer2`、`reliability/`、`workspace/Workspace.kt`、`runtime/RuntimeWorkspace.kt`、`session/SessionAbstractions.kt:37 TerminalExecutionBackend`、`LegacySignalTool/LegacyCloseTool`（未注册）、`LegacyTerminalManager`（DI 提供、无人注入）、`StreamingShellExecuteTool`（未注册）、两份死 `SdkDownloader`。
**治理风险**：新贡献者无法分辨哪套是 canonical；CI 编译时间被浪费；更严重的是 `BoundedJobRegistry` 这类"修 bug 的代码"躺在死代码里。

**P1-F：长会话资源泄漏（与 Ubuntu 长期会话目标直接冲突）**
- EventLog 无界（`TerminalEventLogImpl.kt:23` per-session ArrayList；`drop()` 从未被调用）；`BackpressureConfig.eventBufferLimit=10000` **从未接线**。
- `JobManagerImpl.jobs` 与 `SemanticStateReducer.backgroundJobs` 无界增长 → observe(SEMANTIC) token 成本随历史无限膨胀。
- 泵在 shell 退出后永不停止（`SessionManagerImpl.kt:151` 只有 close() 停泵；退出后 50Hz 空转）+ 100ms exit-watcher 终身轮询。
- `InputManagerImpl.drop()` 不关 channel、不取消 writer 协程（`InputManagerImpl.kt:221-224`）→ 每 close 一个 session 泄漏一个 parked 协程。
- `wait()` 每次调用 `afterCursor=0` 重放**全部**历史事件（`WaitEngineImpl.kt:81-106`），O(history) 退化；`matchOutput` 忽略 pattern（`WaitEngineImpl.kt:73-79`）；`IdleFor` 永不匹配（`:68`）。

**P1-G：PR #68 "Real Linux Runtime" 的四个缺口**
- G1 **假 PID**：`PRootProcessProvider.kt:41,77` 用 `AtomicLong(10000)` 计数器当 PID，不是 OS pid；signal/wait/进程映射全是假的。
- G2 **管道冒充 PTY**：`PRootPtySession.resize()` 是文档化的 no-op（`PRootPtyProvider.kt:65-71`）；无 winsize/termios/SIGWINCH，交互程序（vim/python REPL）行为错误。
- G3 **无 PRootBinaryProvider 生产实现**：接口在 `PRootBackend.kt:33-36`，只有测试里的 `RealPRootBinaryProvider`（探测 `/usr/bin/proot` 等 host 路径）。**APK 未打包 proot 二进制，无 jniLibs**。
- G4 **环境注入错位**：`PRootProcessProvider.kt:70-73` 把 guest 环境变量写进了 **host 进程**的 ProcessBuilder env（注释声称 `-E` 会注入 guest，但 `-E` 只在 `PRootCommandBuilder` 的 argv 里生效，两处叠加导致 host env 污染）。

**P1-H：Native 层并发瓶颈**
- `PtyEngine` 全局 mutex 串行化所有 session 的 read/write/signal（`pty_engine.cpp:38-54`）。
- `PtySession::close()` 持引擎锁做**无限阻塞 `waitpid`**（`pty_session.cpp:248-278`）→ 一个挂死的 close 可以冻结全 App 所有终端。
- `PtySession::write` 在 EAGAIN 上持锁 busy-loop `usleep(1000)`（`pty_session.cpp:139-152`）。

**P2-I：Agent 工具层缺陷**
- `terminal.create` **静默忽略** `env`/`privilege` 参数（`TerminalCreateTool.kt:65-71` 只解析 4 个字段）。
- `terminal.observe` EVENT 模式只返回 `eventCount`，不返回事件本身（`TerminalObserveTool.kt:120`）。
- 策略门不对称：`shell_execute` 走 `CommandPermissionGate`（用户确认），13 个 `terminal.*` 工具绕过该 gate（只有 TerminalPolicy 内部拦截）——权限模型语义不一致。
- README/ToolRegistrationGuide 声称 6 个 legacy 工具，实际注册 4 个。
- 恢复服务把恢复出来的 state **丢弃**（`RuntimeRecoveryService.kt:77-84` 注释自认 v2 待办）；周期性自动保存从未启用（只存 ProcessExited/SessionClosed 时刻）。

**P2-J：CI / 测试体系无法证明真实运行**
- `PRootRuntimeIntegrationTest` 用 **host `/` 当 rootfs**（`TestRootfsProvider(rootfsPath="/")`），永远不测 Ubuntu 下载/解压；GH runner ptrace 受限时全部自跳过（`:110-131`）。
- CI（`ci.yml`）：无 instrumented test、无模拟器 job、`continue-on-error` 容忍 Hilt/KSP 失败、`:core:tool-registry:test` 因编译错误被跳过。
- **ATR 2.1（PR #61-69）零文档**：KDoc 里引用的 "Spec: PR #6x sections" 不在仓库中。

**P2-K：环境层是"能干的小岛"**
- PR #66/67 的 `EnvironmentManager/Resolver/AdaptiveProvisionLoop`（174 个测试）**零生产接线**；`AdaptiveProvisionLoop` 需要的 `executor` lambda 无生产供应者。
- App 实际的"环境依赖中心"（`TerminalViewModel.kt:133-162`）往 Android `/system/bin/sh` 里发 **winget/scoop 命令**（Windows 包管理器）——UI 审计已标为 UI-005 必死按钮。

### 1.3 一句话诊断

> 仓库现状 = **一个接近成熟但带着 4 个真机致命 bug 的本地 PTY 核心** + **一个工程质量不错但完全断路、且自带两套重复 provisioning 的 Linux 平行宇宙** + **约半个模块的死代码**。下一阶段的正确动作不是继续加 Contract，而是：**先修核心 → 再把 PRoot/RootFS 真正接到 spawn 点上 → 然后让 E2E 在模拟器上跑通**。

---

## 2. 目标架构

### 2.1 最终链路（必须真实）

```text
Agent (LLM tool-call)
  ↓
AgentTool: terminal.create / run / observe / write / wait / signal / resize / snapshot / close
  ↓
TerminalRuntime（backend 无关的编排层 —— 现有实现，基本不动）
  ↓
SessionManager ——【新增唯一的接缝：ExecutionBackend】
  ↓                ┌──────────────────────────────┐
  SpawnSpec        │ ExecutionBackend (接口)      │
  (argv, env, cwd, │  ├─ LocalShellBackend        │→ NativePty.forkpty → /system/bin/sh -i     （成熟路径，回归保护）
  rows, cols)      │  └─ LinuxPRootBackend        │→ NativePty.forkpty → proot -r rootfs … -- /bin/bash -i
                   └──────────────────────────────┘
  ↓（两条路径产出同一种东西：一个真实 PTY master fd + child pid）
PtyOutputPump → RingBuffer → VirtualTerminal(VT100) → SemanticStateReducer → ObservationEngine → terminal.observe
InputManager（write/signal/resize —— 统一走 nativeSessionId）
JobManager（run = 向长生命周期 shell 写命令行；job = shell 命令）
WaitEngine / EventBus / EventLog（统一事件驱动）
SessionMetadataStore / RuntimeRecoveryService（持久化 + 恢复）
```

### 2.2 分层职责与共享/隔离边界

| 层 | 共享（backend 无关） | 隔离（backend 特有） |
|---|---|---|
| Agent 工具层 | 9 个 `terminal.*` 工具、schema、权限 | create 的 `backend` 参数 |
| Runtime 编排 | create/run/observe/wait/write/signal/resize/snapshot/close/recover | 无 |
| 会话/作业 | Session、Job、状态机、event 语义 | SpawnSpec 构造 |
| I/O | 泵、ring buffer、VT、观察 | 无（统一 PTY fd） |
| 平台 | —— | Local：Android env；Linux：proot argv/env/binds/rootfs/workspace |
| 供给 | —— | Linux：RootfsProvisioner、apt、environment profiles |

### 2.3 不可破坏项（红线）

1. **本地 Android shell 路径的行为保持回归兼容**：默认 `backend=LOCAL`，不传参时行为与今天完全一致（现有 174+ 终端测试全绿为准）。
2. **不再新增平行实现**：任何新能力必须落在既有 canonical 类型上（Session/JobManager/InputManager/ObservationEngine/NativePty），禁止再造 process3/control2/api2。
3. **Fake 只允许存在于 test source set**：`FakeRootfsSource`、`FakePackageManager` 等从 main 移到 test（或标注 `@VisibleForTesting` 并由架构测试保证生产 DI 不引用）。
4. **不改已有公共 API 语义**：`TerminalRuntime` 9 操作的签名只做**加参数（带默认值）**式扩展。

---

## 3. ExecutionBackend 设计（回答架构问题 Q1）

### 3.1 三个候选方案的裁决

- **方案 A（TerminalRuntime 下挂 LocalExecutionBackend / LinuxExecutionBackend）**：方向正确，但字面执行会把 SessionManager/InputManager/泵的全部内部协作复制进 backend——形成第二个 runtime。**否决（按字面形式）。**
- **方案 B（TerminalRuntimeImpl 内部 if/else 判断 runtime 类型）**：把平台差异撒进编排层，不可测试、不可扩展。**否决。**
- **方案 C（裁决：PTY-Spawn 级接缝，A 的精化）**：核心洞察是——**现有 Terminal Core 的全部下游（泵/VT/观察/等待/写入/信号/缩放）本来就只依赖"一个真实 PTY master fd + nativeSessionId"**。Linux 会话与本地会话在 PTY 层面**没有本质区别**：唯一差异是 *forkpty 的 child exec 什么程序、带什么 env、落在什么 cwd*。因此把接缝放在**唯一的 spawn 点**：

```kotlin
// platform/terminal/…/runtime/ExecutionBackend.kt（新文件，唯一新抽象）
interface ExecutionBackend {
    val id: String                                   // "local" | "linux-ubuntu"
    val runtimeType: RuntimeType                     // ANDROID_LOCAL | LINUX
    suspend fun availability(): BackendAvailability  // READY / NEEDS_ROOTFS(state) / FAILED(reason)
    suspend fun prepare(request: SessionSpawnRequest): SpawnSpec
}
data class SessionSpawnRequest(
    val shellHint: String?, val cwd: String, val rows: Int, val cols: Int,
    val env: Map<String, String>, val workspaceId: WorkspaceId?, val privilege: PrivilegeLevel
)
data class SpawnSpec(
    val argv: List<String>,          // LOCAL: ["/system/bin/sh","-i"]；LINUX: [proot, "-r", rootfs, …, "--", "/bin/bash","-i"]
    val env: Map<String, String>,    // 完整 env（各自构建）
    val cwd: String,                 // host 侧 cwd（forkpty child 的 chdir；LINUX 下由 proot -w 翻译）
    val cwdIsGuestPath: Boolean,     // LINUX=true：cwd 是 guest 路径，需翻译成 host 或交给 proot -w
    val metadata: BackendSessionMetadata  // rootfsId、workspaceDir、binds 等，进 SessionRecord
)
```

`SessionManagerImpl.create()` 内部唯一改动：`native.nativeCreateSession(shell, cwd, …)` → `native.nativeCreateSessionArgv(spec.argv, spec.cwd, spec.env, rows, cols)`（见 §11）。**LocalShellBackend 生成的 argv/env 与今天硬编码在 `pty_session.cpp:60-85` 的内容逐字节一致**（把 C++ 里的硬编码 env 迁到 Kotlin LocalShellBackend，C++ 只保留安全默认值）——这是"不破坏成熟路径"的结构性保证。

### 3.2 为什么这是最小风险

- 下游 **0 行改动**即可获得 Linux 会话：泵读同一个 master fd；write 写同一个 fd；signal 走同一条 `kill(-pgid)`（forkpty 使 proot 成为 session leader，`--kill-on-exit` 保证树清理）；resize 走同一个 `TIOCSWINSZ`（guest bash 收到真实 SIGWINCH）。
- `PRootCommandBuilder`（`PRootBackend.kt:66-105`）**原样复用**为 LinuxPRootBackend 的 argv 构造器——PR #63 的资产不浪费。
- 回归保护 = LocalShellBackend 与旧硬编码输出的差异测试（golden argv/env snapshot）。

### 3.3 P68 ProcessBuilder 路径的定位（避免"第三套"）

`PRootProcessProvider/PRootPtyProvider`（ProcessBuilder + 管道）**保留但降级**为 `ProotExecutor`：专用于**无 PTY 的短命令**（rootfs 探针、apt、环境检测）与 **CI/JVM 集成测试**（JVM 无 JNI .so，必须走 ProcessBuilder）。它与 PTY 路径共享 `PRootCommandBuilder`/`RootfsDescriptor`/env 构建。文档明示：**生产交互会话一律走 forkpty 路径**。

---

## 4. Linux Runtime 设计

### 4.1 现有 `LinuxRuntime` 契约（PR #62）的处置

`LinuxRuntime` 及其配套类型（`LinuxRuntimeInfo/Capability/Filesystem/ProcessProvider/PtyProvider/RootfsDescriptor/…`）**保留为能力描述与诊断模型**，但**不再作为执行入口**：

- `PRootRuntime` 类保留，职责收缩为：`initialize()` 时 locate/verify proot 二进制 + 校验 rootfs + **产出 `LinuxPRootBackend` 所需的全部配置**（binaryPath、rootfsDescriptor、binds、envBuilder）——即从"运行时"降格为"backend 工厂 + 健康报告器"。
- `LinuxRuntimeInfo.supports()` 上报的能力与 §2.2 分层一致（PTY/SIGNALS/RESIZE=true；PROCESS_GROUPS 标 false——见 §12.4 的诚实语义）。
- `FakeLinuxRuntime` 等整体移入 test source set。

### 4.2 RuntimeType / 会话类型选择

- `TerminalRuntime.create(..., backend: BackendId = LOCAL)`（带默认值，向后兼容）。
- `availability()` 驱动 Agent 的能力发现：`terminal.backends` 工具（§16）返回 `[{id:"local",READY},{id:"linux-ubuntu",NEEDS_ROOTFS(DOWNLOADING,42%)}]`，Agent 据此决策（或触发安装）。

---

## 5. PRoot 设计（回答架构问题 Q3）

### 5.1 PRoot 二进制来源（G3 的解法）

- **打包方式**：以 `jniLibs/<abi>/libproot.so` + `libproot-loader.so`（如所用 proot 构建需要独立 loader）随 APK 分发——`nativeLibraryDir` 是 Android 上**唯一保证可执行**的 App 可控目录（Termux/UserLAnd/Andronix 的标准做法）。
- **构建来源**：v1 采用 Termux 打包的 proot 静态构建（GPL——App 分发需遵守 GPL，二进制+源码引用，法务确认项 §25）；v2 选项改为自行 CI 构建（proot + talloc 静态链接）。
- **关键 gradle 设置**：`packaging { jniLibs { useLegacyPackaging = true } }`（否则 AGP 默认不解压 .so 到 nativeLibraryDir，proot 不存在于磁盘上——**这是最常见的翻车点**）。
- **运行环境**：`PROOT_TMP_DIR` 指向 `cacheDir/proot-tmp`（默认 /tmp 在 Android 不可写）；loader 路径通过 `PROOT_LOADER` env 或 argv0 相对定位。
- `NativeLibraryPRootBinaryProvider : PRootBinaryProvider`（生产实现，P71 新增）：locate = `context.applicationInfo.nativeLibraryDir + "/libproot.so"`；verify = 执行 `libproot.so --version` 解析版本 + ABI 匹配（`Build.SUPPORTED_ABIS`）。

### 5.2 argv 规范（完整命令行契约）

```text
<proot>
  -r <rootfsDir>                          # rootfs（versions/<id> 绝对路径）
  -0                                      # fake root（uid 0 映射）
  --kill-on-exit                          # proot 退出时杀光 guest 进程树
  -b <workspaceDir>:/workspace            # workspace bind（可写）
  -b <resolvConfFile>:/etc/resolv.conf    # DNS（§9.3）
  -b /proc                                # 显式（虽然 proot 默认处理，显式声明便于审计）
  -b /dev                                 # 同上（/dev/urandom 等）
  -b /sys                                 # 同上
  -b /sdcard:/sdcard                      # 可选：用户授权后挂载共享存储（opt-in，默认关）
  -w /workspace                           # 初始 cwd（guest 路径）
  -E TERM=xterm-256color -E LANG=C.UTF-8 -E HOME=/root -E SHELL=/bin/bash
  -E PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  -E TMPDIR=/tmp
  -- /bin/bash -i                         # 长生命周期交互 bash
```

- **cwd 语义**：`-w` 使用 guest 路径；host 侧 chdir 设为 rootfs 任意安全目录（proot 忽略 host cwd，以 -w 为准——现有 `PRootCommandBuilder.build` 已处理 `-w`，P71 修正其 `removePrefix("workspace:")` 的路径映射错误：guest cwd 必须以 `/workspace/...` 形式给出）。
- **env 注入修正（G4）**：guest 环境只经 `-E` 传入；host ProcessBuilder/forkpty env 仅含 proot 自身需要的 `PROOT_TMP_DIR/PROOT_LOADER/PATH=system`，**两套 env 严格分离**。
- **stdin/stdout/stderr**：forkpty 路径下即 PTY master fd（天然三合一、支持全双工与 winsize）；ProotExecutor 路径下为 pipes（`redirectErrorStream(false)` 保持分离）。
- **exit code**：`waitEngine` 收 `ProcessExited`；proot 透传 guest 退出码（`--kill-on-exit` 场景下 proot 被杀 → 137/SIGKILL 语义）。
- **process group**：forkpty 使 proot 成为 session+group leader（PGID==proot PID）→ 现有 `kill(-PGID)` 语义直接成立；proot 内部前台组通过 PTY 的 `tcgetpgrp`（Ctrl-C 走 0x03 字符 → 内核 line discipline → guest 前台组，天然正确）。

### 5.3 启动成本与长生命周期裁决（回答"是否应该 Session 创建时启动一个长期 PRoot shell"）

**YES，这是唯一正确模型**，理由：
1. **成本**：每次 proot 冷启动 = fork+exec proot（~50-100ms）+ ptrace attach guest（~100-300ms）+ bash 登录 + profile（~0.5-2s，取决于 rootfs I/O）。若每次 `terminal.run` 都重启 proot，Agent 一个 20 步任务要付出 20×(2-3s) 的纯开销，还会**丢失 shell 状态**。
2. **语义**：用户明确要求"同一 shell、同一 cwd、同一 env、同一 PTY"。而现有 `JobManagerImpl.startJob` 本来就是**向长生命周期 shell 写命令行**（`JobManagerImpl.kt:126-151`）——本地路径已经实现了这个模型，Linux 会话免费继承。
3. **实现**：`create(backend=LINUX)` 启动一个 proot+bash 直到 `close()`；`run/write/observe/wait/signal/resize` 全部作用于该会话；后台作业用 shell `&`（§12.4）。
4. **并发上限**：每个 Linux 会话 = 1 个 proot（RSS ~5-15MB + guest bash）。`TerminalPolicy` 增加每 backend 最大并发会话数（v1: linux=2, local=8）。

---

## 6. RootFS 设计

### 6.1 来源与格式（裁决）

- **制品**：`ubuntu-base 24.04`（官方最小 rootfs，tar.gz，arm64 约 30MB 压缩 / ~120MB 解压——比 cloud-images 小一个数量级，且无 kernel/boot 依赖）。
- **URL**（沿用 `OfficialUbuntuRootfsSource.kt:50,60`）：
  - arm64: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz`
  - amd64: `…/ubuntu-base-24.04-base-amd64.tar.gz`（x86_64 模拟器/CI 用）
- **校验和（P0-B 解法）**：resolve 阶段拉取同目录 `SHA256SUMS` 解析真实值；同时**内置 pin 表**（发布时固化）作为 TOFU 信任锚；两者不一致 → `CHECKSUM_MISMATCH` + 明确错误信息。`isVerifiable` 修为 `sha256 != null && !isPlaceholder`。
- **格式支持（P0-C 解法）**：**废弃手写 USTAR 解析器，改用 Apache Commons Compress**（`org.apache.commons:commons-compress` + `org.tukaani:xz`）：
  - 正确支持 USTAR prefix、GNU longname(`L`/`K`)、PAX(`x`)、symlink、hardlink、mode/uid/gid、sparse。
  - zstd 不引入（zstd-jni 需要额外 native 库；v1 只需 tar.gz/tar.xz）。
  - 保留现有**流式解压 + 路径穿越防护 + 逐 entry 取消**的框架（`RootfsExtractor` 的对外 API 不变，内部换引擎；`sanitizeEntryName` 保留为第二道防线）。
  - uid/gid：Android 上无法 chown → 全部文件归属 App uid；`-0` fake-root 下 guest 视角 root 拥有（proot 的 stat 翻译）。device node：`ubuntu-base` 不含；解压器对 device/hardlink entry 记 warning 不失败。
- **多版本**：同一 rootfs 允许多版本共存（versions/ 目录天然支持）；`RootfsTarget` 增加版本号解析（24.04/24.04.x）。

### 6.2 存储布局（沿用 P69 `RootfsInstallLayout`，微调）

```text
<filesDir>/linux/ubuntu/
  ├── staging/                 # 解压中（crash → 整目录作废）
  ├── versions/<artifactId>/   # 已激活 rootfs（artifactId 如 ubuntu-24.04-arm64）
  │   └── .provisioned         # 【新增】in-rootfs 完成哨兵：目录级"真实 READY"证据
  ├── archives/                # 下载缓存（.part 断点 + 完整 tar.gz）
  ├── current                  # 原子 marker（tmp+rename，内容=artifactId）
  └── rootfs.json              # RootfsMetadata（schema 1）
```

- **状态机**（沿用 `ProvisioningState` 13 态）：`IDLE→RESOLVING→DOWNLOADING→VERIFYING→EXTRACTING→VALIDATING→CONFIGURING→ACTIVATING→READY`；失败态 `FAILED/CANCELLED`；`REMOVING/REMOVED`。
- **"看起来 READY 实际损坏"防御（用户红线）**：三重证据链——`current` marker（最后写）+ `rootfs.json` state==READY（倒数第二写）+ `versions/<id>/.provisioned` 哨兵（ACTIVATING 前写）。`current()` 校验三者一致才返回 READY；`reconcile()` 已有 CLEAN_STAGING/CLEAN_TEMP/REPAIR_METADATA 动作，新增 `INVALIDATE_VERSION`（哨兵缺失 → 整版本作废重装）。
- **磁盘预检**：沿用 20× 解压系数 + 100MB 安全垫（`RootfsProvisionerImpl.kt:122-127`）；增加"解压后实际占用回填 metadata"。
- **锁**：`RootfsInstallLock`（进程内 AtomicBoolean）保留——App 单进程足够；`markInUse/markIdle`（`:63-66`）在 P73 接到 SessionManager（会话存活期间置位，remove() 拒绝执行）。
- **清理/GC**：archives 保留最近 1 份完整包；versions 保留当前 + 1 个回滚版本；`remove()` 语义修正（当前返回 `Ready(removedDescriptor)` 的别扭封装 → 改返回 `Removed`，属 API 硬化的**破坏性修正**，在 P72 一并处理并更新测试）。

### 6.3 configure 阶段（guest 侧首次配置，P72 实现）

1. 生成 `/etc/resolv.conf`（Android DNS → nameserver 行；详见 §9.3）。
2. **arm64 改写 apt 源**为 `ports.ubuntu.com/ubuntu-ports`（amd64 保持 archive.ubuntu.com）——P0-D2 解法；写 `/etc/apt/sources.list.d/ubuntu.sources`。
3. `/etc/hosts` 最小条目（localhost + android 设备名）。
4. `cp /etc/skel/.bashrc /root/.bashrc`（让 bash 有彩色提示符，利于 InputWaitingDetector）。
5. `machine-id` 生成；`/etc/localtime` → UTC（TZ 由会话 env 决定）。
6. 创建 `/workspace` 挂载点（已有 `configureBasicEnv`，`RootfsProvisionerImpl.kt:268-275`）。

### 6.4 P64 旧栈处置

`UbuntuDistributionProvider/FakeRootfsProvider/UbuntuRootfsState/AtomicInstallConfig/…`（`UbuntuEnvironment.kt`）与 P69 语义重叠且为模拟实现：**P72 删除**，保留 `UbuntuRuntime`（薄装饰，改为持 backend 引用）与类型被引用的部分；其测试改写为针对 RootfsProvisioner 的真实行为。`UbuntuBaseProfile` 的自检语义并入 §9 的环境自检。

---

## 7. Workspace 设计

### 7.1 裁决（对应用户五选项）

- **A（Android filesystem）+ B（bind 到 /workspace）+ E（与 rootfs 分离）：采用**。
- **C（每 Agent session 一个）：否决**——Agent 的多个会话/多轮任务需要延续同一项目文件；session 级隔离会造成"上一步写的文件这一步看不见"。
- **D（每 project 一个）：采用为默认粒度**。

模型：

```text
<filesDir>/workspaces/<workspaceId>/     # Android 侧真实目录（App 私有，无需权限）
    ↓ PRoot -b
/workspace（guest 视角，可写）
```

- RootFS 只承载系统/工具链（apt 安装的包写进 rootfs —— rootfs 是**可变的**，但不承载项目文件）→ 项目变化不污染 rootfs；rootfs 损坏重装不丢项目。
- `WorkspaceManager`（P75 实现，首个 `Workspace` 接口实现者）：create/list/open/delete + 持久化 workspace 注册表（id、display name、创建时间、绑定 project 路径）。
- 会话绑定：`create(backend=LINUX, workspaceId?)` → 缺省 `"default"`；`SessionRecord` 记录 workspaceId + host 目录；恢复时用于重建 binds。
- `WorkspacePath("workspace:/…")` 值类型保留给未来 guest 路径表达；v1 工具层仍用 guest 绝对路径字符串（`/workspace/...`），避免工具 schema 大改。
- 共享存储（/sdcard）为 opt-in bind（默认不挂，尊重 scoped storage；用户在设置中授权后追加 `-b /sdcard:/sdcard`）。

---

## 8. Environment 设计

### 8.1 会话环境（LinuxPRootBackend 生成，进 `-E`）

| 变量 | 值 | 依据 |
|---|---|---|
| `PATH` | `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin` | guest 标准 |
| `HOME` | `/root` | ubuntu-base 唯一用户是 root（fake root） |
| `SHELL` | `/bin/bash` | |
| `TMPDIR` | `/tmp` | rootfs 内 tmp（proot 翻译） |
| `LANG` | `C.UTF-8` | 免装 locales 即可用的 UTF-8 locale |
| `TERM` | `xterm-256color` | 与本地路径一致的 VT 假设 |
| `DEBIAN_FRONTEND` | `noninteractive` | apt 场景（仅 ProotExecutor 路径注入） |
| `PROOT_*` | 仅 host 侧 | §5.2 |

- 用户/Agent 自定义 env：`create(env=…)` 参数（修复 P2-I 的"env 被忽略"）以 `-E` 追加，**不允许覆盖** PATH/HOME 的系统段（白名单合并）。

### 8.2 Provisioning 分层（Base + Profiles + apt）

```text
Layer 0  ubuntu-base rootfs            （系统引导：bash/apt/coreutils）
Layer 1  bootstrap                     （resolv.conf + 源改写 + ca-certificates —— P72/P76）
Layer 2  Environment Profiles          （python/jdk/node/cpp/rust/go —— 复用 P66 BuiltInProfileRegistry）
Layer 3  Adaptive Provision Loop       （P67：诊断→补装→重试，executor=Linux 会话）
```

- **复用而非重写** P66/P67 的 `EnvironmentProfileRegistry/ProjectEnvironmentAnalyzer/EnvironmentResolver2/DiagnosticRules/AdaptiveProvisionLoop`——它们的设计质量高，缺的只是 executor 接线（P77）。
- `EnvironmentManager` 的 per-workspace env 持久化继续使用；会话创建时注入该 workspace 的 env 叠加层。
- App 的 winget/scoop 依赖中心（UI-005）在 P77 替换为"profile 安装"（走 apt），删除两份死 `SdkDownloader`。

---

## 9. Package Manager（AptPackageManager）

### 9.1 可行性判定（回答"apt 到底能不能工作"）

**能，但有四个前提**，全部在 Android/PRoot 侧解决（apt/dpkg 本身与内核无冲突，proot 的 syscall 翻译覆盖 dpkg 所需）：

| 前提 | 问题 | 解法 |
|---|---|---|
| DNS | guest 无 resolv.conf | §9.3 |
| 源 | arm64 在 archive.ubuntu.com 不存在 | §6.3 改写 sources |
| CA | HTTPS 源无证书 | v1 全 HTTP 源；`ca-certificates` 作为 bootstrap 第一个包 |
| dpkg lock | 上次中断留下 `/var/lib/dpkg/lock*` | §9.4 repair 流程 |

### 9.2 执行通道与命令构造

- **通道**：`ProotExecutor`（ProcessBuilder + pipes，§3.3）——apt 是非交互批处理，不需要 PTY；干净拿到 exit code / stdout / stderr 分离流。**复用 `AptCommandBuilder`**（`PackageManager.kt:136-164`）已构造好的结构化 argv。
- 标志：`apt-get -y --no-install-recommends install`、`-o Dpkg::Options::=--force-confold`、`DEBIAN_FRONTEND=noninteractive`、`LC_ALL=C.UTF-8`。
- **进度**：解析 apt 输出行（`Get:`/`Unpacking`/`Setting up`/百分比）→ `PackageOperationEvent`（契约已有，`PackageManager.kt:93-123` 的 coordinator/dedup 直接复用）。
- **状态查询**：`dpkg-query -W -f=${Status}` + version 字段（`AptCommandBuilder` 已有）。
- **大输出**：`apt-get update` 的索引列表输出截断进事件流，全文进日志。

### 9.3 DNS 方案（P0-D1）

1. `ConnectivityManager.LinkProperties.dnsServers`（API 21+）→ 生成 `nameserver x.x.x.x` 行；
2. 失败/为空 → 读取系统属性 `net.dns1/net.dns2`（兼容路径）；
3. 兜底 `nameserver 8.8.8.8 / 2001:4860:4860::8888`；
4. 写入 rootfs `/etc/resolv.conf`（provisioning 时一次 + 每次 Linux 会话创建前校验存在）。
5. 网络 RESTRICTED/_metered 状态 → `availability()` 报告 `NEEDS_NETWORK`，apt 工具前置检查。

### 9.4 中断恢复（dpkg 半装状态）

- `AptPackageManager.repair()` 序列：`dpkg --configure -a` → `apt-get install -f -y` → `apt-get update`。
- 检测：`/var/lib/dpkg/updates/` 非空或 lock 文件存在 + 无并发会话 → 进入 repair。
- 进程内并发：`PackageManager` 的 coordinator（已有）+ `RootfsInstallLock` 复用；跨启动：reconcile 阶段执行 repair 一次。

### 9.5 FakePackageManager

保留，**移入 test source set**，作为 `PackageManager` 契约测试替身（现有测试照跑）。

---

## 10. Session（Linux 会话生命周期）

### 10.1 创建链（P73/P74）

```text
terminal.create(backend="linux-ubuntu", workspaceId?, env?, rows, cols)
  → TerminalRuntime.create
  → LinuxPRootBackend.availability()  ── READY? ──否──→ TerminalError:RootfsNotReady(state, progress)（Agent 可读的引导性错误）
  → RootfsProvisioner.current()（三重证据校验）+ markInUse()
  → PRootCommandBuilder.build(...)（§5.2 argv）
  → SessionManager.create(SpawnSpec) → nativeCreateSessionArgv → forkpty → child: execv(proot … bash -i)
  → 泵/VT/reducer/observation engine 照常装配（0 改动）
  → SessionRecord{backendId, rootfsId, workspaceId, prootPid}
```

### 10.2 生命周期映射（用户要求的 Session→Process 链）

```text
TerminalSession.id (Long, Kotlin)
  ↕ 1:1（SessionManager 映射表，A4 修复后唯一权威）
nativeSessionId (Int, C++ PtyEngine)
  ↕ 1:1
forkpty child = proot 进程（PID = session pid；PGID = 同值；session leader）
  ↕ ptrace 跟踪树
/bin/bash（guest 内 PID = host PID，proot 翻译 /proc 视图）
  ↕ 内核 job control（guest tcsetpgrp）
guest 前台/后台进程（bash 作业）
```

- **会话存活判定**：现有 `nativeIsAlive`（proot 进程存活）+ 泵读 EOF → `SessionClosed/ProcessExited`。bash 死而 proot 活（异常场景）→ proot 很快退出（无 tracee）；观测上等同会话结束。
- **PID 诚实语义**：`session.pid` = proot host PID（真实 OS pid，修复 G1）；guest 内部 PID 不做翻译（Agent 需要 `ps` 时在 guest 内执行，天然正确）。

### 10.3 关闭链

`close(force)` → 现有 `JobCancellationController`（TERM→5s→KILL 到 proot pgrp）→ proot `--kill-on-exit` 清 guest 树 → `waitpid`（**P70 修正为带超时 + 释放引擎锁**）→ `markIdle()` → SessionRecord 状态落盘。

---

## 11. PTY（回答架构问题 Q2）

### 11.1 裁决："真正的 Ubuntu PTY 由谁创建"

**由我们自己的 native PTY 引擎（forkpty）创建，child 直接 exec proot**：

- PTY 是**宿主内核对象**；bash 获得的是真 PTY slave（`/dev/pts` 经 proot 翻译）→ winsize/termios/SIGWINCH/`tcgetpgrp`/Ctrl-C 全部是内核真实行为，**与是否在 proot 内无关**。
- 与"JniNativePty→Android host shell"的区别仅在 exec 目标（argv），PTY 机制本身完全复用——这正是方案 C 的立足点。
- 与"LinuxPtyProvider→ProcessBuilder"（P68）的区别：管道不是 PTY（G2）。P68 路线被降级为 ProotExecutor（§3.3）。
- **不需要**新写 native PTY 层；需要**一次小幅泛化**：

### 11.2 Native 层改造清单（P70/P71）

| 改动 | 文件 | 内容 |
|---|---|---|
| N1 新增 `nativeCreateSessionArgv(argv: Array<String>, envKeys/Vals, cwd, rows, cols)` | `NativePty.kt` + `jni_bridge.cpp` + `pty_session.cpp` | child 从 `execl(shell,"-i")` 改为 `execv(argv[0], argv)`；保留旧入口转发（本地路径回归兼容） |
| N2 修复 idle-read | `JniNativePty.kt:56-71` | 空读 + !hasData → 返回 **0**（无数据）而非 -1；-1 仅表示 fd 错误/EOF（EOF 由 `isAlive`+read==0 判定） |
| N3 新增 `nativeReadBytes(sessionId, buf, max): Int` | 同上 | 绕过 `NewStringUTF` 的 NUL 截断/UTF-8 破坏；泵改用之；旧 String 入口保留给 legacy |
| N4 修复双换行 | `TerminalInput.kt:32-33` 或 `pty_session.cpp:154-157` | `\n` 只在一处追加（裁决：删 C++ 侧 writeLine 的追加，保留 Kotlin LINE 语义；RAW/KEY 走 write 不走 writeLine） |
| N5 write 统一 nativeSessionId | `InputManagerImpl.kt:97` | `assembly(sessionId).nativeSessionId` |
| N6 termios 修正 | `pty_session.cpp:98-103` | 恢复 `ICRNL`（0x0D→0x0A 翻译，否则 ENTER 键不能结束行）；ECHO 保持 |
| N7 关闭非阻塞化 | `pty_session.cpp:248-278` | `waitpid(WNOHANG)` 循环 + 500ms 超时强杀；**移出**引擎全局锁 |
| N8 写 EAGAIN | `pty_session.cpp:139-152` | poll/非阻塞写，放弃持锁 busy-loop |

### 11.3 Android 侧无 root 前提

`forkpty` 在 untrusted_app 域完全可用（本地路径已在产线证明）；proot 的 ptrace 仅需 trace **自己的子进程**（Yama ptrace_scope=1 语义内），untrusted_app 允许——Termux/UserLAnd 在无 root 设备上长期运行即经验证（设备矩阵风险见 §18/§25）。

---

## 12. Job / Process（回答"哪套 canonical"）

### 12.1 裁决

- **Canonical = `job/`（JobManagerImpl + TerminalJob）**：它是 `TerminalRuntimeImpl.run/cancel/stop` 的实际后端（`TerminalRuntimeImpl.kt:110,144`），且其"job = 写入长生命周期 shell 的命令行"模型**天然就是 Linux 会话需要的**。
- `process/`：`ProcessController`（pgid 注册 + 信号路由）与 `JobCancellationController` 保留（活跃）；`TimeoutController` 的优雅超时语义在 P79 并入 JobManagerImpl（替换现在的裸 SIGKILL，`JobManagerImpl.kt:154-169`）。
- `process2/`：**P79 整包删除**。其 `BoundedJobRegistry`（500 上限）逻辑移植进 JobManagerImpl（修 P1-F 的无界泄漏）；`JobStateReducer` 的状态判定对照并入现有 `onEvent` 逻辑作为测试基准。
- 其余平行宇宙（`control/`、`api/TerminalApi.kt`、`observation2/`、`input2/`、`reliability/`、`RuntimeWorkspace.kt`、未注册的 legacy 工具、两份 SdkDownloader、`LegacyTerminalManager`）：P79 统一删除（先加"无引用"守护测试再删）。

### 12.2 Linux 会话下的作业语义

- `terminal.run(sessionId, "python test.py")` = 向持久 bash 写一行 → bash 作业控制自然成立（前台阻塞 shell、`&` 后台、`wait %1` 等）。
- **完成检测 v1**：沿用 `WaitingInput` 高置信 prompt 判定（`JobManagerImpl.kt:85-107`）——bash 默认 `root@…#` prompt 命中现有 regex（§6.3 的 .bashrc 保障彩色+标准形态）。
- **完成检测 v2（P74，opt-in 精确化）**：`PROMPT_COMMAND` 注入哨兵：create(LINUX) 时设置 `PS1` 尾部追加 `\033]1337;JOB:$?\007`（自定义 OSC 序列），`InputWaitingDetector` 识别后携带**真实 exit code**——替代启发式。默认开，探测失败自动降级。
- **前台/后台**：`background=true` 语义 = 自动追加 `&`（写入 shell）；后台 job 的 `wait(ProcessExited)` 在 v1 需 Agent 显式 `run("wait %N || echo done")`——诚实写入工具文档（已知限制：后台 job 无提示符回显时 prompt 探测不触发）。
- **timeout**：P79 后统一为 TERM→宽限→KILL 序列（对 proot pgrp 生效 + `--kill-on-exit` 兜底）。
- **exit code**：v1 = proot 透传（前台命令 = bash 退出码经 proot）；v2 = OSC 哨兵精确值。

---

## 13. Observation（复用现有引擎，修缺陷）

现有链路 `PTY → PtyOutputPump → RingBuffer → RealVirtualTerminal → SemanticStateReducer → ObservationEngine → observe/screenStateFlow` **整体保留、零重写**（`TerminalRuntimeImpl.kt:369-377` 的推式 Flow 也保留）。需要的修复/增强：

| 项 | 问题 | 方案 | PR |
|---|---|---|---|
| EVENT 模式空壳 | observe(EVENT) 只回 count（`TerminalObserveTool.kt:120`） | 返回事件摘要数组（type/ts/jobId/exitCode 截断序列化） | P74 |
| EventLog 无界 | P1-F | 接线 `BackpressureConfig.eventBufferLimit=10000`：环形丢弃最旧 OutputProduced；close 时 `drop()` 总清理（log+bus+input channel 取消） | P70 |
| 泵空转 | shell 退出后 50Hz 空转 | `isAlive==false` 且 read EOF → 泵自然退出 + SessionManager 停 exit-watcher | P70 |
| VT 全量渲染 | 每 8KB chunk ≥3 次全屏 render（`RealVirtualTerminal.kt:42-46`） | snapshot 缓存 + 脏标记；detector 读缓存 | P80 |
| 观察 token 成本 | SEMANTIC 含全部 backgroundJobs 历史 | BoundedJobRegistry + jobs 摘要化 | P79/P80 |

大输出（`cat` 100MB 文件）：ring 256KB 滚动 + `maxBytes` 截断已可防护；P80 增加 chunk 聚合（16ms 窗口合并 OutputProduced）降低事件风暴。

---

## 14. Persistence

- `SessionRecord`（`SessionMetadataStore`，JSON/会话，原子写）扩展字段：`backendId`、`rootfsId`、`workspaceId`、`prootPid`、`spawnArgvHash`（恢复时校验 rootfs 未变）。向后兼容：字段缺省 = LOCAL。
- 保存时机扩展（P78）：现有 ProcessExited/SessionClosed 触发点保留 + Linux 会话每 60s 心跳保存（cwd 通过 `run("pwd")` 低频采样可选，v1 不做）。
- `RootfsMetadataStore`（P69）沿用；workspace 注册表新增 `workspace-registry.json`（同目录模式）。
- 敏感信息：argv/env 可能含 token——存储层加 `security-crypto` EncryptedFile 可选项（app 已依赖，v2 决定，默认明文+文档说明）。

## 15. Recovery（Linux 会话恢复设计，覆盖 A–E）

| 情况 | 判定 | 动作 |
|---|---|---|
| A：proot 已死（App 重启后） | SessionRecord.prootPid 不存活（`isPidAlive`，现有 kill -0 探测） | 会话置 EXITED（只读快照可查）；**自动重建策略**：Agent 下次 `run` 该 session → 返回 `TerminalError:SessionExpired` + 提示 create；或配置 `autoRecreate=true` 时在 workspace 根目录静默重建 |
| B：proot 活着但 App 重启（PTY fd 已丢） | pid 活 && 本进程无该 nativeSessionId | **无法重挂 PTY fd（v1 硬限制，与本地路径一致）**→ 主动 `kill(-prootPid, SIGTERM)`→5s→SIGKILL（`--kill-on-exit` 清 guest 树）→ 会话置 BROKEN → 按策略重建。**必须杀**，否则孤儿 proot 永久泄漏 |
| C：guest shell 活着 | 包含于 B（bash 在 proot 树内） | 同 B |
| D：rootfs 安装中崩溃 | staging 非空 / .part 残留 | `reconcile()`：CLEAN_STAGING / CLEAN_TEMP（已有，`RootfsProvisionerImpl.kt:301-362`） |
| E：rootfs 已装但 metadata 未提交 | current 存在但 rootfs.json 缺/损坏 | REPAIR_METADATA（已有）+ 新增 INVALIDATE_VERSION（`.provisioned` 哨兵缺失 → 版本作废） |

- 恢复服务修复（P78）：`RuntimeRecoveryService` 把恢复结果**注入 snapshot()**（现在丢弃，P2-I）；B 情况的孤儿清理在 `recover()` 首步执行。
- Rootfs in-use 保护：会话创建 `markInUse()` / 全部关闭 `markIdle()`（接线 P69 已有钩子）。

## 16. Agent Tool

- `terminal.create` schema 增加：`backend?: "local"|"linux-ubuntu"`（默认 local）、`workspaceId?: string`；**修复 env/privilege 解析**（P2-I）；返回 `backend` 与 availability 摘要。
- 新增 `terminal.backends`（轻量只读工具）：列出 backend、可用性、rootfs 状态/进度、磁盘余量——Agent 自主决策"是否触发安装/等待"。
- 新增 `terminal.rootfs.install / cancel`（P72/P73 随 DI 一起注册；长任务走 PROGRESS 流式——现有工具卡片流支持）。
- `terminal.pkg.*`（P76）：install/remove/search/status，包装 AptPackageManager；事件流复用 ToolOutputChunk。
- `terminal.env.*`（P77）：resolve/provision/profiles，包装 P66/67 环境层。
- 输出截断统一走 `ToolOutputTruncator`（AgentConfig.maxToolOutputLength 默认 2000 → 建议对 terminal.observe 类工具单独放宽至 8000，head/tail 策略已有）。
- 权限门对称化（P79 决策项）：`terminal.*` 与 `shell_execute` 的确认策略统一由 `CommandPolicy` 表达（危险命令白/黑名单已存在于 TerminalPolicyImpl）——**不建议**给普通 `ls` 级命令加用户确认（会杀死 Agent 自主性）；仅 rootfs 安装/删除、包安装等重操作走确认。

## 17. DI（Hilt）

新增 `app/di/TerminalLinuxModule.kt`（P71-P73 逐步充实）：

```kotlin
@Provides @Singleton prootBinaryProvider(ctx): PRootBinaryProvider = NativeLibraryPRootBinaryProvider(ctx)
@Provides @Singleton rootfsLayout(ctx): RootfsInstallLayout = RootfsInstallLayout(filesDir/linux/ubuntu)
@Provides @Singleton rootfsProvisioner(source, layout, …): RootfsProvisioner = RootfsProvisionerImpl(…)
@Provides @Singleton prootExecutor(…): ProotExecutor
@Provides @Singleton aptPackageManager(…): LinuxPackageManager = AptPackageManager(…)
@Provides @Singleton localBackend(…): ExecutionBackend = LocalShellBackend()
@Provides @Singleton linuxBackend(…): ExecutionBackend = LinuxPRootBackend(provisioner, builder, workspaceManager)
@Provides @Singleton backendRegistry(local, linux): ExecutionBackendRegistry
// TerminalModule: TerminalRuntimeImpl(..., backends = registry)
```

- `platform/terminal` 模块保持无 Android Context 依赖的纯 JVM 可测性（接口在 terminal，实现需要 Context 的放 app 或以 lambda 注入 baseDir）。
- Fake 类整体迁移 test source set（P72）。

## 18. Android 特殊限制逐项分析

| 限制 | 对本项目的影响 | 对策 |
|---|---|---|
| App sandbox（untrusted_app） | 只能写自己的数据目录；无 root | 全部路径落 `filesDir/cacheDir`；proot `-0` fake root |
| 文件可执行性 | `nativeLibraryDir` 保证可执行；`filesDir` 在绝大多数 ROM 上可 exec（SELinux `app_data_file` 对属主允许 execute），**个别 OEM ROM 可能收紧** | proot/loader 放 jniLibs（保底可执行）；rootfs 内 guest 二进制 exec 由 proot 处理——若 ROM 禁止 data 目录 exec，proot 自身不受影响（在 libDir），guest 二进制 exec 在 data 目录（Termux 先例证明可用）；真机矩阵验证（§21） |
| `useLegacyPackaging` | AGP 默认不解压 .so | 显式开启（§5.1） |
| mmap | 正常可用 | 无 |
| symlink | App 数据目录（ext4/f2fs）支持 | ubuntu-base 的 symlink 在解压时由 commons-compress 正确创建 |
| /proc | host /proc 可 bind | proot `-b /proc` |
| /dev | 仅需 urandom/null/pts 等 | `-b /dev`（proot 翻译 device 访问；**不能** mknod，不需要） |
| ptrace | 允许 trace 自己的子进程（Yama scope 1 内） | proot 场景成立；个别内核配置风险见 §25 |
| seccomp（app 进程过滤器） | 某些 guest 程序使用的 syscall 可能被过滤（历史案例：`personality`） | 真机矩阵 + 已知绕过（dpkg 对 personality 的调用可被 env 关闭）；Termux 生态长期验证 apt/dpkg/python/node/gcc 在此模型下可用 |
| SELinux | app 域禁止的操作（如 mount） | proot 是**用户态翻译器，不 mount**——天然规避 |
| ABI | 见 §19 | |
| 64 位 only 趋势 | Android 15+ 大量设备 arm64-only | 与 v1 目标一致 |
| 后台限制 | 后台杀进程 → 孤儿 proot | §15-B 恢复清理 + 前台服务可选项（v2，本文档不展开） |
| 磁盘配额 | rootfs ~120MB + apt 包增长 | 预检（已有）+ 空间水位工具（P75） |
| 网络权限 | INTERNET 权限已有（LLM 调用） | DNS/CA 见 §9.3 |

## 19. ABI

| ABI | 裁决 | 理由 |
|---|---|---|
| arm64-v8a | **v1 必须支持（主目标）** | 真机主力；proot(arm64) + ubuntu-base(arm64) + ports 源 |
| x86_64 | **v1 支持（CI/模拟器）** | GH Actions 模拟器 & 开发者桌面验证；proot(x86_64) + ubuntu-base(amd64) + archive 源 |
| armeabi-v7a | **v1 不支持，编译期排除** | ubuntu-base armhf 源不完整、32 位 ptrace+proot 生态几乎无人维护、现代设备 arm64-only；`abiFilters` 中移除（当前 `platform/terminal/build.gradle.kts:19` 含 armeabi-v7a，但 `RootfsTarget.fromAndroidAbi` 对 ARM32 返回 UNSUPPORTED——现状即自相矛盾）；32 位需求留待用户反馈 |
| proot/rootfs ABI 匹配 | 运行时强制校验 | `Build.SUPPORTED_ABIS[0]` ↔ proot 二进制 arch ↔ rootfs arch 三方一致，否则 `ARCHITECTURE_MISMATCH`（错误码已有） |

## 20. 性能

| 指标 | 目标 | 手段 |
|---|---|---|
| proot 冷启动（create LINUX） | ≤ 2.5s（arm64 真机，warm cache） | 长生命周期会话（§5.3）摊销；bash `--noprofile?` 否——保留 profile 但精简 |
| `terminal.run` → 首字节 observe | ≤ 150ms | 现有泵模型（100ms waitForData 窗口）+ N3 字节读 |
| stdout 吞吐 | ≥ 2MB/s（guest `cat` 大文件） | N3（去 String 往返）+ 8KB 读块；P80 升 64KB |
| JNI copy | 每 chunk 1 次 memcpy | N3 `GetByteArrayRegion` 直拷 |
| 背压 | ring 256KB + 事件限界 | P70 接线 BackpressureConfig |
| 观察频率 | screen 推送 ≤ 30Hz | 脏标记 + 节流（P80） |
| 大输出 | 事件风暴免疫 | 16ms chunk 聚合（P80） |
| native 并发 | 关闭不阻塞全局 | N7；P80 细化锁粒度（per-session 锁） |
| rootfs 解压 | ≤ 90s（120MB 解压，arm64） | commons-compress 流式；进度回调已有 |
| apt install（python3） | 首次 ≤ 60s（网络相关） | --no-install-recommends；索引复用 |
| 基准基建 | 可复跑 | P80 `bench/` 模块：microbenchmark（JVM）+ macrobenchmark（androidTest） |

**"每次启动 PRoot 的成本"量化**（P71 产出基准数据填充）：fork+exec ~80ms；ptrace attach+首次翻译 ~200-400ms；bash -i + profile ~300-1500ms（rootfs I/O 敏感）。结论固化：**会话级启动、作业级复用**。

## 21. 测试（四级矩阵）

| 级别 | 环境 | 内容 | 现状 → 动作 |
|---|---|---|---|
| Unit（JVM） | host JVM | 契约/Fake（现有 174+ terminal 测试全保留）；backend argv/env golden 测试；command builder 表驱动 | 扩展：LocalShellBackend golden argv/env == 旧硬编码快照 |
| Integration（JVM+真实 proot） | GH runner（直跑 VM，ptrace 可用） | `ProotExecutor` 跑真实 proot + **CI 下载的 ubuntu-base**（workflow 步骤下载+校验+缓存）：exec /bin/true、echo、exit code、apt update（HTTP 源） | 新增 `ProotUbuntuIntegrationTest`（assumeTrue 门控 + rootfs fixture 注入）；修正现有 host-/ rootfs 测试的定位（保留为快速烟测） |
| Instrumented（androidTest，模拟器 x86_64 + amd64 rootfs；真机 arm64 手动/邀请） | `connectedDebugAndroidTest` | **真 forkpty + 真 proot + 真 rootfs + 真 PTY**：create→run→observe→write→resize(SIGWINCH: `stty size` 验证)→signal→wait→close 全链路；解压器对真实 ubuntu-base 的端到端；recovery A/B/D/E 场景注入 | 新增 `terminal-linux/` androidTest source set + `UbuntuE2eTest`（工具层驱动） |
| E2E（Agent 回环） | 模拟器 | 模拟 LLM 的 scripted tool-call 序列：create(linux)→backends 探测→rootfs.install→run("apt-get update && apt-get install -y python3")→run("python3 -c …")→observe→wait→断言 | `AgentTerminalE2eTest`（Fake LLM 适配器，现有 agent-engine 测试模式复用） |

测试夹具：仓库内置 **10KB 手工 busybox 风格 mini-rootfs tar.gz**（脚本生成，快速用）+ CI/设备侧下载真 ubuntu-base（完整用）。禁止在单测里联网。

## 22. CI

在现有 `ci.yml` 三 job 基础上：
1. `static-analysis`：加 **死代码守护**（`process2|control\.|api\.TerminalApi` 等包禁止新增引用的 grep 检查，为 P79 铺路）。
2. `app-compile`：移除对 Hilt/KSP 失败的 `continue-on-error` 容忍（债务在 P70 清偿后）；`:core:tool-registry:test` 恢复。
3. 新增 `linux-integration`（JVM）：下载 ubuntu-base（actions/cache 按月缓存）→ `:platform:terminal:test --tests "*ProotUbuntuIntegration*"`（VM 直跑，ptrace 可用——验证并移除现有测试里对 GH runner 的错误假设）。
4. 新增 `android-e2e`（x86_64 模拟器，API 34/35）：`connectedDebugAndroidTest` + ubuntu-base amd64 预下载进设备。GH hosted emulator 免费额度内可行（~15-20min）。
5. `build-apk` 保持。
6. 与 open PR #70（ci-hardening）的改动协调合并，避免 workflow 冲突。

## 23. E2E 验收标准（Definition of Done）

在 x86_64 模拟器（CI）与 arm64 真机（手动矩阵）上，以下脚本 100% 通过：

```text
1. terminal.backends                     → linux-ubuntu: NEEDS_ROOTFS
2. terminal.rootfs.install               → READY（真实下载+校验+解压+配置+激活）
3. terminal.create(backend=linux-ubuntu) → sessionId, pid=proot pid
4. terminal.run("cd /workspace && echo hi > f.txt && export TEST=123")
5. terminal.run("echo $TEST && cat f.txt")   → observe 捕获 "123\nhi"（证明同 shell/env/cwd 延续）
6. terminal.run("python3 -c 'print(1+1)'")   →（apt 安装 python3 后）observe 捕获 "2"
7. terminal.run("top") → terminal.write(KEY:q) → 退出               （交互程序 + PTY 语义）
8. terminal.resize(40,120) → terminal.run("stty size") → "40 120"   （真 SIGWINCH）
9. terminal.run("sleep 300") → terminal.signal(SIGINT) → wait        （信号到 guest 前台组）
10. 终止 App 进程（模拟崩溃）→ 重启 → recover() → 无孤儿 proot（B 场景清理）→ create 新会话即用
11. 全程 local backend 回归：现有 terminal 测试全绿，Android 本地 shell 会话行为不变
```

---

## 24. PR Roadmap（P70–P80）与下一 PR 定义

> 编号依据：内部 P 序列 git 历史已至 **P69**（`729b69a`），故从 **P70** 起；GitHub PR 序列已至 #74（#70 open）——本 roadmap 的每个 Pxx 对应一个 GitHub PR，实际 PR 号由 GitHub 顺序分配（预计 #75 起）。**每个 PR = 一个完整可验证能力**（独立合并、独立回滚、带自己的测试与文档段落）。

| PR | 标题 | 核心交付 | 验证 | 依赖 |
|---|---|---|---|---|
| **P70** | **Native PTY Core Hardening（本地核心加固）** | N1(argv 泛化)+N2(idle-read)+N3(readBytes)+N4(双换行)+N5(id 映射)+N6(ICRNL)+N7(非阻塞 close)+N8(EAGAIN)；EventLog/背压接线；泵生命周期修复；LocalShellBackend 骨架 + golden 快照测试；死代码守护 CI | 现有测试全绿 + 新增 JNI 契约测试 + 模拟器冒烟（本地 shell 手动/自动） | — |
| **P71** | **Production PRoot Execution（生产 PRoot）** | proot+loader 打包 jniLibs（arm64/x86_64）+ `useLegacyPackaging`；`NativeLibraryPRootBinaryProvider`；`ProotExecutor`（P68 降级重命名，修 G4 host/guest env 分离、G1 假 PID→真实 pid）；`LinuxPRootBackend` v1（forkpty 路径，PRootCommandBuilder 复用 + 修正）；`ExecutionBackend` 接口 + Registry | JVM 集成（host rootfs 快速烟测）+ androidTest（mini-rootfs 夹具：exec/exit/signal/resize 全链）+ 启动延迟基准数据 | P70 |
| **P72** | **Ubuntu RootFS Provisioning（生产化）** | SHA256SUMS 真实校验 + pin 表；Commons-Compress 解压器（修 USTAR prefix/GNU/PAX）；configure 阶段（resolv.conf/源改写/hosts/bashrc/machine-id）；`.provisioned` 哨兵 + reconcile 强化；P64 模拟栈删除；Fake 迁移 test；remove() 语义修正 | `RootfsProvisioningTest` 对**真实 ubuntu-base 下载**的 opt-in 集成模式 + CI 缓存；损坏注入测试（杀进程于各阶段） | P71（仅 DI 接线依赖；解压器可独立） |
| **P73** | **Linux ExecutionBackend Integration（Terminal↔Linux）** | SessionManager 接 SpawnSpec；`TerminalRuntime.create(backend=…)`；工具 schema + env/privilege 修复；`terminal.backends` 工具；DI `TerminalLinuxModule`；availability 错误模型（RootfsNotReady 引导）；SessionRecord 扩展 | 回归：默认 LOCAL 行为 byte-equal（golden 测试）；LINUX create 在模拟器可用 | P71+P72 |
| **P74** | **Ubuntu Long-lived PTY Session + Agent E2E** | PROMPT_COMMAND 哨兵精确 exit code（探测降级）；observe(EVENT) 修实；bash prompt/PS1 配置；E2E 测试 §23 的 1-9 项（模拟器）；工具文档 | `UbuntuE2eTest` + `AgentTerminalE2eTest`（CI 模拟器 job 上线） | P73 |
| **P75** | **Workspace Implementation** | `WorkspaceManager`（首个 Workspace 实现）+ 注册表 + bind 接线 + `/sdcard` opt-in + 空间水位 + 清理 | workspace 生命周期测试 + 多会话共享测试 | P73 |
| **P76** | **Apt Package Manager** | `AptPackageManager`（ProotExecutor 通道）+ repair 流程 + 事件流 + `terminal.pkg.*` 工具 + bootstrap（ca-certificates） | JVM 集成（CI ubuntu-base，apt update/install python3）+ 模拟器复跑 | P72+P74 |
| **P77** | **Environment Provisioning Integration** | P66/67 环境层接线（executor=Linux 会话）；profiles 安装；UI-005 依赖中心替换（删 winget/scoop + 2 份 SdkDownloader）；`terminal.env.*` 工具 | AdaptiveProvisionLoop 真实 executor 集成测试（缺包→诊断→安装→重试收敛） | P76 |
| **P78** | **Linux Recovery & Reliability** | §15 A–E 全场景；RuntimeRecoveryService 注入 snapshot + 孤儿 proot 清理；心跳持久化；rootfs in-use 接线；崩溃注入测试矩阵 | 恢复场景 androidTest（每场景一个 kill 点） | P74 |
| **P79** | **Job/Process Consolidation & Dead Code Purge** | process2/control/api/observation2/input2/reliability/RuntimeWorkspace/未注册工具删除；BoundedJobRegistry 移植；timeout 统一（TERM→KILL）；wait() afterCursor 修复；权限门对称化决策落地 | 死代码守护 CI 转绿；全量回归 | P74（功能稳定后） |
| **P80** | **Benchmark & Performance Optimization** | bench 模块 + §20 指标测量；N3 全面采用；锁粒度细化；观察节流/聚合；VT 缓存；性能基线文档进 docs/ | 基准报告（模拟器+真机）+ 目标达成表 | P79 |

**排序对照用户优先级**：①=P71 ②=P72 ③=P73 ④⑤=P74 ⑥=P75 ⑦=P76/P77 ⑧=P78 ⑨=P79 ⑩=P80；P70 是为满足"真机能跑"而新增的前置质量门（4 个 P0 bug 不修，P71+ 的所有真机验证都会失败——**这不是重构，是缺陷修复**，范围严格锁定在 §11.2 清单）。

### 下一 PR（P70）详细定义

**范围（严格）**：
1. §11.2 的 N1–N8（native 层 8 项修复/泛化，`NativePty.kt`/`jni_bridge.cpp`/`pty_session.cpp`/`pty_engine.cpp`）。
2. Kotlin 侧对应修复：`JniNativePty`（N2/N3 语义）、`InputManagerImpl` nativeSessionId（N5）、`TerminalInput` 双换行单点化（N4）、泵 EOF/退出治理、EventLog 界限接线（`BackpressureConfig`）、`InputManagerImpl.drop()` 资源清理。
3. `ExecutionBackend`/`SpawnSpec`/`LocalShellBackend` 类型 + 把 `pty_session.cpp:60-85` 的硬编码 env 迁移为 LocalShellBackend 生成（golden 快照测试证明 byte-equal）。
4. CI：死代码守护 grep；`:core:tool-registry:test` 恢复（若债务过大则单列 issue，不扩 P70 范围）。
5. 文档：本方案落库 + `docs/terminal-native-contract.md`（JNI 字节语义：read 返回值约定、write 换行约定、信号约定）。

**不做什么**：不接 PRoot、不下载 rootfs、不动 tools schema（除修复 env/privilege 解析 bug——归 P73 以保持 P70 纯粹，此处仅加 TODO 测试标记）、不删死代码（守护先行）。

**验收**：全部现有单测绿；新增"JNI 契约测试"（FakeNativePty 行为对齐新语义：idle→0、EOF→-1 仅限 fd 错误）；模拟器本地 shell 冒烟（echo/交互程序/resize/signal）；`dev` 文档记录基准：本地 run→首字节延迟。

---

## 25. 风险与开放问题

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | 个别 OEM ROM 禁止 data 目录 exec / 收紧 ptrace | 中 | proot 在 libDir（保底）；真机矩阵（Pixel/三星/小米/华为各 1）在 P71 验证；失败设备进"不支持清单"并给出诊断信息 |
| R2 | cdimage.ubuntu.com 限流/不可达 | 中 | 多镜像（ports/镜像站列表）+ 重试已有 + Archive.org 兜底；校验和 pin 保证安全性 |
| R3 | GPL（proot 为 GPLv2+）随 APK 分发 | 中 | 附源码 offer + 许可证文件；v2 可切换自建静态构建 |
| R4 | GH Actions 模拟器不稳定/超时 | 中 | E2E 拆小（每场景独立 test）；androidTest 允许重跑；本地脚本等效复跑 |
| R5 | proot 在某些内核上的 syscall 翻译缺陷（新内核 flag） | 低 | proot 版本锁定 + 升级通道（版本探测工具）；社区（Termux）持续维护同源二进制 |
| R6 | rootfs 磁盘占用增长（apt 缓存） | 低 | `apt-get clean` 常态化 + 水位清理工具（P75） |
| R7 | 观察引擎 token 成本（大输出会话） | 中 | §13/P80；工具层 maxBytes 默认收紧 + 智能截断已有 |
| O1 | 是否需要前台服务保活 Linux 会话（后台执行长任务） | 开放 | v1 不做（Agent 前台使用为主）；设计已预留（SessionRecord 持久化 + 恢复重建） |
| O2 | 多 rootfs（Debian/Alpine）扩展 | 开放 | 契约已支持（LinuxDistribution）；v1 只做 Ubuntu 24.04 |
| O3 | `terminal.run` 是否要支持 `ProotExecutor` 快速一次性执行模式（无会话开销） | 开放 | 倾向不暴露（语义分裂）；Agent 用会话即够 |

---

## 附：本方案与既有资产的关系一览

| 既有资产 | 处置 |
|---|---|
| TerminalRuntimeImpl / SessionManager / JobManager / InputManager / WaitEngine / EventBus / ObservationEngine / RingBuffer / RealVirtualTerminal | **保留为唯一核心**（仅 P70 缺陷修复 + P73 spawn 接缝） |
| JniNativePty + jni_bridge.cpp + pty_engine/session | **保留**，N1-N8 加固 |
| PRootCommandBuilder / RootfsDescriptor / mounts / envBuilder（PR #63） | **复用**（LinuxPRootBackend 内） |
| PRootProcessProvider / PRootPtyProvider（PR #68） | **降级改名** ProotExecutor（探针/apt/CI），修 G1/G4 |
| RootfsProvisionerImpl / Downloader / Extractor / MetadataStore / Layout（PR #69） | **保留为生产实现**，修校验和/解压器/哨兵/配置 |
| UbuntuDistributionProvider 等模拟栈（PR #64） | **删除**（P72） |
| PackageManager 契约 + AptCommandBuilder（PR #65） | **复用**，AptPackageManager 补实现 |
| Environment 层（PR #66/67） | **复用**，P77 接线 executor |
| FakeLinuxRuntime / FakePackageManager / FakeRootfsSource | **迁入 test source set** |
| process2 / control / api / observation2 / input2 / reliability / RuntimeWorkspace / 未注册工具 | **P79 删除**（CI 守护先行） |
| LegacyTerminalManager / legacy 4 工具别名 | 保留别名至稳定后（P79 评估下线） |
