# PR — Coding 引擎 v1.2：七档思考系统 + 长任务中心

> 用户指令：对专属 agent 长任务复制任务的顶级优化；coding 也要有思考程度
> 从 none 到 ultracode 到 apexcode 共 7 层；新增代码不少于 10000 行。

## 总览

v1.2 给 Coding 模式（并惠及 Agent 模式）交付两大特性，共 **48 文件
+10,000+ 行**（含测试与文档），本地全模块编译 + 449 项 agent-engine
单测 + 49 项 code-engine 单测 + 4 道 CI 门禁全绿。

### 特性一：七档思考系统（none → … → ultracode → apexcode）

ThinkingLevel 从 6 档扩为 **7 深度档 + AUTO 元档**：

```
NONE → LIGHT → STANDARD → DEEP → MAXIMUM → ULTRACODE → APEXCODE   (+AUTO)
```

- **ULTRACODE**（编码闭环档）：依赖地图 → 候选改法 → 风险排序 →
  最小修改 → 即时验证 → 回归扫描；thinking_budget 32768、迭代 ×2.0、
  工具输出预算 12000
- **APEXCODE**（巅峰档）：架构定位 → 影响半径测绘 → 多方案对比矩阵 →
  对抗性自审自己的 diff → 全量验证矩阵（构建/lint/测试/回读）→
  证据链汇报；budget 65536、迭代 ×3.0、压缩倍率 0.9（唯一更晚压缩档，
  保上下文撑证据链）、专属**五问终检清单**（目标/副作用/遗漏/不变量/
  回归）
- **APEXCODE 永不被 AUTO 自动选择**（成本防线：×3.0 迭代 + 全量验证
  的开销只应由人决策触发；选档器 KDoc + 320 组合穷举测试双重锁定）
- AUTO 升档能力扩展：评分 >11 → ULTRACODE；深水区（>30 工具调用且
  近期 ≥2 错误）保底 ULTRACODE

**Coding 模式完整接入**（v1.1 前是硬编码 STANDARD 无 UI）：

- 输入栏上方档位选择器（8 项下拉 + **档位指南**弹层：阶梯总表 +
  逐档卡片可直切档）
- 持久化 `AgentSettings.codeThinkingLevel`（与聊天页
  thinkingLevelOverride 分立，两模式各自记忆）
- 引擎双通道：patchConfig（通用画像：迭代/压缩/输出预算倍率）+
  CodeThinkingPrompts（编码特化指令：读码纪律/改动集思维/不变量守护/
  依赖地图/对抗性自审）
- 模型原生 reasoning effort 同步（T1 通道，镜像 Agent 模式语义）
- AUTO 档可解释性：对话流插「🧠 自适应选档：LEVEL: 因子→评分→档位」

### 特性二：长任务中心（顶级优化的复制任务）

```
AgentEvent 流 → LongTaskTracker（内存聚合+检查点）→ 规模判定
   → 长任务入库 LongTaskStore（原子写/缓存/保留50条）
   → 三页签面板：任务记录 / 任务模板 / 档位效能
   → 复制 / 重跑 / 续跑 / 对比 / 删除
```

- **自动留档**：迭代≥8 / 工具≥12 / 时长≥120s / 文件≥3 任一过阈值
  （MEDIUM/LONG/EPIC 三档规模徽标）；短任务静默丢弃，列表信噪比优先
- **检查点**：每 5 轮迭代（完成型事件触发，计数满值）或 60s 静默
  突破；≤10 个丢最旧；面板内时间线渲染（轮次徽标/相对时间/计数/
  完成度）
- **复制任务**：源 → 副本（parentTaskId 复制链、标题「（副本 n）」、
  统计归零、上下文/todo/文件清单/档位/目标工作区按选项复制）→ 可
  立即重跑
- **重跑 vs 续跑**（新语义）：重跑 = 换思路重新完成（带上次上下文
  提醒）；**续跑 = 从第 N 轮检查点沿原路线继续**（「已完成项不要
  重做」）——无检查点自动回退重跑
- **运行对比**：复制链上两次运行的文件交集/差集、迭代/工具/时长差，
  以系统消息进对话
- **8 内置模板**：重构/修Bug/新功能/评审/测试/文档/性能/迁移——
  一键应用推荐档位 + todo 骨架 + goal 模板
- **档位效能统计**：长任务留档按「工作区 × 档位」聚合（runs/成功率/
  平均迭代/工具/时长）——用用户自己的历史数据支撑选档
- **启动维护**：后台 prune 保留最新 50 条（模板豁免），存储有界

## 架构纪律

- **引擎零改动**：追踪挂在 VM 的 collect 链（一行 onEvent），长任务
  包纯新增文件
- **双模式互用**：ThinkingLevel/画像/选档器为 agent-engine 共享层，
  agent 模式的选择器/指南/持久化解析自动获得新档位
- **编码特化与通用画像三层分离**：通用推理框架（ThinkingProfile）/
  编码方法论（CodeThinkingPrompts）/ Provider 原生参数
  （ReasoningEffort）各司其职不重复注入

## 验证

| 项 | 结果 |
|----|------|
| :app:compileDebugKotlin | ✅ BUILD SUCCESSFUL |
| :core:agent-engine:test | ✅ 449 tests, 0 failures |
| :core:code-engine:test | ✅ 49+ tests, 0 failures |
| :core:code-tools / :tool-registry / :platform:terminal:test | ✅ |
| check_file_size（main≤1200/test≤1600） | ✅ 671 main + 189 test |
| check_code_quality（反射/printStackTrace） | ✅ 双 GATE |
| kotlin_balance + 嵌套注释词法扫描 | ✅ 全部改动文件 |
| strings en/zh 对称 | ✅ 130 = 130 键 |

## 文档

- [docs/thinking-levels.md](../thinking-levels.md) — 七档阶梯总表、
  三层注入架构、AUTO 评分模型、FAQ
- [docs/long-tasks.md](../long-tasks.md) — 数据流、判定阈值、检查点
  策略、复制/续跑语义、设计取舍 FAQ

## 后续（建议 issue）

- Agent 模式（聊天页）接入 LongTaskTracker 与长任务中心（复用同一
  包，一行 onEvent 接线 + 入口按钮；本 PR 未动 AgentChatViewModel，
  控制高危文件的变更范围）
- 档位效能的跨工作区聚合视图
- 长任务记录导出/分享（JSON 或 Markdown 报告）
