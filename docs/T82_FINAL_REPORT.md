# T82 Final Report — Ubuntu Product Lifecycle 2.0

## 0. 任务范围

用户判定（原文）：项目具备完整 Ubuntu 安装基础设施，但"Product-Level Auto
Lifecycle 还需要补齐"。T82 把 **Install → Bootstrap → Capability → Repair →
READY → Terminal → Agent → UI** 从代码层面完整串联（先 JVM/CI 层解决，真机
测试最后再做）。方案由本任务实读代码决定，完成后直接提交 PR。

前置：本任务同时修复了 T81 PR #96 的三项 CI 失败（Hilt MissingBinding /
androidTest 4 个编译错误 / 括号平衡），PR #96 已全绿。

## 1. Phase 0 审计结论（实际读 main + t81 分支代码）

| 断点 | 定位 | 修复 |
|------|------|------|
| B-1 App 启动无 Ubuntu 生命周期 | ApexApp.onCreate 仅 Shizuku/CS-Mem | `initUbuntuLifecycleRecovery()` → warmUp |
| B-2 依赖安装中心失效（apt 命令跑在 Android shell） | TerminalViewModel.ensureSession() 无 backendId；DepCatalog 全 apt 命令 | EnvironmentProvisioner.ensureUbuntuSession + VM 路由 |
| B-3 Agent 三步三状态机 | install/bootstrap/capabilities 三个独立工具 | terminal.ubuntu.ensure 聚合入口 |
| B-4 crash 现场无产品级恢复 | bootstrap isInProgress 无人消费 | warmUp reconcile + 派生 |
| N/A Code Mode | 仓库无 Code Mode UI（搜索确认） | 未来入口已备好（ensureReady + linux-ubuntu create），如实报告非本任务交付 |

Ubuntu On-Demand 安装语义**保留**（首次安装仍是显式动作 —— Agent ensure 或
用户进依赖下载中心；App 启动只做恢复不做下载）。

## 2. 交付物

**新增（platform/terminal）**：
- `ubuntu/lifecycle/UbuntuLifecycleCoordinator.kt`（577 行）—— 7-phase 产品级
  状态机 + ensureReady/warmUp/refreshState/cancelInstall/repair/stateFlow/
  progressFlow；函数端口注入（无第二套抽象）；
- `tools/v2/TerminalUbuntuLifecycleTool.kt`（174 行）—— terminal.ubuntu.ensure +
  terminal.ubuntu.status（机器可决策 JSON 契约）；
- 测试 2 个文件 41 用例（30 coordinator + 11 tools），全绿。

**修改（app，均为 Terminal/Ubuntu 集成必需）**：
- TerminalModule：+provideUbuntuLifecycleCoordinator（BootstrapResult 六分支
  归一化适配）；
- ToolModule：+2 工具注册（细粒度工具全保留）；
- EnvironmentProvisioner：+ensureUbuntuSession（Ubuntu 路由 + 诚实降级）；
- TerminalViewModel：依赖安装走 Ubuntu 会话；+ubuntuLifecycleState；
- ApexApp：+warmUp 接线（SupervisorJob scope，零下载承诺）；
- docs/ubuntu-lifecycle.md（架构文档）。

## 3. 关键设计决策

1. **状态派生 vs 复制**：phase 由 derivePhase(hasRootfs, rootfsState,
   bootstrapState) 纯函数合成（11 组合矩阵测试）；单一事实源不动；
2. **函数端口而非接口**：bootstrap/probe/repair 以 `suspend (…) -> …` 注入 ——
   生产 DI 适配单例、测试注 fake，不建第二套类抽象（T81 禁令）；
3. **超时=IN_PROGRESS**：与 TerminalUbuntuInstallTool 同语义 —— 断点续传
   （T72 Range 下载）+ bootstrap stageEvidence 续跑，进度永不丢失；
4. **probe 失败不否定 READY**：环境可用与诊断快照分离（T81 §29 语义）；
5. **RECOVERING 是过程态**：refreshState 一律派生事实终态，不残留。

## 4. 测试与验证

- T82 新增 41 JVM 用例：**41/41 绿**（含并发单飞 10 并发 1 次 install、
  crash 中断续跑、warmUp 零下载、cancelInstall 真取消、repair 未接线诚实降级）；
- 全量回归：149 测试类 1045 用例 —— 1037 绿 + 7 个 forkpty/定时器类
  **环境敏感 flaky**（A/B 验证：单独运行 19/19 全过，基线与 T82 树一致 →
  并发资源竞争所致，非代码回归）+ 1 个本地 runner 伪报（Kt 类，gradle 不收集）；
- 质量门禁：行数 max 633（预算 1200/1600）、括号/paren 平衡、无
  printStackTrace/反射/TODO、工具 ID 唯一 —— 全过；
- app 层：EnvironmentProvisioner+DepItem 本地 kotlinc 编译过；其余 4 文件
  （TerminalViewModel/ApexApp/TerminalModule/ToolModule）含 android import，
  依赖 CI 的 App Module Compile + Build Debug APK 验证（类型逐一核对：
  BootstrapResult 六分支字段 / CapabilityReport 字段 / LinuxEnvironmentError
  .message / RootfsProvider·PRootBinaryProvider 接口绑定）。

## 5. NOT VERIFIED（如实声明，绝不写 PASS）

- **真机全链**：install 真下载 → bootstrap 真 apt → 创建 Ubuntu 会话 →
  pwd/workspace/HOME → apt install → terminal 可见 → close → recreate ——
  需 Android 真机（与 T81 同口径；instrumentation 用例已就绪）；
- **ensureReady 超时预算校准**：15 分钟默认值未真机采样；
- **ApexApp warmUp 在真进程的启动时序**：JVM 层已验证语义，真机行为待测。

## 6. 与既有系统的关系

- 保留：terminal.ubuntu.install / terminal.linux.bootstrap /
  terminal.linux.capabilities / terminal.linux.repair / terminal.linux.status /
  terminal.backends —— 全部细粒度入口不删；
- 复用：RootfsProvisioner（T72）、UbuntuBootstrapManager（T76）、
  LinuxCapabilityProbe（T81 §29）、EnvironmentRepairService（T81 §30）、
  LinuxEnvironmentHealth（T76）；
- 未改：TerminalRuntime / SessionManager / PTY / PRoot 执行路径（零触碰 ——
  T82 是编排层任务）。
