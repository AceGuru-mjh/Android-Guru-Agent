# 全能 Agent：生活技能矩阵 × 渐进披露 × 聊天自动记忆 × 首轮问候极简

> **v2 演进说明（Hub 生态）**：本文的「70 技能全内置」是 v1 形态。
> 当前 APK 内置已收敛为 13 个核心技能，其余 62 个生活/通用技能迁往官方
> 仓库 [apex-skill-hub](https://github.com/Ultra-Guru/apex-skill-hub)
> （市场「官方仓库」源直装），斜杠菜单只放行已安装且已启用的技能。
> 渐进披露 / 自动装备 / 聊天记忆等机制不变。详见
> [hub-ecosystem.md](hub-ecosystem.md)。

> 状态：已落地（v1）
> 主题：Agent 模式从「编码向」扩展为「全领域」——聊天、生活、健康、职业、消费决策一视同仁；
> 技能注入从「全量常驻」演进为「目录 + 会话装备」双层结构；对话内容自动沉淀为长期记忆；
> 首轮问候从大段能力介绍收敛为一句话反问。
> 前置：[tool-system-v4.md](tool-system-v4.md)（工具渐进披露——本篇把同一思想推广到技能层）·
> [memory-and-workflow-research.md](memory-and-workflow-research.md)（记忆调研——本篇补齐「对话内容」记忆缺口）·
> [chat-memory-v2.md](chat-memory-v2.md)（聊天记忆 v2：结构化账本与批量蒸馏——本文 §4 的现行架构）

---

## 0. 一句话

把 Tool System v4 的「目录 + 按需装载」推广到技能层（46 个内置领域技能不再全量注入），
给对话装上「自动记忆管线」（双速捕获 + 主动召回 + 评分生命周期三点创新，v2 演进为
结构化账本），
用提示词双保险驯服首轮问候（你好 → 一句话「需要帮助吗？」），
并为网页自动化补上「独立条件等待 + 页面类型推断」两块最后拼图。

## 1. 用户反馈与根因

| 反馈 | 表象 | 根因 |
| --- | --- | --- |
| 「发你好，回一堆编程内容」 | 首轮问候收到大段能力介绍 | 系统提示词只有 "You are not a chatbot" 的任务导向，无闲聊语义；模型把一切输入当任务 |
| 「Agent 只会编程」 | 生活/情感/消费类问题回答质量平 | 内置技能 10 个全部是编码域（debugging/api-design/sql-optimizer…） |
| 「聊天记不住我」 | 跨会话忘记用户偏好/背景 | cs-mem 只记 UI 轨迹；知识图谱 MCP 只能靠模型显式调用写入；对话内容无人沉淀 |
| 技能库扩张的隐形墙 | 46+ 技能全量注入会撞请求体积上限 | `SkillRegistry.getPromptInjections()` 把所有启用技能全文注入每次请求 |

## 2. 技能矩阵：10 → 46 → 70（assets/skills 目录）

> Round 2 新增 24 个（§9.2）：创作表达/人际情感/生活技能/数码消费/兴趣爱好全
> 覆盖——小红书文案、起名、恋爱军师、解梦、MBTI、收纳、清洁、搬家、减脂
> 食谱、咖啡茶饮、户外徒步……详见下文 §9.2。

新增 36 个 prompt 型技能（apex-skill-v1 manifest，随 APK 打包、首启幂等释放），覆盖：

- **情感与陪伴**：chat-companion 情感陪伴聊天 · emotional-value 情绪价值 · psychology-healer 心理疏导（含危机信号转介红线）
- **生活服务**：life-assistant 百事通 · travel-planner 旅行规划 · cooking-master 家常菜（含翻车抢救） · pet-care 宠物养护 · party-planner 聚会策划 · gift-chooser 礼物挑选
- **健康美丽**：health-consultant 健康顾问（含就医转介边界） · fitness-coach 健身私教 · fashion-stylist 穿搭 · beauty-skincare 护肤
- **娱乐消费**：movie-cinephile 影视推荐 · music-companion 音乐 · game-strategist 游戏攻略 · shopping-deals 比价 · house-hunting 买房 · car-buying 购车 · constellation-star 星座（娱乐性质声明）
- **学习职业**：language-coach 语言学习 · translation-master 翻译 · writing-coach 写作 · study-methods 学习方法 · exam-tutor 备考 · resume-crafter 简历 · interview-coach 面试 · career-navigator 职业 · workplace-wisdom 职场 · public-speaking 演讲 · negotiation-master 谈判 · brainstorm-partner 头脑风暴 · time-gtd 时间管理
- **家庭育儿**：parenting-guide 科学育儿
- **常识边界**：finance-literacy 理财（不构成投资建议） · legal-consult 法律（不构成法律意见）

每个技能 = 领域方法论（触发场景 → 分步方法 → 输出约定），与既有 debugging.json 同构；
tags 含 2-3 个高区分度中文短词（「菜谱」「穿搭」「简历」）供自动装备匹配。

## 3. 技能渐进披露（v2 注入架构）

### 3.1 双层结构

```
系统提示词
├── ## Skill Catalog (46 installed)     ← 全部技能的一行摘要（恒在）
│     - travel-planner: 旅行规划师 — 行程/预算/签证全流程
│     - cooking-master: 家常菜大师 — 备料/火候/调味公式
│     （指引：任务命中领域 → 先调 skill_activate(skill_id)）
└── ## Active Skills                    ← 仅「已装备」技能的方法论全文
      （默认 0 个；FIFO 上限 8 个 ≈ 30KB 封顶）
```

### 3.2 三条装备路径

| 路径 | 入口 | 成本 |
| --- | --- | --- |
| 模型路 | `skill_activate(skill_id)` 工具（工具结果即时返回方法论全文 + 写入激活集） | 1 次工具调用 |
| 用户路 | 斜杠 `/skill:<id>`（SlashCommands.handle 路由时同步装备） | 0 |
| **自动路（新）** | [SkillAutoActivator]：消息与技能 tags/id/name 字面命中 → 发送前预激活（≤ 2 个/条） | 0 |

自动路规则刻意保守：关键词长度 ≥ 2、只认字面子串、取命中词最长（区分度最高）的前两个、
禁用/已装备技能跳过。

### 3.3 关键决策

- **激活集进程级共享**：Agent 模式、Coding 模式（`CodeModule` 的内层引擎）、子代理
  （`SubAgentRunner` 工厂）读同一 `SkillActivationStore` 单例——skill_activate 写入后
  三处下一轮全部生效；对比 tool_open 的独立激活存储（请求级语义），技能装备是
  会话级「用户能力配置」，共享才符合直觉，且避免 46 技能全量注入撞爆子代理请求。
- **legacy 通道保留**：`getPromptInjections()`（全量）不变；`skillActivation=null` 的
  引擎实例（单测/旧装配）行为与升级前完全一致。
- **市场热加载不变**：安装/卸载/启停 → `SkillRegistry.changes` → `SkillHotReloader`
  增量同步工具注册（#151 原链路）；prompt 注入本就每轮重读。市场新装技能即时出现在
  目录里，可被 skill_activate / 自动装备使用。

## 4. 聊天自动记忆（ChatMemoryPipeline）

> v2 演进说明：本节描述的架构已升级为「结构化账本 + 批量蒸馏 + 评分召回 +
> 巩固遗忘」——详见 [chat-memory-v2.md](chat-memory-v2.md)。本节保留 v1
> 设计动机，现行机制摘要如下。

### 4.1 设计要点（v2 现行架构）

1. **双速捕获**：启发式层零成本同步落账（自我披露句式匹配：「我叫/我喜欢/我在做…」
   整句入账，≤ 3 句/轮，重复即重要性抬升）；LLM 蒸馏层批量异步跑（待蒸馏轮次
   攒批 ≥ 6 轮开蒸，SUMMARY 角色路由便宜模型，写入门禁 + 结构化 JSON 协议，
   防御式解析）——不依赖模型主动调工具，成本与漏记双收敏。
2. **主动召回**：引擎 execute 入口自动检索并注入 `## Remembered About You` 段
   （画像评分 Top-10（重要 × 新近 × 常用）+ 关键词直接命中补充 + 按消息关键词
   命中的主题实体观察，≤ 900 字符）——不等模型想起调 memory 工具，记忆自己到场；
   被注入的条目自动打点（访问计数 +1），越用越牢。
3. **结构化账本**：自动记忆沉淀到独立的 `MemoryLedgerStore`
   （`<filesDir>/chat_memory/ledger.json`，带重要性/可信度/时间戳/访问计数），
   与 memory MCP 的知识图谱物理分离（模型显式写入的主题实体仍走图谱）；
   每批蒸馏后巩固 pass（近重复合并 + 容量淘汰）保证账本收敛不发散；
   用户经 MemoryScreen「聊天记忆」分区可见、可删、可清空。

### 4.2 接线（v2）

```
ApexAgentEngine.execute()
  ├─ 入口：memoryObserver.recallChatMemory(text) → chatMemoryNote → 系统提示词段
  └─ finally：取本轮最后一条有正文的 Assistant → memoryObserver.onConversationTurn()
                └─ CsMemSessionObserver（合流点）→ ChatMemoryPipeline
                       ├─ captureHeuristic / updateTone / captureMilestones（同步，零成本）
                       ├─ enqueueTurn（持久化待蒸馏队列，进程被杀不丢）
                       └─ distillBatch（攒批触发，SupervisorJob + IO fire-and-forget）
                            └─ 巩固 pass（近重复合并 + 容量淘汰）
```

引擎侧扩展是 [ExecutionMemoryObserver] 两个带默认实现的挂点（未注入观察者的
单测/子代理零改动）；失败语义全部防御式：recall 失败返回 null（段落省略），
沉淀失败只记日志、蒸馏失败队列保留重试——记忆子系统绝不阻断主对话。

## 5. 首轮问候极简（双保险）

1. **静态层**：`EnginePrompts` 新增 `## Conversational Openness` 段（所有轮次）——
   问候/闲聊是合法输入；短消息短回复；生活/情感/非编程话题一等公民；
   禁止倒能力清单（除非显式问「你能做什么」）。
2. **动态层**：[SmallTalkDetector]（纯函数，DP 切段词表匹配，宁可漏判不可误判：
   「你好，帮我写个脚本」不判为问候）判定「会话首条用户消息且纯问候」时，
   注入 `## First-Turn Greeting (THIS TURN)` 硬约束——
   一句话（中文 ≤ 20 字），示例形状『需要帮助吗？想聊哪方面的？』，
   禁止工具调用/清单/markdown，然后停止。

「首轮」口径：追加本轮消息前历史中无任何 User 消息（含从 ConversationMemory
载入的跨启动历史）——用户回访会话后再打招呼会得到有上下文的自然回应而非机械模板。

## 6. 网页自动化收尾（最后两块拼图）

| 新能力 | 工具 | 说明 |
| --- | --- | --- |
| 独立条件等待 | `browser_wait_for(mode, value, timeout_ms)` | selector 出现 / 文本包含 / URL 包含三态轮询（300ms 步进，上限 60s）——补齐 navigate 之外（点击/提交后 SPA 异步刷新）的等待手段；同步 JS 检查不经 Promise（evaluateJavascript 回调不等 Promise 完成） |
| 页面类型推断 | `browser_page_type` | 一次 JS 采信号（密码框/输入框/正文/视频/列表/搜索框/导航/文本量）→ Kotlin 分类：auth/form/article/video/search/list/portal/generic + 每类的 focus 策略建议——gap audit「缺失 H（页面类型推断）」的框架层落点 |

两工具均走 TracedTool 可观测包装 + 人工接管守卫；`browser_wait_for` 的 value 入
trace 脱敏。

## 7. 验证

| 项 | 结果 |
| --- | --- |
| kotlinc 全模块编译（CI static-analysis 同款命令） | logging / llm-adapter / tool-registry / agent-engine / code-tools / code-engine / code-workspace / mcp-host 全绿 |
| 既有单测（本地 JUnitCore） | RolePromptTest 9 · EngineToolSystemV4Test 7 · BundledSkillsTest+SkillHotReloaderTest 17 · HumanAssist/ModePresets/DecisionPoint 42 —— 全绿 |
| 新行为 sanity（本地） | SmallTalkDetector 8 问候 + 5 非问候判定 ✓；问候/记忆/目录/激活段渲染与门控 ✓；自动装备→注入→幂等→FIFO 链路 ✓ |
| 门禁 | check_file_size（AgentChatViewModel 恰 1200 = 上限内）· check_code_quality · kotlin_balance · 工具 id 唯一性 —— 全绿 |
| 46 技能资产 | JSON 合法性逐个验证；id 唯一；promptInjection 1.5-2.3KB |

## 8. 后续方向

- 技能目录的语义召回（当前字面 tags 匹配；可给技能描述建嵌入索引，跨语言命中）
- 记忆蒸馏的 ADD/UPDATE/NOOP 决策（~~当前只增；冲突事实靠内容级去重兜底~~
  v2 已落地：写入门禁 + new/update 结构化提取协议，见 chat-memory-v2.md）
- `## Remembered About You` 的设置页开关与 MemoryScreen 联动展示
  （#218 已有总开关；v2 记忆页已按类别分区展示自动记忆）

## 9. Round 2 增量：共情引擎 × 里程碑记忆 × 安装即装备

### 9.1 共情引擎（ChatSignalDetector，core 新文件）

情绪四分类（低落/焦虑/愤怒/欢快，词表命中数最多者胜，平票负向优先）+
模糊求助检测（归一化整串精确匹配：「怎么办」命中、「我电脑蓝屏了怎么办」
不命中），在 `ApexAgentEngine.execute` 入口与首轮问候检测同位运行，产出
`ChatSignal` 注入系统提示词：

- `## Emotional Attunement (THIS TURN)`：负向情绪 → 先处理心情再处理任务
  （一句真诚回应，不说教、不命令式安慰、不毒鸡汤；任务紧随——行动本身就是
  安慰）；欢快 → 同频具名庆祝；危机措辞 → 安全优先转介；
- `## Vague Request (THIS TURN)`：无宾语短求助禁止猜主题倾倒长文，
  强制「一个聚焦追问 + 2-4 个具体选项」；
- 话题延续规则（静态层追加）：非工具型闲聊回答落定后可收尾一行 ≤3 个延伸
  方向（工具重任务/长回答/用户要结束时跳过）。

护栏：任务指令拦截（帮我写/翻译成/生成一…里的情绪词是素材不是用户状态）、
代码围栏拦截、超长拦截——宁可漏判不可误判（与 SmallTalkDetector 同哲学）。

### 9.2 技能矩阵 46 → 70（+24）

创作表达/人际情感 12（小红书文案、朋友圈文案、短视频脚本、诗词对联、故事
大王、起名大师、恋爱军师、人情世故、社交礼仪、解梦趣谈、MBTI人格、节日
祝福）+ 生活技能/数码消费/兴趣 12（收纳整理、养花种草、清洁妙招、家电急救、
搬家攻略、数码选购、隐私卫士、一周食谱、减脂食谱、咖啡茶饮、读书搭子、
户外徒步）。边界纪律：清洁剂混用剧毒警示/断电断气红线/减脂极端节食拒绝/
户外安全红线置顶/解梦与 MBTI 全程娱乐性质声明/恋爱军师拒绝操控话术。

### 9.3 记忆增强：情绪基调 + 里程碑日历（ChatMemoryPipeline R2）

- **情绪纵览**：每轮与引擎同一套口径判定情绪 → 滚动窗（近 6 个情绪轮）
  → 「用户近况」单条基调（有界替换，v2 起落账本 STATE 类）→ 召回注入
  `### 用户近况`——跨对话开头模型就知道用户近来状态；
- **里程碑日历**：句子级「日期模式（X月X日/周X/明天/节日…）× 人生事件
  标记（生日/面试/领证/搬家…）」双命中 → 「用户里程碑」（≤2 条/轮，v2 起
  容量淘汰封顶）→ 召回注入 `### 里程碑`——Agent 的时间感知能力；
- 创作护栏：帮我写/文案/翻译…里的日子是素材，不入库。

### 9.4 市场热加载闭环收口：安装即装备

链路核实：install/uninstall/setEnabled → `SkillRegistry.changes`（SharedFlow）
→ `SkillHotReloader.resync` → 工具表热更；`getSkillDigests` 实时 → 目录热更。
唯一缺口：新装技能只 enabled 不在激活集，方法论并未装载。修复：
- `MarketInstallManager.installSkillFromJson`（全部安装路径收口点：URL/模板/
  文件/魔搭/GitHub/ClawHub）安装成功即 `activate`；
- `SkillInstallTool`（模型侧 skill_install 工具）同样接入，并修正历史遗留的
  「需重启生效」错误文案（热加载下无需重启）；
- 提示语升级：「已安装并装备 Skill：xx，下一轮对话即生效」。

### 9.5 Round 2 验证

| 项 | 结果 |
| --- | --- |
| kotlinc 全模块编译（main 源） | logging / llm-adapter / tool-registry / agent-engine 全绿（1306 classes） |
| ChatMemoryPipeline + KnowledgeGraphStore 提取编译 | 全绿（24 classes） |
| 新增 ChatSignalDetectorTest 16 + RolePromptTest 9 | 全绿 |
| BundledSkills/SkillHotReloader/ModePresets/EngineToolSystemV4/HumanAssist/DecisionPoint 72 | 全绿（回归零破） |
| 门禁 file_size / code_quality / kotlin_balance（8 个改动 kt） | 全绿；ApexAgentEngine 1194/1200（预算内） |
| 70 技能资产 | 24 新增 JSON 逐个验证；id 全局唯一；promptInjection 1.5-1.9KB |
