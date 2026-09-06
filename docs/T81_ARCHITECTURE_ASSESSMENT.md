# T81 Architecture Assessment — Terminal + Ubuntu Execution Infrastructure 2.0

> Phase 1 (read-only audit) 产出。基准：`main @ 026c5be`（PR #92 合并后）。
> 审计方式：全量源码精读（native 6 文件 + Kotlin 137 文件 / 20,658 行主源码）+ 三路并行子系统审计。
> 本文以真实代码为准；所有行号/函数名均可在基准 commit 中复现。

---

## 1. 组件 Mapping（真实状态，非记忆判断）

| 组件 | 状态 | 真实能力 | 已发现缺陷 | T81 动作 |
|---|---|---|---|---|
| Native PTY (`pty_session.cpp`) | 已实现·P70/P71 加固 | 真 forkpty、进程组信号、EAGAIN 退避、EOF/EIO 语义、二进制安全 read | close() 内 waitpid 不更新 exitCode_；exitCode_ 非 atomic；全局 engine 锁串行化所有 session IO | **强化** |
| JNI bridge (`jni_bridge.cpp`) | 已实现 | argv 通用 spawn、字节通道、状态透传 | 边界 OK；真机覆盖不足（androidTest compile-only） | **强化** |
| PtyEngine (`pty_engine.cpp`) | 已实现 | 单例 map + shared_ptr 生命周期 | **read/write/signal 持全局 mutex_ —— 多 session IO 完全串行化（100 session 并发不可达）** | **重构** |
| Session | 已实现 | 315 行 manager，assembly 装配 | close 不持锁可双发 SessionClosed；BROKEN cause 死代码；`SessionStateMachine` 仅测试使用（生产零校验）；assembly.session 永久陈旧 | **强化** |
| Job | 已实现 | 218 行，事件驱动状态推进 | **timeout watcher SIGKILL 杀全组（连 shell，一次超时报废 session）**；合成退出 exitCode 恒编 0；`JobStateMachine` 生产零调用 | **强化** |
| Input | 已实现 | Channel 串行写、控制态仲裁 | **策略门禁死代码（sendLine 降级 RAW，policy 永不触发）**；未映射键误报成功 | **修复** |
| Output pump | 已实现 | 单 reader、二进制安全 | start check-then-act 竞态（可双 reader）；ReadFailed 后 session 停留 READY 无 reader（半开僵尸） | **强化** |
| Buffer | 已实现 | 256KB 环形、cursor 语义正确 | latest() 窗口起点可截断 UTF-8 序列（调用方 toString 产生 U+FFFD） | **修复** |
| Wait | 已实现 | 事件驱动 + OutputMatch 真匹配（4KB 窗口） | register 的 `return@collect` 不终止 collect；await 固定 afterCursor=0 全量回放；PromptDetected/ScreenChanged 假阳性；IdleFor 硬编码 false；无 case-insensitive | **强化** |
| Observation | 已实现（v1 生效；v2 平行未接线） | SEMANTIC/EVENT/SCREEN/RAW 四模式 | ObservationEngine2Impl 生产 0 实例化 | **收敛** |
| Process/control | 部分死代码 | ProcessController 生效；TimeoutController 三级序列设计正确但 `startTimeout` 生产 0 调用 | **cancelAll 全局无 session 过滤（close A 误杀 B 定时器）**；cancel 谎报 SIGINT/130 | **接线** |
| Persistence | 已实现 | JSON per-session、schema v3 兼容 v1/v2 | **伪原子写（copyTo 非 rename、无 fsync）**；recover() 空壳（状态算了就丢）；startAutoSave 无异常捕获；保存丢 backend 字段 | **修复** |
| Recovery | 空壳 | reconcile 分类正确 | 恢复的 session 不注册进 runtime（不可见）；isPidAlive fork `kill -0`（Android 不可用） | **实现** |
| Shutdown | **缺失** | — | TerminalRuntime 无 shutdown()；pump/协程/native session 无统一收敛 | **新增** |
| PRoot exec (`ProotExecutor`) | 已实现 | 有界输出（1MB 首尾 ring）、超时强杀、G4 host/guest env 分离 | execute() 无界路径（1GB StringBuilder 风险）；无 cancel；reader 线程吞异常 | **强化** |
| PRoot backend | 已实现 | forkpty→libproot→bash -i 全链路；availability 三态 | buildGuestEnv 与 LinuxEnvironmentManager 键集漂移（PWD/OLDPWD/LC_ALL） | **对齐** |
| RootFS | 已实现·T72 | 下载续传、SHA-256、tar 防逃逸、原子激活、reconcile 5 分派 | cancel() 假取消；markInUse 生产零调用（remove 无活跃保护）；extractErrorCode 子串匹配且丢 recoverable；metadata load 吞损坏 | **强化** |
| Bootstrap | 已实现·T76 | 8 态状态机、幂等、崩溃续跑 | L171-172 `!!` NPE 路径；timeoutMs 死参数；无磁盘 preflight | **修复** |
| APT | 已实现·T76 | 10 操作、25 错误码、双层锁、与 terminal 共享 rootfs/home | **runAptRead 吞一切异常（isInstalled 静默假 false）**；OsLockHeld 错标 UNKNOWN；写操作无磁盘检查 | **修复** |
| Workspace | 已实现·T75 | 懒创建、bind 计数、原子元数据 | 全局 synchronized 串行（inspect 大目录阻塞）；bind 依赖调用纪律 | **可接受** |
| HOME 持久化 | 已实现·T75 | /root bind、skel 播种 | 全局单例（跨 workspace 共享 —— 设计如此，文档如实声明） | **如实声明** |
| Health | 已实现·T76 | 7 维门面 + overall | 无 detect→repair→verify 编排（repairable 仅标志位） | **新增编排** |
| Capability | **模型完整·零接线** | 12 capability 模型 + profile + adaptive loop | EnvironmentSnapshot 无生产者（快照恒 EMPTY）→ 整套死代码 | **接线** |
| 错误模型 | 部分 | TerminalError/LinuxError/PackageError 存在 | 生产路径全部 `RuntimeException("TerminalError:X — 字符串")`；sealed 模型零使用 | **渐进统一** |
| 事件系统 | 已实现 | log + bus + 去重订阅 | **EventLog 无容量上限（per-session 无限增长）**；bus drop 无调用方（close 后 collector 协程泄漏）；订阅启动窗口可丢事件 | **修复** |

---

## 2. 生产必挂级缺陷（P0，测试绿但真机必挂的根因）

1. **真实 PS1 不匹配 → job 永久 RUNNING**：`InputWaitingDetector` 的 HIGH 正则只认裸 `$`/`#`/`>>>`，不认 `user@host:~$` / `(venv) $`（PromptDetector 有该正则，两个检测器不同步）。FakeNativePty 输出裸 `"$ "` → 测试全绿，真实 bash 前台 job 完成后永远 RUNNING，wait() 挂到超时。
2. **timeout SIGKILL 杀全组**：JobManagerImpl 私有 timeout watcher 到期直接 `sendSignal(SIGKILL)` → native `kill(-PGID)` 连同 shell 一起杀 → 一次 job 超时报废整个 session；无 SIGTERM 宽限；timer 不可取消；与 TimeoutController 的三级序列（TERM→5s→KILL）矛盾且后者零调用。
3. **策略门禁死代码**：InputManagerImpl 仅对 `kind==LINE` 查 TerminalPolicy，但 sendLine 内部降级为 RAW 类型落盘 → 生产全部命令绕过策略。
4. **内存泄漏链**：TerminalEventLogImpl append-only 无上限 + bus SharedFlow 永不 complete + close 不 drop bus → 每 session 永久泄漏 collector 协程 + 事件列表无限增长。BackpressureConfig 三档配置全仓库 0 引用。

## 3. Native 层缺陷（本次精读发现）

- **N-1（吞吐）**：`PtyEngine::readEx/write/sendSignal/isAlive/hasData` 全部持有 `mutex_`，session A 的 64KB read 会阻塞 session B 一切 IO —— 多 session 并发被单锁串行化。修复：锁内拷贝 shared_ptr（waitForData 已示范此模式），IO 移到锁外执行；PtySession 自身 ioMutex_ 保证 per-session 串行语义不变。
- **N-2（正确性）**：`close()` 内 `waitpid(pid_, &status, 0)` 后直接 `pid_=-1`，不解析 status → 经 close 路径终止的 session exitCode 恒 -1。
- **N-3（严格性）**：`exitCode_` 普通 int 跨线程读写（reapChild 写 / getExitCode 读）——改 atomic relaxed。
- **N-4（鲁棒）**：`close()` 阻塞 `waitpid(...,0)`：SIGKILL 后进程仍不退出（D state）时永久阻塞 JNI 调用线程 → 改为 WNOHANG 有限重试。
- **N-5（语义）**：close 的 HUP→TERM→KILL 之间固定 usleep 50/100ms —— 改为轮询存活提前退出（多数情况 <50ms 完成）。

## 4. Ubuntu 侧缺陷（Top）

- **U-1**：`RootfsProvisionerImpl.cancel()` 无 Job 引用 → 假取消（运行中 install 不可停止，且删 staging 与 extractor 竞态）。
- **U-2**：`UbuntuAptPackageManager.runAptRead` catch(Exception)→null → proot 崩溃/rootfs 缺失时 `isInstalled()=false` 与真实"未安装"不可区分（静默失败）。
- **U-3**：`UbuntuBootstrapManager` L171 `provisioner.current()!!` NPE → 泛型 catch 吞成 "bootstrap crashed: null"。
- **U-4**：apt 写操作 + bootstrap BASE_PACKAGES 无任何 free-disk 检查（rootfs preflight 只覆盖下载/解压）。
- **U-5**：RootfsMetadataStore/BootstrapStateStore `load()` runCatching 吞 JSON 损坏 → 健康数据被静默无视，引导全量重装。
- **U-6**：PackageOperationLock 跨实例 OS 锁竞争抛 `PackageLockError:OsLockHeld`，上层无此映射 → 错标 UNKNOWN/不可恢复（应 APT_LOCKED/可重试）。
- **U-7**：DEBIAN_FRONTEND 违规检查是死代码（interactiveViolation 恒空）；interactive env 基线两处漂移（PWD/OLDPWD/LC_ALL 只在 Manager 侧）。
- **U-8**：capability 栈（模型 12 capability + 6 profile + adaptive loop，2000+ 行）生产零接线 —— EnvironmentSnapshot 无生产者。
- **U-9**：`ProotExecutor.execute()`（非 bounded 路径）Long.MAX_VALUE → head StringBuilder 可积 1GB。
- **U-10**：markInUse/markIdle 生产零调用 → remove() 可在 proot 会话运行中删除整个 rootfs。

## 5. 并行/未接线子系统处置决策

仓库存在三套"规格先行、实现未接线"的平行层（observation v2、process2、reliability/RecoveryCoordinator），以及两套仅测试引用的状态机。T81 处置原则：

- **不删除**（约束 §50-12：禁止删除已有能力重新实现）。
- **接线优先于新写**：TimeoutController 三级序列接入 JobManager（替换私有 timeout watcher）；SessionStateMachine/JobStateMachine 通过 transition() 挂校验（production 防非法迁移）。
- **平行层保持原样**，不在本任务扩大范围（其消费者属于后续上层任务）。

## 6. 实施决策（D-1 ~ D-7）

- **D-1 Native 并发**：PtyEngine IO 方法改"锁内拷贝 shared_ptr → 锁外执行"（waitForData 模式推广）；engine mutex_ 只保护 map 结构。多 session IO 从全局串行变为 per-session 串行。
- **D-2 Job 终止语义**：timeout/cancel 统一走 TimeoutController 三级序列（SIGTERM→grace→SIGKILL）；信号目标从"整个 session 进程组"收窄为"前台进程组（tcgetpgrp）+ shell 组"，shell 优先保留；ProcessExited 事件的 exitCode/signal 如实上报（不再编 130/0）。
- **D-3 Job 完成检测**：InputWaitingDetector 补齐真实 PS1 正则（与 PromptDetector 对齐：`user@host:path$`、`(venv) $`、`PS1 变体`），保留裸提示符匹配；JobManager 合成退出仅在 native exit code 不可得时使用（nativeIsAlive=false 后读取 nativeGetExitCode）。
- **D-4 事件系统收敛**：TerminalEventLogImpl 加 per-session 容量上限（默认 500，BackpressureConfig 接线）；SessionManagerImpl.close 调用 eventLog.drop(sessionId) + eventBus.drop(sessionId) → 修复 collector 泄漏；bus subscribe 改为"先建 live channel 再查历史"消除启动窗口丢失。
- **D-5 持久化真原子**：SessionMetadataStore.save 改 tmp 写 + fsync + rename（与 T76 FileTaskStore 同模式）；RuntimeRecoveryService.recover 完整实现（合成 SessionClosed 事件 + 恢复记录注册进 store 查询面 + LinuxError 词汇），恢复的 session 一律 EXITED/BROKEN（不伪造 RUNNING）；isPidAlive 改 `/proc/<pid>` 存在性检查（Android 可用，无 fork）。
- **D-6 LinuxExecutionContext**：新增统一构造点（rootfs+workspace+home+env+arch+proot+cwd），LinuxPRootBackend / UbuntuAptPackageManager / LinuxNetworkProbe / Bootstrap 全部复用；env 基线单一来源（消除 PWD/OLDPWD/LC_ALL 漂移与 DEBIAN_FRONTEND 死检查——实装校验）。
- **D-7 Capability 真接线**：基于 ProotExecutor 的轻量真实探测（`which X` / `X --version`，有超时+缓存），产出 EnvironmentSnapshot → `terminal.linux.capabilities` 工具（status: AVAILABLE/MISSING/BROKEN/INSTALLABLE/UNKNOWN + 版本）；INSTALLABLE 判定 = 包名在 BasePackageProfile/sources 可得。

## 7. Shutdown 设计（§15）

`TerminalRuntime.shutdown()`：幂等（AtomicBoolean）；
顺序 = 停新请求（shutdown 标志）→ cancel 全部 job（TimeoutController 三级）→ 停 pump（stop）→ close session（SIGHUP→TERM→KILL 收敛 + bus/log drop）→ scope.cancel → nativeCloseAll（终态兜底）→ persistence flush。二次调用直接返回。

## 8. 测试基线与目标

- 基线：JVM 902 @Test / 49 文件（CI 已跑）；androidTest 27 @Test（compile-only，真机从未执行 —— 如实声明）。
- T81 目标：新增 ≥600 JVM @Test（PTY 生命周期/并发、session/job 状态机、input/wait/output、持久化/恢复、PRoot/RootFS/APT/Bootstrap/Health/Capability/Repair、ExecutionContext、错误模型）；androidTest 补 Ubuntu 全链路 + crash + 并发用例（本地无真机 → **NOT VERIFIED 如实标注**，CI 补 connectedAndroidTest 触发说明）。

## 9. 范围边界（§51 确认）

修改面：`platform/terminal/**`、`app/.../di/TerminalModule.kt`（能力探测/工具注册所需）、`docs/**`。不触碰 core/agent-engine、task-orchestrator、UI Code Mode、Skill、Market、Pipeline UI、Model Runtime。
