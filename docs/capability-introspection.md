# Capability Introspection — 能力自省与自主扩展

> 一个特性一篇文档（仓库惯例）。本篇对应 PR：**feat(agent-engine): 能力自省与
> 自主扩展 —— 权限阶梯 / 能力报告 / 市场检索 / 扩展攻略**。

## 1. 根因：agent 为什么「不懂自己能干什么」

用户反馈的四个具体缺口，逐一对应旧实现的断点：

| 用户抱怨 | 旧实现断点 |
|---|---|
| 「不懂得自己拥有什么权限」 | 系统提示词只有当前等级的 CAN/CANNOT 两行；无阶梯全貌（每级解锁什么）、无升级路径；遇 `permission denied` 只能盲目重试或放弃 |
| 「不懂得能干什么」 | `tool_list` 只覆盖工具注册表；权限/环境/技能/MCP 全貌没有统一自省入口 |
| 「不知道有什么工具可以安装什么」 | 市场源（HubSource 等 7 源）只服务 UI 市场页，agent 侧完全不可见；`skill_search` 只搜 GitHub 关键词，`mcp_connect` 要求自备 url/command |
| 「不会自己找方法」 | 提示词只列已装能力；没有「装不到就找、找不到就装」的决策梯度；失败换路提示只在引擎层事后注入 |

## 2. 方案总览（四个组件，两条注入线）

```
                       ┌──────────────────────────────┐
                       │  PrivilegeLadder（单一真值源）  │
                       │  tool-registry · catalog 包    │
                       │  normalize / infoFor /         │
                       │  promptSection / briefLine     │
                       └──────┬───────────┬───────────┘
                              │           │
              ┌───────────────┘           └───────────────┐
              ▼                                           ▼
   EnginePrompts（引擎线）                        capability_report（工具线）
   ## Device Privilege Level（阶梯+升级+护栏）      Privilege: ... CAN/CANNOT/Upgrade
   ## Capability Expansion Playbook（六条梯度）     + Environment + Tools/Skills/MCP
   OrchestratorPrompts（BUILD 编排线）             + Expansion ladder
   PrivilegeLadder.briefLine + 扩展通道提示
                              │
                              ▼
                    MarketSearchTool（market_search）
                    官方技能 hub + MCP hub 双目录检索
                    → 返回可执行安装指令
```

**同源原则**（仓库既有约定：prompt 里的与 gate 里的永远是同一份状态）：
- 权限知识 → `PrivilegeLadder`（prompt 段与 `capability_report` 输出共用）；
- 环境快照 → `ToolEnvironmentState`（prompt 的 Live Environment 段与执行侧
  `ToolEnvironmentGate`、`capability_report` 的 Environment 行共用）；
- 工具库存 → `ToolRegistry` + `ToolTierPolicy`（请求 tools 数组与报告的
  CORE/CATALOG 二分共用）。

## 3. 组件明细

### 3.1 PrivilegeLadder（`core/tool-registry/.../catalog/PrivilegeLadder.kt`）

权限阶梯知识库，纯静态零状态：

- `normalize(level)` —— 任意输入串折叠为三级（未知 → `NORMAL_SHELL`，
  与引擎 prompt 历史 else 分支口径一致，`"NORMAL"` 等历史值安全兼容）；
- `infoFor(level)` —— 结构化 `LevelInfo`（summary / can / cannot / upgradeHint）；
- `promptSection(rawLevel)` —— 系统提示词整段（heading 保留**原始**串以兼容
  既有断言，正文规范化渲染：阶梯行 `NORMAL_SHELL < SHIZUKU < ROOT (yours: X)`、
  CAN/CANNOT 清单、升级路径、「不许盲目重试」护栏）；
- `briefLine(level)` —— 编排线单行简介（低 token 预算路径）。

三级知识要点：

| 级别 | CAN | CANNOT | 升级路径 |
|---|---|---|---|
| ROOT | su 全能、/system、/data、mount、SELinux、iptables | （无） | 已到顶 |
| SHIZUKU | pm / am / settings / dumpsys / input / /sdcard | /system、他应用私有数据、mount、iptables（需 ROOT） | Root（KernelSU/Magisk） |
| NORMAL_SHELL | /sdcard 基础文件、应用沙箱、PRoot Ubuntu | pm / am / settings / input（需 SHIZUKU）；/system 等（需 ROOT） | Shizuku（免 Root，`shizuku.rikka.app`，ADB/无线调试启动） |

### 3.2 capability_report（`CapabilityTools.kt`）

一键自省工具（CORE 集常驻，无参数）。输出四段：

1. **Privilege** —— 与 `PrivilegeLadder` 同源的 CAN/CANNOT/升级路径；
2. **Environment / Tools / Skills / MCP** —— 实时库存（计数经构造 lambda
   注入：`-1` = 未知 → 整行省略，fail-open 不教假事实）；工具行含
   CORE/CATALOG 二分与 `tool_search → tool_open` 指引；
3. **Expansion ladder** —— 五条扩展梯度（同提示词攻略的紧凑版）；
4. 护栏句「Never claim a task is impossible before trying these routes」。

触发场景（工具描述已写入）：不确定能不能做 / 用户问「你能做 X 吗」 /
permission denied 之后 —— 先自省，再行动，不猜不弃。

### 3.3 market_search（`CapabilityTools.kt`）

官方市场检索（CORE 集常驻）。参数：`query`（必填）、`kind`
（`skill|mcp|all`，默认 all）、`limit`（1..10，默认 5）。

- 数据源：`HubSource.listSkills()` / `listMcpServers()`（官方
  `apex-skill-hub` / `apex-mcp-hub` 双仓库 index.json）——**此前这两个目录
  只服务 UI 市场页，agent 完全不可见**；
- 打分：与 `tool_search` 同款朴素评分（全等 > 前缀 > 包含，多词 OR）；
- 返回**可直接执行的安装指令**（Anthropic 工具写作指南：高信号、可操作）：
  - 技能：`skill_install({"source":"url","url":"<raw.githubusercontent.com 直链>"})`
    ——直链由新增的 `HubSource.skillManifestUrl(entry)` 生成；
  - MCP（STDIO）：`mcp_connect({"name":...,"command":...,"args":[...],
    "run_in_sandbox":true})`（hub 条目自带完整配置）；
  - MCP（HTTP/SSE）：`mcp_connect({"name":...,"url":...})`；
- 错误契约：无命中 → 建议放宽关键词或 `skill_create` 自建；市场不可达 →
  `web_search` 兜底提示（可操作错误，非裸 traceback）。

测试不联网（仓库纪律）：目录拉取经构造 lambda 注入固定数据
（`fetchSkills` / `fetchMcpServers`），DI 侧绑 `HubSource` 实例。

### 3.4 Capability Expansion Playbook（`EnginePrompts.kt`）

系统提示词新段（权限段之后、Live Environment 之前；CHAT 模式省略——
零工具纯对话下攻略只会与「建议切换 AGENT 模式」自相矛盾）：

```
1. INSTALLED TOOLS — tool_search → tool_open（已注册工具）
2. LIVE SELF-CHECK — capability_report（权限/环境/库存全貌）
3. SKILLS — market_search / skill_search → skill_install → skill_activate
4. MCP SERVERS — market_search(kind="mcp") → mcp_connect → mcp_list
5. LINUX TOOLCHAIN — terminal.ubuntu.ensure → apt / pip / npm / git
6. PRIVILEGE WALL — 告诉用户开什么（Shizuku / Root）、解锁什么，然后等待
```

护栏：装完必须**验证生效**（skill_list / mcp_list / 重跑）再报成功；
告知用户正在获取什么与为什么；**没爬完 1-5 不许说不可能**。

Tool-Use Policy 第 8 条同步扩展：目录检索无果 → 爬扩展攻略。

### 3.5 OrchestratorPrompts（BUILD 编排线同步）

旧版只有一行裸 `Privilege level: $level`（比引擎线更失明）。现在：

- `PrivilegeLadder.briefLine(level)` + 升级指引（与引擎线同源）；
- 一段紧凑扩展通道提示（tool_search / skill_install / mcp_connect /
  terminal / capability_report + 「先找方法再谈不可能」）。

## 4. 注入与接线

- **CORE 集**：`capability_report` / `market_search` 入 `ToolTierPolicy
  .CORE_TOOL_IDS`（AGENT 模式的编码工具黑名单前缀 `code_` / `github_` /
  `mcp__github__` 均不命中，两工位可见）；
- **DI**（`ToolModule.provideToolRegistry`）：计数 lambda 绑定现成单例
  ——`privilegeInfoProvider.currentLevel()` / `skillRegistry.getInstalled()
  .size` / `mcpManager.getConfigs().size` / `mcpManager.getConnectedServers()
  .size` / `environmentState`；市场检索绑 `HubSource`（MarketplaceModule
  单例）；
- **零新增 Provider 接口**：全部复用既有装配（`privilegeInfoProvider` 本就
  是 `provideToolRegistry` 参数）。

## 5. 行为效果对比

| 场景 | 旧行为 | 新行为 |
|---|---|---|
| 「帮我卸载这个应用」（无 Shizuku） | 盲试 `pm uninstall` → 失败 → 「我做不了」 | prompt 已知 NORMAL_SHELL 无 pm → 直接告知开 Shizuku 的步骤与解锁范围 |
| 「你能下载 YouTube 视频吗」 | 「我无法…」 | 爬梯度：tool_search 无果 → market_search 命中技能 → skill_install(url) → 验证 skill_list → 执行 |
| 「分析这个 CSV」（缺 pandas） | 「没有这个能力」 | market_search / skill 无果 → terminal.ubuntu.ensure → pip install pandas → 跑 |
| 「连一个 PDF 工具」 | 要求用户自己找 MCP | market_search(kind="mcp") → 命中 → mcp_connect(完整配置) → mcp_list 验证 |
| BUILD 编排线长任务 | 全程不知道自己有什么 | 权限简介 + 扩展通道常驻编排器 prompt |

## 6. 测试

- `PrivilegeLadderTest`（tool-registry，9 例）：normalize 折叠 / 三级
  infoFor / heading 原始串兼容 / ROOT 无 CANNOT / briefLine；
- `CapabilityToolsTest`（tool-registry，11 例）：报告四段与未知省略 /
  legacy 剔除二分 / 安装指令完整 URL / STDIO 沙箱与 HTTP 两种连线 /
  kind+limit 过滤 / 无命中与不可达错误 / CORE 集登记；
- `CapabilityPromptTest`（agent-engine，9 例）：攻略六条梯度与护栏 /
  静态注入（默认参数零变化路径）/ CHAT 省略 / 权限段换源兼容 /
  Tool-Use Policy 指向 / 段落顺序 / 编排线同步（有/无 Provider）。

本地验证：`bash scripts/run_core_tests_jvm.sh` → **1425 tests OK**
（基线 1394 + 新增 31）；`check_file_size.sh` / `check_code_quality.sh` /
`kotlin_balance.py` 全绿。
