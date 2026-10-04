# P1/P2 工程批次 —— 权限链落地 / OOM 防线 / 特权工具路由

> 用户审查结论（2026-10-02）：T91 已修完三项 P0 架构问题，但 P1 六项工程问题
> 一项未动。本批次逐项核验**当前代码真态**（多数已有部分修复合入）后补齐
> 每一项的**剩余真根因**，并全部配上回归测试。方法学不变：先核验、后修复、
> 每项锁定。

## 逐项核验结论（先核验，后动手）

| Issue | 审查时的认知 | 核验后的代码真态 | 本批次补齐 |
|---|---|---|---|
| #230 P1 security | MEDIUM 风险永不弹确认 | `RiskAwareToolGate` 的 FILE_MUTATING_TOOLS 修复**已合入**（fdacdb9）——但**生产引擎全部走 `executeStream`，该路径从不咨询 gate**：Agent 聊天/任务编排里 MEDIUM（write_file/edit_file）甚至 HIGH 全部静默执行 | **流式路径补 gate/schema 前置**（executeStream 共享 pipeline.preCheck）+ code_write/code_edit 入集 + 标准线防双弹窗 |
| #232 P1 OOM | PDF/DOCX 绕过 16MB | 16MB 前置**已合入**（f23fee7）——但 `extractDocx` 对 `word/document.xml` 的 `readBytes()` 读的是**解压后全文**：KB 级 zip 炸弹可解压出 GB 级 XML，字符上限在解压之后才生效，进程照样 OOM | **解压字节预算防线**（32MB 流式预算，64KB 分块搬运）+ PDF 读入钳制 |
| #240 P2 bug | open_notifications 误映射 keyevent 26 | root 映射修复**已合入**（fdacdb9，`cmd statusbar expand-notifications`）——但 `UiAction.OpenNotifications` **零生产调用方**：修正后是死代码，LLM 依旧无入口 | **ui_notifications 工具**（open/close LLM 面）+ `UiAction.CloseNotifications`（a11y BACK 收合 / `cmd statusbar collapse`） |
| #239 P2 | 无障碍开启截图永远失败 | a11y API30+ 实现 + root 回退**已合入**（fdacdb9 + cd4fc60）——但 `screenshot` 工具**从不走 PrivilegeManager**：恒走裸 shell（普通沙箱 screencap 必 EACCES）——无障碍开启无 root 的设备依旧必败 | **ScreenshotTool 接特权链**（a11y → root → shell 兜底，双失败并列上报） |
| #233 P2 | 字号 8..32 vs 8..24 | **已全量修复**（T89：VM/设置表/view 库/宿主注入四层统一 8..24，TerminalViewSettingsTest 锁定） | 仅补 **app↔view 跨层契约测试**（常量一致 + 越界钳回收） |
| #236 P2 | Keep Alive 关后服务照跑 | ApexCoreService 停止接线**已合入**（fdacdb9 CoreServiceGate）——但传播是**一次性 UI 调用**：备份恢复/导入等非 toggle 路径翻转 keepAlive，服务照跑 | **agentSettings 流驱动同步**（ApexApp 收集器：keepAlive 翻转 → CoreServiceGate） |

## 修复明细

### #230 · 流式路径门控前置（P1 security —— 三级权限链运行时落地）

**根因**：`EnhancedToolExecutor.executeStream`（生产唯一路径：Agent 聊天
主循环 `EngineToolExecution:80` / 任务编排 `ToolCallRunner:204`）只做
限流/熔断/钩子，**没有 `pipeline.preCheck`** —— 门控链
（`PermissionAwareToolGate` = 权限模式门 + `RiskAwareToolGate` 的
write_file/edit_file MEDIUM 确认、HIGH 确认）从未被咨询。类 KDoc 自己
写着「流式路径 v3 本就无 gate/schema 前置」——与 `ToolExecutor` 契约
（两入口共享 查找→门控→校验）直接矛盾。

**修复**（`EnhancedToolExecutor.executeStream`）：
- 与 `execute()` 共享同一 `pipeline.preCheck`（gate → PreToolUse 钩子 →
  schema），拒绝以 gate 同款文案（`Error: permission denied: …`）短路为
  单一 `ToolStreamEvent.Error` —— 模型侧行为与非流式逐字节一致；
- 拒绝计入 trace/usage（`recordSideChannelDenial`，reason =
  message slug）；
- `RiskAwareToolGate.FILE_MUTATING_TOOLS` 补 `code_write`/`code_edit`
  （StandardToolSurface 把 write_file/edit_file 映射过去 —— 同一底层
  行为必须同一确认语义）。

**防双弹窗（收尾）**：`StandardModeEngine` 自带业界标准式权限门（规则 →
会话记忆 → 模式兜底 → ASK 弹窗）。#230 之后若继续共用主执行器（组合门），
同一动作会被问两次。新增 `@Named("standardEngineTools")` 执行器（ToolModule）：
**仅环境门**（`ToolEnvironmentGate`）+ 限流/熔断/超时/重试/追踪/钩子/脱敏
全保留，权限确认归引擎层独占。深潜线（CodeAgentEngine → ApexAgentEngine，
无引擎级门）与 Agent 聊天线继续用主执行器 —— 三条线各自恰好一层权限。

### #232 · DOCX 解压字节预算（P1 OOM —— zip 炸弹防线）

**根因**：`DocumentTextExtractor.extractDocx` 的
`zip.getInputStream(entry).readBytes()` 读入**解压后全文** ——
`file.length()`（16MB 检查的对象）是 zip **压缩**尺寸。恶意构造的 .docx
可用 KB 级 zip 携带 GB 级 `word/document.xml`；`MAX_CHARS = 2M` 的字符
上限在全文已驻内存后才生效 —— 预算爆在解压阶段，进程 OOM。

**修复**（对标 ProotExecutor.executeBounded 的字节预算模式 —— 绝不先
全文缓存再截断）：
- `boundedZipEntryText`：64KB 分块流式搬运，`total > MAX_DOCX_XML_BYTES`
  在下一块写入前判出即退出 —— 常驻内存上限 ≈ 32MB + 64KB，与文档实际
  膨胀率无关；超预算返回 null → 如实报错（含预算说明 + pandoc/libreoffice
  分段转换指引）；
- `MAX_DOCX_XML_BYTES = 32MB`：MAX_CHARS=2M 字符正文 ≈ 6-8MB + Word
  标签膨胀 3-5× —— 超预算文档其可提取文本也早已超字符上限，诚实拒绝
  无信息损失（预算内 ~8MB 合法大文档正常提取，有防误杀回归测试）；
- `extractPdf` 读入钳制 `readNBytes(16MB)`（防御纵深 —— 上游 FileReadTool
  已拒 >16MB，直用 extractor 也不过量）。

### #240 · 通知栏工具的 LLM 面（P2 收尾）

root 误映射（`input keyevent 26` = 电源键熄屏）已在 fdacdb9 修正为
`cmd statusbar expand-notifications` —— 但修正后**零调用方**。本批次：

- `UiNotificationsTool`（id `ui_notifications`，open/close）：a11y 就绪 →
  `GestureAction.OpenNotifications/CloseNotifications`（语义通道）；
  否则 shell `cmd statusbar expand-notifications` / `cmd statusbar collapse`
  （**绝不用 keyevent 26** —— 工具注释里写明原始事故）；
- `UiAction.CloseNotifications`（a11y：BACK 收合 shade —— 状态栏展开时
  BACK = collapse 的系统语义；root：`cmd statusbar collapse` 与 expand
  对称）；
- `PrivilegeUiProvider` 手势映射 + describeAction 补两分支。

### #239 · screenshot 工具接特权链（P2）

**根因**：a11y API30+ 与 root screencap 回退链已实现（fdacdb9/cd4fc60）
但 `ScreenshotTool` 恒走 `shellExecutor`（PrivilegeDetector → 无 root 时
裸 sh）—— 无障碍开启无 root 的设备（#239 的主场景）必败。

**修复**：`ScreenshotTool` 新增 `privilegedScreenshot` 供给（core 层中立
形状 `PrivilegedScreenshot(pngBytes, error)` —— 模块方向不允许直接依赖
platform 类型，app 层适配 `ScreenshotResult`）：
- 特权成功 → 字节直接落盘（File API，mkdirs + 异常如实报错）；
- 特权失败 → shell 兜底保留（模拟器/调试设备非特权 screencap 仍可用）；
  兜底也失败 → **两段失败并列上报**（特权链根因 + shell 输出，可诊断）；
- `ToolModule` 注册时接线 `privilegeManager.takeScreenshot()`。

### #236 · Keep Alive 流驱动同步（P2 收尾）

CoreServiceGate（toggle 直连 apply）已合入 —— 但传播是一次性 UI 调用：
备份恢复/导入等非 toggle 写入路径翻转 keepAlive 时服务照跑。
`ApexApp.initKeepAliveServiceSync()`：`agentSettings` 流
`drop(1).distinctUntilChangedBy { it.keepAlive }.collect` →
`CoreServiceGate.apply`（toggle 直连保留 —— 双发幂等：onStartCommand
幂等重申、stopService 停态 no-op）。启动初值不拉起（drop(1) —— 启动权
属 MainActivity/BootReceiver）。

### #233 · 字号契约锁（P2 —— 已修，补跨层测试）

T89 已统一 8..24（VM 常量 / 设置表 / view 库默认 / 宿主注入四层）。
新增 `TerminalFontSizeContractTest`（app 层）：app 常量 ↔ view 库默认
一致 + 32f/4f 越界值经库层钳制必落 [8,24]（25..32 旧病灶锁死）。

## 验证

- `run_core_tests_jvm.sh`（本 PR 新增本地回归入口）：**1369 tests OK**
  （含 17 新增：StreamGateSecurityTest 6 + DocumentTextExtractor #232 3 +
  PrivilegedChannelToolsTest 8）；
- `compile_core_jvm.sh`：1383 classes 0 error；
- `compile_privilege_jvm.sh`（本 PR 新增：platform/privilege 本地类型检查，
  Shizuku/inject 本地 stub）：68 classes 0 error；
- `compile_terminal_view_jvm.sh`：161 classes 0 error（回归确认未破坏）；
- 三门禁（code_quality / file_size / kotlin_balance 14 文件）通过；
- app 模块（ApexApp/ToolModule/CodeModule/PrivilegeUiProvider/
  RiskAwareToolGate/契约测试）由 CI `:app:compileDebugKotlin` +
  app unit tests 兜底。

## 真机回归清单

1. **#230**：Agent 聊天模式让 AI `write_file` → 弹确认（本会话允许/仅一次/
   拒绝）—— 此前静默写入；拒绝后模型收到 `Error: permission denied`；
   coding 标准线写文件 → **只弹一次**（引擎层 ASK，不再双弹窗）；
   `delete_file`（HIGH）在聊天模式同样弹确认；
2. **#232**：构造超大 .docx（>16MB）→ `read_file` 拒绝并提示 shell 替代；
   恶意 zip 炸弹 docx → 「解压后超过 32MB 预算」如实报错，进程不 OOM；
3. **#239**：无障碍开启、无 root 的设备 → `screenshot` 工具成功（a11y
   通道）；全关时 → 错误信息同时含特权链根因与 shell 输出；
4. **#240**：让 AI「打开通知栏」→ 通知栏展开（屏幕**不熄**）；「关闭通知栏」
   → 收起；无障碍关闭 + root 开启 → `cmd statusbar` 生效；
5. **#236**：开 Keep Alive → 备份恢复一个 keepAlive=false 的设置包 →
   前台服务自动停（此前照跑）；
6. **#233**：设置表字号输入 32 → 界面拒绝（8–24 提示）。
