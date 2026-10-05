# Coding 模式 Git 工作区面板 + Agent 专家模板（v6）

> 用户规格收口：
> 1. 「在 coding 模式右上角设立一个工作区，显示变动的文件和 git 功能」；
> 2. 「模板方面，coding 模式增加一些重要的 agent 模板——增加一个 git
>    角色、一个 android 开发专家，反正对每一个编程语言都增加一个专家，
>    置顶一个全栈开发角色」。

## 1. Git 工作区面板（右上角入口）

### 入口
WorkspaceBar（顶栏）固定尾新增 Git 入口（CallSplit 图标，48dp 触区）：
工作区名 → 思考逻辑切换 → **Git 工作区** → 长任务 → 新会话。

### 面板（`CodeWorkspacePanel`，ModalBottomSheet）
- **头部**：当前分支（porcelain -b 解析）+ 变更计数 + 手动刷新；
- **变更文件列表**：porcelain 状态徽标（M 修改 / A·N 新增 / D 删除 /
  U 冲突 / R 重命名，色语义成对）+ 仓库相对路径；点击行按需展开该文件
  的 diff（等宽渲染，高度封顶内滚）；rename 取新路径展示；
- **提交区**：说明输入 + 一键「提交全部变更」——`git add -A` +
  `git -c user.name/user.email commit -m … --no-verify`（身份注入与
  GitCommitTool 同源：agent 署名、不动用户全局配置）；成功回执保留
  至用户读完（自动刷新不清回执），失败带回 stderr 摘要；
- **非仓库态**：一键 `git init` 引导；
- **空态**：工作区干净。

### 架构（God-file 预算纪律）
- `CodeGitPanelController`（独立文件）：StateFlow 状态 + refresh /
  loadDiff / commitAll / initRepo；调度器注入（测试虚拟时间）；
  错误全部折叠为状态，面板绝不向上抛；
- `CodeViewModel` 仅 +14 行接线（gitRunner 注入 + lazy 控制器）；
- `ProotGitCommandRunner` 恒定把激活工作区 bind 到 guest /workspace
  —— 命令天然作用于当前工作区，切工作区后刷新即生效；
- 列表高度纪律：LazyColumn `heightIn(max=340.dp)` 封顶内滚。

## 2. Coding 专家模板

### 数据（`AgentRole.CODING_EXPERTS`，内置不落盘）
19 个内置专家，顺序即菜单顺序：

| 位次 | 角色 | id |
|------|------|-----|
| **置顶** | 🧭 全栈工程师 | builtin_coding_full_stack |
| 2 | 🌿 Git 专家 | builtin_coding_git |
| 3 | 📱 Android 开发专家 | builtin_coding_android |
| 4-19 | 各语言专家 | kotlin / java / python / javascript / typescript / go / rust / cpp / csharp / swift / php / ruby / sql / shell / 前端（HTML/CSS） |

每个角色：中文显示名 + emoji + 英文 roleDefinition（含专项工作纪律，
如 Rust「unsafe 必须附安全证明」、Git「禁止未经询问改写共享历史」）。

### 选择入口
Coding 屏底部模式行**前插角色胶囊**（复用 Agent 屏 AgentRoleSelector，
全宽行加横向滚动防挤压）：全栈置顶 → Git → Android → 各语言 →
用户自定义角色（跨模式复用同一编辑器与持久化池）。

### 生效通道（薄包装不重写）
- 持久化：`AgentSettings.codeActiveRoleId`（与 Agent 屏 activeRoleId
  **互不干扰**，同一自定义角色池）；
- 引擎：`CodeEngineFacade.updateRolePersona(definition, prompt)` 新通道
  —— 深潜线 `CodeAgentEngine` → `delegate.patchConfig`（AgentConfig
  人设字段，与 Agent 屏 applyRoleToEngine 同通道）；标准线
  `StandardModeEngine` → 系统提示词「## Coding Role」段；
  `DualLogicCodeEngine` 双线同步（切线后人设不丢）；
- 热切换：`CodeRoleController`（独立文件）监听设置流 → 引擎人设，
  下一轮请求生效，无需重启。

### 兼容性
- 老用户 JSON（无 codeActiveRoleId）→ 反序列化即全栈缺省，零迁移；
- 悬空 id → 诚实回落全栈置顶；
- Agent 屏角色行为零变更（独立字段、独立通道）。

## 3. 验证

- `CodeGitPanelControllerTest`（app，6 用例，本地 JVM 实跑通过）：
  porcelain 解析（分支/ahead 后缀/rename/未跟踪/冲突）、非仓库识别、
  commitAll 身份注入 + 自动刷新、失败回执、幂等护栏、diff 缓存；
- `AgentRoleTest`（app，+5 用例）：专家清单与置顶顺序、语言覆盖核对、
  自定义接续、悬空回落、旧 JSON 零迁移、与 Agent 屏互不干扰；
- core:code-engine 全量 464 tests OK（本地 JVM，含 DualLogic 门面
  updateRolePersona 双线同步）；
- CI：静态分析 + app 编译 + 9 步测试 + 结构四门禁。
