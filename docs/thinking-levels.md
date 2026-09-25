# 思考程度系统 — 七档思考阶梯（v1.2）

> Coding 模式与 Agent 模式共用的思考深度控制体系。
> v1.2 起 ThinkingLevel 从 6 档（NONE/LIGHT/STANDARD/DEEP/MAXIMUM/AUTO）
> 扩展为 **7 个深度档 + 1 个 AUTO 元档**，新增编码特化的 ULTRACODE 与
> APEXCODE 两档，并把思考档位完整接入 Coding 模式（选择器 / 持久化 /
> 编码特化指令 / 档位效能统计）。

## 一、七档阶梯总览

深度档按推理强度递增（ordinal 比较 `<` 语义在全链路成立）：

| # | 档位 | 定位 | thinking_budget | reasoning_effort | 迭代倍率 | 工具输出预算 | 压缩倍率 | 工具自检 | 终检清单 |
|---|------|------|-----------------|------------------|----------|--------------|----------|----------|----------|
| 0 | NONE | 直接执行，不生成推理 | 0 | —（不发字段） | ×0.8 | 6000 | 1.2（更早压缩） | ✗ | ✗ |
| 1 | LIGHT | 1-2 句简思后行动 | 256 | LOW | ×0.9 | 7000 | 1.0 | ✗ | ✗ |
| 2 | STANDARD | 标准三步思维链（默认） | 1024 | MEDIUM | ×1.0 | 8000 | 1.0 | ✗ | ✗ |
| 3 | DEEP | 多路径五步推理 | 4096 | HIGH | ×1.2 | 9000 | 1.0 | ✓ | ✗ |
| 4 | MAXIMUM | 七步思维树穷举 | 16384 | MAX | ×1.5 | 10000 | 1.0 | ✓ | ✓（三问） |
| 5 | **ULTRACODE** | 编码特化深推理闭环 | 32768 | MAX | **×2.0** | **12000** | 1.0 | ✓ | ✓（三问） |
| 6 | **APEXCODE** | 架构级穷举 + 对抗性自审 | 65536 | MAX | **×3.0** | **16000** | **0.9（更晚压缩）** | ✓ | ✓（**五问**） |
| 7 | AUTO | 元档：按任务复杂度逐轮选档 | —（选档后取该档） | —（不动原生 effort） | ×1.0* | 8000* | 1.0* | 跟随* | 跟随* |

\* AUTO 行数值为无决策时的占位回退；实际执行策略取
`AdaptiveThinkingSelector` 逐轮选出档位的画像。

### 两档新增档的设计依据

- **ULTRACODE（编码闭环档）**：面向「改代码」这件事本身的推理强度——
  依赖地图 → 候选改法 → 风险排序 → 最小修改 → 即时验证 → 回归扫描的
  六步闭环。迭代 ×2.0 给足「每处改动立即验证」的轮次空间；
- **APEXCODE（巅峰档）**：把每次改动当工程评审对待——架构定位 →
  影响半径测绘 → 多方案对比矩阵 → 对抗性自审自己的 diff → 全量验证
  矩阵（构建/lint/测试/回读）→ 证据链汇报。迭代 ×3.0、压缩倍率 0.9
  （唯一 <1 的档：更晚压缩，保留更多上下文支撑证据链）、专属**五问
  终检清单**（目标/副作用/遗漏/不变量/回归）；
- **两档 Provider 侧 effort 同为 MAX**：这是 OpenAI o-series /
  DeepSeek-R1 等Provider 接受的天花板值——档间差异完全由提示词
  推理框架与执行策略（迭代/预算/压缩）承载，不下发 Provider 不存在
  的参数值（诚实映射，不发明协议）。

### AUTO 自适应（元档）

AUTO 不携带静态指令，每轮由 `AdaptiveThinkingSelector` 按复杂度评分选档：

| 维度 | 条件 | 加分 |
|------|------|------|
| 文本长度 | >500 字 +2；>1500 字 +3 | 长需求含隐式多步 |
| 多步指示词 | 先/然后/接着/步骤/first/then/step/plan | +2 |
| 代码/命令含量 | ``` 围栏 / `$ ` / git·npm·apt·pip·gradle 等 | +2 |
| 风险词 | rm -rf/删除/uninstall/格式化/刷机/权限 | +3（硬保底 DEEP） |
| 错误恢复 | 最近 3 轮有失败 | +2 且显式升一档 |

总分 → 档位：`<3 LIGHT · 3..5 STANDARD · 6..8 DEEP · 9..11 MAXIMUM ·
>11 ULTRACODE`。另有深水区升级规则：工具调用 >30 且近期错误 ≥2 →
至少 ULTRACODE。

**APEXCODE 永不被自动选择**——它只能由用户显式指定。这是刻意的成本
防线：×3.0 迭代 + 全量验证矩阵的开销只应由人决策触发（KDoc 与
320 组合穷举测试双重锁定该不变量）。

## 二、三层注入架构（不重复注入）

思考档位的生效路径分三层，各层职责单一：

```
┌─────────────────────────────────────────────────────────────┐
│ ① 通用思考画像（ThinkingProfile.forLevel）                     │
│    引擎每轮经 ThinkingModeController 注入                     │
│    "## Thinking Instructions" 段：推理框架指令 +              │
│    迭代/压缩/工具输出预算倍率 + 工具自检/终检清单              │
│    —— 任何模式共用，引擎配置层（AgentConfig.thinkingLevel）   │
├─────────────────────────────────────────────────────────────┤
│ ② 编码特化指令（CodeThinkingPrompts.thinkingDirective）       │
│    Coding 模式专属：读码纪律/改动集思维/不变量守护/            │
│    依赖地图/对抗性自审等编码方法论                             │
│    —— 经 CodeAgentEngine.refreshContext 拼进                  │
│       additionalSystemContext（与 Rules/Workspace 段并列）     │
├─────────────────────────────────────────────────────────────┤
│ ③ 模型原生 reasoning 参数（ReasoningEffort）                   │
│    app 层映射：ThinkingLevel → effort 枚举名 → 默认           │
│    ModelProfile.reasoningEffort → DynamicLlmClient 重建 →    │
│    StreamingOpenAiClient 按 Provider 差异化下发               │
│    reasoning_effort / thinking.budget_tokens / enable_thinking│
└─────────────────────────────────────────────────────────────┘
```

- NONE 档三层全空（真正的「不思考」）；
- AUTO 档：① 随轮次动态（选档器决定）；② 注入 DEEP 级编码纪律兜底
  （自适应实际档位 ≥STANDARD 时全部适用）；③ 不动原生 effort——逐轮
  档位由引擎侧决定，Provider 级参数无法逐轮切换就不越权。

## 三、Coding 模式接入（v1.2）

v1.1 及之前 Coding 模式 `thinkingLevel` 在 CodeModule 硬编码 STANDARD，
无 UI 无切换。v1.2 补齐：

| 环节 | 落点 |
|------|------|
| 选择器 UI | `CodeThinkingSelector`（输入栏上方 AssistChip + 下拉，8 项全列） |
| 持久化 | `AgentSettings.codeThinkingLevel`（与聊天页 `thinkingLevelOverride` **互不干扰**，两模式各自记忆） |
| 引擎同步 | `CodeAgentEngine.updateThinkingLevel`：双通道（①patchConfig thinkingLevel ②存字段供 refreshContext 取②指令） |
| 原生 effort | 镜像 AgentChatViewModel.setThinkingLevel 的 T1 通道（默认 Profile 持久化 + 即时重建） |
| AUTO 可解释 | IterationStart 后拉取 `currentThinkingDecision()`，对话流插入「🧠 自适应选档：…」系统行（仅 AUTO 档） |
| 档位效能 | `ThinkingEvolutionTracker`（见 [long-tasks.md](long-tasks.md) §5） |

### 档位描述文案

ULTRACODE / APEXCODE 的画像描述与聊天页共享字符串键
（`chat_thinking_ultracode_desc` / `chat_thinking_apexcode_desc`）；
其余档位在 Coding 屏用编码语境专属文案（`code_thinking_*_desc`，
如 STANDARD = 「标准编码循环：读→改→验→回读，diff 最小化」）。

## 四、相关文件索引

| 文件 | 职责 |
|------|------|
| `core/agent-engine/.../engine/AgentConfig.kt` | ThinkingLevel 枚举（8 项）+ 三映射方法 |
| `core/agent-engine/.../engine/thinking/ThinkingProfile.kt` | 八档画像全量静态表 + APEX 五问清单 |
| `core/agent-engine/.../engine/thinking/AdaptiveThinkingSelector.kt` | AUTO 复杂度评分选档（纯函数） |
| `core/agent-engine/.../engine/thinking/ThinkingModeController.kt` | 引擎每轮钩子（画像解析/自检/预算倍率） |
| `core/agent-engine/.../engine/thinking/ThinkingEvolutionTracker.kt` | 档位效能统计（工作区 × 档位聚合） |
| `core/code-engine/.../code/CodeThinkingPrompts.kt` | Coding 特化思考指令（7 档 + AUTO 兜底） |
| `core/code-engine/.../code/CodeAgentEngine.kt` | updateThinkingLevel 双通道 + currentThinkingDecision 透传 |
| `app/.../ui/screen/code/CodeThinkingSelector.kt` | Coding 屏选择器 |
| `app/.../ui/screen/agent/AgentChatDialogs.kt` | 聊天页选择器（共用枚举） |
| `app/.../ui/screen/agent/ModeGuideSheet.kt` | 模式指南的档位总表 |

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
各自 init 恢复各自的选择。模型原生 effort 挂在默认 Profile 上是全局
的（与「切换模型」一致的全局语义），档位语义本身（画像/指令/迭代倍率）
是每引擎实例独立的。

## 六、兼容性与迁移

- **序列化兼容**：ThinkingLevel 以枚举名（String）持久化
  （thinkingLevelOverride / codeThinkingLevel），旧值 none/light/
  standard/deep/maximum/auto 全部继续有效；新值 ultracode/apexcode
  对旧版本 APP 是未知值——旧版回落 STANDARD（AgentModule 解析表
  else 分支），不崩溃不静默错档（ignoreUnknownKeys 容错在
  AgentSettings 层）；
- **ordinal 稳定性**：AUTO 从 5 改为 7（新档插入其后），所有
  `<`/`>` 比较语义在新序下重新成立（NONE<…<APEXCODE<AUTO），
  ThinkingLevelLadderTest 快照锁定；
- **ReasoningEffort 不变**：llm-adapter 层零改动（两新档映射既有的
  MAX），Provider 差异化逻辑无需感知新档。

## 七、性能预算

七档画像的执行策略倍率对请求预算的影响（以 maxIterations=40 的
coding 引擎为例）：

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
