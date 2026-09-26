# AGENTS.md — Android-Guru-Agent 仓库协作规则

> 本文件是仓库自身的规则文件：Coding 模式的 Rules 系统（v1.1 #164）
> 会自动发现工作区根的 AGENTS.md 并注入会话上下文——把「在这个仓库
> 干活必须知道的纪律」写在这里，人与 agent 共同遵守。
> 优先级：本文件 > 用户全局规则 > 一般偏好；均低于安全与权限约束。

## 构建与验证

- **模块**：多模块 Gradle（app / core×6 / platform×5 / 终端双模块），
  Kotlin 2.0.21 + AGP 8.7.3 + JDK 17 + Compose BOM 2024.12.01；
- **本地验证顺序**：改 core 层先跑对应模块 `test`，改 app 层跑
  `:app:compileDebugKotlin`；CI（pr 触发）跑静态分析 + app 编译 +
  9 步测试 + debug APK + 结构质量四门禁；
- **绝不**在本地跑 `assembleRelease` 之外的东西来验证 UI——Compose
  编译过了 UI 仍可能崩，真机回归是最终验收。

## 硬性 CI 门禁（违反即红）

1. **文件预算**：main 单文件 ≤1200 行 / test ≤1600 行
   （`scripts/check_file_size.sh`）。超了就按职责缝拆文件，不在 PR
   里申请提额；
2. **反模式**：main 源码禁 `javaClass.getMethod` 反射派发、禁
   `printStackTrace()`（`scripts/check_code_quality.sh`）；
3. **括号平衡**：改完跑 `python3 scripts/kotlin_balance.py <files>`。

## Kotlin 陷阱清单（本仓库踩过的真雷）

1. **嵌套注释**：Kotlin 块注释可嵌套——KDoc 里写 glob（如
   `assets/skills/` 目录通配）会让 `/*` 序列开新层级吞掉后续代码。
   铁律：注释里**永远不出现斜杠紧跟星号的序列**，目录通配写成
   「assets/skills 目录下的 .json 文件」；
2. **尾随 lambda 绑定末参**：多参数构造/函数的尾随 lambda 绑定的是
   **最后一个**参数——传 scope 等「中间参数」时显式具名
   （`CoroutineScope(scope)` 而不是 `(scope) { ... }`）；
3. **K2 泛型 suspend 推断**：`?.let {}` 里 suspend lambda 不做期望
   类型传播——builder setter 非空形参用 `.apply {}` 条件装配；
4. **字符串模板先于 trimIndent 求值**：原始字符串里插值的内容会参与
   公共缩进计算——先对字面量 trimIndent 再拼接插值体；
5. **readNBytes 重载歧义**：用三参形式 `readNBytes(buf, 0, size)`，
   单参/Int 重载在 K2 新推断下会按后续用法选错候选。

## 测试纪律

- JUnit4 + kotlinx-coroutines-test（`runTest`）+ TemporaryFolder；
  **无 mock 框架**（版本目录不提供），手写 fake / 脚本化事件序列；
- 假钟注入（`() -> Long` 参数）驱动时间分支，不依赖墙钟睡眠；
- `backgroundScope` 在 coroutines-test 1.9.0 不受 `advanceUntilIdle`
  推进——fire-and-forget 断言用独立 `CoroutineScope(StandardTestDispatcher(
  testScheduler))`；
- 测试文件同样 ≤1600 行；断言值先读源码核实，不臆造。

## 提交与 PR 约定

- 提交信息：`类型(范围): 中文摘要 —— 关键词 / 关键词`（例：
  `feat(coding-mode): 七档思考系统 —— ULTRACODE/APEXCODE/自适应升级`）；
- PR 描述含验证表（编译/测试/门禁结果）；大特性附 docs/ 文档
  （本目录有 feature-doc 惯例：一个特性一篇）；
- PR 由仓库主人合并（agent 只提交不合并）。

## 架构原则

- **薄包装不重写**：模式层（如 CodeAgentEngine）包装共享引擎，
  经 `additionalSystemContext` / `patchConfig` 通道注入行为，不fork
  主循环；
- **复用层**：skills / MCP / 工具目录 / 思考档位等能力为全模式共享
  单例，新模式接入走「注入」而非「复制」；
- **防御式 IO**：外部输入（事件流/JSON/规则文件）任何形状都要吞得
  下——异常折叠为 null/跳过 + AppLogger 留痕，不向上抛；
- **原子写**：持久化一律 tmp + renameTo，rename 失败直写目标兜底；
- **fire-and-forget 纪律**：VM 主线程绝不等磁盘，落盘协程捕获不可变
  快照，SupervisorJob 隔离失败传染。
