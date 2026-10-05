# 作用域隔离：市场分级 × 引擎工具面（v3）

> 状态：已落地（Task 2 核心层 + 2-S6 注册侧打标）
> 主题：MCP 与技能的双工位（Agent 屏 / Coding 屏）隔离——把市场已有的
> scope 二级分离贯通到引擎侧工具计划。
> 前置：[hub-ecosystem.md](hub-ecosystem.md)（市场二级分离与 tier 来源）
> · [tool-system-v4.md](tool-system-v4.md)（工具注册表与一等 MCP 工具）
> 参考：docs/agent-modes.md（工位与模式归属）。

---

## 1. 动机：市场分了级，引擎没消费（缺口 A）

#197 起市场就把技能与 MCP 按 agent/coding 做了二级分离：

- `McpServerConfig.scope`（"agent" | "coding" | "all"，默认 all）——市场
  Agent/Coding 双目录过滤、内置服务器归属（github/fs→coding，
  memory/thinking/search→agent）、沙箱预置（fs-sandbox/everything-sandbox→
  coding，memory-sandbox→agent）；
- 技能 manifest 的 `scope` 字段（apex-skill-v1，缺省 all）——斜杠菜单、
  prompt 注入（`getActivePromptInjections(activeIds, scope)`）都按它过滤。

但引擎侧此前**不消费**这个字段：`mcp__server__tool` 一等工具与技能复合
工具全部注册进同一个 ToolRegistry，AGENT 模式的请求 tools 数组里照样出现
Coding 专属服务器的工具（缺口 A 的根因：scope 只被市场 UI 消费，过滤发生
在「目录展示」而不是「工具计划」）。用户在 Agent 屏问天气，模型却看到一排
`mcp__fs_sandbox__write_file`，工具清单互相污染、上下文预算白白消耗。

## 2. 数据流：tier → 配置 → 元数据 → 计划（单一事实源）

```
市场 tier（HubSource 条目 scope）
  │ 安装（MCP: toServerConfig / 技能: manifest 自带 scope 落盘）
  ▼
McpServerConfig.scope        SkillManifest.scope
  │ McpToolRegistrar           │ SkillRegistry.getActiveToolsWithScope()
  │   .registerServer()        │   （注册时快照，脏值折叠 all）
  │   唯一注册入口：初始 sweep  │ SkillHotReloader.registerLocked()
  │   / onServerConnected /    │   SkillToolAdapter(skillScope=…)
  │   Supervisor 重连全部汇入  │
  ▼                            ▼
ToolMetadata.scope（"agent" | "coding" | "all"，默认 all）
  │
  ▼
EngineToolPlanner.filterOutOffScopeTools（读 metadata.scope，
  按 AgentConfig.skillScope 过滤；AGENT/LOOP 与 GOAL/BUILD 全模式生效）
  ▼
本轮请求 tools 数组 == 系统提示词工具清单（visibleToolsFor 与请求同源，
  模型看到的清单与能调用的工具永远一致）
```

关键约束：

- **同源**：注册侧打的 scope 与市场 tier、prompt 注入过滤读的是同一个
  字段，没有第二套口径；
- **规范**：注册打标统一过 `ToolMetadata.normalizeScope`（trim + 小写，
  非 agent/coding 一律折叠 all——手改 mcp_servers.json 的笔误不该把工具
  从两个工位同时藏死，fail-open）；
- **热更**：技能 manifest 改写 scope 后 `SkillHotReloader` 的 diff 会比对
  已注册实例的 `metadata.scope`，触发 REPLACE 刷新（原地升级语义）；
  MCP 侧 scope 改动在下次连接/重连的注册时生效（快照语义）；
- **零变化默认**：未声明 scope 的旧 manifest 与旧配置一律 all，
  111 个存量内置工具不打标（默认 all），存量行为零变化。

## 3. 可见面：mcp_list 与 capability_report

过滤在计划层强制，但模型需要**看见**归属才能正确解释工具清单：

- `mcp_list`：每台已连接服务器追加 `scope:` 行（如
  `scope: coding (Agent 屏不可见)`；all 不带尾注）。只展示、不过滤；
- `capability_report`：MCP 段后追加事实行
  `Workspace isolation: scope enforcement at engine plan level (per
  workspace: agent/coding/all).`——该工具不感知设置开关，只陈述机制。

## 4. 开关语义（mcpScopeIsolation）

- `AgentSettings.mcpScopeIsolation` 默认**开**：引擎计划层执行
  filterOutOffScopeTools；
- 关 = 回 v2 行为（全局可见，不做作用域过滤）——兼容「我就想在两个
  工位都看到全部工具」的用户偏好；设置层经 patchConfig 热更新生效；
- 前缀黑名单（AGENT 模式剔除 `code_` / `github_` / `mcp__github__`）
  是独立机制，不受本开关影响（见已知边界）。

## 5. 已知边界

1. **github_* 原生工具**：`github_create_issue` 等 app 层 REST 工具不走
   ToolMetadata.scope，仍由 AGENT 模式的 `AGENT_EXCLUDED_TOOL_PREFIXES`
   前缀黑名单剔除（两套机制并行，原生工具的 scope 化留给后续任务）；
2. **SkillActivationStore 仍跨工位共享**：激活集不分工位（Agent 屏激活
   的技能，Coding 屏的目录里也标记已激活）；prompt 注入已按 skillScope
   过滤，共享的只是激活簿记，不泄漏方法论正文；
3. **计划级强制 ≠ 执行墙**：`tool_open` 渐进披露理论上能让模型翻到
   目录里其它工位工具的描述，执行层不做 scope 二次校验（回显名兜底路由
   依赖注册表直查）——防呆不防赖，主路径的隔离目标已达成；
4. **第三方 manifest 无 scope**：手写/URL 导入的 manifest 未声明 scope
   时按 all 双工位可见（向后兼容语义）；官方 hub 清单自带 scope，
   mcp_connect 工具自建的服务器配置同理缺省 all。
