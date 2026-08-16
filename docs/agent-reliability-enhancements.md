# Agent 基础能力增强：LLM 链路可靠性与循环韧性

> 落地日期：2026-08-12 ｜ 范围：`core/llm-adapter` + `core/agent-engine` + `core/tool-registry`
> 三个方向：**瞬时故障自动重试**、**工具调用超时保护**、**非法参数/空响应兜底**。

---

## 1. LLM 瞬时故障自动重试（`StreamingOpenAiClient`）

**问题**：任何一次网络抖动 / 限流（429）/ 服务端 5xx 都会抛异常直接杀死整个任务，
用户只能重新发起，成本高且体验差。

**方案**：
- 可重试状态码：`408`（请求超时）、`429`（限流）、`5xx`（服务端错误），以及
  连接层 `IOException`（断连 / DNS / 超时）。
- 指数退避 + 随机抖动：`base × 2^(attempt-1)`，上限 8 秒，抖动防"惊群"。
- 次数可配：`LlmConfig.maxRetries`（默认 2，即最多 3 次尝试）、
  `LlmConfig.retryBaseDelayMs`（默认 500ms）。
- **流式安全**：重试只发生在请求建立阶段（收到第一个 SSE 分片之前）；
  流已经开始后的中断**不重试**（无法安全续传，避免重复 token）。
- 不可重试（400/401/403/404/422）立即失败，`LlmException` 增加 `retryable` 标记供上层判断。

## 2. 工具调用超时保护（`ApexAgentEngine.executeToolCallStreaming`）

**问题**：一个挂起的工具（如 `shell_execute` 跑了 `sleep 1000`）会无限阻塞整个 ReAct 循环，
只能靠用户手动 abort。

**方案**：
- 每个工具调用包在 `withTimeout(config.toolTimeoutMs)` 中，默认 **120 秒**（可配）。
- 超时后工具被取消，按**失败结果**写回对话历史并发射 `ToolCallComplete(success=false)`，
  LLM 下一轮自动决定换方案或重试 —— 不会卡死循环。
- 注意：`TimeoutCancellationException` 是 `CancellationException` 子类，
  必须在 abort 重抛逻辑之前捕获，避免误判为用户中止。

## 3. 非法工具参数自动修复（`ApexAgentEngine.repairToolCallArguments`）

**问题**：LLM 流式生成的 tool call arguments 偶尔不是合法 JSON（带 markdown 围栏、
前后缀说明文本），工具收到垃圾参数后报错或行为异常。

**方案**（修复策略由宽松到严格）：
1. 原样可解析 → 直接执行；
2. 剥离 ```` ```json ... ``` ```` 围栏后解析；
3. 截取第一个 `{` 到最后一个 `}` 的子串后解析；
4. 仍无法修复 → **不调用工具**，把 `Error: invalid JSON arguments` 写回历史并标记失败，
   由 LLM 下一轮自行纠正。
5. 修复成功的调用会先发一条 `[engine] ... auto-repaired and executed.` 提示，行为透明。

## 4. 空响应自动重试（`ApexAgentEngine.executeBuildLoop`）

**问题**：LLM 偶发返回"无内容也无工具调用"（服务端截断/超时），原实现直接 `Error` 结束。

**方案**：
- `AgentConfig.emptyResponseRetries`（默认 1）：空响应时追加一条系统提示消息
  （"请输出工具调用或最终回答"）并重试；达到上限仍为空才报错。
- 重试消息进入对话历史，保持上下文连贯。

---

## 配置汇总

| 配置 | 默认 | 说明 |
|---|---|---|
| `LlmConfig.maxRetries` | 2 | LLM 瞬时故障重试次数 |
| `LlmConfig.retryBaseDelayMs` | 500 | 重试退避基础延迟（ms） |
| `AgentConfig.toolTimeoutMs` | 120_000 | 单工具调用超时（ms） |
| `AgentConfig.emptyResponseRetries` | 1 | 空响应自动重试次数 |

## 测试

- `core/agent-engine/src/test/.../ToolCallArgumentRepairTest.kt`：参数修复 9 个用例
- `core/tool-registry/src/test/.../LineDiffStatTest.kt`：行级 diff 统计 10 个用例
  （新增/删除/替换/插入/整文件重写/大文件退化）
- 运行：`:core:agent-engine:test`、`:core:tool-registry:test`（纯 JVM，无需设备）
