# Tool Dedup Map — 工具重复能力映射与合并决策（#171 / #172）

> Tool System v5 治理文档。回答三个问题：哪些工具重复了？为什么保留/合并？
> 合并后旧 id 去了哪里？

## 1. 四族合并（v5 完成）

碎片化的 UTILITY 工具按「一族一工具多操作」合并（`op` 参数区分操作）。
旧工具类**保留注册**（历史会话兼容），但 id 移入
`ToolTierPolicy.LEGACY_ALIAS_IDS` —— 永不进入 LLM 请求，也从
`tool_search` 结果中隐藏。

| 新工具 | 合并自 | 覆盖操作 | 层级 |
|---|---|---|---|
| `time` | `datetime` + `get_time` + `cron_next` + `duration_convert` | now / format / parse / convert_tz / duration / cron_next | CORE |
| `random` | `uuid_generate` + `random_generate` | uuid_v4 / uuid_v7 / int / float / string / pick | CATALOG |
| `regex` | `regex_extract` + `regex_replace` | test / extract / replace / match_all / split | CATALOG |
| `json` | `json_path` + `json_transform` | query / transform(七操作) / validate / format | CATALOG |

**合并原则**：
- 操作全集覆盖旧工具全部入参语义（先读旧实现再端口，行为对齐）；
- 输出格式对齐新约定（错误以 `Error:` 开头，可被模型自修复）；
- 新 id 更短、语义更聚合，schema 反而变小（一个 schema 服务多个操作）。

## 2. 同源多通道（保留，场景分工明确）

以下重复是**架构性的**（不同沙箱根 / 不同消费方），各有真实场景，保留但在此登记分工：

### 2.1 读/写文件 ×4

| 工具 | 沙箱根 | 消费方 |
|---|---|---|
| `read_file` / `write_file` / `edit_file` | Agent 默认工作区 | Agent 模式 |
| `code_read` / `code_write` / `code_edit` | Code 工作区（`CodeWorkspaceRoots` 动态解析，可多根） | Coding 模式 |
| `mcp__fs__*` | 当前激活编码工作区（MCP 语义，路径三级防线） | MCP 客户端生态 / 外部 MCP 消费方 |
| `terminal.fs` | PRoot Ubuntu guest rootfs | 终端会话编排 |

### 2.2 Shell 执行 ×3

| 工具 | 语义 | 适用 |
|---|---|---|
| `terminal.exec` | 一次性结构化执行（stdout/stderr/exit_code 分离） | **默认入口**（CORE） |
| `shell_execute` | 流式一次性执行（Root > Shizuku > app-shell 通道） | 需要提权通道时 |
| `terminal.run` | PTY 会话内非阻塞执行（jobId + observe/wait） | 多步骤 / 长命令 / 交互命令 |

### 2.3 Web 搜索 ×2

- `web_search` / `web_fetch`：原生工具（CORE）。
- `mcp__search__web_search` / `web_fetch`：内置 search MCP 的进程内转发
  （同一 WebSearchTool 实例）——为 MCP 生态兼容保留（Coding 模式 MCP 客户端
  及外部客户端可发现），Agent 提示词不主动引导走此通道。

### 2.4 GitHub ×2

- `github_*`（9 个）：原生工具（读仓库 / issue / 分支 / 代码搜索）。
- `mcp__github__*`（7 个）：内置 github MCP（`BuiltinGithubMcpTransport`），
  同一 `GithubApiService`。保留理由同上：MCP 生态兼容 + 外部 MCP 客户端。

## 3. 上下文回顾（新增，#172）

长会话被压缩后模型会「失忆」。新增会话内回顾三件套（读
`ConversationMemory` 单例，即引擎的真实上下文镜像）：

| 工具 | 功能 | 层级 |
|---|---|---|
| `context_recap` | 结构化摘要：用户目标 / 已执行动作统计 / 错误摘要 / 最近结论 | CORE |
| `context_search` | 全文检索会话消息与工具输出（命中片段 + 定位） | CATALOG |
| `session_stats` | 消息分布 / token 估算 / 工具调用直方图 / 错误率 | CATALOG |

分类：新 `ToolCategory.CONTEXT`（上下文回顾，排序 62，紧随 MEMORY 60）。

与 CS-Mem（`memory_*` 三件套）的分工：CS-Mem 是**跨会话**记忆（经验/宏/情景），
`context_*` 是**本会话**回顾（压缩自救）。两者互补，不重复。

## 4. 高级设备工具（新增，#172）

`tts_speak`（朗读）/ `torch`（手电筒）/ `vibrate`（振动）/ `share_content`
（系统分享）/ `deep_link`（深链拉起）/ `network_info`（网络诊断）/
`battery_status`（电池）/ `image_info`（图片信息）/ `image_convert`
（缩放/旋转/转格式）——全部 CATALOG 层（渐进披露），不增加常驻 schema 体积。

## 5. CORE 集变更摘要

- 移除：`get_time`（并入 `time`）
- 新增：`time`、`context_recap`
- 不变：其余 45+ 常驻工具

## 6. 合并后规模

- UTILITY 类工具数：20 → 12（四族 10 个旧 id 退出请求层）
- 常驻 schema 体积：净减少（`time` 单 schema < `datetime`+`get_time` 双 schema）
- 工具总数（注册表）：140 → 153（新增合并 4 + 上下文 3 + 高级 9，旧 10 个转 legacy）
