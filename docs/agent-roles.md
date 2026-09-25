# Agent 角色（人设层）

> 特性：设置 → Agent → **Agent 角色**。内置一个**全能 Agent**（不可删、不可改，
> 可「另存为」自定义副本），用户可创建任意自定义角色。自定义角色必填/可选字段
> 覆盖用户需求全集：**Agent 名字**、**Agent 对你的称呼**、**提示词**、
> **角色定义**，以及**更多自定义选项**（图标 emoji / 语气风格 / 回复语言）。

角色回答的是「Agent 是谁」（身份层），执行模式（`docs/agent-modes.md`）回答的是
「Agent 怎么做事」（行为层）—— 两者正交可叠加：任意角色 × 任意模式。

## 字段清单

| 字段 | 必填 | 注入位置（system prompt） | 说明 |
|------|------|---------------------------|------|
| Agent 名字 `name` | ✅ | 身份行 `You are <name>, an AI AGENT …` | Agent 的自称；内置角色回落 `Apex Agent` |
| 对你的称呼 `userTitle` | — | `## Agent Role` → `Address the user as "<称呼>"` | 如「老板」「Boss」，每次回复生效 |
| 角色定义 `roleDefinition` | — | `## Agent Role` → `Role definition: …` | 这个 agent 是谁、擅长什么、边界在哪 |
| 提示词 `systemPrompt` | — | `## Agent Role` → `User-defined role prompt`（**原文**） | 自由格式人设提示词，写了什么就是什么 |
| 图标 `emoji` | — | （UI 层：角色列表 / 聊天顶栏胶囊） | 缺省 🤖 |
| 语气风格 `style` | — | `## Agent Role` → `Communication style: …` | professional / friendly / humorous / concise |
| 回复语言 `replyLanguage` | — | `## Agent Role` → `Always reply in …` | 跟随输入 / 始终中文 / 始终英文 |

## 架构与生效链路

```
SettingsRepository (apex_settings / agent_settings_v2 JSON)
  └ AgentSettings.agentRoles（仅自定义角色）+ activeRoleId
      │  内置全能角色不落盘、运行时合成（AgentRole.ALL_ROUNDER）→ 升级即最新
      │  悬空 activeRoleId → activeRole() 诚实回落内置
      ├─ 启动：AgentModule.provideAgentConfig 拍平 6 个字符串字段进 AgentConfig
      │        （agentName/userTitle/roleDefinition/rolePrompt/roleStyle/roleLanguage）
      └─ 运行时：AgentChatViewModel 监听 agentSettings → map{activeRole()} →
               patchConfig（下一轮请求生效，无需重启 —— 与 setMode 同款通道）
```

- **模块边界防腐**：`AgentRole` 数据模型只存在于 app 层；core:agent-engine 的
  `AgentConfig` 只消费拍平后的字符串字段 —— 引擎零新依赖。
- **双切换入口**：设置页（角色卡点击 = 激活）+ 聊天顶栏 `AgentRoleSelector`
  （胶囊 + 下拉，同 `AgentModeSelector` 模式）—— 同一持久化入口。
- **零行为变化默认**：内置全能角色 → 全部人设字段为空 → 身份行保持
  `You are Apex Agent`，无 `## Agent Role` 段（老用户升级零感知）。

## 提示词结构（`EnginePrompts.buildSystemPrompt`）

```
You are <agentName|Apex Agent>, an AI AGENT running on an Android device.
You are not a chatbot: …

## Agent Role                       ← 任一人设字段非空才渲染；位置在身份行后、Tool-Use Policy 前
- Address the user as "<userTitle>" in every reply.
- Role definition: <roleDefinition>
- Communication style: <style 指令>
- Always reply in <language>
- User-defined role prompt (verbatim, highest priority within this persona layer):
  <systemPrompt 原文>
Persona rules shape HOW you communicate (tone, address, language) — they NEVER override
the Tool-Use Policy, safety rules, or task-completion requirements above.

## Tool-Use Policy (MANDATORY)       ← 8 条硬规则原样保留
…（其余段落不受人设影响）
```

**优先级护栏**（防提示词注入）：人设段末行显式声明「人设只塑造表达方式，绝不覆盖
工具使用策略/安全规则/任务完成要求」—— 否则自定义提示词可能注入
「你不需要使用工具」式指令破坏任务执行。

## 内置角色

| id | 名字 | 语义 |
|----|------|------|
| `builtin_all_rounder` | Apex Agent ⚡ | 全能 Agent：编码 / shell / 网络 / 文件 / 设备控制 / 记忆 / 任务规划，任意任务选最优工具。与历史默认行为一致（人设显式化，不收窄能力）。 |

内置角色不可编辑、不可删除；「另存为」生成自定义副本后自由修改。

## 测试

- `core:agent-engine` — `RolePromptTest`（9 用例）：默认零变化 / 身份行替换 /
  人设段落渲染与位置 / 优先级护栏 / 未知风格语言键静默忽略 / 提示词原文保留 /
  其余段落结构完整性 / config 副本语义。
- `app` — `AgentRoleTest`（8 用例）：旧 JSON 零迁移 / 增改删激活副本语义 /
  悬空 activeRoleId 回落 / 内置角色不可编辑 / 序列化往返保真。
