# 思考程度系统 — Coding 模式专属七档思考阶梯

> **Coding 页专属**的思考深度控制体系（7 深度档 + 1 个 AUTO 元档）。
> Agent 聊天页是**另一套独立**的六档体系（NONE/LIGHT/STANDARD/DEEP/
> MAXIMUM + AUTO，见 `core/agent-engine` 的 `thinking` 包与
> `docs/modes-guide.md` 相关章节）——两页面各自解释自己的思考阶梯，
> 枚举 / 画像 / 选档器 / 效能统计 / 文案 / 持久化字段全部分立，
> 仅共享引擎执行内核（见「二、三层注入架构」）。

## 一、七档阶梯总览

深度档按推理强度递增（ordinal 比较 `<` 语义在全链路成立）：

| # | 档位 | 定位 | thinking_budget | reasoning_effort | 迭代倍率 | 工具输出预算 | 压缩倍率 |
|---|------|------|-----------------|------------------|----------|--------------|----------|
| 0 | NONE | 直接执行，不生成推理 | 0 | —（不发字段） | ×0.8 | 6000 | 1.2（更早压缩） |
| 1 | LIGHT | 1-2 句简思后行动 | 256 | LOW | ×0.9 | 7000 | 1.0 |
| 2 | STANDARD | 标准三步思维链（默认） | 1024 | MEDIUM | ×1.0 | 8000 | 1.0 |
| 3 | DEEP | 多路径五步推理 | 4096 | HIGH | ×1.2 | 9000 | 1.0 |
| 4 | MAXIMUM | 七步思维树穷举 | 16384 | MAX | ×1.5 | 10000 | 1.0 |
| 5 | **ULTRACODE** | 编码特化深推理闭环 | 32768 | MAX | **×2.0** | **12000** | 1.0 |
| 6 | **APEXCODE** | 架构级穷举 + 对抗性自审 | 65536 | MAX | **×3.0** | **16000** | **0.9（更晚压缩）** |
| 7 | AUTO | 元档：发送前预检选档 + 深水区升级 | —（预检后取该档） | —（不动原生 effort） | 跟随 | 跟随 | 跟随 |

### 两档深水档的设计依据

- **ULTRACODE（编码闭环档）**：面向「改代码」这件事本身的推理强度——
  依赖地图 → 候选改法 → 风险排序 → 最小修改 → 即时验证 → 回归扫描的
  六步闭环。迭代 ×2.0 给足「每处改动立即验证」的轮次空间；
- **APEXCODE（巅峰档）**：把每次改动当工程评审对待——架构定位 →
  影响半径测绘 → 多方案对比矩阵 → 对抗性自审自己的 diff → 全量验证
  矩阵（构建/lint/测试/回读）→ 证据链汇报。迭代 ×3.0、压缩倍率 0.9
  （唯一 <1 的档：更晚压缩，保留更多上下文支撑证据链）、专属**五问
  终检清单**（目标/副作用/遗漏/不变量/回归，内嵌 APEXCODE 编码指令）；
- **两档 Provider 侧 effort 同为 MAX**：这是 OpenAI o-series /
  DeepSeek-R1 等 Provider 接受的天花板值——档间差异完全由提示词
  推理框架与执行策略（迭代/预算/压缩）承载，不下发 Provider 不存在
  的参数值（诚实映射，不发明协议）。

### AUTO 自适应（元档，coding 自治）

AUTO **不在引擎侧逐轮选档**——全部在 coding 侧自治（引擎从不接收
AUTO）：

1. **发送前预检**（`CodeAdaptiveThinkingSelector.select`）：VM 在每次
   发送前按复杂度评分解析出具体深度档，决策进系统消息 +
   `uiState.adaptiveDecision`（选择器旁回显）；
2. **运行中深水区升级**（`escalateOnDeepWater` 观察器）：本轮工具
   调用 >30 且最近 3 次成败滑窗内 ≥2 次失败 → 热切换 ULTRACODE +
   系统消息说明。

预检评分模型（每项写进决策理由）：

| 维度 | 条件 | 加分 |
|------|------|------|
| 文本长度 | >500 字 +2；>1500 字 +3 | 长需求含隐式多步 |
| 多步指示词 | 先/然后/接着/步骤/first/then/step/plan | +2 |
| 代码/命令含量 | ``` 围栏 / `$ ` / git·npm·gradle 等 | +2 |
| **编码意图**（coding 专有） | @file 引用 / code_edit·code_grep 语义词 / 「实现/重构+函数/类」形态 | **+1** |
| 风险词 | rm -rf/删除/uninstall/格式化/刷机/权限 | +3（硬保底 DEEP） |
| 错误史 | 上一轮 run 有失败 | +2 且显式升一档 |

总分 → 档位：`<3 LIGHT · 3..5 STANDARD · 6..8 DEEP · 9..11 MAXIMUM ·
>11 ULTRACODE`。另有上轮深水区规则：上轮调用 >30 且错误 ≥2 →
本轮开局即 ULTRACODE。

**APEXCODE 永不被自动选择**——它只能由用户显式指定。这是刻意的成本
防线：×3.0 迭代 + 全量验证矩阵的开销只应由人决策触发（KDoc 与组合
穷举测试双重锁定该不变量）。

## 二、三层注入架构（映射打底 + 旋钮反补，引擎零改动）

```
┌─────────────────────────────────────────────────────────────┐
│ ① 引擎六档通用画像（agent-engine ThinkingProfile）           │
│    CodeThinkingLevel.toAgentLevel() 映射后交给引擎：         │
│    NONE..MAXIMUM 一一对应；深水两档映射 MAXIMUM 打底；        │
│    增量经 ② 反补。引擎 Thinking Instructions 段照常注入      │
├─────────────────────────────────────────────────────────────┤
│ ② 旋钮补偿（CodeThinkingProfile.compensated* 纯函数）        │
│    深水两档超出 MAXIMUM 的增量经 patchConfig 反补：          │
│    ULTRACODE 迭代 ×(2.0/1.5)、APEX ×(3.0/1.5)、             │
│    输出预算抬到档位预算（12000/16000）、APEX 压缩 /0.9；     │
│    基数取构造时快照（幂等——任意次换档不污染基数）；          │
│    其余档位基数复位，由引擎自己的倍率体系接管                 │
├─────────────────────────────────────────────────────────────┤
│ ③ 编码特化指令（CodeThinkingPrompts.thinkingDirective）      │
│    读码纪律/改动集思维/不变量守护/依赖地图/对抗性自审/        │
│    APEX 五问终检清单——经 CodeAgentEngine.refreshContext      │
│    拼进 additionalSystemContext（与 Rules/Workspace 并列）    │
├─────────────────────────────────────────────────────────────┤
│ ④ 模型原生 reasoning 参数（ReasoningEffort）                  │
│    app 层映射：CodeThinkingLevel → effort 枚举名 → 默认      │
│    ModelProfile.reasoningEffort → DynamicLlmClient 重建 →    │
│    StreamingOpenAiClient 按 Provider 差异化下发               │
└─────────────────────────────────────────────────────────────┘
```

- NONE 档各层全空（真正的「不思考」）；
- AUTO 档：预检解析出具体深度档后走该档全链路；③ 注入 DEEP 级编码
  纪律兜底；④ 不动原生 effort（Provider 级参数无法逐轮切换就不越权）。

## 三、Coding 页接入

| 环节 | 落点 |
|------|------|
| 选择器 UI | `CodeThinkingSelector`（输入栏上方 AssistChip + 下拉，8 项全列 + AUTO 预检决策回显 + 指南入口） |
| 档位指南 | `CodeThinkingGuideSheet`（阶梯总表直读 `CodeThinkingProfile.forLevel` + 逐档卡片可直切） |
| 持久化 | `AgentSettings.codeThinkingLevel`（与聊天页 `thinkingLevelOverride` **互不干扰**，两模式各自记忆） |
| 引擎同步 | `CodeAgentEngine.updateThinkingLevel`：三通道（①档位映射 ②旋钮补偿 ③编码指令） |
| 原生 effort | T1 通道（默认 Profile 持久化 + 即时重建）；AUTO 档不动 effort |
| AUTO 可解释 | 预检决策进 `adaptiveDecision` + 「🧠 自适应预检：…」系统消息；深水区升级「⚠️」系统消息 |
| 档位效能 | `CodeThinkingEvolutionTracker`（见 [long-tasks.md](long-tasks.md) §5） |

### 档位描述文案

全部使用 Coding 屏自有键（`code_thinking_*_desc`，en/zh 对称，
含 ULTRACODE/APEXCODE 两档）——不跨模块复用聊天页字符串。

## 四、相关文件索引

| 文件 | 职责 |
|------|------|
| `core/code-engine/.../code/thinking/CodeThinkingLevel.kt` | coding 七档枚举（7 深度档 + AUTO）+ toAgentLevel 映射 + fromName 解析 |
| `core/code-engine/.../code/thinking/CodeThinkingProfile.kt` | 七档画像表 + APEX 五问清单 + compensated* 旋钮补偿纯函数 |
| `core/code-engine/.../code/thinking/CodeAdaptiveThinkingSelector.kt` | AUTO 预检选档 + 深水区升级观察器（纯函数） |
| `core/code-engine/.../code/thinking/CodeThinkingEvolutionTracker.kt` | 档位效能统计（工作区 × 档位聚合） |
| `core/code-engine/.../code/thinking/CodeThinkingPrompts.kt` | 编码特化思考指令（7 档 + AUTO 兜底 + APEX 五问内嵌） |
| `core/code-engine/.../code/CodeAgentEngine.kt` | updateThinkingLevel 三通道 + 旋钮基数快照 |
| `app/.../ui/screen/code/CodeThinkingSelector.kt` | Coding 屏选择器（含预检决策回显） |
| `app/.../ui/screen/code/CodeThinkingGuideSheet.kt` | 档位指南弹层 |
| `app/.../di/CodeModule.kt` | 选档器 DI 注册 + 引擎装配 |
| `app/.../di/CodeLongTaskModule.kt` | 效能统计 DI（长任务中心共用） |

（Agent 聊天页六档体系的文件在 `core/agent-engine/.../engine/thinking/`
与 `AgentChatDialogs.kt` / `ModeGuideSheet.kt`，与本文档无耦合。）

## 五、常见问题

**Q: 为什么 APEXCODE 不能被 AUTO 自动选？**
成本防线。×3.0 迭代 + 全量验证矩阵在最长的任务上意味着显著 token
开销与时长。把「最强档」交给启发式自动触发，等于让一个评分函数替
用户做成本决策——这类决策应保留给人。

**Q: ULTRACODE/APEXCODE 的 thinking_budget 会被 Provider 钳制吗？**
32768/65536 是发给支持 `thinking.budget_tokens` 类参数 Provider 的
请求值；个别 Provider 有自己的上限会按其实现钳制（服务端行为），
客户端按档位如实下发，不预先自我阉割。

**Q: 换档位对正在运行的会话生效吗？**
生效。patchConfig 是运行时通道，下一次迭代即用新画像；原生 effort
经 Profile 重建，下一次请求生效。

**Q: 两模式档位会互相覆盖吗？**
不会。持久化字段分立（`codeThinkingLevel` vs `thinkingLevelOverride`），
枚举/画像/选档器也各自独立。模型原生 effort 挂在默认 Profile 上是全局
的（与「切换模型」一致的全局语义），档位语义本身（画像/指令/迭代倍率）
是每引擎实例独立的。

**Q: 为什么不直接把七档加进引擎枚举？**
那是 v1.2 走过的弯路：Agent 聊天页跟着出现了编码档（模式错位）。正确
形态是本档体系归 coding（CodeThinkingLevel），引擎保持六档通用内核，
深水两档「映射 MAXIMUM 打底 + 旋钮反补」——引擎零改动、Agent 零感知。

## 六、兼容性与迁移

- **序列化兼容**：CodeThinkingLevel 以枚举名（String）持久化
  （codeThinkingLevel），旧值 none/light/standard/deep/maximum/
  ultracode/apexcode/auto 全部继续有效（fromName 大小写不敏感）；
  未知值返回 null → 兜底 STANDARD，不崩溃不静默错档；
- **历史值迁移**：Agent 侧若残留 ultracode/apexcode 的
  thinkingLevelOverride 值，净化后的六档 `valueOf` 走 runCatching
  兜底链（不覆盖现有选择），无需数据迁移；
- **ReasoningEffort 不变**：llm-adapter 层零改动（深水两档映射既有
  的 MAX），Provider 差异化逻辑无需感知新档。

## 七、性能预算

七档画像的执行策略倍率对请求预算的影响（以 maxIterations=40 的
coding 引擎为例；深水两档 = 基数补偿 × 引擎 MAXIMUM 倍率的端到端值）：

| 档位 | 有效迭代上限 | 工具输出预算 | 压缩阈值（base 0.8） |
|------|--------------|--------------|----------------------|
| NONE | 32 | 6000 | 0.667（更早压缩） |
| LIGHT | 36 | 7000 | 0.8 |
| STANDARD | 40 | 8000 | 0.8 |
| DEEP | 48 | 9000 | 0.8 |
| MAXIMUM | 60 | 10000 | 0.8 |
| ULTRACODE | 80 | 12000 | 0.8 |
| APEXCODE | 120 | 16000 | 0.889（更晚压缩） |

APEXCODE 的 ×3.0 上限（120 轮）是刻意天花板：给「架构级穷举 + 全量
验证矩阵」留足轮次，同时仍是有限值——失控保护是档位设计的隐含前提。
