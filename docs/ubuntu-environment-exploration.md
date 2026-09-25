# Ubuntu 环境全面探索验证报告 —— 终端可用性 / Agent 可调用性 / 开发环境供给链

> 任务性质：应用户要求对 Ubuntu（PRoot 沙箱）做端到端探索验证 ——
> ① 终端中是否能正常使用；② 是否能正常被 Agent 调用；③ 开发相关环境
> 能否正常下载使用 / 是否内置；④ SDK、JDK 及开发相关链路是否可用。
>
> 诚实口径（沿用 T81/T82 报告惯例）：**代码级验证的写代码级验证，本地
> 可跑的测试本地跑，无法验证的明确标注 NOT VERIFIED，绝不写假 PASS。**
> 本报告同时是本次两处 P0 修复（§6 / §7）的依据与回归锚点。

---

## TL;DR —— 四个问题的直接回答

| 探索问题 | 结论 | 依据 |
|---|---|---|
| ① Ubuntu 终端能否正常使用 | ✅ **架构完整可用**（PTY 交互 + VT100 + 真实退出码 + workspace 隔离 + 持久 home） | §2 + 既有 1000+ JVM 测试矩阵 |
| ② 能否正常被 Agent 调用 | 🔴→✅ **会话类工具可用；环境入口类工具修复前 100% 被 60s 截杀**（`terminal.ubuntu.ensure` / `capabilities ensure` / `workspace.environment ensure` / `repair` / `network` / `status`），**本 PR 已修** | §6 + 新增 `TerminalToolPolicyTest` |
| ③ 开发环境：内置了什么 | ✅ **58 包开箱即用**（gcc/g++/make/cmake/gdb、python3 全量+pip+venv+dev、nodejs+npm、git、curl/wget/openssl、sqlite3/jq/ripgrep、vim/tmux/man），**离线可用** | §3 + `scripts/rootfs-packages.txt` |
| ④ SDK / JDK 能否正常下载 | 🟡 **JDK：apt 可装但装完 JAVA_HOME 缺失（本 PR 已修）；Android SDK：`DepCatalog` 链路是死代码（sdkmanager 无安装来源），诚实判定不可用** | §7 / §8 |

---

## 1. 探索范围与方法

- **范围**：`platform/terminal` 全模块（ubuntu/ proot/ pkg/ environment/ workspace/ tools/ health/ network/ runtime/）+ `app/di/ToolModule`（Agent 工具注册与执行器装配）+ `app/environment/DepCatalog`（依赖安装目录）+ 相关测试矩阵与文档。
- **方法**：
  1. **全链路追踪**：从 LLM 发起 tool call → `EnhancedToolExecutor`（gate/限流/熔断/**超时策略**）→ `TerminalToolAdapter` → platform:terminal 工具 → `ProotExecutor`/PTY → proot 进程 → guest Ubuntu。逐层核对预算与语义。
  2. **预算对账**（本次新引入的视角）：每个环境工具的「执行器预算 vs 工具自身内部预算」逐项对表 —— 这是发现 §6 P0 的方法。
  3. **既有测试矩阵复核**：rootfs 供给（50 用例）、bootstrap、sources.list、extractor（14 用例）、工具契约（T82）、环境能力（locale/tz/mirror/proxy）等。
  4. **真机链路**：`UbuntuLinuxEnvironmentInstrumentationTest` 仍为 `COMPILED_NOT_EXECUTED`（12 个真机用例注释状态）—— 真机全链路维持 T82 报告的 NOT VERIFIED 判定，本报告不冒认。

## 2. ① 终端可用性 —— 架构层结论

```
terminal.create(backend="linux-ubuntu")
  → LinuxPRootBackend.prepare()
      proot -r <rootfs> -0 --kill-on-exit
            -b <filesDir>/linux/home:/root          ← 持久用户 home（跨 rootfs 版本存活）
            -b <workspace>:/workspace               ← 项目工作区
            -b /proc -b /dev -b /sys                ← 系统文件系统（ps/lsof 可用）
            [-b /storage/emulated/0:/sdcard]        ← 共享存储（授权后）
            -w /workspace -E TERM=xterm-256color -E HOME=/root -E PATH=... 
            -- /bin/bash -i
  → forkpty（native pty_engine.cpp）→ VT100/ANSI 全量解析 → 渲染/事件流
```

- **交互语义完整**：真实 PTY + VT 解析（DECCKM/括号粘贴/OSC 52/scrollback 可读）、
  OSC 633 shell marker 拿到**每命令真实退出码**、前台组精确信号（Ctrl-C 只杀
  前台作业不杀 shell）。`terminal.run/write/observe/wait/signal/resize/snapshot/close`
  九件套 + 4 个 legacy 别名覆盖完整会话生命周期。
- **双执行路径共享同一 rootfs**：交互 PTY 会话（`LinuxPRootBackend`）与
  非交互批处理（`ProotExecutor`，apt/探测用）—— 同一 rootfs / workspace /
  home / dpkg 数据库，apt 装的包对终端会话立即可见（反之亦然）。
- **网络**：proot 不虚拟网络，guest 直接用宿主 Android 网络；DNS 经
  `RootfsConfigurator` 注入（ConnectivityManager → 宿主 resolv.conf →
  公共 DNS 兜底 223.5.5.5/119.29.29.29/8.8.8.8）；代理（http_proxy 等）
  从 JVM 代理配置注入。T72 报告记录 CI 上真实 `apt-get update` 10/10 通过。
- **结论**：终端本身可用性在架构与测试层面均成立（真机渲染链路维持既有
  NOT VERIFIED 口径，无新证据推翻）。

## 3. ③-内置 开发环境清单（离线开箱即用）

rootfs 内置于 APK（`jniLibs/<abi>/libubuntu-rootfs.so`，压缩 ~300MB，解压
~1GB+），`scripts/rootfs-packages.txt` 固化 58 包（构建期安装）：

| 类别 | 内置包 | 开发场景 |
|---|---|---|
| C/C++ 工具链 | `build-essential`（gcc/g++/make）、`pkg-config`、`cmake`、`gdb`、`strace` | 编译/调试 C/C++/原生项目，**离线** |
| Python | `python3` + `pip` + `venv` + `dev` + `full` | `pip install numpy` 类含 C 扩展包可直接装（有 Python.h + gcc） |
| JS | `nodejs` + `npm` | 运行 JS 工具链 / npm 装包 |
| 版本控制 | `git` | clone/commit 全流程 |
| 网络/TLS | `curl` `wget` `openssl` `openssh-client` `socat` `netcat-openbsd` `rsync` | 下载/远程/隧道 |
| 数据/检索 | `sqlite3` `jq` `ripgrep` | 数据库 / JSON / 代码检索 |
| 编辑/复用 | `vim` `nano` `tmux` `man-db` + manpages(-dev) | 真实 CLI 工作体验 |
| 归档 | `tar gzip bzip2 xz-utils zip unzip zstd` | 解包任意发行物 |
| locale/tz | `locales`（en_US + zh_CN 预生成）`tzdata` | 中文环境 + 时区 |

**诚实取舍（构建清单注释原文转述）**：golang（~250MB）、rustc、default-jdk、
ffmpeg 等重型运行时**不内置**，走 apt 在线扩展 —— 交付体积是产品红线。

## 4. ③-可下载 开发环境供给链（apt 扩展）

- **apt 通道**：`terminal.linux.packages`（结构化 API：update/install/remove/
  upgrade/search/info/isInstalled/installed/status/autoremove/clean/mirror）。
  结构化 argv（绝无 shell 拼接注入）、跨实例 OS 文件锁、磁盘预检（DISK_FULL
  先拒）、有界输出（head 512KB + tail 512KB）、600s apt 超时 + TIMED_OUT 与
  FAILED 状态区分、错误码（APT_LOCKED→可重试 / NETWORK_DNS_FAILED→修环境 /
  PACKAGE_NOT_FOUND→换请求）。
- **镜像**：官方（arm: ports.ubuntu.com；x86_64: archive.ubuntu.com）+
  TUNA/USTC/Aliyun 一键切换（大陆网络友好）。
- **一发式工具链供给**：`terminal.linux.capabilities {ensure:[...]}` ——
  probe → 缺什么取 aptPackage → 批量安装 → invalidate → 复测汇报前后状态。
  覆盖 java/javac（default-jdk）、golang-go、rustc/cargo、clang、python3-venv 等。
- **项目感知闭环**：`terminal.workspace.environment {action:"ensure"}` ——
  扫描 workspace 标记文件（requirements.txt / package.json / build.gradle.kts /
  Cargo.toml / go.mod / CMakeLists.txt…）→ 检出语言 → 缺失工具链批量补装 →
  复测。**Gradle 项目会触发 default-jdk 安装** —— 与 §7 的 JAVA_HOME 修复
  闭环。
- **T84 离线短路**：58 包预装真值 + `dpkg-query` 单次只读校验 → 全齐时跳过
  联网 apt 阶段，bootstrap 离线完成（首次开终端不强制联网）。
- **结论**：apt 扩展链路架构完整、JVM 契约测试全绿；真机真实 apt install
  维持 NOT VERIFIED（无新真机证据），CI 侧 T72 E2E（真实下载 + proot +
  apt-get update）10/10 为最强旁证。

## 5. ② Agent 调用链路 —— 会话类工具（可用 ✅）

Agent → `TerminalToolAdapter`（JSON 字符串进出，模块边界防腐）→ 24 个 v2
工具。会话生命周期（create/run/observe/wait/write/signal/resize/snapshot/
close/backends/workspaces/fs/bridge）为快操作或非阻塞操作：

- `terminal.run` **非阻塞**返回 job 句柄（超时压力不存在）；
- `terminal.wait` 阻塞但自身管理 `timeoutMs`（见 §6 修复项）；
- 文件工具（read_file/write_file/edit_file…）沙箱根 = default workspace =
  guest `/workspace` —— **Agent 写的文件与 Ubuntu 会话同一份**（无双轨盲区）。

## 6. ② Agent 调用链路 —— 环境入口类工具（🔴 P0 超时错配，本 PR 修复）

**发现方法**：预算对账 —— `DefaultToolRunPolicyResolver` 对未显式覆盖的
mutating 工具推断 `mutating()` = **60s 执行预算 + 0 重试**；而环境工具自身
管理分钟级~半小时级预算。执行器 `withTimeout` 先于工具自身超时逻辑杀掉调用
（与既有 `terminal.exec` P0 修复注释描述的同类 bug，但波及面是整个环境链路）。

**修复前实况**（Agent 视角的确定性故障）：

| 工具 | 工具自身预算 | 执行器预算（修复前） | Agent 调用结果 |
|---|---|---|---|
| `terminal.ubuntu.ensure` | 30min（`DEFAULT_ENSURE_TIMEOUT_MS=1800s`） | 60s | 🔴 100% "timeout: tool call exceeded 60000ms budget"（解压需 2~5min） |
| `terminal.workspace.environment` ensure | 15min（900s 内部） | 60s | 🔴 同上（ensureReady + 批量 apt） |
| `terminal.linux.capabilities` ensure | 600s apt + 探测 | 60s | 🔴 装任何工具链必死；probeAll 冷缓存 ~15 次 proot exec 也可能超 |
| `terminal.linux.repair` | rootfs 重解包（分钟级） | 60s | 🔴 修复轮必死 |
| `terminal.linux.network` diagnose | 端到端 = 真实 apt-get update（600s apt 超时） | 60s | 🔴 慢网络必死 |
| `terminal.linux.status`（全量） | 6 维 × proot exec（10~30s/维） | **30s**（annotations 推断 readOnly → quickRead，比 mutating 更短） | 🔴 全量检查必死 |
| `terminal.wait` | schema 无上限（模型可请求 > 60s） | 60s | 🟡 默认值恰好撞线 |

已覆盖的（对照，说明这是**增量遗漏**而非设计缺失）：`terminal.ubuntu.install`
（20min）、`terminal.linux.bootstrap`（20min）、`terminal.linux.packages`
（10min）、`terminal.exec`（610s）。

**修复**（`app/di/ToolModule.kt`）：策略表提取为公开常量
`TERMINAL_TOOL_RUN_POLICIES` 并补齐 7 项（预算 = 内部预算 + 余量，均不盲重试
—— 幂等由工具状态机保证，IN_PROGRESS 可续跑）：

```
terminal.ubuntu.ensure        1_830_000ms（30min + 30s）
terminal.workspace.environment  960_000ms（15min + 1min）
terminal.linux.capabilities     660_000ms（600s apt + 1min）
terminal.linux.repair         1_260_000ms（21min）
terminal.linux.network          660_000ms
terminal.linux.status           300_000ms
terminal.wait                   660_000ms
```

**回归锚点**：新增 `app/src/test/.../TerminalToolPolicyTest.kt` ——
① 必须覆盖清单（新增环境工具忘配策略会被抓住）；② 执行器预算 ≥ 内部预算
对账表（与 platform:terminal 源码常量对齐）；③ 环境工具零盲重试；
④ 守护测试（mutating 默认确实 60s，覆盖表的存在意义）。

## 7. ④-JDK 链路 —— apt 可装，但 JAVA_HOME 从不导出（🟡 本 PR 修复）

**探索发现**（三处证据交叉）：
1. `terminal.linux.capabilities ensure ["java"]` → apt 装 `default-jdk` →
   `java/javac` 经 PATH 可用（probe 复测 AVAILABLE）—— 安装侧 ✅；
2. `LinuxEnvironmentManager.interactiveGuestEnv` 恰好 11 键
   （TERM/LANG/LC_ALL/HOME/USER/LOGNAME/SHELL/PATH/TMPDIR/PWD/OLDPWD）——
   **无 JAVA_HOME**；批处理 apt env 也没有；
3. TERMUX_CAPABILITY_MATRIX 7.6 行规划「ensure sets toolchain env profile
   into persistent .bashrc (JAVA_HOME/GOROOT/CARGO_HOME)」—— **从未实现**
   （探索报告确认为缺口）。

**后果**：`java -version` 能跑，但 `gradle`（读 JAVA_HOME 的启动脚本）、
`sdkmanager`（Android cmdline-tools，硬依赖）、一切 `JAVA_HOME` 依赖的构建
脚本**全部失败** —— JDK 「装了但不可用」。

**修复**（`platform/terminal/.../workspace/GuestUserHome.kt`）：向持久 guest
home（`<filesDir>/linux/home` → bind `/root`，跨 rootfs 版本存活）的 `.bashrc`
**幂等注入受管代码块**（标记对包裹，`# >>> apex-toolchain-env >>>` …
`# <<< apex-toolchain-env <<<`）：

```bash
if [ -z "$JAVA_HOME" ] && [ -d /usr/lib/jvm/default-java ]; then
    export JAVA_HOME="/usr/lib/jvm/default-java"        # apt default-jdk 标准路径
fi
if [ -z "$GOROOT" ] && [ -x /usr/local/go/bin/go ]; then
    export GOROOT="/usr/local/go"                        # 官方 tarball 安装位
    case ":$PATH:" in … esac                             # PATH 去重追加
fi
if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/android-sdk/cmdline-tools" ]; then
    export ANDROID_HOME="$HOME/android-sdk"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"              # §8 手工装 SDK 后自动接通
    case ":$PATH:" in … esac                             # cmdline-tools + platform-tools 入 PATH
fi
```

**设计要点**：
- **动态 `-d` 探测**：包什么时候装上，下一个 shell 启动就导出 —— 无需
  host 侧安装回调回写；未装静默跳过，永不产生悬空路径；
- **`-z` 守卫**：用户/Agent 显式 export 过的变量永不被覆盖；
- **幂等**：标记对锚点检测，重复 ensureReady 不重复追加（字节级不变）；
- **存量用户覆盖**：`ensureReady()` 每次会话创建都会补齐块（旧 home 的
  .bashrc 无块 → 追加一次；用户内容严格在前，绝不重写）；
- **生效时机**：bash -i（PTY 会话）启动即 source → **新会话即时生效**；
  已存活旧会话 `source ~/.bashrc` 立即生效（受管块在 -z 守卫下可安全重入）。
- **验证**：`GuestUserHomeTest` 扩展 5 用例（注入内容 / 幂等 / 存量 .bashrc
  追加不丢用户内容 / 存量无 .bashrc home 补齐 / bash 结构 if-f_i case-esac
  配对自检）。

## 8. ④-Android SDK 链路 —— 诚实判定：死代码（⚠️ 记录，不在本 PR 修复）

**探索发现**：`app/environment/DepCatalog`（旧终端「依赖安装中心」的清单）
ANDROID 组四项（cmdline-tools / NDK / platform-tools / build-tools）的
install 命令全部是 `sdkmanager "…"` —— 但：

1. **sdkmanager 没有任何安装来源**：Ubuntu 仓库无此包；rootfs 不内置；
   没有任何代码下载 Android cmdline-tools 发行物（对照：CI 的 ubuntu-latest
   自带 Android SDK 才使 `sdkmanager` 可用 —— 设备上不成立）；
2. **无 ANDROID_HOME / ANDROID_SDK_ROOT**（§7 修复后，`~/android-sdk` 手工
   安装位会被自动接通 —— 但「手工装」本身无产品化入口）；
3. `sdkmanager` 硬依赖 JAVA_HOME（§7 已修），修复前即便手工装了也跑不起来。

**结论**：设备上 `DepCatalog` ANDROID 组 100% `sdkmanager: command not found`
—— 链路是死代码。修复方向（后续 PR 候选）：`download_file` 拉取 Google
cmdline-tools zip → 解压到 `~/android-sdk`（`terminal.fs` guest API 可用）→
§7 的受管块自动导出 ANDROID_HOME → `sdkmanager --licenses`（需交互/自动
接受）→ 按需装 platform-tools/build-tools。本次探索将其记录为**已知缺口**
而非冒然实现 —— 涉及 license 交互与镜像策略，需独立设计评审。

## 9. 探索中发现但不阻塞的问题（记录备忘）

| # | 问题 | 判定 | 备注 |
|---|---|---|---|
| 1 | 无「一发式 Ubuntu 内执行」工具（`terminal.exec`/`shell_execute` 跑在 Android 宿主，Ubuntu 内执行需 create→run→wait→observe 3~4 调） | 📋 设计取舍 | `ProotExecutor` 批处理路径已存在（apt/探测共用），工具化是后续增强候选 |
| 2 | apt 600s 内部超时 == packages 工具 600s 执行器预算（无余量） | 🟡 临界 | 慢网络长安装可被竞态截杀；后续对齐为 660s |
| 3 | `TerminalExecTool` env 参数仅 local-sh 通道生效（su 通道 env_applied=false） | 📋 已知 | 工具 JSON 有诚实标记 |
| 4 | 全量 rootfs 真机链路（安装→引导→会话→apt 可见）12 用例注释状态 | NOT VERIFIED | 维持 T82 口径；本 PR 修复均 JVM 可验证 |
| 5 | 新克隆仓库不含 rootfs 伪 .so（`scripts/fetch_rootfs.sh` 构建期拉取） | 📋 构建期行为 | 运行时失败诚实（resolve 时报「Bundled rootfs archive missing」） |

## 10. Agent 使用指引（修复后的推荐编排）

```
1. terminal.ubuntu.ensure                     ← 一发式：安装→引导→能力快照（≤30min，可续跑）
2. terminal.create {backend:"linux-ubuntu"}   ← 开交互会话（新会话自动 source 工具链块）
3. terminal.linux.capabilities {ensure:["java","python3"]}   ← 按项目补装工具链
4. terminal.run / terminal.wait / terminal.observe           ← 在 Ubuntu 里构建/运行
5. terminal.workspace.environment {action:"ensure"}          ← 项目级一键环境
```

修复后：步骤 1/3/5 不会再被 60s 执行器预算截杀；步骤 3 装完 JDK 后，
步骤 2 的新会话（或 `source ~/.bashrc`）即有 JAVA_HOME，gradle 直接可用。

## 11. 本次交付物汇总

| 类型 | 文件 | 内容 |
|---|---|---|
| 修复 | `app/src/main/kotlin/com/apex/agent/di/ToolModule.kt` | 策略表提取为 `TERMINAL_TOOL_RUN_POLICIES` + 补 7 项环境工具预算（§6） |
| 修复 | `platform/terminal/src/main/kotlin/.../workspace/GuestUserHome.kt` | `.bashrc` 受管工具链环境块（JAVA_HOME/GOROOT/ANDROID_HOME，§7） |
| 测试 | `app/src/test/kotlin/com/apex/agent/di/TerminalToolPolicyTest.kt` | 4 用例：覆盖清单 / 预算对账 / 零盲重试 / resolver 行为守护 |
| 测试 | `platform/terminal/src/test/.../workspace/GuestUserHomeTest.kt` | +5 用例：块注入 / 幂等 / 存量追加 / 无 bashrc 补齐 / 结构自检（并修正 2 个因新行为需更新的既有断言） |
| 文档 | 本文件 | 探索验证全量记录（含 NOT VERIFIED 诚实区） |
