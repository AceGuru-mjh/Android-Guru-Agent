# 市场 · MCP Registry 目录（官方 Registry + PulseMCP + Operit 社区插件）

> 特性文档 —— 把市场 MCP 货架从「官方精选 8 台 + mcp.so 长尾」升级为
> 「官方 Registry 9000+ 结构化目录 + PulseMCP 同规格源 + Operit 社区
> 聚合」，并把 npm 形态的安装从「只落 JSON 配置」升级为「沙箱真实
> `npm install -g` 预装 + 失败回滚」的完整流水线。

## 背景

市场此前只有两个 MCP 目录源：

| 源 | 规模 | 形态 |
| --- | --- | --- |
| 官方 Hub（apex-mcp-hub） | 8 台精选 | index.json 内联配置（raw.githubusercontent.com） |
| mcp.so | 1.5 万+（HTML 抓取） | 无公开 JSON API，正则解析卡片 + 详情页 mcpServers |

缺口：**官方 MCP Registry**（registry.modelcontextprotocol.io，9000+
结构化 server.json 元数据）没有接入 —— 它是最大的结构化目录，且
`packages[].identifier` 直接告诉安装器怎么装（npm 包名）、`remotes[].url`
给出免沙箱直连端点。其次，npm 形态的安装一直是「只写 mcp_servers.json
配置」，首次启动靠 `npx -y` 冷启动，移动网络下撞 180s 握手超时
（Issue #163 放宽后仍然不够）。

## 新增源

### 1. 官方 MCP Registry（registry.modelcontextprotocol.io）

- **端点**：`GET /v0/servers?cursor=&limit=&search=&version=latest`，
  无认证公开只读；cursor 分页 + 服务端搜索；`version=latest` 恒定开启
  （否则同一服务器按版本重复出现）；
- **响应形状**：server 元数据嵌套在 `servers[].server`，字段 camelCase
  （`registryType` / `identifier` / `runtimeHint` / `environmentVariables`）；
- **实现**：`core:tool-registry` 的 `OfficialRegistrySource`（thin 包装）→
  `GenericRegistryApi`（共享客户端，见下）。

### 2. PulseMCP Sub-Registry（api.pulsemcp.com）

- PulseMCP 实现与官方 Registry **同一份 Generic Registry API 规格**
  （server.json 直连管道 + 访问量等富化元数据），差异只有两点：
  端点为 `/v0.1`，且是**合伙制认证**（每请求需 `X-API-Key` +
  `X-Tenant-ID`，无凭据 401）；
- **凭据**：市场 MCP 页签的 Pulse 源有「凭据」入口，弹窗填入后经
  `PulseMcpCredentialsStore`（EncryptedSharedPreferences，模式同
  GithubTokenManager）加密落盘，保存即切源加载；未配置时源返回带指引
  的 failure，不发注定 401 的请求；
- **实现**：`PulseMcpSource`（同 thin 包装，凭据经 provider 注入 ——
  保存/更换后即时生效，与 ModelScope 的 token 模式同构）。

### 3. Operit 社区插件（GitHub 聚合）

- **数据源**：GitHub Search API 双查询合并（`topic:operit-plugin` +
  `operit mcp`），按 star 降序去重，上限 40 条；GitHub token 可选注入
  提配额；单查询失败（匿名限流 10 次/分）时目录**降级返回**：列表照给，
  `partialError` 进错误横幅提示可重试，双失败才整体 failure；
- **安装语义（诚实探测，不装残配置）**：点击「探测安装」时拉仓库根的
  `mcp_config.json`（Operit 生态约定文件名）→ `mcp.json`（标准
  mcpServers 外壳，或裸服务器对象自动包壳）→ `package.json` 的
  `mcpServers` 键 → `McpConfigImport` 统一解析 → STDIO 条目自动路由
  PRoot 沙箱；默认分支解析失败时回退 `HEAD` 符号引用（GitHub raw
  恒解析默认分支，master/main 通吃）；未暴露标准 MCP 配置的仓库
  （ToolPkg / JS 脚本形态）得到明确报错 + 「浏览器打开仓库」指引；
- **实现**：`core:tool-registry` 的 `OperitPluginSource`（纯解析函数
  public 可单测：`parseOperitSearchItems` / `parseMcpConfigText` /
  `packageJsonMcpServersText` / `mergeDirectory`）。

## GenericRegistryApi（共享客户端）

官方 Registry 与 PulseMCP 同规格 → 一个客户端两个实例：

```
GenericRegistryApi(httpClient, baseUrl, extraHeadersProvider)
├── OfficialRegistrySource → https://registry.modelcontextprotocol.io/v0（无认证）
└── PulseMcpSource         → https://api.pulsemcp.com/v0.1（X-API-Key/X-Tenant-ID）
```

纯 JVM（OkHttp + kotlinx.serialization）、`withContext(Dispatchers.IO)`、
响应体 2MB 上限、任何失败 `Result.failure` 不抛异常 —— 错误契约与
HubSource / McpSoSource / ClawHubSource / ModelScopeSource 一致。

### 数据模型（RegistryServer）

| 字段 | 来源 | 用途 |
| --- | --- | --- |
| `name` | server.name（reverse-DNS 全名） | 去重键 / 展示 |
| `displayName` | title ?: name 末段 | 配置名（`configName`） |
| `remotes[]` | server.remotes | 远端端点（type=streamable-http / sse，headers 模板保留） |
| `packages[]` | server.packages | 发行包（registryType / identifier / runtimeHint / envVars） |

### 安装决策树（installKind，单源实现）

```
remotes[] 有端点 ──────────→ REMOTE（streamable-http 优先于 sse；
                              未知/缺失 type 的端点不入 remotes，
                              留给 packages[] 回退 npm 决策）
                              → 直装 HTTP/SSE 配置，无沙箱依赖
                              → headers 占位符（如 Bearer {key}）原样保留，
                                成功文案点名「到编辑里补真值」

packages[] 有 npm+stdio 包 ─→ NPM
                              → ① identifier 包名形态校验（供应链硬化：
                                拒绝 URL / git spec / 前导 - 等任意源拉取）
                              → ② rootfs 门禁（未装引导 terminal.ubuntu.install；
                                版本标记指向缺失目录 → 已损坏请重装）
                              → ③ 沙箱真实 npm install -g {identifier}（5 分钟超时）
                              → ④ 写 npx -y [runtimeArguments…] {identifier}
                                沙箱 STDIO 配置（enabled=false；
                                runtimeArguments 与内置 -y 去重）
                              → ⑤ 配置写入失败 → 回滚 npm uninstall -g
                                （回滚结果如实报告：未确认时给手动清理指引）
                              → 必填环境变量与 packageArguments 在成功文案里点名

其余（pypi/mcpb/oci/nuget/  → UNSUPPORTED：明确报错 + 仓库链接，
非 stdio npm 包）              引导按 README 手动接入，不装残配置
```

**为什么远端优先**：零环境依赖（无 rootfs、无 node），连接即用。

**为什么 npm 只认 stdio 形态**：非 stdio 的 npm 包（transport.url 带
`{port}` 占位符）需要本地起进程再走 HTTP，端口编排超出 v1 范围。

## 沙箱真实下载（ProotSandboxCommandRunner）

市场安装链路的「真实下载」执行通道，与 `ProotGitCommandRunner` 同款
五步模板（rootfs 门禁 → current 标记解析 → proot 宿主前置 → argv 组装
→ 有界执行），差异：

- **无工作区概念**：cwd 恒为 guest `/root`（与 ProotMcpProcessLauncher
  一致），npm 全局目录落在持久化 home（`<filesDir>/linux/home`），与终端
  会话 / MCP launcher 共享 npx 缓存；
- **argv 手工内联**：libproot + `-r` + `-0` + `--kill-on-exit`（能力门）+
  binds（home + /proc /dev /sys + /sdcard）+ `-w /root` + env trampoline
  （PATH 用 LinuxEnvironmentManager.GUEST_PATH 单源）+ 命令；
- **有界执行**：stdout/stderr 各 512K（首 256K + 尾 256K 滚动）；50ms
  轮询等待（协程取消在半个间隔内响应，杀进程后原样上抛）；deadline 用
  `System.nanoTime()` 单调钟（分钟级安装不受墙钟跳变影响）；超时/取消
  路径先关三路流 fd 再 destroyForcibly（SIGKILL 下 proot 的
  --kill-on-exit 清理不保证生效：关 stdin 让等输入的 guest 子进程退出，
  关 stdout/stderr 解开 drain 线程的阻塞 read，防线程泄漏）；
- **rootfs 解析分流**：版本标记指向的目录缺失（升级中断/文件被清）→
  「已损坏请重装」文案，与「未安装」的安装引导分流（与 git runner 的
  RootfsResolution 同款）。

**安装时序（npm 形态）**：

```
MarketRegistryController.installServer(entry)
  → RegistryMcpInstaller.install(entry)
      installKind == NPM:
        1. sandboxRunner.run(["npm", "install", "-g", identifier], 300_000)
           ↑ 真实下载：绕开 npx 冷启动撞 180s 握手超时的问题（Issue #163）
        2. mcpManager.addServer(config)   ← enabled=false，安装 ≠ 启动
        3. addServer 失败 → sandboxRunner.run(["npm", "uninstall", "-g", ...])
           ↑ 回滚：不留「沙箱里装了包但配置没落」的半残状态；
             回滚结果如实报告（未确认时给手动清理指引，不谎称已卸载）
```

## 市场集成

- **控制器**：`MarketRegistryController`（官方/Pulse 源切换 + cursor 分页
  + 服务端搜索 + 行级 busy 安装）与 `MarketOperitController`（目录 +
  探测安装）—— 从 MarketViewModel 拆出的独立状态域（与
  MarketMcpSoController 同构，God-file 预算纪律）；两者安装完成经
  `refreshMarket` 通知宿主 VM 刷新快照（已装徽标 / 已配置列表联动 ——
  吸取 PR #292 修复的「tryEmit 零收集者」教训，接线即订阅）；
  控制器错误契约闭环：所有协程体 catch Exception 折叠进 error 文案
  （CancellationException 原样上抛保取消语义），逃逸异常不崩 App；
- **在途请求管理**：Registry 目录请求有代际（generation）+ Job 取消 ——
  源切换 / 搜索 / 重试 / 回目录都会取消在途旧请求并作废其响应（迟到
  响应不落进新源/新模式的 UI 状态）；追加加载不抢占在途请求；搜索
  模式的翻页/重试用已提交查询词（activeSearchQuery），不吃输入框实时值；
- **UI**：MCP 页签新区块顺序 = 官方 MCP 仓库 → **MCP Registry 目录** →
  mcp.so → **Operit 社区插件** → 已配置服务器；Registry 区块头部有源
  切换 Chip + 搜索框 + Pulse 凭据入口（X-API-Key 密钥框默认遮罩，
  尾部图标切换明文），行卡带安装形态徽标（远端直装 /
  npm·沙箱预装 / 需手动），npm 安装期间显示「沙箱内真实安装（最长约
  5 分钟）」提示；
- **DI**：MarketplaceModule 新增 OfficialRegistrySource / PulseMcpSource /
  OperitPluginSource / ProotSandboxCommandRunner 四个 provider
  （RegistryMcpInstaller 与两个控制器走 @Inject 构造自动解析）。

## 设计取舍记录

| 决策 | 理由 |
| --- | --- |
| enabled 恒 false | 安装 ≠ 启动，与官方 Hub / mcp.so 口径一致；连接在「已配置列表」统一操作 |
| 远端 headers 占位符原样保留 | 占位符值（`Bearer {smithery_api_key}`）无从代填；连接 401 的报错 + 成功文案点名「到编辑里补真值」比静默丢头诚实 |
| remotes 白名单 streamable-http/sse | 未知类型的端点不静默产出大概率打不开的 HTTP 配置——跳过后留给 packages[] 回退 npm 决策 |
| runtimeArguments 自动拼 / packageArguments 只点名 | npx 旗标（-y 等）可安全自动拼（与内置 -y 去重）；包体参数（--allowed-directories 等）需要用户补值，不猜测——避免装出「启动即缺参报错」的配置，装后到「编辑」补 |
| npm identifier 包名形态校验 | 供应链硬化：官方源有校验，但 Pulse 源是合作方提交的数据——URL / git spec / 前导 - 会让 npm 从任意源拉取执行代码 |
| 必填环境变量不弹表单 | v1 先在成功文案点名（「需补齐：GCS_BUCKET、…」），用户到「已安装管理 → 编辑」补；后续可复用 #205 目录的 env 弹窗模式 |
| 不做跨源列表合并 | 各源是独立区块（仓库既有 UX），同一条目在 Registry 与 mcp.so 都出现时各自展示；区块内按 name 去重已由解析层保证 |
| pypi/mcpb/oci/nuget 不装 | 沙箱内无 pip/docker 运行时保证；明确报错优于装残配置。MCPB 的 fileSha256 已解析保留，后续支持时可用 |
| Pulse 无凭据不发请求 | 401 是确定结局；提前返回带申请指引的文案（pulsemcp.com） |
| 请求头构造在 try 内 | OkHttp 对非法请求头值（换行/非 Latin-1）抛 IAE——凭据是用户手输，不能因非法字符逃出 Result 错误契约崩到控制器协程 |

## 验证

- core 模块：`scripts/compile_core_jvm.sh` 编译通过（1394 classes）；
  `scripts/run_core_tests_jvm.sh` 全量 1393 个测试通过（含
  `GenericRegistryApiTest` 18 例 / `OperitPluginSourceTest` 11 例，夹具
  取自真实 API 响应切片；覆盖 runtimeArguments 去重、packageArguments
  不自动拼、remotes 白名单回退、isRequired 字符串宽容、双查询合并
  降级提示等行为）；
- 结构门禁：`kotlin_balance.py` / `check_file_size.sh` /
  `check_code_quality.sh` / `check_string_mirror.py`（中英 1932 键 1:1）
  全部通过；
- app 层：`:app:compileDebugKotlin` 由 CI 验证（本地无 Android SDK）；
  真机回归项：MCP 页签三新区块的加载/搜索/翻页/安装、Pulse 凭据保存
  后切源、npm 形态在 rootfs 就绪设备上的端到端安装（沙箱 npm 输出在
  logcat 过滤 ProotSandbox）。

## 已知限制

1. Operit 的 ToolPkg / JS 脚本插件（非 MCP 形态）不能装 —— 它们是
   Operit 宿主 API 的脚本（需要 polyfill 层），本 PR 只接天然兼容的
   MCP 插件子集；
2. PulseMCP 凭据是租户级 —— 换凭据后旧目录可能残留，切源会清空重拉；
3. PulseMCP 凭据的首次加密存储初始化（EncryptedSharedPreferences
   MasterKey）发生在主线程 —— 与 GithubTokenManager 同款取舍，低端机
   首开市场屏可感知（后续可挪到后台读优化）。
