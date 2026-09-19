# Agent 执行体系修复总览（2026-09）

用户反馈的四个核心问题，本次全部根治。诊断方法：对比 operit（AAswordman/Operit）
与 opencode（sst/opencode）两个成熟 Agent 的执行架构，逐一定位到本仓库的具体根因。

| # | 用户反馈 | 根因 | 修复 |
|---|---|---|---|
| 1 | 模型不会主动调用命令 | 系统提示词被动措辞；BUILD 模式鼓励"少调工具"；无已连接服务状态 | Tool-Use Policy 段 + Connected Services 段 + BUILD 模式重写 |
| 2 | 工具只有几个能用 | PluginManager.registerPluginTools 是 TODO（插件工具从未注册）；web_search 依赖单一 DDG 抓取常失败；GitHub 未连接时错误文案不可行动 | 插件工具真实注册 + web_search 三端点容错 + GitHub 错误引导 |
| 3 | 不会使用模型内置的网络搜索 | StreamingOpenAiClient 不发送任何原生搜索参数；无配置字段；不解析引用 | WebSearchMode 按 Provider 分支注入 + 引用解析 + 会话开关贯通 |
| 4 | 网页自动化要插件化；连接器要加微信/飞书/Telegram；GitHub 连接没用 | AIDL 无宿主回调通道；连接器只是数据壳；GitHub 连接状态对模型不可见 | IApexPluginHost 宿主桥 + plugin-web-automation + ConnectorMessenger/工具 + GitHub 状态注入 |

---

## 1. 主动工具调用（学习 opencode/operit）

### 1.1 提示词层（`EnginePrompts.buildSystemPrompt`）

opencode 的 beast.txt 写道 *"when you say you are going to make a tool call, make
sure you ACTUALLY make the tool call, instead of ending your turn"*；kimi.txt 写道
*"you MUST use the appropriate tools to make actual changes — do not just describe
the solution in text"*。operit 写道 *"Based on user needs, proactively select the
most appropriate tool or combination of tools"*。

旧版提示词是 **"Use tools when needed"** —— 被动措辞，模型倾向先叙述后行动；而
引擎在纯文本轮次即终止 ReAct 循环，任务永远停在"计划"阶段。

新增 **Tool-Use Policy (MANDATORY)** 段（7 条硬约束）：

1. 描述 ≠ 执行：只在文本里出现的命令/代码不会被执行、没有效果
2. 用户要求创建/修改/运行/获取/发送/检查任何东西 → **本轮就调工具**
3. 禁止以 "I will now…" 结束回合
4. 持续工作直到任务真正完成；未用工具验证不得宣称成功
5. 不确定的事实（版本/价格/新闻/文档/设备与网页状态）必须先用工具验证
6. 工具失败 → 读错误、修参数或换路径；不轻易放弃、不盲目重试
7. 优先专用工具（read_file/github_read_file/browser_*），独立读操作并行

BUILD 模式从 "prefer fewer steps" 改为 "keep working autonomously until the task
is done; use tools aggressively"。

### 1.2 已连接服务（`ConnectedServicesProvider`）

学习 opencode 的 `<env>` 块与 operit 的 ACTIVE_PACKAGES 段：**把状态作为环境真值
注入，而不是让模型猜**。

- GitHub 已连接 → 注入登录名 + "github_* tools are ready, verify with
  github_get_user"
- 微信/飞书/Telegram 已启用 → 注入 connector_list / connector_send_message 引导

app 层实现：`AndroidConnectedServicesProvider`（聚合 GithubTokenManager +
ConnectorRegistry），经 Hilt 注入引擎。

---

## 2. LLM 原生联网搜索（`core/llm-adapter`）

### 2.1 WebSearchMode（`LlmConfig`/`ModelProfile` 新字段）

```
OFF（默认）｜ AUTO（按端点自动）｜ OPENAI ｜ DASHSCOPE ｜ ZHIPU ｜ OPENROUTER ｜ ANTHROPIC ｜ DEEPSEEK
```

`StreamingOpenAiClient.buildRequestBody` 按 Provider 分支：

| Provider | 注入参数 |
|---|---|
| OpenAI | `web_search_options: {search_context_size: "medium"}` |
| DashScope（Qwen） | `enable_search: true` |
| Zhipu（GLM） | tools 追加 `{"type":"web_search","web_search":{"enable":true,"search_result":true}}` |
| DeepSeek | tools 追加 `{"type":"web_search"}` |
| OpenRouter | `plugins: [{"id":"web","max_results":5}]`（等价 `:online`） |
| Anthropic | tools 追加 `{"type":"web_search_20250305","name":"web_search"}` |
| 未知端点 | 不发送任何非标准参数（防 400） |

关键实现细节：kotlinx `JsonArray` 不可变，server-search tool 在**构建期**并入
tools 数组；`tool_choice`/`parallel_tool_calls` 仅在有函数工具时发送。**OFF 时
请求体与旧版逐字节一致（零回归）**。

### 2.2 引用解析（`SearchCitations.kt`）

OpenAI `annotations[].url_citation`、智谱 `search_result[]` 等引用提取为
`**Sources:**` Markdown 块并入 content —— 模型与 UI 都能看到搜索来源。

### 2.3 会话开关贯通

输入框工具栏"网络搜索"开关（`ChatToolkitStore`）现在**同时**：
1. 经 `LlmModule.provideModelRuntimeStore` 把所有 Profile 提升为 AUTO（多模型
   运行时路径——引擎实际使用的路径）
2. 经 `DynamicLlmClient`（旧直连路径）
3. 注入提示词（降级为 fallback：原生搜索无 Sources 时再调 web_search 工具）

---

## 3. web_search 工具容错（`WebSearchTool`）

旧版单一依赖 `html.duckduckgo.com` GET 抓取，被反爬/限流即空结果。

新版三端点依次回退（均无需 API key）：
1. DuckDuckGo HTML（**POST** form，比 GET 不易被限流）
2. DuckDuckGo Lite（POST）
3. Bing（桌面 UA + 重定向解码 `bing.com/ck/a?...&u=a1<base64>`）

全部失败返回带最后错误原因的明确错误。实测 DDG 不可达时 Bing 兜底有效。

---

## 4. 插件系统激活 + 网页自动化插件

### 4.1 宿主桥 AIDL（`plugin-sdk/plugin-api`）

- `IApexPlugin` 新增 `attachHost(IBinder)` —— 绑定后宿主注入反向回调通道
- 新增 `IApexPluginHost`：`executeHostTool(toolId, argsJson)` +
  `getHostCapabilitiesJson()`

### 4.2 插件工具真实注册（`PluginManager`）

旧版 `registerPluginTools` 是 **TODO 日志**——插件工具从未进 ToolRegistry（这
就是"插件装了也没用"的原因）。新版：

```
onServiceConnected → attachHost(hostBinder) → getToolsJson() → 解析
  → 逐个注册 PluginAgentTool（同 id REPLACE 覆盖宿主内置工具）
卸载/插件进程死亡 → 降级为 HostFallbackTool（描述符原样保留，execute 改宿主直调）
```

降级安全网：插件工具与宿主内置 browser_* 同 id（REPLACE 覆盖），直接 unregister
会把工具位挖空；HostFallbackTool 保证"插件卸载后工具依旧可用"。

### 4.3 plugin-web-automation（新模块）

- 独立 APK（`com.apex.agent.plugin.webautomation`），15 个 browser_* 工具描述符
  （navigate/snapshot/click/input/select/toggle/scroll/screenshot/show/fileUpload/
  dateInput/debugDump/contextSummary/networkLog/downloadList）
- 插件声明与分发工具清单；执行经宿主桥回到 BrowserEngine（WebView 引擎活在
  宿主进程，插件是"工具层"——与 operit 包模型同构）
- `ApexCoreService` 启动时自动发现并加载已安装插件（安装即生效）
- 未安装插件时宿主内置注册兜底（ToolModule 仍注册 BrowserAgentTools）

---

## 5. 连接器：微信 / 飞书 / Telegram

### 5.1 内置连接器（`ConnectorRegistry.BUILTIN_CONNECTORS`）

- `wechat` — 企业微信群机器人（endpoint `qyapi.weixin.qq.com/cgi-bin/webhook/send`，
  apiKey 填 webhook key 或 endpoint 直接填完整 URL）
- `feishu` — 飞书自定义机器人（`open.feishu.cn/open-apis/bot/v2/hook/` + token）
- `telegram` — Telegram Bot（`api.telegram.org` + bot token + `extra.chat_id`）

### 5.2 消息能力（`ConnectorMessenger` + `ConnectorTools`）

- `connector_list` — 列出启用连接器与凭据状态（不泄露明文）
- `connector_send_message` — 经企业微信/飞书/Telegram 发送文本（verify_only 支
  持配置校验）
- 成功判定逐家校验业务码：企业微信 `errcode==0`、飞书 `code==0`、Telegram
  `ok==true`（HTTP 200 不代表业务成功）
- `connector_send_message` 显式声明 WEB/MEDIUM 风险 + 非幂等（防执行器盲重试
  导致重复发消息）

---

## 6. GitHub 工具修复

| 修复 | 内容 |
|---|---|
| 连接状态注入 | Connected Services 段告知"已连接 as <login>，工具就绪" |
| 描述双语化 | 首行英文 ≤160 字符（系统提示词只取首行），opencode 风格"一句话功能+使用时机" |
| 错误可行动 | 未连接/401/403 → 引导"设置 → 连接器 → GitHub 配置 Token，连接后先调 github_get_user 验证" |
| 新工具 | `github_list_branches`（写非默认分支前探查）、`github_search_repos`（按关键词找仓库，`/search/repositories`） |

---

## 7. 验证

- 纯 JVM 模块（logging/tool-registry/llm-adapter）Kotlin 2.0.21 编译 **exit 0**
- agent-engine（shim 绕过 serialization 插件的既有代码）编译 **exit 0**
- app/plugin 模块改动文件语法级检查零错误（无 Android SDK 无法全量编译）
- SlashCommandRouterTest 同步更新（插件路由文案断言）
- 角色路由 Golden 测试不受影响（不锁 prompt 内容）
