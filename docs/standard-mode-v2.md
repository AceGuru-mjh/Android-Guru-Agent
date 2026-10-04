# 标准模式 v2 — 对齐业界标准 CLI 编码智能体的全面完善

> **状态：已实施**。标准线（`StandardModeEngine`）在 v1 骨架（画像 /
> 权限门 / 子代理 / 压缩）之上的系统性补强：提示词工程（env 块 + 行为
> 规范 + 工具纪律）、权限链闭环（设置层接线 + PLAN 硬门 + 敏感文件
> 保护）、read-before-edit 引擎级硬约束、模型感知上下文窗口、未知工具
> 自纠、增量压缩摘要与继续指令。参照对象为业界标准 CLI 编码智能体的
> 公开设计实践，本文描述全部落地行为。

## 一、动机

v1 标准线搭好了「标准件」骨架，但对照业界标杆仍有明显洼地：

| 洼地 | v1 行为 | v2 行为 |
|---|---|---|
| 模型自我认知 | 无 env 块（模型不知道自己是谁/今天几号/在哪） | 系统提示词注入 env 块（模型 ID / 日期 / 平台 / 工作区 / git 探测） |
| 行为规范 | 散落画像文案 | 独立核心规范段（简洁回复 / 专业客观 / 文件纪律 / git 纪律 / 代码引用 `file:line`） |
| 工具纪律 | 仅工具描述 | **专用工具优先**映射表（防模型用 shell 的 cat/grep/sed 绕过专用工具） |
| 上下文窗口 | 恒 128K 静态配置 | 模型真实窗口（`ModelProfile.contextWindow`）优先，静态兜底 |
| 权限规则 | 设置层规则从未生效（updateRules 零调用） | 设置层快照源每轮任务前拉取，改设置即时生效 |
| PLAN 档 | 仅靠画像约束 | planGate 硬门 + 回合级只读提醒（双保险） |
| 敏感文件 | 任意读取 | `.env` 族 / 密钥 / 凭据文件默认询问（BYPASS 跳过，子代理折叠拒绝） |
| edit/write 契约 | 仅描述文案宣称 | 引擎级 read-before-edit 硬约束（未读先编直接拒绝并引导） |
| 未知工具 | 静默失败 | 拒绝 + 相近名修正建议（Levenshtein ≤2 / 前缀 / 包含） |
| 拒绝反馈 | 只有拒绝事实 | 用户拒绝时的指示原文回传模型（模型可改道） |
| 压缩摘要 | 单次快照 | 结构化模板（Objective / Work State / Next Move / Relevant Files）+ 增量合并 + 压缩后继续指令 |

## 二、提示词工程层（`StandardPrompts.kt`）

系统提示词装配序（`buildSystemPrompt`）：

```
画像身份 → 核心行为规范 → 任务方法论 → 工具面说明 → 工具纪律
→ Todo 指引（面含 todo 时）→ env 块 → 工作区上下文 → 规则注入
（全局/项目）→ 会话附加段 → 子代理隔离声明
```

- **env 块**（`environmentContext`）：模型 ID（`ModelRuntime` 解析链
  首选项）、日期、平台（`Android with a PRoot Ubuntu sandbox`）、
  工作区标签、git 仓库探测——与业界 env 注入实践同款信息面；
- **核心行为规范**（`coreConduct`）：简洁回复（非对话轮省 token）、
  专业客观（指正错误基于事实、不粉饰）、**不乱建文件**（宁编既有
  文件不建新文件）、不主动 commit / push、代码引用统一 `file:line`
  格式、`<system-reminder>` 标签只由系统使用；
- **工具纪律**（`toolDiscipline`）：专用工具优先映射表——查文件用
  `code_read` 不用 shell cat、搜代码用 `code_grep` 不用 grep、改文件
  用 `code_edit` 不用 sed/echo、找文件用 `code_glob` 不用 find/ls -R；
  命令行仅用于真正需要 shell 的场景（构建/测试/git 操作）；
- **PLAN 回合级提醒**（`planModeReminder`）：请求级注入（不进会话
  时间线）——每回合提醒「当前处于规划阶段，只调查不修改，计划确认
  后才能执行写操作」；
- **Todo 完整状态机**（`todoGuidance`）：When to use（≥3 步 / 用户
  多任务 / 新指令中途捕获）/ When NOT to use（单一琐碎任务 / 纯问答）/
  States（同一时刻恰好一个 in_progress）/ Rules（实时更新不批处理、
  completed 仅在验证成功后、保留用户指令原文）；
- **plan→build 切换声明**（`planExecutionBrief`）：计划确认切构建者
  时，首条指令明确「计划已获用户批准，按以下步骤执行」。

## 三、权限链闭环（`StandardPermissionEngine.kt` + 接线）

### 3.1 完整裁决序（v2）

```
DENY 规则 ──► PLAN 硬门（planGate）──► 会话记忆 ──► 命令级规则
──► 工具级规则 ──► 敏感文件保护 ──► 模式兜底（BYPASS/DEFAULT/ACCEPT_EDITS/PLAN）
```

- **PLAN 硬门**：`AgentMode.PLAN` 档引擎级传入 `planGate=true`，
  写类调用直接拒绝（与设置层权限模式正交，BYPASS 也拦）；归因
  「PLAN 档只读硬门」；
- **敏感文件保护**：路径名段匹配 `.env` / `.env.*`（排除 `.env.example`
  / `.env.sample`）/ `*.pem` / `*.key` / `*.keystore` / `*.jks` /
  `id_rsa*` / `credentials.json` / `secrets.json` / `secrets.yaml`，
  命中默认 ASK（归因「敏感文件保护：{文件名} 可能包含密钥」）；
  BYPASS 模式跳过；子代理上下文折叠 DENY；显式 ALLOW 规则放行；
- **用户显式授权优先**：会话记忆与规则裁决先于敏感保护（用户说
  「总是允许」就是允许——与业界语义一致）。

### 3.2 设置层接线（`StandardPermissionSource`）

```
SettingsRepository.agentSettings（StateFlow）
  └─snapshot()→ Snapshot(mode, rules, commandRules)
       └─引擎每轮任务开始 refreshPermissionConfig()
            └─permissionEngine.updateMode / updateRules / updateCommandRules
```

- app 层 `CodeModule` 装配时注入 settings 快照源（与主 Agent 模式
  `PermissionModeGate` 的 settingsProvider 同款模式）；
- 规则分流：含空格的 pattern 视为 shell 命令模式（如 `git push*`），
  其余为工具 id 模式；
- **修复 P0**：v1 中 `updateRules` / `updateCommandRules` 生产零调用，
  设置层的规则三元组从未生效；
- 设置源下传子代理（DENY 规则对子代理同样生效；ASK 自动折叠 DENY）。

### 3.3 ASK 交互增强

拒绝不再只是事实，而是**可携带指示**：

- 应答词表：「允许/allow/yes/y/好/ok/1/执行」= 本次放行；「总是/
  always/全部允许/session/2」= 本会话总允许；**其余任意非空文本 =
  拒绝 + 指示原文回传模型**（模型收到「不要动 build.gradle，先只改
  app 层」这类具体指示后可改道）；空 / 超时（5 分钟）= 拒绝。

## 四、read-before-edit 引擎级硬约束（`StandardReadGuard.kt`）

业界标准 edit/write 契约的铁律：**编辑文件前必须先读过**——否则
模型对文件真实内容的认知是幻觉，old/new 精确替换必然失准。

- `check`：`code_edit` / `code_write`（覆盖已存在文件）执行前校验，
  未读先编直接拒绝并引导（「先用 read 工具读，再改；新建文件请用
  code_write」）；**新建文件免检**（创建合法）；
- `record`：`code_read` / `code_edit` / `code_write` 成功后登记读
  状态（写过的文件内容已知，等价于读过）；
- **路径归一**：raw 绝对路径 / workspace 相对路径 / guest 前缀
  `/workspace/...` 三形态统一归一到规范绝对路径，防「读 A 形态、
  编 B 形态」绕过守卫；
- 工作区切换 `reset()` 清零重新累积（新文件域）；
- 拦截发生在权限门**之前**（守卫失败不占权限询问次数）。

## 五、模型感知与自纠（`StandardModeEngine.kt`）

- **真实上下文窗口**：`ModelInfo(modelId, contextWindow)` 提供者由
  DI 从 `ModelRuntime.resolve(ModelRole.PRIMARY)` 解析链首选项注入；
  env 块消费 modelId，压缩预算消费 contextWindow（修复恒 128K 的
  窗口盲区——大窗口模型被过早压缩、小窗口模型爆上下文）；
- **未知工具自纠**：模型幻觉出不存在的工具名时，拒绝结果携带相近
  名修正建议（Levenshtein ≤2 / 前缀 / 包含，前 3 候选），模型下一
  轮自纠，不浪费回合；
- **工具名容错**：别名归一升级（大小写不敏感 + 连字符/下划线归一，
  如 `Code-Read` / `codeRead` → `code_read`）；
- **task 子代理 general 档**：新增 general 类型子代理（全工具面通用
  执行者——子代理上下文不能再派发、不能询问，写操作受权限门约束），
  子代理结果统一 XML 信封（`<task state="completed|partial|error">`）。

## 六、压缩增强（`StandardCompactor.kt`）

- **结构化摘要模板**：Objective / Important Details / Work State /
  Next Move / Relevant Files 五段固定结构（模型摘要不跑偏）；
- **增量合并**：二次压缩时提取旧摘要（`[SESSION SUMMARY` 开头的
  System 消息）作为 priorSummary 传入——新摘要覆盖增量、丢弃旧版，
  不丢早期工作状态；
- **继续指令**：replacement 尾部追加 System 消息（「上文已压缩进
  摘要，请继续当前任务」）——模型不会在压缩后突然失忆停顿；
- 摘要预算 800 → 1000 token。

## 七、工具描述对齐（`core/code-tools`）

七个编码工具的 description 全面对齐业界契约语义（纯提示词层改动，
执行逻辑 / schema / 工具 id 零变更）：

- `code_read`：offset 续读语义、目录模式说明（每行一条 + 尾随 `/`）、
  使用指引（避免碎片小读 / 单回合批量读 / 读 `.env` 类敏感文件可能
  需要用户确认）；
- `code_write`：**优先编辑既有文件**（本工具主要用于新建与全量重写）+
  **不主动创建文档文件**（`*.md` / README 除非用户明确要求）；
- `code_edit`：read-before-edit 声明（运行时强制）+ oldString 精确匹配
  规则（含缩进空白 / 多处命中需更多上下文 / 模糊兜底不可依赖）；
- `code_todo`：完整状态机说明（When to use / When NOT to use /
  States / Rules）；
- `code_grep` / `code_glob`：开放性探索委派子代理指引。

## 八、PLAN 档解析独立（`StandardPlanParser.kt`）

规划文本（`### Goal` / `### Steps` 四段结构）解析按职责缝拆出为
独立解析器（引擎主文件预算纪律）：支持编号 / 复选 / 无序三种列表行
形态，Steps 段缺失降级为普通回复，Goal 缺失回退会话标题。

## 九、验证

- `:core:code-engine:test` 全绿（464 用例，含 37 用例权限矩阵 + 10
  用例压缩链 + 13 用例 read-guard/plan-parser 单元矩阵）；
- `:core:code-tools:test` 全绿（74 用例）；
- 门禁：`check_file_size.sh`（标准引擎 1173 行 ≤1200）/
  `check_code_quality.sh` / `kotlin_balance.py`（42 文件平衡）全过；
- app 模块改动（DI 装配 + 权限设置 UI 文案）由 CI 编译门禁验证。

## 十、文件清单

| 文件 | 变更 |
|---|---|
| `standard/StandardPrompts.kt` | +6 方法（env/conduct/discipline/planReminder/generalSubAgent/unknownTool），todoGuidance 等增强 |
| `standard/StandardPermissionEngine.kt` | planGate 硬门 + 敏感文件保护 + extractPath |
| `standard/StandardPermissionSource.kt` | 新增：设置层快照源接口 |
| `standard/StandardCompactor.kt` | 结构化模板 + 增量合并 + 继续指令 |
| `standard/StandardReadGuard.kt` | 新增：read-before-edit 硬约束 |
| `standard/StandardPlanParser.kt` | 新增：规划文本解析（职责缝拆出） |
| `standard/StandardRunSupport.kt` | 新增：运行支撑结构（报告/累加器/防循环） |
| `standard/StandardModeEngine.kt` | 全部接线 + 文件预算收敛（1308→1173） |
| `standard/StandardToolSurface.kt` | general 档 + suggestToolIds + 容错归一 |
| `standard/StandardAgents.kt` | GENERAL 画像 KDoc 更新 |
| `CodePrompts.kt` / `RulesProvider.kt` / `CodeTaskTool.kt` / `SubAgentPrompts.kt` | 注释清理与契约对齐 |
| `core/code-tools` 7 工具 | description 对齐业界契约 |
| `di/CodeModule.kt` | 权限源 + 模型信息源装配 |
| `di/ToolModule.kt`、app 权限层、README、docs | 注释 / 文案对齐 |
