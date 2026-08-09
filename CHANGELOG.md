# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Agent 引擎支持 BUILD / PLAN 双模式运行
- Plan 模式：LLM 先生成执行计划，等待用户确认后逐步骤执行
- 思考深度（ThinkingLevel）支持 NONE / STANDARD / DEEP 三档
- Reasoning Effort 调节（NONE / LOW / MEDIUM / HIGH）
- 上下文压缩（Context Compression）：SlidingWindow / LLM Summary / Hybrid 三种策略
- Tool Output 截断（ToolOutputTruncator）防止大输出撑爆上下文
- LLM 流式输出（ResponseChunk / ThinkingChunk 逐 token 推送）
- 工具流式执行（ToolStreamEvent：Output / Progress / Complete / Error）
- MCP（Model Context Protocol）工具集成
- Skill 系统：SkillRegistry + SkillMenuProvider + SkillToolAdapter
- 插件 APK 架构（plugin-sdk + plugin-workflow 示例）
- 多模态输入：图片附件转 ImageContent 注入 LLM（Vision 支持）
- 预测性附件预处理（PredictiveAttachmentPreprocessor）：编辑时后台预拷贝
- 输入框草稿持久化（SavedStateHandle，跨配置变更/进程回收保留）
- Git Bash / Shizuku / Root 三级权限适配
- Terminal PTY 子系统（NativePty + AnsiStripper + PtySessionState）
- Terminal 工具集（6 个 PTY 相关工具）
- GitHub MCP 工具集成（github_repos / github_issues / github_prs）
- 启动广播接收器（BootReceiver）支持开机自启前台服务

### Fixed
- 附件竞态：发送新消息前取消并清空旧附件 Job（缺陷 1 修复）
- 斜杠指令残留附件：发送前无条件清空附件列表（缺陷 2 修复）
- 输入框草稿丢失：用 SavedStateHandle 替代 rememberSaveable（缺陷 3 修复）
- 工具输出高频重组：16ms 节流 + 尾部窗口 4000 字符（性能优化）

### Changed
- AgentEngine 接口支持多模态 UserInput（images + files）
- 持久化记忆不存 base64 图片，仅存文本副本（防存储爆炸）
- 所有附件 I/O 操作切到 Dispatchers.IO

---

## [0.1.0] — 2025-XX-XX

### Added
- 初始版本：基础 ReAct Agent 循环（Think → Act → Observe）
- 内置工具集：shell_execute、read_file、write_file、edit_file、glob_files、search_files
- Web 工具（curl / fetch）
- 内存工具（memorize / recall）
- 系统控制工具（截屏 / 剪贴板 / 通知）
- UI 工具（tap / swipe / input_text）
- 传感器工具（电量 / 网络状态）
- Hilt DI 架构
- Jetpack Compose UI（AgentChatScreen / SettingsScreen / MemoryScreen / SkillScreen）
- ConversationMemory（SharedPrefs 持久化）
- LLM 适配器：OpenAI 兼容接口（支持多种 LLM 提供商）

[Unreleased]: https://github.com/AceGuru-mjh/Android-Guru-Agent/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/AceGuru-mjh/Android-Guru-Agent/releases/tag/v0.1.0
