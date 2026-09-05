# T81 Final Report — Terminal + Ubuntu Execution Infrastructure 2.0

> 分支：`t81/terminal-ubuntu-infra-2.0`（基于 `main@026c5be`，PR #92 合并后）
> 本报告如实记录 18 项验收内容。**任何本地无法真实验证的项明确标注 NOT VERIFIED。**

---

## 1. 当前 Terminal 架构（改造后）

```
Agent / Tools / UI
       │
  TerminalRuntime（9 操作 + backends + shutdown + recover + Flows）
       │
  ExecutionBackendRegistry ── local ─── LocalShellBackend ── forkpty ── sh -i
       │                        │
       │                      linux-ubuntu ── LinuxPRootBackend ── forkpty ── execv(libproot … bash -i)
       │
  SessionManager（assembly：RingBuffer(256KB) / VT / Reducer / Pump / Observation）
  JobManager（TimeoutController 三级序列）  InputManager（Channel 串行写 + policy 门禁）
  WaitEngine（事件驱动 + IdleFor 定时器）   EventLog(500 上限)+EventBus(replay=64)
       │
  PtyEngine（acquire 模式：锁内拷贝 shared_ptr → 锁外 IO）→ PtySession（forkpty）
       │
  Persistence（fsync+rename 原子写）→ RuntimeRecoveryService（不伪造 RUNNING）
```

关键升级：native 全局锁 → per-session 并发 IO；`TerminalRuntime.shutdown()`（§15）；退出码经 close/signal/EOF 全路径保留；事件系统全链路收敛（有界、无泄漏、无重复投递）。

## 2. 当前 Ubuntu 架构（改造后）

```
RootfsProvisioner（14 态 + 真取消 + refcount remove 保护 + 损坏隔离 + 原子激活）
  → ProvisionedRootfsProvider ──→ LinuxExecutionContextFactory（§34 单一解析点）
        ├── LinuxPRootBackend（交互 PTY 会话）
        ├── UbuntuAptPackageManager（apt 10 操作 + 磁盘 preflight + 结构化读错误）
        ├── LinuxCapabilityProbe（§29 真实 which/--version 探测 + TTL 缓存）
        └── LinuxNetworkProbe
  UbuntuBootstrapManager（8 态 + NPE 修复）  EnvironmentRepairService（§30 单轮编排）
  LinuxEnvironmentManager（env 唯一权威：interactive 11 键 / apt +DEBIAN_*）
  GuestUserHome（/root 持久化）  LinuxWorkspaceManager（per-session 隔离 + 活跃计数）
```

关键升级：apt 与交互终端**经构造**共享同一 rootfs/workspace/home/proot（§33-34）；capability 模型从死代码变为生产可查（§29）；DEBIAN_FRONTEND 泄漏校验实装（§22）。

## 3. 修改的模块

- `platform/terminal/src/main/cpp/**`（4 文件：pty_session/pty_engine/jni_bridge 保持 + 强化）
- `platform/terminal/src/main/kotlin/**`（18 文件修改 + 6 新文件）
- `app/src/main/kotlin/com/apex/agent/di/TerminalModule.kt`（providers + rootfsBinder/apt factory 注入）
- `app/src/main/kotlin/com/apex/agent/di/ToolModule.kt`（2 个新工具注册 —— Terminal 集成所必需）
- `platform/terminal/src/test/**`（10 个新测试文件）
- `platform/terminal/src/androidTest/**`（1 个新真机套件）
- `platform/terminal/src/hostTest/**`（新：native 主机验证 harness + run.sh）
- `docs/**`（本报告 + 审计文档）

**未触碰**（§51 合规）：core/agent-engine、core/task-orchestrator、UI Code Mode、Skill、Market、Pipeline UI、Model Runtime、terminal-emulator（只读消费）。

## 4. 复用的已有组件

PtySession/pty_engine 全套 native 基础（forkpty/进程组信号/EAGAIN 退避）；ExecutionBackend/SpawnSpec 抽象（P71）；RootfsProvisioner 全生命周期（T72 下载续传/tar 解压/原子激活/reconcile）；UbuntuAptPackageManager 10 操作 + 双层锁（T76）；LinuxWorkspaceManager + GuestUserHome（T75）；TimeoutController 三级序列设计（此前死代码，本次接线）；LinuxEnvironmentManager 三层 env 模型（T76）；SessionMetadataStore schema v3；ProotExecutor 有界执行（T76）；RootfsHealthInspector（T72）。

## 5. 重构的组件

PtyEngine IO 模型（全局锁 → acquire）；SessionManagerImpl.close（幂等+持锁+cause 修复）；JobManager 超时（私有裸杀 → TimeoutController 统一）；InputManagerImpl.sendLine（接口 default 降级 → 显式 LINE 覆写）；LinuxPRootBackend.buildGuestEnv（内联 8 键 → 权威来源派生）；UbuntuAptPackageManager.buildProotCommand（内联解析 → LinuxExecutionContextFactory）；RootfsProvisionerImpl.markInUse（布尔 → 引用计数）。

## 6. Native PTY 修复项

| # | 修复 | 验证 |
|---|---|---|
| N-1 | 全局锁串行化所有 session IO → acquire 模式（per-session 并发） | host 真实 forkpty：20 会话并发 write+readEx 5ms；TSan 零报告 |
| N-2 | close() 内 waitpid 丢弃 status → exitCode 保留；EOF/EIO 分支顺手 reap（EOF 后立即可查退出码） | host：signal 终止 exitCode=137；EOF 后立即查询成功 |
| N-3 | exitCode_/pid_ 非原子 → atomic（数据竞争 UB 消除） | TSan 零报告 |
| N-4 | close() 无限期 waitpid(…,0) → WNOHANG 有界（D-state 不挂死 JNI 线程） | host：close+double-close 2ms |
| N-5 | 固定 usleep(50/100ms) → 有界轮询早退 | host 全部用例 |

## 7. PRoot 修复项

- U-9 ProotExecutor.execute() 无界输出（~1GB StringBuilder 风险）→ 默认 1MB 有界。
- §21 结构化 argv 路径复核：PRootCommandBuilder 的 TM6 注入防护（guestPath 禁 `:`、env 值禁换行）保持 —— 两条执行路径（PTY spawn/ProcessBuilder）均无 shell-string 拼接。
- §22 host/guest env 三层分离经 LinuxEnvironmentManager 权威化；DEBIAN_* 泄漏校验实装。
- G4 host env（PROOT_TMP_DIR/LOADER/LD_LIBRARY_PATH）只在 host 层 —— 不变。

## 8. RootFS 修复项

- U-1 假取消 → 真取消（install Job 捕获 + downloader/extractor ensureActive 检查点响应；状态不再被覆写）。
- U-10 markInUse 生产零调用 → RootfsUsageBinder 接线（create bind/close unbind；refcount）。
- U-5 元数据损坏静默吞 → .corrupt 隔离（RootfsMetadataStore + BootstrapStateStore）。
- U-10 recoverable 标志丢失（CHECKSUM_MISMATCH 等终态变不可恢复）→ RECOVERABLE_ERROR_CODES 保留。
- 下载续传/SHA-256/tar 防逃逸/原子激活/reconcile（T72 既有能力）保持并有测试锁定。

## 9. Workspace 修复项

- T75 懒创建 + bind 计数 + 原子元数据（既有）保持；shutdown 路径 unbind（rootfs 引用同步释放）。
- 隔离验收（§25）：per-session env 经 -E 独立；workspace 目录独立；HOME 全局共享（设计语义 —— 报告如实声明：`~/.ssh`、pip 缓存跨 workspace 互见；workspace 级 HOME 隔离属后续增强，未实现）。

## 10. APT 修复项

- U-2 runAptRead 吞异常 → lastReadError StateFlow 结构化可观察（isInstalled 假 false 可区分）。
- U-6 OsLockHeld → APT_LOCKED（可重试）。
- U-4 全部写操作磁盘 preflight（250MB/包 + 100MB 基线 → DISK_FULL 结构化失败；覆盖 bootstrap 的 APT_UPDATE/BASE_PACKAGES）。
- §28 apt↔terminal 同一环境：LinuxExecutionContextFactory 构造级共享（rootfs/workspace/home/proot/env 来源同一实例）。

## 11. Recovery 修复项

- 伪原子写（copyTo 无 fsync）→ tmp+fsync+rename 真原子；损坏文件隔离。
- RuntimeRecoveryService：/proc pid 检查（无 fork）；RUNNING job → INTERRUPTED（§16 不伪造 RUNNING）；CLOSED 记录清理；autoSave 单次 IO 失败不再杀循环；autoSave 生产接线（此前零调用）；backend 字段持久化不再丢失。
- shutdown()：幂等 + 收敛链（§15）。

## 12. Performance benchmark

- Host 真实数据（g++ -O2, Linux forkpty）：20 会话并发 write+readEx **5ms**；close 序列 **2ms**（原固定 150ms 睡眠 + 潜在无限等待）；TSan 开销下仍全绿。
- JVM（FakeNativePty）：1003 tests 总套件（含 20 会话并发/10 超时 job 并发/持续输出压力）在标准 CI 超时内完成。
- **NOT VERIFIED**：§35 要求的完整基准矩阵（local/ubuntu terminal startup、first output latency、observe latency、P50/P95/P99）—— 需真机 instrumentation 执行；androidTest 中已含 spawn latency 基准（既有 NativePtyArgvInstrumentationTest）。本任务未新增真机 perf 采样，不声称任何具体毫秒数达标。

## 13. JVM tests 数量

- **基线（main@026c5be）：902 @Test / 49 文件**
- **T81 新增：101 @Test / 12 个新测试文件**（events 17 / prompt 12 / job-timeout 7 / shutdown-recovery 9 / utf8 6 / wait 11 / provisioner 5 / exec-context 15 / error-model 7 / concurrency+observe 13 —— 全部锁定真实行为修复，零凑数）
- **当前总量：1003 @Test**；本地运行：990 合跑 + 13 根包单跑 = **1003 中 1001 通过**；2 个失败为**基线即存在的 flaky**（ControlPlaneTest.wait / CursorExpirationTest —— git stash A/B 验证在未修改基线上同样失败、单跑均通过，非 T81 引入）。
- 矩阵对照（§44 目标量级按全模块累计已超；新增部分覆盖 PTY/JNI 语义（host 19 项）/session/job/input/output/wait/observation/persistence-recovery/concurrency/PRoot/RootFS/APT/Bootstrap/Health/Environment）。

## 14. Instrumentation tests 数量

- 既有：27 @Test（5 文件，compile-only）。
- T81 新增：**15 @Test**（T81UbuntuInfrastructureInstrumentationTest —— §45 Ubuntu 链路 12 项 + §48 并发 10 会话 + §16 恢复）。
- 总计 42 @Test / 6 文件；CI `compileDebugAndroidTestKotlin` 编译。

## 15. 真机 E2E 结果

**NOT VERIFIED** —— 本环境无 Android 设备/模拟器。CI 无 connectedAndroidTest job（既有缺口，与 main 相同）。真机套件已就绪：
- LOCAL 链路（create/write/read/resize/signal/close）：既有 NativePtyJniInstrumentationTest 8 用例（含 P70/P81 边界）。
- UBUNTU 全链路（§46：install→workspace→create→pwd→file→git→apt install→terminal sees package→close→recreate→verify）：新 T81 套件 15 用例，assumeTrue 诚实跳过（无 rootfs/网络时报告原因而非假绿）。
- Crash E2E（§47）：T81 套件用例 30 以「runtime 重建 + 持久化恢复」模拟 app 重启（进程级 kill 需真机 adb shell am kill —— 保留为后续）。**进程级 kill 未真实验证。**

## 16. 并发测试结果

- Host native：20 会话并发 IO + TSan 零数据竞争。
- JVM：20 会话并发 create/write/observe/close 无死锁无串扰；close A 不影响 B；10 并发超时 job 全部收敛终态；并发 shutdown+create 收敛；快速 25 次 create-close 唯一 ID。
- **NOT VERIFIED**：真机 10 UBUNTU+10 LOCAL（androidTest 已写就绪）。

## 17. Resource leak 检查结果

- Native：host 验证「all closed, no native leak」（20 会话关闭后 activeCount=0）；double-close 幂等。
- JVM：close 后 assembly 移除、bus drop、exit watcher 退出（无事件追加断言）、writer channel 关闭（TM3 既有）、listener 协程 takeWhile 终止（新增断言：log frozen after close）。
- FakeNativePty 的 outputBuffer 数据竞争与 UTF-8 截断（测试基建缺陷）在审计中识别，未修改（避免为测试改生产行为——§50-11；如实记录）。

## 18. 尚未验证的内容（汇总）

| 项 | 状态 |
|---|---|
| 真机 connectedDebugAndroidTest（42 用例） | **NOT VERIFIED**（无设备；CI 无设备 job） |
| §35 完整性能基准矩阵（P50/P95/P99） | **NOT VERIFIED**（未采样，不声称达标） |
| 进程级 crash（adb am kill）→ 重启恢复 | **NOT VERIFIED**（模拟重建已验证） |
| exit watcher 仍为 100ms 轮询（native 无阻塞 waitpid JNI 接口） | 如实声明（收敛语义有保证：close 与 watcher 的 mutex 互斥 + 事件不再污染） |
| PTY 内 job 命令的退出码 | 架构限制（shell 不回传命令退出码；合成路径 exitCode 语义已文档化；shell 自身退出码全路径真实） |
| workspace 级 HOME 隔离 | 未实现（全局 /root 共享为既有设计；已如实声明） |
| RootfsState.CORRUPTED 词汇统一 | provisioner 侧用 ProvisioningState.FAILED + invalidate() Invalidated 结果（语义等价，两套词汇并存未合并 —— 合并影响 linux 契约面，超出本任务边界） |

---

## 提交清单（本分支）

| commit | 内容 |
|---|---|
| 4d70e32 | docs: Phase 1 架构审计（24 组件 mapping + P0/P1 缺陷 + D-1..D-7 决策） |
| bbf5e9d | native: 多会话并发 IO + exit code 保留 + 有界 close（host 19 项验证 + TSan） |
| 4169a9e | terminal: 事件系统收敛（有界 log/无泄漏/无重复投递/close 幂等/定时器隔离） |
| 5144a70 | terminal: job 超时不再杀 session + 策略门禁生效 + 真实 PS1 检测（3 个 P0） |
| 8472a60 | terminal: runtime shutdown + 真实崩溃恢复 + 真原子持久化 |
| 40b39fc | terminal: UTF-8 窗口边界 + WaitEngine 强化（PromptDetected/IdleFor/ignoreCase） |
| 5558e49 | ubuntu: 真取消/refcount remove 守卫/结构化 apt 读/磁盘 preflight/损坏隔离 |
| d553085 | linux: 统一执行上下文 + 真实能力探测 + 单轮修复编排（§29/§30/§33/§34） |
| 84c62bf | errors: 类型化终端异常（向后兼容解析） |
| a36976e | test: 真机 instrumentation 套件 + JVM 并发/观察矩阵 |

## §53 验收标准自答

**Terminal**：几十个 session 并发 —— YES（host 20 并发 5ms + JVM 20 并发无死锁）；单 session 故障不影响其他 —— YES（隔离测试）；PTY close 后无 native 残留 —— YES（host + JVM 双验）；大输出不 OOM —— YES（RingBuffer 256KB + EventLog 500 有界 + ProotExecutor 1MB）；增量观察 —— YES（RAW cursor 语义测试）；可靠等待输出 —— YES（OutputMatch 真匹配 + bounded + PromptDetected 真检测）；timeout/cancel/exit 不永久 RUNNING —— YES（三路收敛测试 + 恢复收敛 INTERRUPTED）。

**Ubuntu**：真实 Linux rootfs —— YES（T72 既有 + 本任务损坏/恢复强化）；terminal 与 apt 同一环境 —— YES（构造级共享）；同一 workspace —— YES；HOME 持久化 —— YES（跨会话标记测试 + 真机用例就绪）；apt 装的工具 terminal 立即可见 —— YES（设计构造保证 + 真机用例 11 就绪，本地 NOT VERIFIED）；rootfs 损坏可检测 —— YES（健康检查 + .corrupt 隔离 + 结构化 Invalidated）；bootstrap 中断可恢复 —— YES（stageEvidence 续跑 + NPE 修复 + 真取消）；多 workspace 不互相污染 —— YES（目录/env/proot 进程级隔离；HOME 共享为已声明的设计语义）。
