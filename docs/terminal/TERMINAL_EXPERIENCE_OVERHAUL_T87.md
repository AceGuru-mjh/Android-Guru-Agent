# T87 — Terminal Experience Overhaul（终端体验大修）

> 用户反馈根因清单 → 修复 → 验证。本文是 PR 的技术说明，也是后续终端维护的地图。

## 0. 用户实测反馈（原文）与根因对照

| # | 用户反馈 | 根因（定位） | 修复 |
|---|---------|-------------|------|
| 1 | 「Ubuntu 会直接显示输入失败」 | proot 会话「创建成功但进程即死」：forkpty 成功 + execv 立即失败（ENOENT/ELIBBAD/EACCES…）。旧链路全盲 —— Kotlin 拿到正常 id，之后每次 write 都 EIO → UI 只见「输入失败：WriteFailed」，根因永远不可见 | C++ pre-exec 错误管道（CLOEXEC 协议）+ `nativeGetSpawnError` + `SessionManagerImpl.createFromSpec` 当场揭穿（`TerminalError:ExecFailed — execv(...) failed: <strerror>`）；死会话覆盖层 + 「重启会话」；写失败按语义分流（会话死 vs 策略拦截） |
| 2 | 「Shell 模式只有部分命令可以用」 | Android 本地 shell = toybox 工具集（先天有限）+ 无命令发现入口 + 无 HOME/历史 | mksh rc（`$ENV` 注入）：user@host:cwd 提示符 + 历史记录 + `cmds`（PATH 全量命令列表）+ `cmdf`（命令定位）+ apt 诚实引导（明说 Ubuntu 会话才有）+ 别名；命令历史抽屉（持久化） |
| 3 | 「输入的时候 …输入的文字中间有很大的空白」「运行之后想运行第二个命令，中间有一大段空白」 | 渲染层 follow 滚动目标错误：`scrollToItem(totalRows - 1)` 滚到屏幕最后一行 —— 提示符之后的整屏空行全部进入视口（打开即空屏；每条命令后又是整屏空白） | follow 目标 = **光标行贴视口底部**（Termux 语义：提示符紧贴键盘上沿）；「跳到最新」浮标同修 |
| 4 | 「Shell 模式也要用白色字体，不然黑色啥也看不清」 | `TerminalGrid.baseStyle` 不带 color —— 无 ANSI 着色的普通文本落不到任何 SpanStyle，`BasicText` 兜底色是**纯黑**，在深色终端底上黑字黑底 | baseStyle 显式携带 scheme 前景色（白）；T87 配色系统上线后前景由 scheme 决定 |
| 5 | 「终端文字颜色要看情况彩色，像 Termux 项目一样」 | 旧渲染只透传 ANSI 16 色的标准板色值，无主题系统 | **31 套 Termux 风格配色方案**（Termux/Dracula/Nord/Gruvbox/Solarized/Monokai/Tokyo Night/Catppuccin/Ubuntu/Matrix…）+ 渲染层 ANSI 重映射（`TerminalAnsiRemapper`）+ bold-as-bright + 选择器 UI（即时换肤，零引擎改动） |
| 6 | 「Ubuntu 还是会显示 apt 未引导」 | 引导失败后的降级 READY（bootstrapNote）此前只显示原因不给出口；真机 proot 启动失败被 #1 的盲区掩盖 | 死会话/ExecFailed 诚实上报（#1）+ guest bashrc 的 `apt-fix` 一键引导修复（DNS→dpkg→apt update）+ `command_not_found_handle`（找不到命令 → `apt install <pkg>` 提示） |
| 7 | 「我这个项目的终端真的能被 agent 调用吗」 | 无自证入口 | `terminal.diagnostics` 工具（新注册）：会话/后端面 + **smokeTest=true 真实执行探针**（`printf` 回显自证 exec 链路端到端可用）+ nextActions 结构化建议 |

## 1. 架构：渲染层配色重映射（零引擎改动）

VT 引擎（Kotlin `TerminalCore` / C++ `NativeVtCore`）在引擎内部把 SGR 索引色转成 ARGB（标准 16 色板，奇偶校验测试锁定双实现一致）。渲染 cell 只带最终 ARGB —— 换肤在渲染层做「标准板 → scheme」翻译：

```
SGR 31 (red) → 引擎 → 0xFF800000 → TerminalAnsiRemapper → scheme.ansi[1]（如 Dracula 0xFFFF5555）
SGR 38;2;r;g;b (truecolor) → 引擎 → 0xFF|rgb → 透传（不与 scheme 冲突）
Default fg → 0 → scheme.foreground
bold + 基础色 → bold-as-bright → scheme.ansi[i+8]（xterm 传统，ls 彩色输出依赖）
```

- 方案模型：`TerminalColorScheme`（31 套，`TerminalColorSchemeDefs`）—— 纯 Kotlin `Long`，JVM 直测。
- 注入：`LocalTerminalColorScheme` / `LocalTerminalBoldAsBright`（CompositionLocal，切换整树换色）。
- 持久化：`TerminalColorSchemeSettings`（SharedPreferences，未知 id 兜底默认）。

## 2. C++：exec 失败捕获（pre-exec error pipe）

`pty_session.cpp`：
```
pipe2(O_CLOEXEC) → forkpty →
  child:  execv 成功 = 内核自动关写端；失败 = 写 errno 后 _exit(127)
  parent: 阻塞 read —— EOF(=成功) 或 4 字节 errno(=失败，转 strerror)
```
- 父进程构造返回时结论已定（无 TOCTOU）；
- 子进程「关闭 0/1/2 之外所有 fd」的循环跳过报告管道写端；
- `PtyEngine::spawnError(id)` → JNI `nativeGetSpawnError` → `SessionManagerImpl.createFromSpec`
  在装配前探测：失败 → 关闭死会话 + `TerminalError:ExecFailed — <确切原因>`；
- JVM 契约：`NativePty.nativeGetSpawnError`（FakeNativePty 可注入模拟）。

## 3. 会话死亡的产品化（不再「输入失败」猜谜）

- `TerminalViewModel.sendInput` 写失败按错误语义分流：`WriteFailed/SessionNotFound/SessionClosed`
  → 「会话已退出（进程结束）」+ 重启指引；其余（策略拦截等）保留原文。
- `TerminalScreen.DeadSessionOverlay`：活跃会话 `isAlive=false` 时半透明覆盖终端 ——
  「重启会话」（同 backend 重建）/「关闭」（移除 tab）。
- `TerminalViewModel.restartActiveSession`：Agent 创建的会话按 runtimeType 映射回真实 backend。

## 4. Shell 模式补全（mksh profile）

`GuestShellProfile`（platform:terminal，纯 Kotlin 字符串生成，JVM 直测）：
- PS1 = `user@host:cwd $`（**无 ANSI 转义** —— mksh 提示符宽度计算不识别 `\[ \]`，带色会让行编辑光标错位；Termux 默认同样纯文本）；
- HISTFILE（HOME 由 app 注入可写 `$filesDir/linux/shell`）；
- `cmds` / `cmdf` / `help`：命令发现三件套；
- `apt()` 诚实引导：本地 shell 无 apt → 明说 + 指路 Ubuntu 会话；
- 注入路径：`terminalRuntime.create(env = GuestShellProfile.shellEnv(...))`（T73 语义：调用方 env 覆盖默认）。

## 5. Ubuntu 会话 bashrc（GuestUserHome 播种升级）

`UbuntuBashProfile`（受管块幂等注入）：
- 彩色 PS1（root 绿 + 路径亮蓝 + git 分支，`\[ \]` 宽度标记齐全 —— bash readline 安全）；
- `command_not_found_handle`：未知命令 → `apt install <pkg>` 可行动提示（python3/build-essential/git…）；
- `apt-fix`：DNS 展示 → `dpkg --configure -a` → `apt-get update` 一键三连；
- ls/grep/less/man 彩色（能力探测后 alias，旧 coreutils 不炸）。

## 6. 新工具：terminal.diagnostics（Agent 自证）

```
input:  { smokeTest?: boolean }
output: { ok, sessionCount, sessions: [{id, shell, state, pid, backend, verdict?}],
          backends: [...], smoke?: {command, chainOk, output}, nextActions: [...] }
```
- exec 探针与 `terminal.exec` 共用同一 `ExecEngine(ProotCommandSpawner)` 构造 —— 探到的就是 Agent 实际会走的链路；
- `nextActions` 给出可立即执行的建议（close 死会话 / ubuntu.install / …）。

## 7. 其它交付

- **命令历史**：`TerminalCommandHistory`（提交时刻记录，去重置顶，500 条持久化）+ `TerminalHistorySheet`（点击发送/复制/清空）。
- **扩展键行**：`ExtraKeysConfig`（宏语法 `label=cmd:…` / `text:` / `key:` / `ctrl:` / `paste`，行/键预算约束，解析容错）+ `ExtraKeysBar` + 设置抽屉增删编辑器 —— Termux extra-keys 等价物。
- **光标漂移修复**：CJK 宽字符后 `CursorOverlay` 用 VT 列号索引渲染列表（trail cell 已剔除）→ 每个宽字符错位 1 列；改为以「已消费 VT 列数」终止。
- **行数预算治理**：`TerminalScreen` 1143→1098（抽屉拆出）、`TerminalRenderer` 1035→972（KeyToolbar 拆出）；新增文件全部 < 1200 行。
- **测试**：scheme 重映射/注册表（21 项）、ExtraKeysConfig（11 项）、mksh/bash profile（15 项）、terminal.diagnostics（5 项）。

## 8. 验证

- `./scripts/check_file_size.sh` ✅（751 main + 225 test 全部在预算内）
- `./scripts/check_code_quality.sh` ✅（零反射派发 / 零 printStackTrace / 零空 catch）
- `scripts/kotlin_balance.py` ✅（全部新文件括号平衡）
- Gradle：`:platform:terminal:compileDebugKotlin` / `:platform:terminal:testDebugUnitTest` / `:app:compileDebugKotlin` / `:app:testDebugUnitTest` —— CI（GitHub Actions）全量执行
- 真机行为（proot 启动/exec 失败捕获、mksh ENV 注入、bashrc 播种）由 CI 的 instrumentation 测试链路覆盖编译，运行需设备

## 9. 维护注意

- 引擎标准 16 色板若变更（`TerminalColor.BASIC_16` / C++ 同板），**必须**同步 `TerminalAnsiRemapper.ENGINE_BASIC_16`；
- 新 scheme 进 `TerminalColorSchemeDefs.ALL` 即自动进入选择器（id 唯一性有测试锁定）；
- `terminal.diagnostics` 已加入 `TERMINAL_TOOL_RUN_POLICIES`（660s / maxRetries=0）——
  新终端工具同样要在该表登记（`TerminalToolPolicyTest` 的 mustCover 会抓漏配）。

## 10. 复修轮：「apt 未引导」注记永不消失（第 6 条反馈的深层根因）

第 6 条在 T87 修掉「不给出口」后仍复现（用户反馈原文：**Ubuntu 总是显示 apt 未引导**）。
二次根因审计定位四个洞，本 PR 全部闭合：

| # | 洞 | 机理 | 修复 |
|---|----|------|------|
| a | 降级重试不刷 DNS | `dnsRefreshFn` 只接在健康 READY 短路前 —— 最需要重试的降级路径（note ≠ null → 完整编排）拿的还是安装时刻/上次网络的 `resolv.conf` 快照；切网后每次重试全镜像解析必败 → FAILED 循环 → 注记永不消失 | `runEnsureSteps` 在 bootstrap 前统一刷一次宿主 DNS（毫秒级文件对比、失败静默，不改变降级语义）—— 回归测试 `UbuntuLifecycleCoordinatorTest.10e` |
| b | 重试顺序官方源先行 | 上次 FAILED@APT_UPDATE 已证明官方 + 全镜像都试过；大陆网络下官方源失败是确定性的，旧实现每次重试仍先白等官方源超时（单源可达 600s）才轮到实际能用的镜像 | 上次失败于本阶段 → 镜像先行（TUNA→USTC→Aliyun）、官方殿后；首次引导仍官方先行（HTTP-first 契约 + 海外用户语义不变）—— 回归测试 `UbuntuBootstrapManagerTest.retry after APT_UPDATE failure tries mirrors before official` |
| c | 断网恢复无人重试 | 注记只有两个手动清除出口（环境中心按钮 / 重启 App），`NetworkMonitor` 已在手边却没人听「网络回来了」 | ApexApp 监听离线→在线跃迁（drop(1) 跳过订阅初值 + StateFlow 去重），「引导未完成」态自动补一次 ensureReady（幂等单飞；FAILED 失败现场仍不自动重试，与启动策略一致） |
| d | 启动自动预备漏态 | 条件只含 NOT_INSTALLED/ROOTFS_READY —— 崩溃残留的 BOOTSTRAPPING（bootstrap.json IN_PROGRESS）无人续跑，phase 永停「引导中」spinner（用户视角同样是「一直没引导」） | 启动条件补 BOOTSTRAPPING（续跑未完成阶段）+ 防御性覆盖降级 READY |

四洞合起来解释了「总是显示」：**失败后没有任何一条路径能在网络恢复时把 bootstrap 重跑成功** ——
a、b 让重试跑不成/跑得慢，c、d 让该重试的时机根本没人发起重试。

- 验证：`UbuntuLifecycleCoordinatorTest`（10e）+ `UbuntuBootstrapManagerTest`（mirror-first 回归）
  + 三门禁（file size / code quality / kotlin balance）+ CI 编译与全量单测；
- 真机验收点：切网后打开环境中心 → 注记应在网络恢复后自动消失（无需点「完成初始化」）；
  降级态点重试 → 日志应见「镜像优先重试」而非先白等官方源。
