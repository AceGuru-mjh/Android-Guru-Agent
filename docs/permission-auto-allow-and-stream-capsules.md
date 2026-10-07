# 权限默认自动放行 + 流式卡片胶囊化 — 特性文档

> 2026-10 用户反馈批次：①「每个工具都要确认，点得很麻烦」；②「输出的
> 末尾的结论有时出现在对话最上方」；③「每次调用工具都显示迭代，这个词
> 没有意义」；④「思考时展示全部内容，思考完自动折叠成胶囊；工具使用中
> 详细展示输出，用完折叠成胶囊」。

## 一、权限链重口径（DEFAULT = 基础工具放行）

### 语义变化

| 场景 | 旧行为 | 新行为 |
|---|---|---|
| 只读工具（read_file / web_search…） | 放行（经风险门兜底） | 放行（不变） |
| 普通编辑写（edit_file / code_edit…） | **Ask** | **放行** |
| 幂等覆写（write_file / download_file…） | **Ask** | **放行** |
| 删除类（delete_file / app_uninstall / app_force_stop / terminal.signal…） | Ask | Ask（不变） |
| 敏感动作（sensitiveAction 注解） | Ask | Ask（不变） |
| shell 高危命令（rm / dd / pm uninstall…） | CommandPermissionGate Ask | Ask（不变） |
| 第三方动态工具（mcp__ / plugin，MEDIUM） | Ask | Ask（会话内一次） |
| **全自动模式（BYPASS）** | 工具门放行，**shell 高危命令仍问** | **全链路放行（含命令门短路）** |

### 判定口径（单一事实源）

「删除类」= `destructiveHint && !idempotentHint`（`PermissionDecider.isDeleteLike`）：

- `destructive()` 预设（delete / uninstall / force-stop / signal 族）→ 破坏且
  非幂等 → **删除类**，Ask；
- `idempotentWrite()` 预设（write_file / download_file / app_launch…）→ 破坏
  但幂等（重放收敛、可再次写入恢复）→ **非删除类**，放行；
- `mutating()` 预设（edit_file / ui_tap / clipboard…）→ 非破坏 → 放行。

### 分层改动

1. **PermissionDecider**（app/permission）：`PermissionContext` 增加
   `idempotentHint`；DEFAULT 分支改为 `readOnly → AllowDefault`、
   `isDeleteLike || sensitiveAction → Ask`、其余 → AllowDefault。
2. **PermissionModeGate**：注入四注解（新增 idempotentHint）。
3. **RiskAwareToolGate**（风险门兜底）：撤回 #230 的「MEDIUM 文件改写类
   确认链」，收敛为 HIGH + 第三方动态族（mcp_ / plugin 前缀）——内置
   MEDIUM 全部静默；标题按来源区分「高风险工具 / 第三方工具」。
4. **ToolModule**（命令级）：`shell_execute` 与 `terminal.exec` 的
   CommandPermissionGate 调用点在 BYPASS 模式下短路（实时读设置，切回
   其他模式立即恢复拦截）——「完全不用确认」承诺覆盖全链路。
5. **设置 UI 文案**：BYPASS 更名「全自动」，DEFAULT 标注「默认（推荐）」，
   说明与新语义对齐（zh / en 双语）。

安全边界不变：PLAN 模式零副作用承诺、规则三元组（ALLOW/ASK/DENY 首匹配
可越级）、会话记忆、5 分钟超时 fail-closed、JSONL 审计全部保留。

## 二、Agent 聊天：结论置顶根因修复（尾哨兵）

根因：`scrollToItem(末条内容项)` 把目标项**顶边**对齐视口顶——最终结论
是长气泡时，收尾动画把它的顶部钉在屏幕最上方、尾部在视口外，观感即
「结论出现在对话最上方」。Coding 屏已有同款修复（CodeStreamTimeline 的
1dp 尾哨兵），Agent 聊天屏缺失。

修复：Agent 聊天 LazyColumn 末尾追加 `chat-tail-sentinel`（1dp Spacer），
三处滚动目标（结构变化 / 流式跟随 / FAB 回底）统一改贴哨兵 = 贴真底。

## 三、长对话时序修复（跨迭代叙述落盘）

引擎 `executeBuildLoop` 的 `contentBuilder` 每轮新建：跨轮叙述只活在 UI
`currentResponse`（固定渲染在列表末尾），工具卡全部叠在它上方（时序错
乱），且收尾 `ResponseComplete.fullText` 只含末轮文本——中间叙述被静默
丢弃。

修复（AgentChatEventApplier）：

- `ToolCallStart`：先 flush，再把 `currentResponse` 非空内容落为独立
  Agent 消息（保序在工具卡之前），文本→工具→文本的真实顺序得以保留；
- `ResponseComplete`：以 `currentResponse.ifBlank { fullText }` 落盘
  （涵盖肌肉记忆旁路提示行等引擎未计入 fullText 的内容）；
- `Aborted`：半截回复落为 `isPartial` 消息（与 Error 路径同口径），流式
  气泡不再悬挂、内容不再静默丢失。

## 四、流式卡片胶囊化（运行中展开 / 结束折叠）

| 卡片 | 旧行为 | 新行为 |
|---|---|---|
| Agent 屏 ThinkingBubble | 流式折叠（5 行预览）、完成后展开 | 流式**展开全文**；完成 → **单行胶囊**（新列表项新组合实例，折叠态不渲染正文） |
| Agent 屏 RunningToolCallCard | 运行中默认折叠 | 运行中**默认展开**（时间线/进度/输出流式可见，可手动点收）；完成 → ToolCallCard 小胶囊 |
| Coding 屏 ThinkingCard | 始终折叠 | 流式**展开跟随**；流束收束自动折叠（用户手动点过则尊重手动态） |
| 「第 N 轮迭代」状态行 | 每轮迭代插入一条 | **移除**（CodeStreamSession IterationStart → Unit；轮次统计保留在 Complete 收尾行） |

## 五、验证

- `scripts/kotlin_balance.py`：13 个改动 Kotlin 文件括号平衡通过；
- `scripts/check_file_size.sh` / `check_code_quality.sh`：门禁通过；
- 单测更新：PermissionDeciderTest（新 DEFAULT 决策矩阵 + 删除类口径）、
  CodeStreamSessionTest（迭代状态行移除断言）；CI（app 单测 +
  core:code-engine 测试 + app 编译）远端验证。
