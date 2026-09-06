# Ubuntu Product Lifecycle 2.0 (T82)

> 状态：已实施（JVM 全绿）。真机链路见文末 NOT VERIFIED 声明。

## 1. 问题：安装基础设施 ≠ 产品可用

T72–T81 交付了完整的 Ubuntu 安装基础设施（真实下载 + SHA-256 + 原子解压 +
bootstrap + apt + capability probe + repair），但 T82 Phase 0 审计发现产品层
存在 4 个断点：

| # | 断点 | 证据 |
|---|------|------|
| B-1 | App 启动不感知 Ubuntu 状态：无 reconcile、无状态派生 | `ApexApp.onCreate()` 只做 Shizuku/CS-Mem/attachment，无任何 Ubuntu 生命周期调用 |
| B-2 | Terminal UI 依赖安装中心 100% 失效：`DepCatalog` 全部是 apt 命令，但 `ensureSession()` 用默认 local backend 创建 Android shell 会话执行 → `apt-get: command not found` | `TerminalViewModel.ensureSession()` 调 `terminalRuntime.create()`（无 backendId）；`DepCatalog` v2 注释自述"apt/Ubuntu proot 命令" |
| B-3 | Agent 拉起 Ubuntu 需要 3 次工具调用（install→bootstrap→capabilities）并自行解读 3 套状态机 | `TerminalUbuntuInstallTool` / `TerminalLinuxBootstrapTool` / `TerminalLinuxCapabilitiesTool` 三个独立入口、三种状态语义 |
| B-4 | crash 后"上次中断在哪"需要 Agent 自己探测 | bootstrap 持久化有 `isInProgress` 标记但产品层无人消费 |

## 2. 方案：UbuntuLifecycleCoordinator（编排层，非重复实现）

```
NOT_INSTALLED ──install──▶ INSTALLING ──▶ ROOTFS_READY ──bootstrap──▶ BOOTSTRAPPING ──▶ READY
     ▲                        │                │                          │                │
     └────────────────────────┴── FAILED(stage, retryable) ◀────────────┴────────────────┘
                                │
                          RECOVERING（warmUp reconcile / repair —— 过程态，不残留）
```

**核心入口**（`platform/terminal/.../ubuntu/lifecycle/UbuntuLifecycleCoordinator.kt`）：

| API | 语义 |
|-----|------|
| `ensureReady(force, timeoutMs)` | 幂等单飞编排 install→bootstrap→capability 快照。超时=IN_PROGRESS（断点续传 + evidence 续跑，进度不丢） |
| `warmUp()` | App 启动恢复：provisioner.reconcile + 状态派生。**绝不下载**——用户没同意消耗流量前 App 不替用户做决定 |
| `refreshState()` | 从底层实时**派生** phase（单一事实源仍在 RootfsProvisioner/BootstrapManager，不复制判定逻辑） |
| `stateFlow` / `progressFlow()` | UI/Agent 订阅：phase 转移 + install/bootstrap 双进度聚合 |
| `cancelInstall()` | install 阶段真取消（字节保留续传）；其他阶段诚实 NotSupported |
| `repair()` | 透传 EnvironmentRepairService（单轮 detect→repair→verify，不触发大下载） |

**设计约束**（继承 T81 禁令）：
- 不新建第二套 Provisioner/PackageManager/RootfsManager —— bootstrap/probe/repair
  以**函数端口**注入（生产 DI 适配既有单例；JVM 测试注入 fake）；
- 状态派生而非复制：`rootfsState/bootstrapState` 直接取底层当前值；
- 既有细粒度工具（terminal.ubuntu.install / terminal.linux.bootstrap /
  terminal.linux.capabilities / terminal.linux.repair）**全部保留**，ensure 是聚合
  入口，不是替代。

## 3. 接线（5 处产品层断点修复）

1. **TerminalModule**（DI）：`provideUbuntuLifecycleCoordinator` @Singleton ——
   适配 UbuntuBootstrapManager（Result 六分支归一化）/ LinuxCapabilityProbe /
   EnvironmentRepairService / bootstrap progress Flow；
2. **ToolModule**：注册 `terminal.ubuntu.ensure`（一键拉起）+ `terminal.ubuntu.status`
   （只读快照，零动作）——Agent 决策字段：status/phase/failedStage/retryable/
   capabilities；
3. **EnvironmentProvisioner**：新增 `ensureUbuntuSession()` —— DepCatalog 的 apt
   命令先 ensureReady 再落 linux-ubuntu 会话；不可用时诚实降级 local（真实报错
   而非伪造成功）；
4. **TerminalViewModel**：依赖安装改走 `ensureDepInstallSession()`（Ubuntu 优先，
   失败降级 local 并在安装日志中说明）；暴露 `ubuntuLifecycleState` 供 UI 订阅；
5. **ApexApp**：`initUbuntuLifecycleRecovery()` —— 启动 warmUp（reconcile 现场 +
   派生状态；零下载零 bootstrap）。Code Mode UI 尚不存在于本仓库（审计确认），
   其未来入口即 `coordinator.ensureReady()` + `create(backend="linux-ubuntu")`。

## 4. 测试

- `UbuntuLifecycleCoordinatorTest`（30 用例）：编排顺序/幂等/force/失败矩阵
  （install Failed/Busy/Cancelled/异常、bootstrap Failed/InProgress/Busy）/
  probe 降级/超时续跑/并发单飞（10 并发 1 次 install）/warmUp 不下载/crash
  中断续跑/derivePhase 11 组合矩阵/cancelInstall 三态/repair 三态；
- `TerminalUbuntuLifecycleToolTest`（11 用例）：JSON 契约（READY/ALREADY_READY/
  FAILED/IN_PROGRESS/probeDegraded/status 只读零动作）。

## 5. 诚实声明（NOT VERIFIED）

- 真机链路（install 真下载 → bootstrap 真 apt → Ubuntu 会话 → apt 装包
  terminal 立即可见）依赖 Android 真机/模拟器，本任务未执行 —— 与 T81 一致，
  instrumentation 用例已具备（`T81UbuntuInfrastructureInstrumentationTest`），
  T82 的 coordinator 不阻塞该链路；
- `ensureReady` 全链超时预算（DEFAULT_ENSURE_TIMEOUT_MS = 15 分钟）基于
  "~30MB 下载 + apt update" 的量级估计，未做真机采样校准。
