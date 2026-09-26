# Operit × RikkaHub × apex-agent 三方深度对比

> 状态：调研完成（v1）· 对标：AAswordman/Operit（Android 端最完整的 Agent 平台）· rikkahub/rikkahub（多供应商聊天客户端标杆）· 结论：七个能力域落地为本仓库 P9x 系列
> 前置：[tool-system-v4.md](tool-system-v4.md)（工具渐进披露）· [agent-modes.md](agent-modes.md)（六模式）· [reverse-mcp-host.md](reverse-mcp-host.md)（逆向 MCP Host）
> 证据基线：三仓库本地源码精读（operit @ 1402 Kotlin 文件 / rikkahub @ 683 / apex-agent @ 1102），本文所有论断均附文件路径证据，未验证的推测明确标注「推测」。

---

## 0. 一句话

Operit 证明了 Android 上可以长出一个**平台型 Agent 操作系统**（工具包生态 + 图记忆 + 角色卡 + 终端 + 虚拟屏）；
RikkaHub 证明了**产品级聊天客户端**的工程上限（消息分支 + Transformer 管线 + 19 家搜索抽象 + 会话层状态管理）；
apex-agent 站在两者中间——**自主智能体**的执行内核已经领先（工具 v4 渐进激活、cs-mem 仿生记忆、C++ 终端热路径、逆向 MCP Host），
但平台生态与对话产品化两翼各有明显缺口。本文逐层对比三者，并把差距收敛为 P9x 落地清单。

## 1. 阅读指南

- 本文用 **operit / rikkahub / apex** 分别指代三个仓库；文件路径采用「仓库前缀: 相对路径」写法，
  例如 `operit: api/chat/llmprovider/ApiKeyProvider.kt` 指 `/home/z/operit/app/src/main/java/com/ai/assistance/operit/` 下的该文件。
- operit 源码根 = `app/src/main/java/com/ai/assistance/operit/`；
  rikkahub 源码根按模块划分（`ai/src/main/java/me/rerere/ai/` 等）；
  apex 源码根按模块划分（`core/agent-engine/src/main/kotlin/com/apex/agent/core/engine/` 等）。
- 表格中的行数均为 `wc -l` 实测值（2026-02 快照）。

---

# 一、为什么对比这两个项目

## 1.1 三种产品形态：平台、客户端、智能体

同样跑在 Android 上、同样用 Kotlin + Compose、同样接 LLM，三个项目选择了三条完全不同的路：

**Operit 是平台（Platform）。** 它的核心资产不是某个功能，而是「让第三方往里加功能」的机制：
ToolPkg 工具包（ZIP + manifest + JS 脚本即工具 + WASM + UI 模块 + 10 余个管线钩子）让任何人不改主仓库源码就能注入工具、
UI、路由、甚至新的 AI Provider（`operit: core/tools/packTool/PackageManager.kt`，4101 行）。
围绕这个内核，它长出了图记忆、角色卡、群聊编排、工作流 DSL、独立终端 App、虚拟屏、市场——1402 个 Kotlin 文件里
相当一部分是「平台附属设施」。它是 Android 端目前**功能覆盖最完整**的 Agent 平台，没有之一。

**RikkaHub 是客户端（Client）。** 它不做平台梦，把「和 30 家模型供应商稳定聊天」这一件事打磨到产品级：
sealed `ProviderSetting` 三形态 + 无状态 `Provider` 接口 + ChatCompletions/Response API 双实现
（`rikkahub: ai/src/main/java/me/rerere/ai/provider/Provider.kt`）、
消息分支树（`rikkahub: app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`）、
Material Expressive + Navigation3 的现代 UI、19 家搜索供应商抽象（`rikkahub: search/` 模块）。
683 个文件的体量做到了 Operit 一半不到，但**单功能工程质量密度**是三者中最高的。

**apex-agent 是智能体（Agent）。** 它的权重压在「执行内核」上：6 模式状态机引擎
（`apex: core/agent-engine/.../engine/ApexAgentEngine.kt`，1200 行，顶着仓库文件预算上限）、
Tool System v4 渐进披露（catalog 三元工具 + 64 工具/48KB 请求预算 + 请求级自愈降级）、
cs-mem 仿生记忆（差分摄取→轨迹蒸馏→FSM 旁路→梦境巩固）、C++17 终端热路径 + 逆向 MCP Host。
它假设用户要的是「把任务交给它办完」，而不是「聊天」或「装插件」——109 个工具、6 种执行模式都为此服务。

## 1.2 定位差异表

| 维度 | operit | rikkahub | apex |
|------|--------|----------|------|
| 产品形态 | 平台型 Agent 操作系统 | 产品级多供应商聊天客户端 | 自主智能体执行内核 |
| 核心资产 | ToolPkg 扩展生态 + 图记忆 + 角色卡 | Provider 抽象 + 消息分支 + UI 打磨 | 引擎状态机 + 工具 v4 + cs-mem + 终端 |
| 用户心智 | 「往里装工具/角色/包」 | 「换着模型聊天」 | 「把任务交出去」 |
| 扩展方向 | 第三方开发者（JS/WASM） | 内置功能 + MCP | 插件 App（AIDL）+ 技能市场 |
| Kotlin 文件数 | 1402 | 683 | 675 main + ~180 test |
| 模块数 | 10（业务集中 `:app`） | 13（干净分层） | 18（core/platform/插件三层） |
| 对 apex 的价值 | 生态机制 + 数据层纪律 | 对话产品化 + 供应商抽象 | ——（本体） |

## 1.3 对比方法论

1. **只比事实**：每个论断给出可直接打开核对的文件路径；行数为实测。
2. **比设计而不是比参数**：同样的「多 Key」功能，operit 的 `MultiApiKeyProvider`（互斥锁 + 持久游标 + 三态可用性）与
   rikkahub 的 `KeyRoulette`（一个字符串字段 + LRU 文件）是完全不同的工程决策，本文着重拆解**为什么各自成立**。
3. **以 apex 的缺口为收敛目标**：所有对比最后落到第六节的差距矩阵与 P9x 落地清单，避免「为比而比」。

---

# 二、总览对比表

| 维度 | operit | rikkahub | apex |
|------|--------|----------|------|
| 模块数 | 10（`:app` + terminal/mnn/llama/quickjs/avator×3/showerclient） | 13（app/ai/search/common/document/web/material3/workspace/speech/videogen/oauth/highlight/baselineprofile） | 18（app + core×6 + platform×6 + 终端×2 + plugin-sdk×2 + plugins×2） |
| 语言栈 | Kotlin 2.2.21 + C/C++（MNN/llama.cpp/QuickJS/Shower） | Kotlin 2.4.10 + JS（QuickJS/Pebble/React web-ui） | Kotlin 2.0.21 + C++17（terminal-native） |
| UI 框架 | Compose BOM 2026.02.01 + Material3 + 自研 LazyList 内部 | Compose BOM 2026.09 + Material Expressive + Navigation3 + Haze | Compose BOM 2024.12.01 + Material3 + Liquid Glass 主题 + Haze |
| DI | 无框架（手写单例 + factory） | Koin 4.2.2 | Hilt 2.53.1（14 个 @Module） |
| 持久化 | Room v21（20 步手写迁移）+ ObjectBox（记忆图）+ versionedPreferencesDataStore | Room v25（AutoMigration 为主）+ Paging 3 + DataStore 大 JSON + FTS | SharedPreferences JSON（聊天）+ Room 2.6.1（仅 cs-mem v4） |
| HTTP | OkHttp 4.12 单例 SharedHttpClient + EventListener tracing | OkHttp 5.5 + okhttp-sse + Ktor 3.5（client+server） | OkHttp 4.12 + RetryInterceptor 指数退避 |
| 流式方案 | 自研 `Stream<T>`（lock/unlock 缓冲 + SAVEPOINT/ROLLBACK） | Flow<StreamChunk> + SSE EventSource + okhttp-sse | SSE 手解析（StreamingOpenAiClient 820 行） |
| 工具数 | 内置全量注册（ToolRegistration 2745 行）+ ToolPkg 无上限 | 内置少量 + MCP + search/scrape | 109（builtin ~44 类 + MCP 一等化 + 技能） |
| MCP | 客户端（kotlin-sdk 0.10.0，uvx/npx/local） | 客户端（kotlin-sdk 0.15.0，SSE+StreamableHTTP+OAuth 2.1） | 客户端 + **逆向 Host**（把内置工具反暴露给外部 MCP 客户端） |
| 记忆 | ObjectBox 图谱 + 4 通道混合检索 + 多空间 | Room 平面表 + memory_tool + 全量注入 | cs-mem：Room 图 + Actor 写入 + FSM/梦境/免疫 |
| 测试规模 | 158 JVM + 38 设备（含 JS 契约测试） | 85 JVM + 13 设备 | ~180 JVM（173 个 *Test.kt），无设备测试 |
| CI 门禁 | ci/ 编排（PR 分类/卫生/本地化/构建） | 常规 Android CI | **文件预算 1200/1600 + 反模式三门 + 括号平衡**（最严格） |
| 最低 Android | 8.0（API 26） | 8.0（API 26，targetSdk 37） | API 26，targetSdk 28（刻意，PRoot W^X 约束） |
| 发布渠道 | GitHub Release + 内置市场 | GitHub Release + 官网 | GitHub Release + ClawHub/ModelScope 技能市场 |
| 本地推理 | MNN + llama.cpp（GGUF）+ Ollama/LM Studio | Ollama（OpenAI 兼容） | 无（OpenAI 兼容端点统一） |
| 特色硬件能力 | 虚拟屏 Shower（C++ server）+ 无障碍/Shizuku/root 四套权限 | —— | PRoot 沙箱 + 前台服务保活引擎 |

> 表中「~180 JVM」为实测 `find -name "*Test.kt" -path "*/src/test/*"` 结果 173 个测试文件（另有少量测试辅助文件）。

## 2.1 关键技术栈版本对照

| 依赖 | operit | rikkahub | apex |
|------|--------|----------|------|
| AGP | 8.13.2 | 9.4.0 | 8.7.3 |
| Kotlin | 2.2.21 | 2.4.10 | 2.0.21 |
| Compose BOM | 2026.02.01 | 2026.09.00 | 2024.12.01 |
| material3 | Material3 | 1.5.0-alpha28（Expressive） | Material3 |
| DI | 无（手写单例） | Koin 4.2.2 | Hilt 2.53.1 |
| Room | 2.8.4（+ObjectBox 5.3.0） | 2.8.5 + Paging 3.5.1 | 2.6.1（仅 cs-mem） |
| OkHttp | 4.12.0 | 5.5.0 + okhttp-sse | 4.12.0 |
| 序列化 | kotlinx-serialization 1.9.0 | kotlinx-serialization | kotlinx-serialization 1.7.3 |
| 协程 | 1.10.2 | —— | 1.9.0 |
| MCP SDK | kotlin-sdk 0.10.0 | kotlin-sdk-client 0.15.0 | 自研（客户端+逆向 Host） |
| 模板引擎 | —— | Pebble 4.1.1 | —— |
| JS 引擎 | QuickJS（自建 JNI 模块） | dokar3 quickjs-kt | —— |
| 特殊依赖 | jieba + hnswlib + mediapipe + TFLite（本地 embedding）+ NanoHTTPD + hjson | zxing + quickie + MLKit + Haze + jsoup + metadata-extractor + JavaDiffUtils + termux-terminal-view + Ktor 3.5 | 无额外运行时依赖（零 QR/模板/分词库） |

> 三个时间层清晰可见：apex 的依赖面最保守（2024 末栈 + 零杂依赖），rikkahub 最激进
> （2026 秋栈 + alpha 版 material3），operit 居中但 native 面最重（MNN/llama.cpp/QuickJS/Shower 四套 C/C++）。
> apex 的保守是刻意策略：targetSdk 28（PRoot W^X 约束）+ 无 R8/混淆（kotlinx.serialization 字段名依赖）。

---

# 三、架构对比（逐层）

## 3.1 模块划分与依赖图

### operit：单体 app + native 子模块

```
operit/
├── :app  ─────────────── 全部业务逻辑（UI + Agent + 工具 + 数据，~1400 文件的绝大多数）
│     └── 依赖 ↓ 全部子模块
├── :terminal ─────────── git submodule（OperitTerminalCore，独立终端 App，Ubuntu 24.04 PRoot）
├── :mnn / :llama ─────── CMake 拉取 Alibaba MNN / llama.cpp 源码，JNI 本地推理
├── :quickjs ──────────── QuickJS JNI（ToolPkg 的 JS 引擎本体）
├── :dragonbones/:mmd/:fbx  avator 虚拟形象（骨骼动画/MMD/FBX）
└── :showerclient ─────── Shower 虚拟屏客户端（连接外部 C++ server 建虚拟显示）
```

- 证据：`operit: settings.gradle.kts`（`:app` 之外全部是 native/子模块挂载点）。
- **优点**：业务代码零跨模块跳转——`EnhancedAIService` 可以直接 import 任何工具/数据类，改起来快；
  native 能力（推理引擎、JS 引擎、终端）全部隔离在 CMake 模块里，边界干净。
- **缺点**：`:app` 内部没有编译期边界，`core/tools/packTool/PackageManager.kt` 能长到 4101 行、
  `EnhancedAIService.kt` 3221 行，正是「单模块无围栏」的直接后果；增量编译压力大；无法单独复用任何一块业务。

### rikkahub：13 模块 clean split

```
rikkahub/
├── :app ────────── UI / Service / DB / Koin DI（业务粘合层）
├── :ai ─────────── Provider 抽象（sealed ProviderSetting + Provider 接口 + KeyRoulette + ModelRegistry）
├── :search ─────── 19 家搜索服务（独立可测试，零 UI 依赖除 @Composable Description）
├── :common ─────── HTTP/SSE/缓存/QuickJS fetch
├── :document ───── PDF/DOCX/PPTX/EPUB 解析
├── :web ────────── Ktor 嵌入式服务器（手机跑 web UI，配 React web-ui/）
├── :material3 ──── 扩展组件
├── :workspace ──── PRoot rootfs + shell 运行器
├── :speech / :videogen / :oauth / :highlight / :app:baselineprofile
```

- 证据：`rikkahub: settings.gradle.kts`（include 列表 13 项）。
- **优点**：能力域即模块——`:search` 和 `:ai` 可以被任意复用（甚至别的 App）；`:ai` 模块 85 个单测里最密的
  Provider 请求构造测试就受益于这种隔离；`build-logic` convention plugin 统一配置。
- **缺点**：模块间接口需要正式化（`Provider` 接口 19 参数级别的 sendMessage 不好拆），跨模块改动成本高于单模块；
  `:app` 依然是个大泥球收口层（`ChatService.kt` 1390 行）。

### apex：18 模块 core/platform/plugin 三层

```
apex-agent/
├── :app ────────────────────────── UI + ViewModel + DI 装配（14 个 Hilt @Module）
├── core/（纯 Kotlin 引擎层，无 Android UI 依赖）
│   ├── :agent-engine ──────────── ApexAgentEngine 6 模式 + orchestrator + 压缩 + thinking
│   ├── :llm-adapter ───────────── LlmClient + StreamingOpenAiClient + runtime/（角色路由）
│   ├── :tool-registry ─────────── AgentTool 接口 + catalog/（v4 渐进披露）+ builtin ~44 工具类
│   ├── :logging / :code-tools / :code-engine
├── platform/（Android 平台能力层）
│   ├── :privilege / :terminal / :cs-mem / :code-workspace / :mcp-host / :persistence（保活引擎）
├── :terminal-emulator（Kotlin VT100）+ :terminal-native（C++17 热路径）
└── :plugin-sdk:plugin-api / :plugin-sdk:plugin-host + :plugins:plugin-workflow / :plugins:plugin-web-automation
```

- 证据：`apex: settings.gradle.kts`；分层语义见 `apex: docs/task-execution-architecture.md`。
- **优点**：三层语义清晰（engine 不知道 Android、platform 不知道 UI、app 只做装配）；
  `core/agent-engine` 因此可以被 173 个纯 JVM 测试覆盖；插件用独立 Gradle 模块 + AIDL 进程隔离，边界最硬。
- **缺点**：模块多带来的样板成本（18 份 build.gradle.kts）；`platform/persistence` 名不副实（实为保活引擎，
  聊天存储反而在 `:app` 的 SharedPreferences 里）——**命名与实际职责错位**是当前最大的架构债。

### 三方小结

| 评价轴 | operit | rikkahub | apex |
|--------|--------|----------|------|
| 编译边界清晰度 | 差（:app 单体） | 好 | 最好（core 纯 JVM） |
| 复用性 | 差（不可拆） | 好（:ai/:search 可独立） | 中（core 可复用，platform 绑 Android） |
| 改动成本 | 低（单模块直改） | 中 | 高（跨模块要过接口） |
| God-file 风险 | 高（3221/4101 行文件合法存在） | 中（ChatService 1390） | 受 CI 预算强制 ≤1200 行 |

## 3.2 Agent 执行循环

### operit：EnhancedAIService 单循环 + FunctionType 模型分工

`operit: api/chat/EnhancedAIService.kt`（3221 行）是全部智能的容器：

```
sendMessage(options)
  │ getModelExecutionSnapshot()      ← 按 FunctionType 解析本轮该用哪个模型
  │ prepareConversationHistory()     ← 系统提示 + 记忆 + 工作区附件
  │ prompt hooks                     ← before_finalize_prompt / before_send_to_model
  ▼
provider.sendMessage() → Stream<String>        （流式 collect）
  │   同时: revisionTracker.append()  +  eventChannel 转发 SAVEPOINT/ROLLBACK
  ▼
processStreamCompletion()
  ├─ 纯思考输出检测 → 回传警告让模型继续
  ├─ enhanceToolDetection()          ← 流式 XML 修复
  ├─ detectAndRepairTruncatedToolRound()  ← 截断工具调用修复
  ├─ extractToolInvocations()
  ├─ 无工具 → finalizeAssistantResponse()
  └─ 有工具 → handleToolInvocation()
        │ ToolExecutionManager（带 ToolRuntimeContext: callerCardId / ToolExposureMode ThreadLocal）
        ▼
      processToolResults() → TOOL_RESULT turn 追加 → 递归再调 provider（下一轮）
```

- **多轮循环靠递归**实现，每「轮」由 `ConversationRoundManager` 管理（一条 AI 消息内含多轮工具循环）。
- **FunctionType 模型分工**（11 种）：CHAT / SUMMARY / TITLE_GENERATION / MEMORY / UI_CONTROLLER /
  TRANSLATION / GREP / ROLE_RESPONSE_PLANNER / IMAGE_RECOGNITION / AUDIO_RECOGNITION / VIDEO_RECOGNITION——
  每种功能可独立配模型，由 `MultiServiceManager` 管理服务实例与租约（ServiceLease）。
  这意味着「翻译用便宜模型、UI 控制用视觉模型、主聊用旗舰模型」是**一等公民配置**而非 hack。
- **错误恢复**：`LlmRetryPolicy` MAX_RETRY_ATTEMPTS=5、指数退避 1s→16s；流式重试用
  `TextStreamRevisionTracker` 的 SAVEPOINT/ROLLBACK **原子回滚已输出内容**——重试时 UI 上已经渲染出来的
  半截文本会被撤回重放，而不是接在后面继续生成。这是三家中唯一的**流式回滚**实现。
- 缺点：3221 行单类把「循环、流控、修复、工具派发、上下文管理」全部内联，测试只能走设备/UI 路径。

### rikkahub：GenerationLoop + ConversationSession 会话层

`rikkahub: app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt`（570 行）+ `ChatService.kt`（1390 行）：

```
ChatService（门面）
  └─ ConversationSession（内存会话层, SessionManager 管理）
       ├─ StateFlow<Conversation> 流式更新
       ├─ 引用计数 acquire/release + 空闲驱逐（页面/Web/后台 Service 共享同一 session）
       └─ finishGeneration(NonCancellable)  ← 取消时兜底落盘
            ▼
      GenerationLoop.generateInternal()
        ├─ maxSteps=256 工具循环
        ├─ 工具审批状态机（Auto/Pending/Approved/Denied/Answered）
        ├─ 网络错误指数退避重试
        ├─ 工具输出 >32KB 且有 shell 时 → 截断 4KB 预览 + 落盘 /tool_outputs/ + 提示 cat/grep
        └─ 预创建空 assistant UIMessage 复用同一 ID → 重试幂等（新结果替换而非追加）
```

- **会话层是 rikkahub 独有的产品级设计**：`ConversationSession`/`SessionManager` 用引用计数 + 空闲回收
  管理「同一个对话的多个消费者」（聊天页、嵌入式 Web UI、前台 Service 通知），生成中被取消则用
  `NonCancellable` 上下文兜底保存——**状态永远有一个 owner，也永远会被落盘**。
- **上下文管理是滞回式（hysteresis）**：`limitContext` 阶梯截断，CONTEXT_KEEP_RATIO=0.5——截断点只在
  越限时「前进一大步」，平时保持前缀稳定，**专为命中 provider 前缀缓存设计**；`alignContextStart` 回退
  避免把 tool call 与结果拆散（拆散会被 OpenAI 400 拒绝）。这两个细节加起来，是「真金白银省钱」的工程。
- 压缩：`compressConversation` 递归二分分块（maxMessagesPerChunk=256）并行 async 压缩 → 多条 user 摘要 + 保留最近 N 条。

### apex：ApexAgentEngine + orchestrator/task/longtask 状态机

`apex: core/agent-engine/.../engine/ApexAgentEngine.kt`（1200 行，顶格仓库预算）：

```
ApexAgentEngine.execute()
  ├─ 6 模式分派（BUILD/PLAN/SPEC/REFLECTION/HUMAN_ASSIST/CUSTOM）
  │    ├─ PLAN: 计划 JSON → 用户确认 → 逐步执行 → 总结
  │    ├─ SPEC: 规格生成 → SpecAwaitingConfirmation → 按交付物逐项执行
  │    ├─ REFLECTION: 生成 → 评审 → 修正（reflectionRounds 可调）
  │    └─ HUMAN_ASSIST: 多方案/高风险强制 ask_user_choice
  ├─ BUILD 循环: LlmClient 流式 + 工具调用 + 工具相关 400/413/422 自愈降级
  │    （1=纯 CORE 工具 → 2=无工具纯对话；已流式输出的轮次不重试）
  └─ orchestrator/task/longtask 三层任务状态机（FileTaskStore 原子写 + WorkManager 长任务）
```

- **错误恢复分层**：HTTP 层 `RetryInterceptor` 指数退避（`LlmClientFactory`）；runtime 层
  `DefaultModelRuntime` fallback 链 maxAttempts=4 + `ModelRoleRouter` 五角色路由（PRIMARY/VISION/REASONING/FAST/SUMMARY）
  + 角色降级链；请求层 v4 工具自愈降级。**三层恢复各管一段，这是 apex 相对两家的结构优势**。
- **上下文管理**：`compression/HybridCompressor.kt` 三级压缩（结构化裁剪 → 摘要 → 截断），比 operit 的
  「自动总结（SUMMARY_PROMPT 四段结构）」多一级结构化裁剪，比 rikkahub 的阶梯截断多了摘要层；
  但**没有 rikkahub 的缓存友好设计**（无滞回、无 tool-call 对齐保护）。

### 3.2 小结

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 循环形态 | 递归单循环（单类 3221 行） | maxSteps=256 迭代循环 | 模式状态机 + 三层任务编排 |
| 模型分工 | FunctionType 11 种 + 租约管理 | 全局/助手两级模型选择 | ModelRole 五角色路由 + 降级链 |
| 流式重试回滚 | **SAVEPOINT/ROLLBACK 原子回滚**（独家） | 预创建消息 ID 幂等替换 | 已输出轮次不重试（防重复） |
| 上下文 | 自动总结四段结构 | 滞回截断 + tool-call 对齐（缓存友好） | Hybrid 三级压缩 |
| 会话状态 owner | ViewModel + 前台 Service | **ConversationSession 引用计数**（独家） | ViewModel + FileTaskStore |

### 3.2.1 错误恢复与重试深对比（三方流程图）

**operit：流式 SAVEPOINT/ROLLBACK 回滚**

```
provider.sendMessage() 流式输出
  │ token → revisionTracker.append() → UI 渲染
  │ 网络中断 / LlmRetryPolicy 判定可重试
  ▼
emit ROLLBACK 事件 ──→ UI 撤回已渲染内容（回到最近 SAVEPOINT）
  ▼
指数退避 1s→2s→4s→8s→16s（MAX_RETRY_ATTEMPTS=5）
  ▼
重发请求 → 重新流式输出（用户看到「重写」而非「拼接」）
```

- 关键机制：`RevisableTextStream` + `TextStreamEvent(SAVEPOINT/ROLLBACK)`（`operit: util/stream/`）——
  流是**可修订的**，不只是可消费的。UI 锁流（lock/unlock）与之配合：切走聊天时缓冲冻结，切回重放。
- 工具层修复：`enhanceToolDetection`（流式 XML 修复）+ `detectAndRepairTruncatedToolRound`
  （截断工具调用修复）——在解析层自愈，不重发请求。

**rikkahub：消息 ID 幂等替换 + 工具审批状态机**

```
GenerationLoop.generateInternal()
  │ 预创建空 assistant UIMessage（复用同一 ID）加入消息列表
  │ 流式 chunk → updateCurrentMessages(chunk.messages)
  │     └─ 按 message.id 匹配则替换，否则 append（幂等：重试不产生重复）
  ▼
网络错误 → 指数退避重试 → 新 chunk 仍然替换同 ID 消息
  ▼
工具调用 → ToolApprovalState(Auto/Pending/Approved/Denied/Answered)
  │     └─ Pending 时挂起等待用户（UI 弹审批卡）
  ▼
工具输出 >32KB 且有 shell → 截断 4KB + 落盘 /tool_outputs/ + 提示 cat/grep
```

- 关键差异：rikkahub 不回滚已渲染内容，而是**用稳定 ID 让替换幂等**——实现更简单，代价是
  重试期间用户看到的半截内容会闪烁重写。

**apex：三层恢复各管一段 + 请求级自愈降级**

```
第 1 层 HTTP（LlmClientFactory RetryInterceptor）
  └─ 指数退避 + Retry-After 遵从（网络/5xx/429）
第 2 层 runtime（DefaultModelRuntime）
  └─ fallback 链 maxAttempts=4 + ModelRoleRouter 角色降级（VISION→PRIMARY）
     + ModelAuthenticationFailed 预留「降级到不同 Key 的 Profile」语义
第 3 层 请求（ApexAgentEngine BUILD 循环）
  └─ 工具相关 400/413/422 且本轮零输出 →
       1=纯 CORE 工具重试 → 2=无工具纯对话（带 Tools Unavailable 说明）
     已流式输出的轮次不重试（防内容重复）
```

- apex 的第 3 层是独家能力：两家都没有「工具太多被拒后自动瘦身重发」的机制；
  但 apex **没有流式回滚**（选了「不重试已输出轮」的保守策略）也没有**消息 ID 幂等**——
  P90 Key 池化落地时，Key 轮换重试应借鉴 rikkahub 的幂等模式（换 Key 重发同 ID 请求）。

| 恢复轴 | operit | rikkahub | apex |
|--------|--------|----------|------|
| 网络重试 | 5 次指数退避 | 指数退避 | RetryInterceptor + 退避 + Retry-After |
| 流中重试 | **SAVEPOINT/ROLLBACK 撤回重放** | 同 ID 幂等替换 | 不重试已输出轮 |
| 模型故障 | FunctionType 独立配置 | 手动换模型 | **角色降级链（独家自动化）** |
| 工具请求被拒 | 解析层修复 | 审批状态机 | **自愈降级两级瘦身（独家）** |
| 工具输出爆炸 | —— | **>32KB 截断+落盘+cat/grep 提示** | 16KB 钳制（MCP）/400 字截断（历史） |

## 3.3 LLM 接入层

### operit：35 个 ApiProviderType + 双通道工具调用 + CLI 渐进披露

- **分发**：`operit: api/chat/llmprovider/AIServiceFactory.kt`（`buildService()` 按 `ApiProviderType` 枚举 ~35 个分发：
  OPENAI/ANTHROPIC/GOOGLE 三原生 + OPENAI_GENERIC/GEMINI_GENERIC 等兼容层 + ALIYUN/BAIDU/XUNFEI/ZHIPU/
  BAICHUAN/MOONSHOT/DEEPSEEK/MISTRAL/SILICONFLOW/OPENROUTER/…/OTHER 全家桶）。
  面向**中转站生态**：`FOUR_ROUTER`、`INFINIAI`、`IFLOW`、`OPENCODE` 这类聚合站是一等公民。
- **装饰器栈**：`createService()` 统一包 `TokenTrackingAIService`（token 账本，测试调用传 recordTokenUsage=false 跳过）
  + `RateLimitedAIService`（限流）+ `ToolPkgJsAiProviderService`（**用 JS 实现新 Provider**——ToolPkg 生态的延伸）。
- **双通道工具调用**：默认 XML 协议（`<tool name="x"><param name="y">v</param></tool>`，`AIToolHandler` +
  `MessageContentParser` 流式提取，弱模型也能用）；`enableToolCall=true` 切原生 API，
  `StructuredToolCallBridge` 把 OpenAI function.arguments 字符串 / Gemini args 对象 / Claude input
  **三方言归一**，并把工具结果回填为协议合法历史（未匹配调用补「工具结果缺失」占位防 400）。
- **CLI 渐进披露**：`ToolExposureMode.CLI`（`operit: core/tools/climode/CliToolModeSupport.kt`，689 行）——
  面向本地小模型只暴露 `search`（搜隐藏工具目录）+ `proxy`（转发调用）两个工具，
  `HiddenToolCatalogEntry` 携带 parameterHints/keywords 让小模型检索式发现工具。
- 本地推理：`MNNProvider`（MNNLlmSession）/`LlamaProvider`（LlamaSession，n_ctx/flash-attn/kv-unified 可配）。

### rikkahub：sealed ProviderSetting + 双 API 实现 + ModelRegistry DSL

- **模型**：`rikkahub: ai/.../provider/ProviderSetting.kt`（250 行）sealed class，@SerialName("openai"/"google"/"claude")
  多态序列化——**类型系统即配置校验**，不可能配出「OpenAI 端点 + Claude 参数」的怪胎。
  OpenAI 形态内含 `useResponseApi` 开关 → `ChatCompletionsAPI`（SSE EventSource）与 `ResponseAPI` 两套实现共存。
- **无状态 Provider 接口**：每次调用传 setting（`listModels/streamText/generateText/generateEmbedding/generateImage/editImage`），
  与 operit「构造时注入配置」相比，**运行时改配置零重建成本**。
- **三级自定义 header/body 合并**：`TextGenerationParams.customHeaders/customBodies`（`Provider.kt:75`）由
  `GenerationLoop.kt:393-399` 合并 `assistant.customHeaders + model.customHeaders`（body 同理），加上 Provider 实现层的
  内建协议头（Authorization/Content-Type）——**助手级、模型级、协议级三层正交**，任何中转站的怪癖头都能塞进对应层级。
- **ModelRegistry DSL**（`rikkahub: ai/.../registry/ModelDsl.kt`，785 行）：`defineModel { tokens("gpt","5","1"); notTokens("chat"); visionInput(); toolReasoningAbility() }`
  ——token 匹配 + EXACT_ID_BONUS=1000 的评分推断模型能力，`SettingProviderDetailPage` 拉到 /models 列表后
  **自动补齐能力标注**（vision/tool/reasoning），用户零手工配置。
- **KeyRoulette**：见 3.4。

### apex：StreamingOpenAiClient 单实现 + 角色路由 + 能力交集

- **单实现哲学**：`apex: core/llm-adapter/.../llm/StreamingOpenAiClient.kt`（820 行）是唯一真实客户端——
  SSE 流式/多模态出图/citations/并行 tool_calls index 对齐/Gemini schema 清洗/o-series 兼容/原生搜索按 Provider 注入。
  供应商差异折叠进 `ProviderConfig`（17 个内置 Provider 是**数据**不是代码）。
- **ModelRoleRouter 五角色路由**：PRIMARY/VISION/REASONING/FAST/SUMMARY，`CapabilityResolver` 解析
  Provider∩Profile 能力交集，角色不可用时沿降级链回退（如 VISION→PRIMARY）——
  与 operit FunctionType 的区别：**apex 是按能力路由，operit 是按功能位配置**；前者自动化程度高，后者可控性强。
- `DynamicLlmClient`（`app/di/`）监听 profiles/providers 流即时重建委托——设置页改 Key 立即生效。
- **缺口**：无原生 Anthropic/Google 协议（靠兼容端点）、无 Anthropic prompt caching、无余额查询、无本地推理。

### 3.3 对比表

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 供应商表达 | ApiProviderType 枚举 ~35 个（代码分支） | sealed ProviderSetting 3 形态（类型系统） | ProviderConfig 数据 17 个（零分支） |
| 工具调用协议 | XML + 原生双通道 + Bridge 三方言归一 | 原生 function calling | 原生 function calling + Gemini schema 清洗 |
| 渐进披露 | CLI 模式 search+proxy 两工具 | 无（Gemini 内置工具检测） | catalog tool_search/tool_open/tool_list（v4） |
| 自定义请求 | customHeaders 构造注入 | **三级 header/body 合并** | LlmConfig.customHeaders 单级 |
| 能力推断 | supportsVision 手工配 | **ModelRegistry DSL 自动推断** | CapabilityResolver 交集解析 |
| Key 池 | MultiApiKeyProvider 完整实现 | KeyRoulette 字符串切分 | 数据预埋未实现（见 3.4） |
| 本地推理 | MNN + llama.cpp | Ollama 兼容 | 无 |
| 独特优点 | 中转站生态适配 + JS Provider | Response API 双实现 + 余额查询 | 角色降级链 + 单实现可维护性 |

## 3.4 Key 管理：一个功能的三种工程形态

这是「同一需求、三种决策」的最佳样本，也是 apex 本次落地（P90 / Task 4-a）的直接参照。

### operit：接口抽象 + 池化完整实现

`operit: api/chat/llmprovider/ApiKeyProvider.kt`（111 行）：

```kotlin
interface ApiKeyProvider {
    suspend fun getApiKey(): String
    suspend fun getCandidateKeyCount(): Int
}
class SingleApiKeyProvider(private val apiKey: String) : ApiKeyProvider
class MultiApiKeyProvider(...) : ApiKeyProvider {
    private val mutex = Mutex()
    override suspend fun getApiKey(): String = mutex.withLock {
        // 1. 取配置 → filter(isEnabled)
        // 2. 有任何可用性标记 → 只取 AVAILABLE；无标记 → 全体启用 Key
        // 3. 空候选 → 回退 config.apiKey 单 Key → 仍空抛错
        // 4. currentKeyIndex % size 选中 → updateConfigKeyIndex 持久化游标
    }
}
```

- **三态可用性**：`ApiKeyAvailabilityStatus { UNTESTED, AVAILABLE, UNAVAILABLE }`（`data/model/ApiKeyInfo.kt`）——
  未测试的 Key 不会被误杀，测试过的坏 Key 会被剔除，全部坏掉时回退单 Key。
- **持久化游标**：`ModelConfigManager.updateConfigKeyIndex` 原子更新轮询下标（DataStore），重启不重置。
- **并发可用性测试器**：`ApiKeyPoolAvailabilityTester.kt`（206 行）——StateFlow 驱动 UI 进度
  （totalToTest/tested/available/unavailable/running/paused），Channel<ApiKeyInfo>(UNLIMITED) 当任务队列，
  N 个 worker 并发测试，每测完一个 Mutex 保护写回 + 持久化，**支持暂停/恢复**（cancelAndJoin 后 startOrResume 只补测剩余）。
- 注入点在 `AIServiceFactory.buildService():303`——每个 Provider 持有 `ApiKeyProvider` 而非裸 Key，
  **每次请求时才取 Key**，池的变化即时生效。

### rikkahub：KeyRoulette——一个字符串字段的键池

`rikkahub: ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt`（102 行）：

```kotlin
private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()   // 空格/换行/逗号切多 Key
interface KeyRoulette { fun next(keys: String, providerId: String = ""): String }
// Default: keyList.random()
// Lru: cacheDir/lru_key_roulette.json 记 Map<providerId, Map<apiKey, lastUsed>>
//      优先未用过的 Key，24h 过期，全局文件锁 LruFileLock 防并发读写
```

- **零额外数据模型**：多 Key 就是用户在 apiKey 输入框里粘贴时多写几行——产品上「自然到用户不需要知道有池」。
- LRU 模式把「同一 provider 多实例」用 providerId 区分，24 小时过期防止陈旧记录。
- 局限：无可发性语义（不知道哪个 Key 坏了）、无测试器、随机模式不抗 429（可能连续撞同一坏 Key）。

### apex：数据预埋、逻辑缺位

`apex: core/llm-adapter/.../ModelProfile.kt`：
`ProviderConfig.apiKeys: List<String>` 多 Key 数据结构已存在，`KeyRotationMode` 枚举
（DISABLED/SEQUENTIAL/ON_ERROR/ON_RATE_LIMIT）已定义且注释明说「客户端轮换逻辑由 LLM client 层后续接入」；
但 `SettingsRepository` securePrefs 每 Provider 只存 1 把 Key（`"provider_api_key_"+id` 单键），
`upsertProvider` 把 apiKeys 折叠为 1。**缺整个池化层**——数据层是一等公民、持久化层和运行层还没跟上。

> 落地路径（P90 / Task 4-a）：securePrefs 改多键存储 + hydrate 全列表 → 新建 KeyPoolLlmClient 包装器
> （模仿 DynamicLlmClient，捕获 `LlmException.Http(429/401)` 按 KeyRotationMode 换 Key 重试）→
> 加 KeyRoulette 式批量可用性测试。风险：`ModelRuntimeRegistry` 缓存以 LlmConfig 相等为键，
> **轮换必须在请求层包装器做**，不能改 config 触发重建。

## 3.5 数据持久化

### operit：三引擎分工 + 版本化 DataStore + 备份验证

- **Room v21**（`operit: data/db/AppDatabase.kt`）：ChatEntity（含 parentChatId 聊天级分支、workspace 绑定、
  token 统计）+ MessageEntity（roleName/provider/modelName/input/output/cachedInputTokens/selectedVariantIndex）
  + MessageVariantEntity（消息变体）+ TokenUsageRecordEntity（token 账本，importKey 去重）
  + TokenStatsModelEntity（按模型价格 billingMode/currency/单价）——**20 个 Migration 全手写**，含数据迁移 SQL。
- **ObjectBox**：记忆图谱专用（Memory/MemoryTag/MemoryLink/MemoryProperty/DocumentChunk + embedding 向量索引），
  `objectbox-models/default.json` 管理schema 演进。
- **versionedPreferencesDataStore**（`operit: data/preferences/VersionedPreferencesDataStore.kt:30`）：
  自带版本迁移钩子的 DataStore 扩展，`ModelConfigManager` 有 migratePreferencesFromVersionZero..Three 四代迁移——
  **偏好数据也有 schema 版本概念**，30+ 个 Manager 各管一域。
- **备份验证**：`AppDatabase.kt:485`——恢复校验时复制库后 `DROP TABLE IF EXISTS room_master_table`
  **强制 Room 走全 schema 校验**（room_master_table 存着 schema hash，删掉它 Room 必须重算并比对全部表结构），
  确保恢复的备份与当前代码 schema 一致。这个细节是数据纪律的巅峰。

### rikkahub：Room v25 + AutoMigration + Paging + json_each 统计

- 8 实体（conversation/memory/gen_media/message_node/managed_file/favorite/workspace/folder），
  **绝大多数 AutoMigration**，仅 6_7/8_9/11_12/16_17/22_23 手写——迁移成本远低于 operit。
- `message_node` 表把 `List<UIMessage>` 序列化为 JSON 存列，统计 token 时用
  `json_each()` 虚拟表展开（`rikkahub: app/.../data/db/dao/MessageNodeDAO.kt:47-85`，
  @RawQuery 绕过 Room 编译期校验 + json 参数内校验防损坏行）——**JSON 列上的 SQL 聚合**，
  在「分支消息」这种半结构化数据上免掉了 N 张关联表。
- 会话列表 `LightConversationEntity` 摘要 + Paging3（PAGE_SIZE=20），详情才 loadMessageNodes——**读写分级**。
- FTS4/5 + BM25 全文检索（MessageFtsManager）。
- DataStore：Settings 大 JSON 单 preferences（"providers"/"assistants"/"search_services" 等 key），
  3 版 migration + corruption handler 备份重建——**简单但有整体损坏风险**（一个 key 坏全部重来）。

### apex：SharedPreferences JSON + cs-mem Room

- 聊天存储全在 SharedPreferences：① 活跃会话 `SharedPrefsConversationMemory`
  （`apex: app/src/main/kotlin/com/apex/agent/di/SharedPrefsConversationMemory.kt`，197 行，
  "apex_memory" → conversation_history，后台合并写）；② 历史会话 `ChatHistoryManager`
  （"apex_chat_history" → index + msg_{id}，上限 100 会话，工具输出截 400 字，thinking 不入库，
  恢复仅取 user/agent 文本对——**工具调用配对在恢复时丢失**）。
- Room 仅存在于 cs-mem（`MemoryGraphDatabase` v4：Node/Edge/Episode/FSMMacro/MigrationMap）。
- **无事务性**（JSON 全量重写）、**无迁移机制**（字段演进靠 kotlinx.serialization ignoreUnknownKeys 兜底）、
  大会话性能边界存在（100 会话上限是兜底不是设计）。
- 优点：零 schema 成本、代码极简（94 行的 ChatHistoryManager vs rikkahub 1390 行 ChatService）。

### 3.5 对比表

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 主存储 | Room v21 + ObjectBox + DataStore | Room v25 + DataStore | SharedPreferences JSON |
| 迁移 | 20 步全手写 | AutoMigration 为主 | 无（序列化容忍） |
| 事务性 | ✅ Room 事务 | ✅ Room 事务 + 全量重写事务 | ❌ 原子写但无事务 |
| 大数据量 | ✅（Paging 无但有窗口字段） | ✅ Paging3 + FTS | ⚠️ 100 会话上限兜底 |
| schema 演进纪律 | versionedPreferencesDataStore 四代迁移 | 3 版 migration + corruption handler | 无 |
| 备份 | WAL 快照 + drop room_master_table 全 schema 校验 | 无专门备份 | 无 |
| 统计查询 | TokenUsageRecordEntity 账本表 | **json_each() SQL 聚合** | 无 |

## 3.6 UI 架构

### operit：Compose + 自研基础设施

- 100% Compose + Material3 + MVVM（StateFlow），**无 DI 框架**——手写单例 + factory。
- 自研 LazyListMeasurePolicy/LazyLayoutItemProvider（手写惰性列表内部以支持超大消息流）——
  为「一条消息几万字流式追加」这种极端场景做了底层改造。
- 自研路由（AppRouteCatalog + ScreenRouteRegistry + `AppRouterGateway` 供 ToolPkg JS 注册路由）——
  因为 **ToolPkg 要能在运行时注入页面**，Navigation-Compose 做不到。
- CustomXmlRenderer：流式渲染自定义 XML 标签（工具卡片/think 折叠/文件 diff）。

### rikkahub：Navigation3 + Material Expressive 全家桶

- Navigation3（`rememberNavBackStack` + entryProvider + NavDisplay）：类型安全 route
  （Screen sealed class 带 @Serializable data class），预测性返回 + 共享元素动画。
- MaterialExpressiveTheme + MotionScheme + dynamicColor + AMOLED + 自定义主题 JSON + Haze 毛玻璃——
  **UI 现代化程度三家中最高**，material3 直接用 1.5.0-alpha28（Expressive 早期采用者）。
- 输入状态放 ViewModel（注释明说避免 TransactionTooLargeException）、ImeLazyListAutoScroller、音量键滚动——
  产品级细节密度极高。

### apex：Compose + Liquid Glass + 文件预算驱动的拆分模式

- Compose + Material3 + 自研 Liquid Glass 主题（11 套 AccentPalette + Haze 玻璃）+ 双语 LanguageManager。
- **1200 行文件预算的扩展文件拆分模式**：`SettingsScreen`（1189 行）按 section 拆、
  `AgentChatViewModel`（1019 行）用 internal 扩展文件拆 God-file——**CI 门禁倒逼出的架构模式**，
  与 operit「3221 行合法存在」形成鲜明对照。
- @HiltViewModel + StateFlow + Repository 委托的标准三层。

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 导航 | 自研路由（为 ToolPkg 动态注入） | Navigation3（最新） | Navigation-Compose |
| 主题 | 自定义 SerializableColorScheme 全可编辑 | Material Expressive + 动态色 | Liquid Glass + 11 调色盘 |
| 超大消息 | 自研 LazyList 内部 | ChatSizeChecker 警告 | 常规 LazyColumn |
| God-file 治理 | 无（单体文化） | 无强制 | **CI 1200 行预算强制** |

---

# 四、能力域深对比

> 本章每个小节结构固定：**三方现状 → 差距分析 → apex 可借鉴点（附具体文件引用）**。

## 4.1 工具系统

### 三方现状

**operit：ToolPkg——「工具是一等生态公民」**

- 工具模型：`AITool(name, parameters: List<ToolParameter>, description)` + `ToolExecutor`
  接口（`invoke` + `invokeAndStream: Flow<ToolResult>` + `validateParameters`）；
  `AIToolHandler`（491 行）单例注册表，`executeTool` 完整生命周期：
  通知 → **hook 拦截**（`AIToolHook`：onToolCallRequested/onToolCallIntercept(Allow|Block)/
  onToolPermissionChecked/onToolExecutionStarted/Result/Error/Finished）→ **权限系统**
  （ToolPermissionSystem: Allow/Deny/Ask）→ validateParameters → invoke。
- **懒加载包**：`getToolExecutorOrActivate(toolName)`——"packageName:toolName" 形式自动激活未加载包。
- **ToolPkg 格式**（`operit: docs/TOOLPKG_FORMAT_GUIDE.md`，1599 行）：
  `.toolpkg` = ZIP，manifest.json/hjson 声明 `schema_version/toolpkg_id/version/api_version/requires/main/
  display_name{LocalizedText}/description/logo/author[]/enabled_by_default/subpackages[]/resources[]/
  wasm_modules[]/workflow_templates/workspace_templates`；
  `ToolPkgParser.kt`（1854 行）解析成容器运行时，每个子包是一个 `ToolPackage`。
- **脚本即工具**：`PackageTool(name, description, parameters, script: String /*JS*/, advice)`——
  `PackageToolExecutor`（`ToolPackage.kt:374`）解析 "packageName:toolName" → `jsToolManager.executeScript(...)`。
- **权限分级实现**：同一工具四套实现按目录分层——
  `core/tools/defaultTool/standard`（无障碍层）+ `accessbility` + `admin`（Shizuku/ADB）+ `debugger` + `root`；
  ShellExecutor 接口 + 5 实现（Standard/Accessibility/Admin/Debugger/Root）+ ActionListener 工厂同构。
- **10+ 管线钩子**：promptInputHooks/promptHistoryHooks/systemPromptComposeHooks/toolPromptComposeHooks/
  promptFinalizeHooks/summaryGenerateHooks……ToolPkg JS 可以介入 AI 管线的几乎每个阶段；
  还能注册 UI 模块（Compose DSL 渲染）、UI 路由、导航项、桌面 Glance 小部件、消息处理插件、
  XML 渲染插件、聊天输入/视图/消息钩子、**AI Provider**（JS 实现新供应商四 handler）。
- `JsEngine.kt`（2767 行）：QuickJS 单线程 executor + Java 对象注册表 + binary handle
  （超 32KB 走 `@binary_handle:` 传引用）+ `JsToolCallInterface`（**JS 内可回调 Android 原生工具**）
  + 执行取消（chat 维度 cancelToolPkgExecutionsForChat）。
- **三层生命周期**（`PackageManager.kt`，4101 行）：

```
  Available（资产/外部导入 .toolpkg）
      │ 用户启用
      ▼
  Enabled（用户开启，尚未注入）
      │ usePackage(packageName)（AI 调用或用户手动）
      │   └─ 校验 env 变量（必填缺失则拒绝）
      ▼
  Used（激活注册：包内工具注入 AIToolHandler）
      │ 延迟状态 ToolPackageState(condition, inheritTools, excludeTools, tools)
      ▼
  按 ConditionEvaluator 条件切换工具集（同一包在不同条件下暴露不同工具）
```

**rikkahub：内置工具 + MCP 一等化**

- 内置工具少而精：search_web/scrape_web（§4.6）、memory_tool（§4.2）、TimeInfo 等少量 localTools。
- MCP 工具命名 `mcp__{server}__{tool}` + `needsApproval` 透传；TextContent→Text part、
  ImageContent→存文件转 Image part；非法 server 名抛 `InvalidMcpServerNamesException` 阻断生成。
- 助手级开关 `assistant.mcpServers: Set<Uuid>` + 服务器内 McpTool.enable 单工具级禁用。

**apex：Tool System v4——「工具是受管资源」**

- `AgentTool` 接口（id/name/description/parametersSchema/metadata）+ ToolCategory 17 类 +
  ToolRisk 3 级 + ToolAnnotations；builtin ~44 工具类 + MCP 一等化（`McpAgentTool` 每远程工具→真函数）。
- **v4 渐进激活**（`apex: core/tool-registry/.../catalog/`）：CORE_TOOL_IDS ~45 个常驻 +
  `tool_search/tool_open/tool_list` 目录元工具（tool_open 的完整 schema 作为**工具结果**注入，
  下一轮请求自动带上——operit use_package 的原生函数调用版）；
  `ToolRequestBudget` 钳制 ≤64 工具 / ≤48KB，按 provider 名稳定排序（保护前缀缓存）；
  `ToolNameSanitizer` 点号清洗 + 冲突消解 + 双向映射；`ToolSchemaSanitizer` 修复幽灵 required/坏关键字/超长。
- v3 八层硬化：熔断/限流/追踪等（见 `apex: docs/tool-system-v3.md`）。

### 差距分析

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 第三方可扩展 | **ToolPkg ZIP/JS/WASM**（改包不改 App） | 无（MCP 为唯一外部通道） | 插件 App（AIDL，重）+ 技能（prompt 级） |
| 渐进披露 | use_package 包级 + CLI search/proxy | 无 | **catalog 三元工具（v4，最细粒度）** |
| 权限模型 | 四套物理层实现 + Allow/Deny/Ask | needsApproval 单级 | ToolRisk 3 级 + 审批 |
| 管线钩子 | 10+ 阶段 JS 钩子 | 无 | hook/ 五个会话钩子 |
| 请求预算 | 无硬预算 | 无 | **64 工具/48KB 硬钳制** |

- operit 的 ToolPkg 是**平台飞轮**（有市场、有格式文档、有 JS 回调原生的能力），apex 的插件 SDK 是
  **进程级隔离**（AIDL 跨进程，重但安全边界硬）；rikkahub 干脆不做第三方工具。
- apex 的 v4 渐进披露比 operit 的 use_package **粒度更细**（工具级 vs 包级）、且与原生 function calling
  零歧义；但 operit 的「只列包名 → 激活后才注入完整工具 prompt」两级设计对上下文预算的节约更激进，
  值得 apex 在 catalog 之上再加一层**技能/包级聚合披露**（当前 skill 是 prompt 注入型，不占工具槽位，
  但工具型包没有聚合层）。

### apex 可借鉴点

1. **包级渐进披露的提示词结构**（`operit: core/config/SystemPromptConfig.kt`，712 行）：
   系统提示只列 "Available packages: - name : description" + use_package 指示——apex 的
   EnginePrompts "## Tool Catalog (N more available)" 段可以升级为**类别→包→工具三级目录**，
   tool_search 支持 namespace 限定（`tool_search("terminal:")`）。
2. **工具输出多态**（`ToolResultData`：StringResultData/UiResultData/FileChangeResultData/...）：
   apex 的 AgentTool 结果目前以文本为主，结构化结果（UI 截图/diff/文件变更）可以让 UI 渲染更富。
3. **AITHook 的 Allow|Block 拦截器**：apex 的 hook/ 目前是会话生命周期钩子（SessionStart/Stop 等），
   工具调用拦截可补一层（对齐 v3 硬化的审批流）。

## 4.2 记忆系统：三种哲学

### 三方现状

**operit：图检索派（ObjectBox 图谱 + LLM 抽取 DSL + 4 通道混合检索）**

- 实体（`operit: data/model/Memory.kt`）：`Memory`（title/content/credibility/importance/embedding/
  ToMany tags/properties/links/backlinks/documentChunks）+ `MemoryTag`（层级）+
  `MemoryLink`（type="causes"/"explains"/"part_of" + weight + description）+ `MemoryProperty`（k/v）+
  `DocumentChunk`（文档 RAG 切块）。
- **抽取 DSL**（`operit: api/chat/library/MemoryLibrary.kt`，928 行）：会话清洗（剥 `<think>`/工具输出/
  Gemini thoughtSignature）→ LLM generateAnalysis 产出 `ParsedAnalysis`
  （mainProblem + extractedEntities + links + updatedEntities(ParsedUpdate) + mergedEntities(ParsedMerge) +
  profileMarkdown）→ **幂等落库顺序：先 merge → 再 update → 建 mainProblem 节点 → 实体节点（aliasFor 别名去重）
  → 建 links（先查本轮新建 map 再查 DB）**——合并优先于更新、更新优先于创建，避免重复实体。
- **4 通道混合检索**（`operit: data/repository/MemoryRepository.kt`，2814 行）：
  keyword（标题/内容/token 展开 + **jieba 中文分词**）+ tag + semantic（**HNSW** VectorIndexManager
  按维度分索引，cosine）+ edge（图边扩散）→ **RRF 融合** + 关键词覆盖乘子 + 时间过滤 +
  relevanceThreshold；MemoryScoreMode（BALANCED 等）预设权重；searchMemoriesDebug 返回完整调试信息
  （**UI 有检索模拟对话框**——可解释性做到了产品级）。
- **多记忆空间**（MemorySpace）：每空间独立 ObjectBox + 独立 profile 文档（自动更新用户画像 markdown）+
  独立检索设置——角色卡可绑定不同空间（§4.3）。
- 读写入口：agentic 工具 `MemoryQueryToolExecutor`（query_memory/get_memory_by_title/create_memory/
  update_memory/delete_memory/update_user_profile）+ MemoryAutoSaveScheduler + 附件式注入
  （`<memory_context><available_folders>` XML 指示 AI 用 query_memory）。

**rikkahub：简单可解释派（ChatGPT 式）**

- 模型：`AssistantMemory(id: Int 自增, content: String)`，Room memory 表按 assistantId 隔离，
  `GLOBAL_MEMORY_ID="__global__"` 全局共享池（assistant.useGlobalMemory 切换）。
- 写 = 工具 `memory_tool`（action=create/edit/delete + id + content；prompt 内置规则：勿存敏感信息/
  相似记忆合并/今日日期）；读 = system prompt 后追加 `buildMemoryPrompt` →
  `**Memories**\n...JSON [{id,content}]`。
- **无向量检索——全量注入**。上限低（记忆一多 prompt 爆炸），但**每条记忆用户可见可改**，
  LLM 用 edit 动作合并相似项。零魔法。

**apex：仿生派（cs-mem）**

- 管线全图：

```
会话轨迹（AgentEvent 流）
   │ ① 差分摄取 DifferentialIngestor（只处理增量，去重）
   ▼
原始变更集
   │ ② 轨迹蒸馏 TraceDistiller（LLM 把轨迹蒸馏成图变更提案）
   ▼
图变更提案
   │ ③ FSM 旁路 BypassExecutionEngine
   │     └─ 高频模式匹配 → FSMMacro 直接落图（免 LLM，省 token）
   ▼
MemoryWriterActor（Channel(256) 批量 + 紧急冲刷，无锁写入）
   ▼
Room MemoryGraphDatabase v4（Node 指纹去重 / Edge / Episode / FSMMacro / MigrationMap）
   │ ④ 梦境巩固 DreamRenderer + TopologyMigrator（闲时重构图拓扑）
   │ ⑤ EntropyManager 能量衰减 + MemoryImmuneSystem 冲突检测
   ▼
CsMemRecallTools（memory_* 工具）+ CsMemSessionObserver（桥接引擎）
```
- 存储：Room `MemoryGraphDatabase` v4（Node/Edge/Episode/FSMMacro/MigrationMap），
  节点指纹去重（NodeFingerprint）。
- 写入：Actor 模式无锁（MemoryWriterActor Channel(256) 批量 + 紧急冲刷）——三家中唯一
  **写入路径与 UI 完全解耦**的实现。
- 读取：CsMemRecallTools 提供 memory_* 工具 + CsMemSessionObserver 桥接引擎。

### 差距分析与借鉴

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 模型 | 属性图 + 边类型/权重/可信度 | 平面 KV | 图 + episode + FSM 宏 |
| 写入 | LLM 抽取 DSL（merge→update→create→link） | LLM 工具直写 | 差分 + 蒸馏 + Actor 批量 |
| 检索 | **4 通道 RRF 混合** | 全量注入 | 图遍历 + recall 工具 |
| 可解释性 | 检索模拟对话框 + profile markdown | 记忆页手动管理 | 图可视化（部分） |
| 隔离 | 多空间（角色绑定） | 全局/助手两级 | 会话级 + 图拓扑 |
| 独特武器 | jieba + HNSW + 图边扩散 | 极简零魔法 | 梦境巩固 + 免疫系统 + FSM 旁路 |

- operit 最值得抄的是**检索侧**：apex 的 cs-mem 有最好的写入管线，但检索只有图遍历工具——
  缺 keyword/semantic/edge 多通道融合与 RRF。落地建议：cs-mem 增加 `MemoryRetriever` 接口，
  先做 keyword（无需 embedding）+ edge 两通道 RRF，semantic 通道留到本地 embedding 可用时接入。
- rikkahub 的「记忆用户可见可改 + LLM edit 合并」哲学提醒：**cs-mem 的图对用户是黑盒**，
  DreamRenderer/TopologyMigrator 的变更应该有可视化审计界面（对应 operit 的检索模拟对话框）。

## 4.3 角色与人设

### 三方现状

**operit：CharacterCard——角色是全资源绑定单元**

- `CharacterCard`（`operit: data/model/CharacterCard.kt`，DataStore 存储）：
  name/description/characterSetting（引导词）/openingStatement/attachedTagIds/advancedCustomPrompt/
  marks/**chatModelBindingMode(FOLLOW_GLOBAL|FIXED_CONFIG)+chatModelConfigId**（每角色独立模型！）/
  **memoryProfileBindingMode+memoryProfileId**（每角色独立记忆空间！）/**toolAccessConfig**（工具白名单！）。
- `CharacterCardToolAccessConfig(enabled, allowedBuiltinTools, allowedPackages, allowedSkills,
  allowedMcpServers)` + `CharacterCardToolAccessResolver.resolve(roleCardId, packageManager)`——
  **角色卡是安全边界**：换了角色，包/技能/MCP 全部重新过滤。
- **群聊编排**（`operit: services/core/MessageCoordinationDelegate.kt`，2053 行）：
  `CharacterGroupCard(members: List<GroupMemberConfig(characterCardId, orderIndex)>)`；
  `orchestrateGroupConversation` → planResponseOrder 用专用 **ROLE_RESPONSE_PLANNER** 模型输出
  `{"rounds":[[{id,skip}...]]}`（兼容旧 `{"order":[...]}`）决定谁发言及顺序 → 逐角色顺序生成
  （各自模型/记忆/工具集）→ 群聊系统提示含 buildGroupOrchestrationHint（保持自身身份，
  `[From role: xxx]` 前缀历史仅参考）→ maybeSummarizeAfterGroupRound。
- **SillyTavern 互通**：TavernCharacterCard JSON+PNG 导入导出，`OperitTavernExtension
  (operit_character_card_v1)` 扩展字段保留专有配置。

**rikkahub：Assistant——全人格配置中心**

- `rikkahub: app/.../data/model/Assistant.kt`：模型（chatModelId/temperature/contextMessageLimit）+
  Prompt（systemPrompt/regexes（SillyTavern 风格正则改写：findRegex/replaceString/affectingScope
  USER|ASSISTANT/visualOnly）/messageTemplate（Pebble）/presetMessages（开场白））+
  **注入系统**（modeInjectionIds 模式注入 + lorebookIds 世界书 → PromptInjectionTransformer 按
  InjectionPosition（BEFORE/AFTER_SYSTEM_PROMPT、TOP/BOTTOM_OF_CHAT、**AT_DEPTH + injectDepth**）+
  priority + role 注入；Lorebook entry = RegexInjection(keywords/useRegex/caseSensitive/scanDepth/
  constantActive 触发)）+ 能力开关（enableMemory/useGlobalMemory/enableWebSearch/mcpServers/localTools/
  enabledSkills/workspaceId）+ 展示（avatar/background/quickMessageIds）。
- **SillyTavern 导入**（`rikkahub: app/.../ui/pages/assistant/detail/AssistantImporter.kt`）：
  PNG 卡用 metadata-extractor 读 tEXt chunk 匹配 `[chara:` 前缀正则提取 Base64 → JSON；
  spec 字段路由 chara_card_v2/v3 两个 Parser（TAVERN_PARSERS 策略模式）→
  data.{name/first_mes/system_prompt/description/personality/scenario} 拼装 systemPrompt +
  first_mes 为 presetMessages；PNG 另存为聊天背景。

**apex：AgentRole——6 字段轻量人设**

- `apex: app/.../ui/screen/settings/AgentRole.kt`：name/userTitle/roleDefinition/systemPrompt/style/
  replyLanguage 六字段；AgentSettings.agentRoles + activeRoleId；`patchConfig` 6 字段热切换
  （app 层 15+ 调用点）。
- 优点：切换零成本（改 AgentConfig 即生效）；缺点：**无世界书/lorebook、无角色卡导入、无群聊编排、
  无角色级工具白名单与记忆绑定**。

### 差距分析与借鉴

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 角色绑定资源 | 模型 + 记忆空间 + 工具白名单 | 模型 + 记忆池 + MCP 集 + 注入集 | 无（全局共享） |
| 世界书/lorebook | 无（有 tag 体系） | **有（位置/深度/优先级/关键词触发）** | 无 |
| 角色卡导入 | SillyTavern JSON+PNG 双向 | SillyTavern PNG tEXt/JSON 导入 | 无 |
| 群聊编排 | **LLM planner rounds**（独家） | 无 | 无 |
| 热切换 | 需重建服务 | 切 assistant（配置随会话） | patchConfig 即时 |

- **P95（Task 4-e，后续）**：apex 落地角色卡导入 = 新增 SillyTavern PNG tEXt/JSON v2 解析器
  （app 层纯 JVM，参照 `AssistantImporter.kt` 的 TAVERN_PARSERS 策略模式）→ 映射进 AgentRole
  （description/personality/scenario → roleDefinition+systemPrompt；first_mes → 开场消息）→
  `/persona:<id>` 斜杠命令切换 activeRoleId。
- 中期：AgentRole 扩展 allowedToolIds/allowedSkillIds（对齐 operit 的角色级安全边界——这对 apex 的
  109 工具尤其重要）+ lorebook 注入（对齐 rikkahub 的 InjectionPosition/depth/priority 模型，
  可直接挂进 EnginePrompts 分段装配）。

## 4.4 消息分支与对话树（本次对比最重要的单一特性）

### rikkahub：MessageNode——「不是树，胜似树」

`rikkahub: app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`：

```kotlin
data class Conversation(val id: Uuid, val assistantId: Uuid, val title: String,
    val messageNodes: List<MessageNode>, ...) {
    val currentMessages get() = messageNodes.map { it.messages[it.selectIndex] }  // 线性视图
}
data class MessageNode(val id: Uuid, val messages: List<UIMessage>,  // 同一槽位的候选数组
    val selectIndex: Int = 0, @Transient val isFavorite: Boolean = false) {
    val currentMessage get() = messages[selectIndex]
}
```

**设计本质**：把「对话树」降维成「**节点列表 + 每节点候选数组 + 选中下标**」。

1. **线性渲染免费获得**：`nodes.map { it.messages[selectIndex] }` 就是当前对话——UI 的 LazyColumn
   直接 `itemsIndexed(messageNodes, key=node.id)` 渲染 `node.currentMessage`，**没有任何树的遍历成本**。
2. **regenerate/edit 有机创建分支**：
   - 重新生成（`ChatService.regenerateAtMessage`）：`handleMessageComplete(messageRange = 0..<nodeIndex)`
     只传前缀；GenerationLoop **预创建空 assistant UIMessage 复用同一 ID** → collect 时
     `updateCurrentMessages` 里「按 message.id 匹配则替换，否则 append 且 selectIndex 指向新消息」→
     **新回复自动成为新分支并被选中，重试也不产生重复分支**（幂等）。
   - 编辑（`editMessage`）：`node.copy(messages = node.messages + UIMessage(role, parts),
     selectIndex = node.messages.size)`——**编辑 = 新分支**，原文永远不丢。
   - 删除（`buildConversationAfterMessageDelete`）：从候选中过滤该 id，
     `selectIndex.coerceAtMost(lastIndex)`，候选空则删节点。
   - fork 会话（`forkConversationAtMessage`）：复制节点到 targetIndex+1，节点/消息换新 id，
     **本地附件物理复制**（copyWithForkedFileUrl），标题去重 "title(n)"。
3. **存储**：`MessageNodeEntity`（message_node 表：id PK / conversation_id FK CASCADE / node_index 排序列 /
   messages = JSON 序列化的 List&lt;UIMessage&gt; / select_index）；更新 = 事务内
   deleteByConversation + insertAll 全量重写 + FTS 重建；**json_each() SQL 统计** token/每日消息
   （`MessageNodeDAO.kt:47-85`）。

   | 列 | 类型 | 作用 |
   |----|------|------|
   | id | Uuid PK | 节点稳定标识（LazyColumn key） |
   | conversation_id | FK CASCADE | 会话归属，删会话级联删节点 |
   | node_index | Int | 排序列（线性顺序） |
   | messages | JSON | 候选数组 List&lt;UIMessage&gt;（分支本体） |
   | select_index | Int | 当前选中候选（线性视图投影源） |

4. **UI**：`ChatMessageBranchSelector`（ChatMessageBranch.kt）——仅 `messages.size>1` 时显示
   `◀ 2/3 ▶`，`onUpdate(node.copy(selectIndex±1))`。
5. **无效分支清理**：checkInvalidMessages——未执行工具且不可恢复的候选从节点移除并 selectIndex-1。

   分支数据流全景：

   ```
   用户点「重新生成」
     → ChatService.regenerateAtMessage(nodeIndex)
     → handleMessageComplete(messageRange = 0..<nodeIndex)   只传前缀
     → GenerationLoop 预创建空 UIMessage(同 ID)
     → 流式 chunk → updateCurrentMessages
          ├─ id 已存在 → 替换（重试幂等）
          └─ id 不存在 → append + selectIndex 指向新候选（自动选中新分支）
     → 落库：事务内 deleteByConversation + insertAll + FTS 重建
     → UI: 该节点渲染 ◀ 2/2 ▶，左右切换 = node.copy(selectIndex±1)
   ```

### operit：聊天级分支 + 消息变体

- `ChatEntity.parentChatId`——**聊天级分支**（整段对话 fork，不是单消息重roll）；
  `MessageVariantEntity` + `MessageEntity.selectedVariantIndex`——消息级变体（同一消息的多个版本）。
- 两层机制并存但**不统一**：变体不是节点候选、聊天分支不是消息树；UI 上没有 rikkahub 那种
  「◀ 2/3 ▶」的一目了然。

### apex：完全线性，无分支

- `ChatHistoryMessage` 是线性扁平列表，**无 parentId**；引擎恢复仅 user/agent 文本对；
  regenerate 直接覆盖（旧版本丢失）。

### apex 落地设计建议（P93 / Task 4-c，后续）

```
方案：ConversationTree 纯 JVM 核心 + ChatHistoryMessage 增加 node 元数据

core/agent-engine（纯 JVM，可测）                app 层（薄包装）
┌────────────────────────────────┐              ┌──────────────────────────────┐
│ ConversationTree               │              │ AgentChatHistoryController    │
│  nodes: List<MsgNode>          │ ◄─────────── │  归档时记录分支               │
│  MsgNode(candidates, selIdx)   │              │ AgentMessageActions.kt       │
│  fun currentLinear(): List<M>  │              │  +「从此处分支」入口          │
│  fun branchAt(idx): NodeRef    │              │ UI: ◀ n/total ▶ 选择器       │
│  fun appendCandidate(...)      │              │  (对齐 ChatMessageBranch)     │
└────────────────────────────────┘              └──────────────────────────────┘
         ▲ 数据兼容
ChatHistoryMessage + parentId/branchId（默认 null 向后兼容；
ignoreUnknownKeys 反序列化安全——旧数据零迁移读入）
```

- **关键决策 1：抄 rikkahub 的「节点候选」而不是通用树**——线性渲染免费、UI 成本一个选择器组件、
  regenerate/edit 天然映射为 appendCandidate；通用树（每消息 parentId）反而要写「当前路径」回放逻辑。
- **关键决策 2：核心放 core/agent-engine 纯 JVM**——173 个纯 JVM 测试的覆盖能力直接复用；
  app 层只做 UI 与持久化映射。
- **关键决策 3：存储沿用 SharedPreferences JSON 或仿 FileTaskStore 文件式 store**
  （filesDir/chats/{id}.json 原子写）——**不**为此引入 Room（引 Room 是另一个量级的工程）。
- 风险：apex 恢复路径「工具调用配对丢失」是既有债，分支叠加会放大它——落地顺序应为
  **先修 toolCallId 配对恢复，再上分支**。

## 4.5 提示词工程

### 三方现状

**rikkahub：双引擎变量 + Transformer 管线**

- **引擎 1 内置变量**（`rikkahub: app/.../data/ai/transformers/PlaceholderTransformer.kt` +
  DefaultPlaceholderProvider）：12 个内置变量替换 `{{key}}` 与 `{key}`（ignoreCase）：
  cur_date / model_id / model_name / locale / timezone / system_version（"Android SDK v36 (16)"）/
  device_info（BRAND MODEL）/ battery_level（BatteryManager）/ nickname / char（助手名）/ user 等；
  `PlaceholderInfo(displayName: @Composable, resolver: (PlaceholderCtx) -> String)`——
  **@Composable displayName 使设置页能渲染本地化变量名列表**（变量文档即 UI）；
  扩展点 PlaceholderProvider/PlaceholderBuilder。
- **引擎 2 模板**（TemplateTransformer.kt）：每助手 messageTemplate（默认 `{{ message }}`），
  Pebble 引擎 + AssistantTemplateLoader（cacheKey=assistantId）；上下文变量
  message/role/**time/date 取自消息自身 createdAt 而非当前时间**（源码注释明说：保证多次请求
  渲染稳定，**不破坏 prompt 缓存**）。
- **引擎 3 简单替换**（StringUtils.applyPlaceholders）：titlePrompt/suggestionPrompt/compressPrompt 用
  `{locale}/{content}/{target_tokens}/{additional_context}`。
- **PromptInjectionTransformer**：世界书/模式注入按 位置（BEFORE/AFTER_SYSTEM_PROMPT、
  TOP/BOTTOM_OF_CHAT、AT_DEPTH+injectDepth）× 优先级 × role 三维注入（§4.3）。
- 完整 Input Transformer 链：TimeReminder → PromptInjection → Placeholder → DocumentAsPrompt →
  Ocr → Template → WorkspaceReminder；Output 链：ThinkTag → Base64ToLocalFile → Regex。

**operit：SystemPromptConfig 713 行动态装配**

- `operit: core/config/SystemPromptConfig.kt`（712 行）getSystemPrompt 分段装配：
  包列表（渐进披露入口）→ 工作区 → 工具段 → 角色设置 → Skills → 记忆附件……
  每段都可被 ToolPkg 的 systemPromptComposeHooks 改写。
- 自动总结 SUMMARY_PROMPT 四段结构（核心任务状态/互动情节/对话历程/关键信息）可自定义 section overrides。

**apex：EnginePrompts 分段装配 + Global Rules**

- `apex: core/agent-engine/.../engine/EnginePrompts.kt` buildSystemPrompt 分段拼装：
  身份/角色/工具策略/权限/环境/连接服务/工具目录/skills/SessionContext/GlobalRules/Thinking——
  结构与 operit 同源思路，但**没有变量引擎、没有模板系统、没有注入位置模型**。

### apex 可借鉴点（P91/P92 / Task 4-b）

1. **变量引擎**：apex 挂点已明确——`AgentChatViewModel.sendMessage` 发送前展开 `{{var}}`；
   `AgentConfig.additionalSystemContext` 组装点注入会话级变量；`EnginePrompts.buildSystemPrompt`
   是系统级注入终点。存储仿 `customModePresets` 模式（AgentSettings 加 promptVariables 字段）。
   **核心层保持纯字符串，展开逻辑放 app 层**（薄包装原则，core 不感知 Android 的
   BatteryManager/device_info）。内置变量集可对齐 rikkahub 12 个 + apex 特有的
   `{{workspace}}/{{sandbox}}/{{mcp_servers}}`。
2. **模板库**：完全可复制 ModePresets 模式（issue #168 的既有设计）：
   core 层 @Serializable data class + AgentSettings 持久化 + SettingsRepository 读写 +
   `/template:<id>` 斜杠命令（SlashCommand 加 Template 子类型 + SUPPORTED_TYPES + Router 分支）+
   聊天页快捷卡片。
3. **缓存稳定纪律**：任何时间类变量（cur_date/time）取值要么绑定消息 createdAt（rikkahub 方案），
   要么会话级缓存一次——**绝不每轮变化**，否则亲手打爆 provider 前缀缓存。

## 4.6 搜索

### 三方现状

**rikkahub：19 家供应商 + JSON-schema 工具参数 + 引用协议**

- 接口（`rikkahub: search/src/main/java/me/rerere/search/SearchService.kt`，387 行）：
  `parameters(options): InputSchema?`（**每家自带搜索工具参数 schema，暴露给 LLM**）+
  `scrapingParameters` + `@Composable Description()`（设置页 UI）+ `search/scrape` Result 方法 +
  companion 19 路 sealed dispatch。
- 19 家（`search/` 目录实测）：Tavily / Exa / Zhipu / Doubao / Bing / SearXNG / LinkUp / Brave / Metaso /
  Ollama / Perplexity / Firecrawl / Jina / Bocha / RikkaHub（官方代理）/ Grok（xAI Responses API 包装成
  搜索）/ Tinyfish / Serper / **CustomJs（用户写 JS + QuickJS fetch 自定义搜索源）**。
- **高级参数下放给模型**：Exa 的 type/startPublishedDate/includeDomains 等参数直接出现在工具 schema 里，
  模型自己决定搜学术还是搜新闻；统一 SearchResult(answer?/items[title,url,text,publishedDate?,highlights]/images[])。
- **引用协议**：工具 description 内置 `[citation,domain](id)` 格式说明 + items 加 6 位随机短 id +
  `![](url)` 图片嵌入指令 + "Today is {date}"——**引用规范写进工具说明，模型输出可渲染引用**。
- **原生搜索去重**：`shouldUseExternalWebSearch = assistant.enableWebSearch && BuiltInTools.Search !in model.tools`
  ——Gemini 有原生搜索则跳过外部工具（省 token + 免 API key）。

**operit：浏览器抓取为主**

- 搜索主要走浏览器自动化（StandardBrowserSessionTools 的 snapshot/navigate）+ 内置少量搜索工具；
  无成体系的 API 型搜索供应商层。

**apex：爬虫回退链 + 原生搜索注入**

- `apex: core/tool-registry/.../builtin/WebTools.kt`（669 行）WebSearchTool **三级回退**：
  DDG-HTML → DDG-Lite → Bing（`attempt("duckduckgo-html") ?: attempt("duckduckgo-lite") ?: attempt("bing")`，
  全免 Key 爬虫，P0 修复过单一端点被反爬的问题）；WebFetchTool 四模式正文提取。
- `LlmConfig.webSearch` 原生 Provider 搜索（7 种模式按 Provider 注入请求体）+ SearchCitations 引用提取。
- `BuiltinSearchMcpTransport`（app/search/mcp/）把 web_search/web_fetch 以**进程内 MCP** 暴露——
  外部 MCP 客户端也能用 apex 的搜索。
- **无任何 API-key 型搜索供应商**（Tavily/Exa/Brave/LinkUp 皆无）。

### 差距分析与借鉴（P94 / Task 4-d）

- **SearchProvider 抽象层**是明确缺口：把 `searchWithFallback` 的硬编码三级链抽成
  `SearchProvider` 接口 + 注册表（Tavily/Exa/Brave/LinkUp/SearXNG 起步），Key 从 vault 注入
  （`VaultStore` + `EncryptedPrefsVaultStore` 已有加密存储先例）；爬虫链保留为
  `CrawlerSearchProvider`（零 Key 兜底）——**分级：API 供应商优先、爬虫兜底**。
- 学 rikkahub 的三点：① 每供应商**自带 JSON-schema 参数**（高级参数下放给模型，而不是
  埋在设置页里）；② 工具 description 写**引用协议**（apex 已有 SearchCitations，补
  `[citation,domain](id)` 渲染闭环）；③ 原生搜索与外部搜索**互斥去重**（model.tools 检测）。
- `BuiltinSearchMcpTransport` 委托 WebSearchTool 自动受益——供应商层加好后，MCP 通道免费升级。

## 4.7 终端与沙箱

### 三方现状

```
operit：独立终端 App（submodule）      rikkahub：workspace 模块
┌────────────────────┐            ┌────────────────────┐
│ com.ai.assistance   │            │ :workspace          │
│ .operit.terminal    │            │ PRoot rootfs 安装/  │
│ Ubuntu 24.04 ARM64  │            │ 补丁 + shell 运行器  │
│ PRoot（默认）/chroot │            │ + termux-terminal-  │
│ Python/Node/vim/    │            │   view（复用）       │
│ SSH/tmux 多会话      │            └────────────────────┘
└─────────┬──────────┘
          │ OperitTerminalManager（检测/下载引导）
          ▼
  StandardTerminalCommandExecutor（AI 工具）

apex：双模块自研
┌───────────────────────┐   ┌──────────────────────┐
│ :terminal-emulator     │   │ :terminal-native      │
│ Kotlin VT100           │──▶│ C++17 热路径          │
│ VtParser/ScreenBuffer/ │   │ （性能敏感路径下沉）    │
│ TerminalCore/Utf8…     │   └──────────────────────┘
└─────────┬─────────────┘
          ▼ terminal.exec/create/run/observe/wait/snapshot
          ▼ PRoot 沙箱（MCP 暴露，见 mcp-sandbox.md）
```

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 终端形态 | **独立终端 App**（submodule，Ubuntu 24.04 PRoot/chroot 可选） | workspace 模块（PRoot rootfs 安装/补丁 + termux-terminal-view 视图） | **双模块自研**（Kotlin VT100 + C++17 native 热路径） |
| 环境完整性 | Python/Node/vim/SSH/tmux + **SSH/SFTP 文件系统** + 多会话 | rootfs 安装 + 补丁 + shell 运行器 | PRoot Ubuntu rootfs（T72）+ 包清单 rootfs-packages.txt |
| VT 仿真 | 终端 App 内（submodule 实现） | termux-terminal-view 复用 | **自研 VtParser/ScreenBuffer/TerminalCore** |
| AI 工具接入 | createOrGetSession/executeCommandInSession（非流式聚合） | workspace 绑定助手 | terminal.exec/create/run/observe/wait/snapshot + PRoot 沙箱 MCP |
| 测试 | submodule 侧 | —— | **93 个终端用例**（T85TerminalParityTest 29 + TerminalCoreTest 56 + T82CapabilityTest 8），T82 能力矩阵 150+ 项 |

- **apex 在 VT 仿真上领先**：T85 奇偶校验套件覆盖 colon-style truecolor SGR、C1 控制码（0x9B CSI/
  0x85 NEL/0x9D OSC）、DECSCUSR/DA1/DA2/DSR 响应、REP 重复等深水区行为（`apex: terminal-emulator/
  src/test/.../T85TerminalParityTest.kt`）；C++17 native 层承接热路径。
- **operit 在环境完整性上领先**：独立 App 意味着终端有自己的生命周期（不被主 App 拖累）、
  Ubuntu 24.04 ARM64 默认/chroot 可选、SSH/SFTP 可作为文件系统工作区、tmux 多会话——
  「一台真电脑」的完成度更高。
- **rikkahub 的定位最轻**：workspace 是给助手跑代码/处理文件用的，不是给用户当终端用的——
  但它的 rootfs 安装/补丁流水线值得 apex 的 `build_full_rootfs.sh` 对照（apex 已有等价物）。

### 借鉴

- apex 的 `ubuntu-rootfs-t72.md` / `mcp-sandbox.md` 路线继续走；可补的是 operit 的
  **SSH/SFTP 作为文件系统**（远程工作区）与**多会话管理**（terminalState.sessions 模型）。

## 4.8 扩展生态

### 三方现状

- **operit：ToolPkg 市场 + 技能双轨**。市场统一安装 scripts/ToolPkg/Skills/MCP（GitHub 仓库源，
  `ToolPkgMarketOrigin` 记录来源）；技能是 Anthropic SKILL.md 风格
  （`Downloads/Operit/skills/<name>/SKILL.md`，YAML frontmatter），GitHub 导入
  （codeload.github.com zip + 下载池 + SKILL_ID_PATTERN 校验），
  `tools/github/import_anthropic_skills.py` 同步官方 skills 仓库。
- **rikkahub：无插件 + QR 供应商分享**。分享协议 `"ai-provider:v1:" + Base64(JSON(ProviderSetting,
  models 剥离))`——zxing QRCodeWriter 画 BitMatrix（512px 主题色）、quickie 扫码 + MLKit 相册识别 +
  粘贴板三通道导入；前缀版本号预留格式演进。**社群传播成本最低的供应商配置方式**。
- **apex：插件 SDK（AIDL 进程间）+ ClawHub/ModelScope 市场 + 10 个捆绑技能**。
  插件是独立 App（plugin-api/plugin-host 两模块 + plugin-workflow/plugin-web-automation 两个官方插件）；
  技能是 apex-skill-v1 JSON manifest（4 类型 composite/script/prompt/connector）+ SkillRegistry
  （幂等内置释放 + .disabled sidecar + 路径穿越防御）+ 市场 SKILL.md→prompt 型转换。

### 对比

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| 第三方代码执行 | **JS in-app（QuickJS）** | 仅 CustomJs 搜索 | **独立进程（AIDL）** |
| 安装单元 | .toolpkg ZIP / SKILL.md | 无 | 插件 APK / 技能 JSON |
| 市场 | GitHub 源统一市场 | 无 | ClawHub + ModelScope |
| 供应商配置分享 | 无 | **QR 码** | 无（P96 缺口） |
| 安全边界 | JS 沙箱 + hook 拦截 | 不适用 | 进程隔离（最硬） |

- apex 的进程隔离是**安全上最正确**的第三方代码方案，但**分发摩擦最大**（要装 APK）；
  operit 的 JS 是**摩擦最小**但安全面最大（QuickJS 里能回调原生工具）。
  这个权衡没有银弹——见第七节「不落地项」。

## 4.9 测试与质量

### 三方现状

| 轴 | operit | rikkahub | apex |
|----|--------|----------|------|
| JVM 测试 | 158 文件 | 85 文件 | **173 个 *Test.kt** |
| 设备测试 | 38（androidTest） | 13 | 0 |
| 特殊测试 | **JS 契约测试**（androidTest/js/**：QuickJS bridge 合约/browser 冒烟） | Migration_11_12 androidTest | 无 |
| 风格 | 纯函数断言为主（无 mock 依赖为核心） | 纯函数抽出便于测试（applyTimeReminder/transformMessages internal） | **JUnit4 + runTest + 手写 fake 学派**（FakeLlmClient ScriptedResponse + callLog / FakeToolRegistry / 假钟注入） |
| 测试焦点 | Provider payload 适配（EndpointCompleter×8/MediaLinkParser×6）/ 流式分块 / 工具轮标记 / key 池就绪 | Provider 请求构造 / StreamDecoder / UIMessage 迁移 / StreamTraceReplay 回放 | 引擎状态机 / catalog v4 / 终端 VT 奇偶 |
| CI 门禁 | ci/ 编排（PR 分类/仓库卫生/本地化/WebChat/ToolPkg/构建） | 常规 | **文件预算 + 反模式 + 括号平衡三门禁** |

apex 的三门禁（`apex: scripts/`）：

1. `check_file_size.sh`——main ≤1200 行 / test ≤1600 行，超限即 fail（可 PR 注释豁免但需理由）；
2. `check_code_quality.sh`——① `javaClass.getMethod` 反射派发（核心模块零容忍，KDoc 展示例外）；
   ② `printStackTrace()`（main 源零容忍）；③ 空 catch 块审计报告；
3. `kotlin_balance.py`——括号平衡检查（防 Kotlin 块注释嵌套陷阱类事故）。

三者加上 `quality-gate.yml` 编排，是三家中**对代码形态约束最严**的 CI。

### 评价

- operit 的 **JS 契约测试**是独门武器：ToolPkg 的 JS bridge 用 ADB 在真机跑合约——
  「动态语言接口」的回归只能这么做；apex 的插件 AIDL 接口同样需要契约测试思路（当前缺口）。
- rikkaHub 的 **StreamTraceReplay（trace 回放）**值得注意：把线上请求 trace 录下来回放进测试——
  apex 的 Diagnostics（llm-adapter）已有 trace 基础，可加回放测试。
- apex 的手写 fake 学派 + 假钟注入在「无 mock 框架」约束下做到了最高表达力，
  且 173 个纯 JVM 测试 × core 纯 Kotlin 分层 = **测试覆盖率的结构性优势**；
  缺设备测试（终端/UI 自动化路径无 androidTest）是已知取舍。

---

# 五、优点清单（分项目 ranked）

> 每条含 2-4 行说明 + 关键文件路径。排名综合「工程价值 × 稀缺度 × 对 apex 的可移植性」。

## 5.1 Operit Top 10

### 1. ToolPkg 生态（平台飞轮）
`.toolpkg` ZIP + manifest（hjson）+ 子包 + JS 脚本即工具 + WASM 模块 + UI 模块 + 10 余个管线钩子 +
JS 实现新 AI Provider——Android 上罕见的「不改宿主代码即可注入一切」的机制，且有市场与 1599 行格式文档背书。
→ `operit: core/tools/packTool/PackageManager.kt`（4101 行）/ `ToolPkgParser.kt`（1854 行）/
`core/tools/javascript/JsEngine.kt`（2767 行）/ `docs/TOOLPKG_FORMAT_GUIDE.md`

### 2. API Key Pool（小而美的完整实现）
互斥锁内选择 + UNTESTED/AVAILABLE/UNAVAILABLE 三态 + 持久化轮询游标 + 单 Key 兜底 +
并发可暂停的可用性测试器（StateFlow 驱动 UI 进度）。接口只有两个方法，落地成本半天级。
→ `operit: api/chat/llmprovider/ApiKeyProvider.kt`（111 行）/ `ApiKeyPoolAvailabilityTester.kt`（206 行）/
`data/model/ApiKeyInfo.kt` / `data/preferences/ModelConfigManager.kt`

### 3. 图记忆 DSL（LLM 抽取的幂等协议）
ParsedAnalysis 把「实体抽取」变成结构化协议：mergedEntities→updatedEntities→实体→links 的
固定落库顺序 + aliasFor 别名去重，LLM 输出的不确定性被 DSL 语法收编。检索侧 4 通道 RRF 融合 +
检索模拟调试对话框，可解释性做到产品级。
→ `operit: api/chat/library/MemoryLibrary.kt`（928 行）/ `data/repository/MemoryRepository.kt`（2814 行）/
`data/model/Memory.kt`

### 4. 双通道工具调用 + CLI 渐进披露
XML 流式协议（弱模型可用）与原生 tool call（强模型高效）并存，StructuredToolCallBridge 三方言归一 +
「工具结果缺失」占位防 400；CLI 模式只给 search/proxy 两工具，把渐进披露做到极致。
→ `operit: api/chat/llmprovider/StructuredToolCallBridge.kt` / `core/tools/AIToolHandler.kt`（491 行）/
`core/tools/climode/CliToolModeSupport.kt`（689 行）

### 5. 角色卡全资源绑定
每角色独立模型配置、独立记忆空间、独立工具白名单（allowedBuiltinTools/allowedPackages/allowedSkills/
allowedMcpServers）——角色不只是人设，是**安全与资源边界**。SillyTavern PNG/JSON 双向互通。
→ `operit: data/model/CharacterCard.kt` / `CharacterCardToolAccessResolver` /
`services/core/MessageCoordinationDelegate.kt`（2053 行，群聊编排）

### 6. 流式回滚（SAVEPOINT/ROLLBACK）
自研 `Stream<T>`（lock/unlock/clearBuffer 支持 UI 切聊天时锁流缓冲、切回重放）+
TextStreamRevisionTracker 让流式重试能**原子撤回已显示内容**——三家中唯一的流级事务。
→ `operit: util/stream/Stream.kt`（252 行）/ `RevisableTextStream.kt` / `plugins/StreamXmlPlugin.kt`

### 7. 工作流 DSL（可视化自动化）
5 种节点类型（Trigger/Execute/Condition/Logic/Extract）+ ParameterValue 上游引用（StaticValue |
NodeReference）+ 拓扑排序执行 + 5 种触发器（manual/schedule/tasker/intent/speech/app_open）+ 可视化拖拽编辑器。
→ `operit: data/model/Workflow.kt` / `core/workflow/WorkflowExecutor.kt`（1321 行）/ `WorkflowScheduler.kt`

### 8. FunctionType 模型分工（11 功能位 × 租约管理）
翻译用便宜模型、UI 控制用视觉模型、群聊编排用专用 planner 模型——每种功能独立配置，
MultiServiceManager 管理实例与 ServiceLease。apex 的 ModelRoleRouter 是它的自动化变体。
→ `operit: api/chat/enhance/`（MultiServiceManager）/ `EnhancedAIService.kt`

### 9. 权限分级实现（standard/accessibility/admin/debugger/root）
同一工具五套 ShellExecutor 实现按权限物理层分目录，ActionListener 工厂同构——「能力分级」不是
开关而是真实实现栈。apex 的 privilege 模块可对照升级。
→ `operit: core/tools/`（各 defaultTool/{standard,accessbility,admin,debugger,root} 目录）+
ShellExecutorFactory

### 10. 数据层纪律（迁移 + 备份验证 + 版本化偏好）
Room 20 步手写迁移含数据 SQL；versionedPreferencesDataStore 让偏好也有 schema 版本与四代迁移钩子；
备份恢复用 `DROP TABLE room_master_table` 强制全 schema 校验——数据工程的纪律巅峰。
→ `operit: data/db/AppDatabase.kt`（:485 drop 校验）/ `data/preferences/VersionedPreferencesDataStore.kt`

> 落选但值得记录：TokenUsageRecordEntity 按模型计价的 token 账本（billingMode/currency/单价）；
> 虚拟屏 Shower（C++ server + Binder 广播）；Web Chat（NanoHTTPD LAN API + A2A 协议 handler）。

## 5.2 RikkaHub Top 10

### 1. MessageNode 消息分支（本对比最重要单一特性）
「节点列表 + 候选数组 + 选中下标」三件套：线性渲染免费、regenerate/edit 有机成支、预创建消息 ID
让重试幂等、fork 物理复制附件。产品语义（探索不同回答方向）与工程简洁性（无树遍历）同时成立。
→ `rikkahub: app/.../data/model/Conversation.kt` / `data/db/entity/MessageNodeEntity.kt` /
`ui/pages/chat/ChatMessageBranch.kt`

### 2. Transformer 管线（输入/输出双链）
Input：TimeReminder → PromptInjection → Placeholder → DocumentAsPrompt → Ocr → Template →
WorkspaceReminder；Output：ThinkTag → Base64ToLocalFile → Regex。消息进出模型的所有变换收口成
可组合管线，每个 transformer 单测友好。
→ `rikkahub: app/.../data/ai/transformers/`

### 3. 19 家搜索供应商抽象（含参数 schema 协议）
每家自带 JSON-schema 工具参数（Exa 的日期/域名过滤直接给模型）、@Composable 设置页、统一
SearchResult 模型、`[citation,domain](id)` 引用协议写进工具描述；还有 CustomJs 让用户用 JS 自定义源。
→ `rikkahub: search/src/main/java/me/rerere/search/SearchService.kt`（387 行）+ 19 个 *Service.kt

### 4. 三级自定义 header/body + KeyRoulette
助手级 + 模型级 customHeaders/customBodies 在 GenerationLoop 合并（:393-399），加协议级内建头——
任何中转站怪癖都能配；KeyRoulette 用一个字符串字段 + LRU 文件（24h 过期）实现零心智负担的键池。
→ `rikkahub: ai/.../provider/Provider.kt`（TextGenerationParams:75）/ `ai/util/KeyRoulette.kt`（102 行）

### 5. 会话层状态管理（ConversationSession）
引用计数 acquire/release + 空闲驱逐 + NonCancellable 兜底落盘——聊天页/Web UI/前台 Service
共享同一会话状态，生成被杀也保证持久化。「状态的唯一 owner」教科书。
→ `rikkahub: app/.../service/ChatService.kt`（1390 行）/ ConversationSession / SessionManager

### 6. 提示词变量引擎（12 内置 + @Composable 文档即 UI）
PlaceholderInfo 的 displayName 是 @Composable——设置页自动渲染本地化变量名列表；
`{{key}}` 与 `{key}` 双语法 ignoreCase；扩展点 PlaceholderProvider。
→ `rikkahub: app/.../data/ai/transformers/PlaceholderTransformer.kt` + DefaultPlaceholderProvider

### 7. ModelRegistry DSL（从模型 ID 推断能力）
`defineModel { tokens("gpt","5","1"); notTokens("chat"); visionInput() }`——token 序列匹配 +
EXACT_ID_BONUS=1000 评分，用户拉取 /models 后能力自动标注，零手工配置。
→ `rikkahub: ai/src/main/java/me/rerere/ai/registry/ModelDsl.kt`（785 行）/ `ModelRegistry.kt`

### 8. Assistant 全人格（regexes/lorebook/注入位置模型）
SillyTavern 风格 regexes（findRegex/replaceString/affectingScope/visualOnly）+ 世界书
（位置 × 深度 × 优先级 × 关键词触发）+ 模式注入 + 预设消息——「角色扮演工程」的完整参数空间。
→ `rikkahub: app/.../data/model/Assistant.kt` / `data/ai/transformers/PromptInjectionTransformer.kt`

### 9. QR 供应商分享（社群传播协议）
`"ai-provider:v1:" + Base64(JSON)` 前缀版本化 + models 剥离只导配置；zxing 生成 + quickie 扫码 +
MLKit 相册识别 + 粘贴板三通道。配置分享的成本降到「拍张照」。
→ `rikkahub: app/.../ui/components/ui/ShareSheet.kt` / SettingProviderPage（导入侧）

### 10. 缓存友好的上下文管理
limitContext 滞回式阶梯截断（CONTEXT_KEEP_RATIO=0.5，前缀稳定命中 provider 缓存）+
alignContextStart 保证不拆散 tool call/结果对（拆了会被 400 拒）+ 模板 time/date 取消息自身
createdAt（多次请求渲染稳定）。三个细节都在为「省真金白银」服务。
→ `rikkahub: app/.../data/ai/GenerationLoop.kt` / `transformers/TemplateTransformer.kt`

> 落选但值得记录：嵌入式 Web 服务器（Ktor CIO + React web-ui + JWT/NSD）；
> MCP OAuth 2.1 动态客户端注册；StreamTraceReplay trace 回放测试；翻译挂在 UIMessage 流式更新。

---

# 六、apex-agent 差距矩阵与本次落地

## 6.1 差距矩阵

| 能力 | operit 有 | rikkahub 有 | apex 现状 | 本次落地 |
|------|-----------|-------------|-----------|----------|
| **Key 池化** | ✅ MultiApiKeyProvider（三态+游标+测试器） | ✅ KeyRoulette（字段切分+LRU） | ⚠️ 数据预埋（apiKeys 列表 + KeyRotationMode 枚举），无实现 | **P90 / Task 4-a** |
| **提示词变量** | 部分（角色设置渐进注入） | ✅ 12 内置变量 + 双语法 | ❌ 无 | **P91 / Task 4-b** |
| **提示词模板库** | 部分（functional prompts） | ✅ Pebble 模板 + 助手模板 | ❌ 无（ModePresets 可复用骨架） | **P92 / Task 4-b** |
| **消息分支** | ⚠️ 聊天级 parentChatId + 消息变体 | ✅ MessageNode 完整方案 | ❌ 完全线性 | P93 / Task 4-c（后续） |
| **搜索供应商框架** | ❌（浏览器抓取为主） | ✅ 19 家 + 参数 schema 协议 | ⚠️ 爬虫三级回退 + 原生注入，无 API 供应商 | **P94 / Task 4-d** |
| **角色卡导入** | ✅ SillyTavern JSON+PNG 双向 | ✅ PNG tEXt/JSON 导入 | ⚠️ AgentRole 仅手建 | P95 / Task 4-e（后续） |
| **供应商分享** | ❌ | ✅ QR 码三通道 | ❌ 无 QR 库 | P96 / Task 4-f（后续） |
| 世界书/lorebook | ❌ | ✅ 位置/深度/优先级 | ❌ | 未排期 |
| 群聊编排 | ✅ LLM planner rounds | ❌ | ❌ | 未排期 |
| 图记忆混合检索 | ✅ 4 通道 RRF | ❌ | ⚠️ cs-mem 图遍历 | 未排期（cs-mem 演进线） |
| 流式回滚 | ✅ SAVEPOINT/ROLLBACK | ⚠️ 消息 ID 幂等 | ⚠️ 不重试已输出轮 | 未排期 |
| 多 FunctionType 模型位 | ✅ 11 种 + 租约 | ⚠️ 全局/助手两级 | ✅ 五角色路由（已有等价物） | —— |

## 6.2 落地任务与模块路径

**P90 — Key 池化（Task 4-a）**
- `core/llm-adapter`：KeyPoolLlmClient 请求层包装器（模仿 `app/di/DynamicLlmClient` 委托模式），
  捕获 `LlmException.Http(429/401)` 按 KeyRotationMode（DISABLED/SEQUENTIAL/ON_ERROR/ON_RATE_LIMIT）换 Key 重试。
- `app` SettingsRepository：securePrefs 改多键存储（`provider_api_key_{id}_{idx}`）+ hydrate 全列表。
- `app/di`：装配 + 并发可用性测试器（对齐 operit ApiKeyPoolAvailabilityTester 的可暂停语义）。
- 风险：`ModelRuntimeRegistry` 以 LlmConfig 相等为缓存键——**轮换必须留在请求层包装器**。

**P91 — 提示词变量引擎（Task 4-b）**
- `app`：AgentChatViewModel.sendMessage 展开点 + AgentSettings.promptVariables 存储
  （仿 customModePresets 模式）；内置变量 12 个对齐 rikkahub + apex 特有（workspace/sandbox/mcp_servers）。
- 纪律：时间类变量绑定消息 createdAt 或会话级缓存一次（不破坏前缀缓存）；
  **核心层保持纯字符串，展开逻辑只在 app 层**。

**P92 — 提示词模板库（Task 4-b）**
- 复用 ModePresets 骨架（issue #168）：core 层 @Serializable data class + AgentSettings 持久化 +
  `/template:<id>` 斜杠命令（SlashCommand 加 Template 子类型 + Router 分支）+ 聊天页快捷卡片。

**P93 — 消息分支（Task 4-c，后续批次）**
- `core/agent-engine`：ConversationTree 纯 JVM 核心（节点候选模型，见 §4.4 设计图）。
- `app`：ChatHistoryMessage + parentId/branchId（默认 null 向后兼容）+ AgentMessageActions 分支入口 +
  ◀ n/total ▶ 选择器。前置：先修 toolCallId 配对恢复。

**P94 — 搜索供应商框架（Task 4-d）**
- `core/tool-registry`：WebTools.kt 的 searchWithFallback 抽成 SearchProvider 接口 + 注册表
  （Tavily/Exa/Brave/LinkUp/SearXNG 起步）；Key 走 `VaultStore`/`EncryptedPrefsVaultStore`；
  爬虫链降级为 CrawlerSearchProvider 兜底；`BuiltinSearchMcpTransport` 自动受益。
- 学 rikkahub：供应商自带 JSON-schema 参数 + 引用协议进工具描述。

**P95 — 角色卡导入（Task 4-e，后续批次）**
- `app`：SillyTavern PNG tEXt `[chara:` Base64 / JSON v2 解析器（纯 JVM）→ 映射 AgentRole
  （description/personality/scenario → roleDefinition/systemPrompt；first_mes → 开场消息）+
  `/persona:<id>` 斜杠命令切换 activeRoleId。

**P96 — 供应商分享（Task 4-f，后续批次）**
- 新增 `com.google.zxing:core`（Maven Central 纯 Java，符合仓库依赖白名单政策）+
  `app` ProviderQrCodec（ProviderConfig+Profile JSON，apiKeys 默认剥离需用户确认）+
  设置页导出 QR / 导入扫码（对齐 rikkahub `ai-provider:v1:` 前缀版本化协议）。

---

# 七、结论

## 7.1 三项目的互补性

```
            平台生态（operit）                产品化（rikkahub）
           ToolPkg/市场/角色卡/群聊          消息分支/供应商/UI/搜索
                    ╲                             ╱
                     ╲                           ╱
                      ▼                         ▼
                ┌─────────────────────────────────┐
                │   apex-agent（自主智能体内核）     │
                │  109 工具 v4 · 6 模式 · cs-mem    │
                │  C++ 终端 · 逆向 MCP Host · CI 纪律│
                └─────────────────────────────────┘
                     ▲                         ▲
                    ╱                           ╲
                   ╱                             ╲
            执行内核（apex 已领先）            数据纪律（operit 已领先）
```

- operit 教 apex「**怎么长出生态**」：渐进披露的提示词结构、角色作为资源边界、工具即包、
  市场与格式文档先行。
- rikkahub 教 apex「**怎么把对话做成产品**」：消息分支、变量引擎、供应商抽象、缓存友好截断、
  会话状态唯一 owner。
- apex 反过来有两者都没有的东西：**执行内核的工程深度**（见 7.2）。

## 7.2 apex 的独特优势（本次落地不丢掉）

1. **终端 VT 引擎**：自研 VtParser/ScreenBuffer + C++17 热路径 + 93 个奇偶/能力测试用例
   （`terminal-emulator` 双模块）——operit 靠 submodule、rikkahub 靠 termux-view，apex 是唯一自研。
2. **cs-mem 仿生记忆**：差分摄取 → 轨迹蒸馏 → FSM 旁路 → 梦境巩固 + Actor 无锁写入
   （`platform/cs-mem`）——写入管线三家中最强，缺的只是检索侧多通道（§4.2 建议）。
3. **逆向 MCP Host**：把内置工具反暴露给外部 MCP 客户端（`platform/mcp-host` +
   `docs/reverse-mcp-host.md`）——operit/rikkahub 都是纯客户端。
4. **Tool System v4 渐进激活**：catalog 三元工具 + 64 工具/48KB 预算 + 名称/schema 清洗 +
   请求级自愈降级——比 operit 的 use_package 粒度更细、比 rikkahub 的「全量或无」更精细。
5. **CI 门禁纪律**：文件预算 + 反模式 + 括号平衡三门禁（`scripts/`）——本次所有落地代码
   必须继续通过（单文件 ≤1200/1600 行、禁反射派发/printStackTrace、原子写、手写 fake 测试）。

## 7.3 明确不落地的项与理由

| 项 | 理由 |
|----|------|
| ToolPkg 式 JS 工具生态 | QuickJS 依赖 + JS 内回调原生工具的安全面（`JsToolCallInterface`）与 apex 的进程隔离插件路线冲突；WASM/JS 双运行时维护成本高。短期以「技能市场 + AIDL 插件」覆盖，长期观察 QuickJS 依赖是否值得引入 |
| ObjectBox 图记忆 | 与 cs-mem 的 Room 图存储重复；融合方向应是 **cs-mem 检索侧吸收 operit 的多通道 RRF 思想**，而不是再引一个数据库引擎 |
| 群聊编排（planResponseOrder rounds） | 依赖角色级资源绑定（模型/记忆/工具白名单）先落地；优先级低于单角色体验闭环 |
| Navigation3 / Material Expressive 升级 | UI 框架代际升级风险大、与本次能力域无关；Liquid Glass 主题已是差异化资产 |
| 本地推理（MNN/llama.cpp） | 体积与维护成本高；OpenAI 兼容端点（Ollama 等）已可覆盖 |

## 7.4 后续路线图（优先级序）

1. **P90 Key 池化** → 立即提升多供应商可用性（429 自愈）；
2. **P91/P92 变量 + 模板库** → 提示词工程补课，成本低收益面广；
3. **P94 搜索供应商框架** → 检索质量从「爬虫能搜到」升级为「API 级精准」；
4. **P93 消息分支** → 前置修 toolCallId 配对恢复，然后 ConversationTree 纯 JVM 核心；
5. **P95/P96 角色卡导入 + QR 分享** → 社群与生态入口；
6. cs-mem 检索多通道 RRF + 图可视化审计（吸收 operit 检索哲学）；
7. AgentRole 角色级工具白名单 + lorebook 注入模型（吸收两家角色工程）。

## 7.5 一句话收束

> operit 是「装满工具的瑞士军刀平台」，rikkahub 是「打磨到极致的聊天艺术品」，
> apex-agent 要做的是**拿着手术刀的自主智能体**——这次对比的全部意义，
> 就是把前两者的生态机制与产品细节，移植进后者的执行内核，而不稀释内核的纪律与深度。

---

## 附 A：术语映射表（同一概念在三仓库的不同命名）

| 概念 | operit | rikkahub | apex |
|------|--------|----------|------|
| 供应商配置 | `ApiProviderType` 枚举 + ModelConfigData | `ProviderSetting`（sealed） | `ProviderConfig`（数据类） |
| 模型能力标注 | supportsVision/supportsAudio 手工字段 | `Model.abilities` + ModelRegistry 推断 | `ModelProfile` + CapabilityResolver |
| 消息单元 | `MessageEntity`（Room 行） | `UIMessage`（part 列表，JSON 存列） | `StoredMessage` / `ChatHistoryMessage` |
| 消息分支 | `MessageVariantEntity` + selectedVariantIndex | `MessageNode(candidates, selectIndex)` | （无） |
| 角色/人格 | `CharacterCard` | `Assistant` | `AgentRole` |
| 角色扮演世界书 | （tag 体系近似） | `Lorebook`（RegexInjection 触发） | （无） |
| 工具接口 | `ToolExecutor.invoke(tool): ToolResult` | `Tool`（data/ai/tools/） | `AgentTool.execute` |
| 工具目录发现 | `use_package` + CLI search/proxy | （无） | `tool_search/tool_open/tool_list` |
| MCP 工具命名 | 包内工具注册 | `mcp__{server}__{tool}` | `mcp__{server}__{tool}`（McpToolNaming） |
| 流式抽象 | 自研 `Stream<T>`（lock/unlock/savepoint） | `Flow<StreamChunk>`（okhttp-sse） | 回调式 onChunk（SSE 手解析） |
| 系统提示装配 | `SystemPromptConfig.getSystemPrompt` | Transformer 管线（注入式） | `EnginePrompts.buildSystemPrompt` |
| 上下文截断 | enableMaxContextMode + 自动总结 | limitContext 滞回 + alignContextStart | HybridCompressor 三级 |
| Key 轮换 | `MultiApiKeyProvider`（游标+三态） | `KeyRoulette`（随机/LRU） | `KeyRotationMode`（枚举预埋） |
| 前台保活 | `AIForegroundService` | `ChatGenerationForegroundService`（acquire/release 计数） | platform/persistence 看门狗引擎 |
| 工作区 | ChatEntity.workspace + workspaceEnv | `workspaceId`（PRoot rootfs） | code-workspace 模块 + PRoot 沙箱 |
| 技能 | SKILL.md（Anthropic 风格） | `enabledSkills` | apex-skill-v1 JSON manifest |

## 附 B：证据文件速查索引

| 主题 | operit | rikkahub | apex |
|------|--------|----------|------|
| Key 池 | `api/chat/llmprovider/ApiKeyProvider.kt` / `ApiKeyPoolAvailabilityTester.kt` | `ai/util/KeyRoulette.kt` | `core/llm-adapter/.../ModelProfile.kt`（预埋） |
| Provider 层 | `api/chat/llmprovider/AIServiceFactory.kt` | `ai/.../provider/ProviderSetting.kt` / `Provider.kt` | `core/llm-adapter/.../StreamingOpenAiClient.kt` |
| 执行循环 | `api/chat/EnhancedAIService.kt` | `app/.../data/ai/GenerationLoop.kt` / `service/ChatService.kt` | `core/agent-engine/.../engine/ApexAgentEngine.kt` |
| 工具系统 | `core/tools/packTool/PackageManager.kt` / `ToolRegistration.kt` | `data/ai/tools/`（SearchTools 等） | `core/tool-registry/.../catalog/ToolRequestBudget.kt` / `builtin/WebTools.kt` |
| 记忆 | `data/repository/MemoryRepository.kt` / `api/chat/library/MemoryLibrary.kt` | `data/ai/tools/MemoryTools.kt` | `platform/cs-mem/`（actor/distill/dream/bypass） |
| 消息分支 | `data/db/AppDatabase.kt`（MessageVariantEntity） | `data/model/Conversation.kt` / `data/db/entity/MessageNodeEntity.kt` | `app/.../agent/ChatHistoryManager.kt`（线性） |
| 角色卡 | `data/model/CharacterCard.kt` / `services/core/MessageCoordinationDelegate.kt` | `data/model/Assistant.kt` / `ui/pages/assistant/detail/AssistantImporter.kt` | `app/.../settings/AgentRole.kt` |
| 提示词 | `core/config/SystemPromptConfig.kt` | `data/ai/transformers/PlaceholderTransformer.kt` / `TemplateTransformer.kt` | `core/agent-engine/.../engine/EnginePrompts.kt` |
| 搜索 | （浏览器自动化为主） | `search/src/main/java/me/rerere/search/SearchService.kt` + 19 Service | `core/tool-registry/.../builtin/WebTools.kt` / `app/.../search/mcp/BuiltinSearchMcpTransport.kt` |
| 终端 | `terminal/`（submodule）+ `OperitTerminalManager` | `workspace/`（PRoot rootfs） | `terminal-emulator/` + `terminal-native/` + `docs/mcp-sandbox.md` |
| 测试/CI | `src/test`（158）+ `androidTest`（38） | `src/test`（85）+ `androidTest`（13） | `src/test`（173）+ `scripts/check_*.sh` / `kotlin_balance.py` |

（完）

