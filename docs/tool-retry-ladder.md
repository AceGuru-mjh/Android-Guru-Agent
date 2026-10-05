# 工具韧性 v6 — 重试阶梯 + MCP/Skills 容错换路

> 用户规格收口：**工具出错任务不停止**。网络类瞬时错误按阶梯重试
> （第一次 2s、第二次 5s、第三次 10s……直到 3 分钟封顶自动停止重试）；
> MCP / Skills 出错不硬停，换一种方式继续完成任务。

## 1. 重试阶梯（两层同表）

共享表 `ToolRetrySchedules`（core:tool-registry，`ToolRetrySchedules.kt`）：

```
2s → 5s → 10s → 20s → 40s → 80s → 160s   （下一级 320s ≥ 180s 封顶 → 自动停止）
```

| 层 | 接入点 | 语义 |
|----|--------|------|
| 执行器层 | `ToolRunPolicy.agentLadder()`（`DefaultToolRunPolicyResolver` 对 openWorld 工具生效） | retrySafe 工具的瞬时失败按阶梯重放同一载荷；确定性无抖动（用户可感知规格值） |
| 引擎层 | `RetryPolicy.AGENT_LADDER`（`EngineResiliencePolicy.DEFAULT.toolRetryPolicy`） | Agent 主循环工具调用失败（流内 Error 事件/异常）按阶梯重试；PERMISSION/FATAL 仍立即回灌 LLM |

- 阶梯穷尽后**自动停止重试**（不是无限重试）：单调用最多
  `1 + 7 = 8` 次尝试；
- 停止 ≠ 任务失败：错误照常回灌 LLM，配合既有 EngineResilienceGuard
  的「连续失败 → 换路提示」机制，模型被明确要求换工具/换参数/换分解；
- 测试预设（FAST / DISABLED / 显式构造）保持旧语义零变更——
  data 类默认值不动，只改 `EngineResiliencePolicy.DEFAULT` 组合。

## 2. MCP 失败 → Error 协议 + 换路指引

此前两处协议缺口：

1. **服务端 `isError=true` 渲染无 "Error:" 前缀**（McpAgentTool /
   McpCallTool），引擎的字符串级成败判定把服务端报错**当成功**——
   失败对模型与换路提示完全不可见。现在统一 "Error:" 前缀；
2. **失败即死感**：错误文案只说失败，不说下一步。现在附带
   `[FALLBACK]` 指令段：任务未中止，要求模型
   (a) 换内置工具 (b) 换其他 MCP 服务器（mcp_list）(c) 手工完成该步骤
   并说明限制——禁止原样重发同一调用。

传输层失败（断连/超时）额外保留 `mcp_connect('$server')` 重连提示。

## 3. Skills 步骤失败 → 断点上下文 + 换路指引

`SkillToolAdapter` 的 composite 步骤失败原先直接短路返回裸错误。
现在返回结构化断点：失败步骤序号（`step N/M`）、失败工具名、
错误详情、**最后一段成功输出（截断 500 字）**——模型可从断点
换路续跑而不是从头再来。文案同样带 `[FALLBACK]` 换路指引，
"Error:" 前缀保持引擎协议。

## 4. 不变式（防御式边界）

- 权限拒绝 / 沙箱违规 / 参数错误 = 终态，任何策略下都**一次即停**
  （重试只会骚扰用户/烧预算）——`RetryClassifier` 与
  `FailureClassifier` 的既有语义未被触碰；
- 阶梯只改变「重试间隔与次数上限」，不改变「哪些失败可重试」的
  分类边界；
- 流式执行路径（executeStream）仍不自动重试（已发射部分输出的
  流不可重放，避免副作用翻倍）——设计不变。

## 5. 验证

- `ToolResilienceLadderTest`（tool-registry）：阶梯表精确值、封顶
  自停、确定性无抖动、执行器全程重试后成功、穷尽后结构化停止、
  权限拒绝零重试、MCP 两态渲染、Skill 断点渲染与成功路径回归；
- `EngineLadderRetryTest`（agent-engine）：阶梯退避值、第 8 次失败
  自停、非可重试类立即停、DEFAULT 接阶梯而 FAST/DISABLED 语义不变、
  Guard 端到端判定；
- `run_core_tests_jvm.sh` 全量 1441 tests OK；
- CI：静态分析（镜像编译四个 core 模块）+ app 编译 + 9 步测试。
