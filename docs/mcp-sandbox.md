# MCP 沙箱（PRoot Ubuntu）使用指南

> Issue #163：`runInSandbox` 启用路径打通 + 优质开源 MCP 服务器预置。
> 前置实现见 Issue #149（`ProotMcpProcessLauncher` 执行器）。

## 什么是沙箱 MCP

MCP 官方生态里绝大多数 server 是**本地命令**（`command` + `args`，如
`npx -y @modelcontextprotocol/server-filesystem /tmp`），通过 stdin/stdout 的
换行分隔 JSON-RPC 通信 —— 并不需要一台真正的服务器。

问题：Android 的 app 进程里没有 node/npx/python。**沙箱 MCP** 就是把这条
命令放进 App 内嵌的 PRoot Ubuntu rootfs 里执行（沙箱内已装 nodejs/npm），
宿主与沙箱进程之间仍然走同样的 stdio 管道 —— 上层协议零变化。

对应配置字段：`McpServerConfig.runInSandbox`（默认 `false` = 宿主直接 fork，
桌面 JVM 行为不变；`@Serializable` 默认值保证旧配置向后兼容）。

## 三个启用入口

| 入口 | 用法 |
| --- | --- |
| 市场页添加对话框 | 「市场 · MCP · 添加工具源」选**本地命令**形态，出现「在 PRoot 沙箱中运行」开关。rootfs 就绪时默认开启；未就绪时开关禁用并提示「需先安装 Ubuntu 环境」 |
| Agent 工具 `mcp_connect` | 参数加 `"run_in_sandbox": true`（可选，默认 false）。Android 上 STDIO 建议开启 —— 宿主没有 npx/node |
| 批量导入配置 | 社区通用 `{"mcpServers": {...}}` JSON 的条目里写 `"runInSandbox": true`（严格 Boolean，缺失落 false） |

## 预置服务器清单

App 启动时**幂等预置**以下条目（`enabled=false`，只预置不连接，在
「已安装 · MCP」里手动启用）：

| 名称 | 命令 | 说明 |
| --- | --- | --- |
| `fs-sandbox` | `npx -y @modelcontextprotocol/server-filesystem /workspace` | 沙箱内文件读写（作用域 `/workspace`） |
| `memory-sandbox` | `npx -y @modelcontextprotocol/server-memory` | 官方知识图谱记忆 |
| `everything-sandbox` | `npx -y @modelcontextprotocol/server-everything` | 官方测试服务器（覆盖工具/资源/提示词全部能力面，验证沙箱链路用） |

三台均为官方 reference servers：node 纯 JS 实现、arm64 兼容（无原生模块）。
> 注：原计划预置 git 服务器，但 `@modelcontextprotocol/server-git` 已从
> npm 下架（registry 返回 Not Found），换成同厂官方 `server-memory`。

预置语义（与内置 BUILTIN 预置一致）：
- 同名条目已被你自建为**宿主**条目（`runInSandbox=false`）→ 绝不覆盖；
- 已是沙箱条目 → 升级后按新定义刷新 command/args，但**保留你的 enabled 偏好**。

## rootfs 前置条件

1. 终端页安装 Ubuntu（`terminal.ubuntu.install`），或设置里初始化；
2. 沙箱内需有 nodejs/npm：完整 rootfs（`scripts/build_full_rootfs.sh` 构建，
   已预装 nodejs/npm 并 **npm 全局预装上述三个 server**）开箱即用；最小
   rootfs 需手动 `apt install -y nodejs npm`。

未就绪就启用/连接不会崩：连接失败时 `ProotMcpProcessLauncher` 给出引导性
报错（提示先装 Ubuntu + 装 nodejs）。

## npx 冷启动与超时

`npx -y <pkg>` 首次运行要**在线下载包**（拉 tarball → 装依赖 → 起进程），
移动网络下可能远超 1 分钟。因此沙箱连接的握手/请求超时单独放宽：

- 宿主 STDIO / HTTP / SSE：60s（不变）；
- 沙箱 STDIO（`runInSandbox=true`）：**180s**（`McpManager.SANDBOX_REQUEST_TIMEOUT_MS`）。

完整 rootfs 已预装三个 server，冷启动基本消掉；自建条目首次连接仍可能慢，
属正常现象，二次连接走 npx 缓存。

## 与内置（BUILTIN）服务器的区别

| | BUILTIN | 沙箱 STDIO |
| --- | --- | --- |
| 形态 | 进程内 transport（App 代码直接应答 JSON-RPC） | rootfs 内真实子进程 |
| 依赖 | 无（随 App 分发） | Ubuntu rootfs + nodejs |
| 生态 | 仅 App 预置的几台（github/search/fs/memory/thinking） | 任何 npm 上的 MCP server |
| 用户可建 | 否（工厂未注册会报错） | 是（对话框/导入/mcp_connect 三入口） |
| 预置方式 | `ensureBuiltinServer`（启动即自动连接） | `ensureSandboxServer`（只预置，手动启用） |

一句话：BUILTIN 是「App 能力的 MCP 协议化」，沙箱 STDIO 是「整个 npm
MCP 生态的接入通道」。

## 常见问题

- **`fs-sandbox` 连接报错说目录不存在**：`server-filesystem` 要求作用域目录
  存在。完整 rootfs 已带 `/workspace`；旧 rootfs 可在条目里把 args 的
  `/workspace` 改成 `/root`（沙箱 home，必然存在）。
- **想给沙箱 server 配 API Key**：条目的 env 字段（对话框「环境变量」每行
  `KEY=VALUE`）会经 proot `-E` 传入沙箱。
- **默认开启就够吗**：开关默认值跟随 rootfs 就绪状态（就绪即默认开）；就绪
  前添加的宿主条目不会被自动改写 —— 沙箱化是显式选择。
