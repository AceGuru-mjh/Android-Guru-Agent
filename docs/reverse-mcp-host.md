# 逆向 MCP Host（Reverse MCP Host）— 设计文档

> Issue #173 核心部分 · v1.4.0 · 分支 `feat/reverse-mcp-host`

## 1. 是什么

手机作为 **MCP Server**：PC 上的 AI 客户端（Cline / Claude Desktop / 其它
支持 streamable HTTP 的 MCP 客户端）通过局域网 HTTP 调用手机上的工具 ——
与项目既有的 MCP 客户端方向（`core/tool-registry/.../mcp/`，HTTP/SSE/STDIO/
BUILTIN 四传输）**互为逆向**。

```
┌─────────────────┐        LAN HTTP (streamable 子集)        ┌──────────────────┐
│   PC AI 客户端   │ ───────────────────────────────────────▶ │   Android 手机    │
│  Cline / Claude │    POST /mcp  + Bearer Token             │  McpHostServer    │
│                 │ ◀─────────────────────────────────────── │  (0.0.0.0:8765)  │
└─────────────────┘    JSON-RPC 2.0 响应                      └────────┬─────────┘
                                                                白名单过滤
                                                                    │
                                                              v3 执行管线
                                                        （环境门→权限门→风险门
                                                          →schema→限流→熔断）
                                                                    │
                                                              ToolRegistry
                                                             (~140 静态工具)
```

## 2. 模块结构

```
platform/mcp-host/                      纯 Kotlin JVM（零第三方依赖）
├── build.gradle.kts                    kotlin.jvm + serialization, jvmToolchain(17)
└── src/main/kotlin/com/apex/agent/platform/mcphost/
    ├── http/HttpCodec.kt               最小 HTTP/1.1 编解码（请求解析/响应写出/Bearer 提取）
    ├── rpc/JsonRpc.kt                  JSON-RPC 2.0 信封（解析/成功/失败/通知）
    ├── McpHostConfig.kt                配置 + 持久化接口（Token/白名单/黑名单/限速）
    ├── McpHostBridge.kt                工具暴露桥（白名单过滤 + 经 v3 管线执行）
    └── McpHostServer.kt                ServerSocket 服务器（会话/审计/限速/鉴权）

app/
├── mcphost/McpHostManager.kt           生命周期 + SharedPreferences 持久化 + 自启
├── di/McpHostModule.kt                 Hilt 绑定（不触碰 McpModule/McpManager）
└── ui/screen/market/McpHostSection.kt  设置区（市场 → MCP 页签首卡）
```

技术选型：`ServerSocket` + 协程 + `kotlinx.serialization`。调研过的开源
AndroidMCP 类项目（维护差、无权限门控）不满足安全要求，故深度自研。

## 3. 协议子集（streamable HTTP, MCP 2024-11-05）

| 客户端动作 | Host 行为 |
|---|---|
| `POST /mcp`（JSON-RPC `initialize`） | 200 + `result{protocolVersion:"2024-11-05", capabilities{tools{listChanged:false}}, serverInfo{...}}`，响应头带 `Mcp-Session-Id` |
| `POST /mcp`（`notifications/initialized` 等通知） | 202 Accepted 空体（通知不回 JSON-RPC 响应） |
| `POST /mcp`（`tools/list`，须带会话头） | 200 + `result{tools:[{name,description,inputSchema}]}`（inputSchema 从 parametersSchema 解析，解析失败回退空对象 schema） |
| `POST /mcp`（`tools/call {name,arguments}`） | 成功 `result{content:[{type:"text",text}],isError:false}`；业务失败 `isError:true`；未知/未暴露工具 → error `-32602` |
| `POST /mcp`（`ping`） | 200 + 空 `result{}` |
| `GET /mcp` | 405（本 Host 无服务器推送流 —— spec 允许） |
| `DELETE /mcp`（带会话头） | 会话已知 → 200 并移除；未知 → 404 |
| 其它路径 / 其它方法 | 404 |

- **每请求一连接**（`Connection: close`）：连接生命周期 == 请求生命周期，
  实现最简单可靠；
- 会话校验：`initialize` 后所有请求须带已知 `Mcp-Session-Id`，否则 404；
- 畸形请求（坏请求行 / chunked / 超限体）→ 400 结构化错误体；
  JSON 解析失败 → `-32700`，信封不合规 → `-32600`，未知方法 → `-32601`。

## 4. 安全模型（三重门）

1. **Token 鉴权**：`Authorization: Bearer <token>`，32 位 SecureRandom
   （~190 bit 熵）；`MessageDigest.isEqual` **常时比较**防时序攻击；
   未配置 token = 拒绝所有请求（fail-closed）。Token 可在 UI 重新生成
   （确认对话框，已连客户端下次请求即失效）。
2. **限速 + 边界**：每远程地址每分钟请求上限（固定窗口，默认 60，超限 429）；
   请求体 ≤1 MiB；头行 ≤16 KiB；并发连接 ≤64（超限 503）；读超时 30s。
3. **白名单 + 权限门**：
   - 分类白名单（默认 `UTILITY/FILE/WEB/SYSTEM/SENSOR/MEMORY/CONTEXT` 只读
     友好集；SHELL/TERMINAL/APP/UI/GITHUB/SECURITY 默认关）；
   - 工具黑名单（`vault_*`、`share_content` 等）；
   - **vault_* 硬拦截写死在 Bridge**（安全红线：金库密钥绝不经外部 AI
     通道出入，配置放开也拦截）；
   - legacy 别名（ToolTierPolicy）不暴露；
   - **tools/list 与 tools/call 用同一判定**，无「列表不含但可直调」漏洞；
   - 外部调用经 v3 执行管线（环境门/权限门/风险门/schema 校验/熔断/限流/
     SecretRedactingExecutor 脱敏）—— 与 Agent 内部调用**同一门控链**，
     MCP Host 不旁路任何工具门控。权限门拒绝（Ask/Deny）时返回明确指引
     （交互式授权在 MCP Host 模式不可用）。

## 5. 连接指南（PC 端）

手机与 PC 同一局域网；应用内「市场 → MCP → MCP Host」开启开关，复制
token。Cline（`cline_mcp_settings.json`）：

```json
{
  "mcpServers": {
    "android-guru": {
      "url": "http://<手机IP>:8765/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Claude Desktop 经 `mcp-remote` 连接器接入同一 URL。UI 的「如何连接」折叠
区会自动填入本机局域网 IP 与当前端口/Token。

## 6. 测试

`platform/mcp-host/src/test/`（79 test，CI 同款 kotlinc 2.0.21 + JUnitCore
实跑全绿）：

- `HttpCodecTest`（24）：标准/多头/体边界/畸形/chunked 拒绝/Bearer/响应写出；
- `JsonRpcTest`（19）：id/notification/params 缺失/信封构造/错误码；
- `McpHostBridgeTest`（14）：分类过滤/黑名单/legacy/vault 硬拦截/转发/isError/
  权限门拒绝语义；
- `McpHostServerTest`（22）：**真实端口集成**——initialize/202/401/404/405/
  tools/call 加法工具/DELETE 终结/限速 429/stop 后连接拒绝/审计断言。

## 7. 局限性（v1 如实声明）

- **App 存活期可用**：无前台服务，应用进程被杀即服务终止（下版本可加
  Foreground Service）；
- **无 SSE 推送流**：`GET /mcp` 一律 405，服务端无法主动推送（progress/
  通知）—— 单请求-响应模型；
- **每请求一连接**：无 keep-alive，高频调用有 TCP 握手开销；
- **HTTP 明文**：局域网内传输（家庭/办公网可信前提下可接受；token 泄露
  风险与 HTTPS 缺失的中间人风险并存，公网/不可信网络请勿开启）；
- initialize 响应固定 `2024-11-05` 协议版本（不协商降级）。

## 8. 冲突面（与并行工作流）

- **不改** `McpModule.kt` / `McpManager.kt` / `McpClient.kt` /
  `MarketDialogs.kt` 的 AddMcpDialog；
- runInSandbox UI 已由 PR #175 完成，本 PR 不做；
- app 侧新增文件 + `MarketBrowseTabs.kt` 单点插卡 + `ApexApp.kt` 加一个
  `@Inject` 字段 + `app/build.gradle.kts` 加一行模块依赖 —— 冲突面最小。
