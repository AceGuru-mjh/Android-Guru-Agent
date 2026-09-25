# 内置 MCP 服务器（BUILTIN transport）

> MCP 的常规配置形态是「远端 URL」或「本地命令」，两种都要用户自己准备端点或
> 可执行文件。本项目额外支持第四种传输形态：**BUILTIN（进程内）** —— 由 App 把
> 已有能力包装成一个真 MCP 服务器，用户零配置即可使用。

## 1. 传输形态

| transport | 形态 | 谁提供 |
|-----------|------|--------|
| `HTTP` / `SSE` | 远端端点 POST JSON-RPC | 用户配置 URL |
| `STDIO` | 本地子进程（可跑在 PRoot Ubuntu 沙箱内） | 用户配置 command |
| `BUILTIN` | **进程内 transport**，不 fork、不联网握手 | App 预置 |

`BUILTIN` 的接线（core 不反向依赖 app）：

```kotlin
// app/src/.../di/McpModule.kt
McpManager(
    configDir = File(context.filesDir, "mcp_config"),
    builtinTransports = mapOf(
        BuiltinImMcpServer.ID to { BuiltinImMcpTransport(connectorRegistry, connectorMessenger) },
        ...
    )
)
```

`McpClient.createTransport()` 在 `McpTransport.BUILTIN` 分支调用工厂；
每个 `McpManager.connect()` 构造一个新实例（与 STDIO 每次 fork 语义一致）。
配置条目由 `McpManager.ensureBuiltinServer()` 幂等写入：用户自建同名条目不被
劫持，用户手动禁用后不再自动连接。

## 2. 内置服务器清单

| id | 工具 | 作用 |
|----|------|------|
| `github` | `get_me` `list_repositories` `get_file_contents` `create_or_update_file` `create_issue` `list_issues` `search_code` | GitHub REST 直连（`GithubApiService` 的 MCP 协议化） |
| `search` | `web_search` `web_fetch` | 网络搜索/抓取（DuckDuckGo/Bing 三级回退 + 正文提取） |
| `fs` | `list_directory` `read_file` `write_file` `get_file_info` | 当前激活编码工作区的标准文件接口（三级防路径逃逸） |
| `memory` | entities / relations / observations | 知识图谱记忆（官方 server-memory 语义） |
| `thinking` | `sequential_thinking` | 顺序思考链（per-connection 状态，零持久化） |
| `im` | `im_list_channels` `im_send` `im_verify` | **消息通道推送**：微信 ClawBot/企业微信、飞书、QQ、Telegram（复用市场页配置的连接器，见 [im-connectors.md](im-connectors.md)） |
| `tasks` | `task_add` `task_list` `task_update` `task_done` `task_clear` | **跨会话任务看板**（todo/doing/done，持久化 `filesDir/mcp_tasks/tasks.json`） |
| `http` | `http_request` `http_json` | **HTTP 客户端**：协议白名单 + 响应截断 + 超时封顶的护栏 |

连接成功后，`McpToolRegistrar` 会把工具注册为一等 AgentTool
（`mcp__<server>__<tool>`），Agent 与 Coding 两模式共享。

## 3. 新增一台内置服务器（三件套模式）

以 `tasks` 为例，新增只需一个文件 + 两处登记：

1. **Server 元数据**（`BuiltinXxxMcpServer`）：`ID` 常量 + `config()`
   （`transport = McpTransport.BUILTIN`）。`ID` 同时是 McpManager 配置名、
   `builtinTransports` 的 key、`mcp_call` 的 server 参数、`/mcp:<id>` 的 id——
   四处必须一致。
2. **Transport**（`BuiltinXxxMcpTransport : McpTransportHandle`）：
   - `initialize` → `protocolVersion` + `capabilities.tools` + `serverInfo`；
   - `tools/list` → 工具数组（每个带 JSON Schema `inputSchema`）；
   - `tools/call` → 按名派发；**抛异常一律折叠成 `isError=true` 的 text 结果**，
     而不是让 JSON-RPC 层崩掉（模型看到错误文本后可自行改参数重试）；
   - 通知（`id == null`）返回 null；未知 method 返回 `-32601`。
3. **Bootstrap**（`BuiltinXxxMcpBootstrap`）：`ensureBuiltinServer` + 自持
   IO scope 里 `connect`，失败只记日志不重试。

4. 在 `McpModule.provideMcpManager` 的 `builtinTransports` 里登记工厂，并调用
   `BuiltinXxxMcpBootstrap.ensureAndConnect(manager)`。

## 4. 约定与护栏

- **线程**：`send` 是 suspend，McpClient 已包 `Dispatchers.IO`；纯 CPU/文件操作
  无需再切线程，网络调用请自带 IO 上下文。
- **可预期失败用 isError 文本**：缺凭据、路径越界、参数缺失都属于这一类，
  给模型可操作的引导（如"去市场页配置"），不要抛协议级异常。
- **输出必须有上限**：文件读取限行/限字符、HTTP 响应截断、列表条目封顶——
  大输出会直接撑爆模型上下文。
- **有副作用的服务要可验证**：`im` 提供 `im_verify`，市场页连接器提供
  "发送测试消息"，配置对不对靠真发一条来验收。
