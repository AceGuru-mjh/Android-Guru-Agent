# 混沌工程式代码审查报告（Chaos Review 2026-02）

> 角色：Android 资深质量架构师（专精 Crash 分析与边缘场景）
> 审查对象：`main` 分支 @ `c3176ed`
> 审查维度：① 生命周期竞态条件 ② 数据一致性与边界值陷阱 ③ 隐式依赖与版本兼容性
> 结论：共发现 **12 项**非显性问题（P0×4 / P1×4 / P2×4），本 PR 逐项附带修复。

---

## 一、审查结果总表

| 风险等级 | 文件:行号 | 问题代码片段 | 触发条件 | 修改建议（附代码） |
| :--- | :--- | :--- | :--- | :--- |
| **P0-Crash** | `app/src/main/AndroidManifest.xml:5-22` + `browser/CyberNeonBallManager.kt:210-218` | `val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return; vibrator.vibrate(VibrationEffect.createOneShot(150, ...))` —— 全仓唯一 vibrate 调用，但清单**未声明** `android.permission.VIBRATE` | 服务启动后球常驻；Agent 进入 `WAITING_HUMAN` → `applyState(NEED_HUMAN)` → `triggerVibration()`。`vibrate()` 抛 `SecurityException: Requires VIBRATE permission`，且在 `mainHandler.post` 主线程回调里——**必现崩溃，所有 API 级别** | ① 清单补 `<uses-permission android:name="android.permission.VIBRATE"/>`；② 运行时自防御：`if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.VIBRATE) != PERMISSION_GRANTED) return`，`vibrate` 包 `runCatching` |
| **P0-Crash** | `attachment/PredictiveAttachmentPreprocessor.kt:99`（旧实现） | `val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO); scope.launch { ... ensureActive() ... }` —— 每次调用 new 孤儿作用域，拷贝 Job 无人持有 | 用户选 50MB 附件 → 立即点 ✕ 移除（`cancel(uri)` 只删缓存与文件）→ 拷贝协程满速跑完（`ensureActive()` 永不触发）；移除后重选同一文件 → **第二路并发拷贝交错写同一目录**；退出聊天页后拷贝依旧继续 | 拷贝 Job 登记进缓存条目：`PreprocessResult(..., job: Job?)`；`cancel(uri)` 中 `result.job?.cancel()`；进行中条目 `putIfAbsent` 先登记，重复调用命中复用，不再穿透 |
| **P0-Crash** | `core/agent-engine/.../compression/ToolOutputTruncator.kt:29-35`（旧实现） | `append(output.take(headChars)); append("[... ${output.length - headChars - tailChars} chars omitted ...]"); append(output.takeLast(tailChars))` —— head(1200)+tail(600) 可大于 maxChars | 设置页 `Max Tool Output Length` 合法输入 **200**（允许 200..100_000，`AgentModule`/`DefaultTaskOrchestrator` 未 clamp 直传构造器）。任一工具输出 250 字符 → `take(1200)`/`takeLast(600)` 各取全文 → 产物 ≈ 2× 原文，且省略计数为负：`[... -1550 chars omitted ...]` —— "截断防线"反向膨胀上下文 | 构造期收敛预算：`safeHead = minOf(headChars, (maxChars * 2 / 3).coerceAtLeast(1)); safeTail = minOf(tailChars, (maxChars - safeHead).coerceAtLeast(0))`；省略计数 `(output.length - safeHead - safeTail).coerceAtLeast(0)`；`truncateJson` 的 head/tail 同步收敛 |
| **P0-Crash** | `core/agent-engine/.../compression/SlidingWindowCompressor.kt:49`（旧实现） | `val preserveStart = maxOf(systemEnd, history.size - preserveRecent)` —— 纯按条数硬切 | `preserveStart` 恰好落在 `Assistant(tool_calls)` 与其 `ToolResult` 之间：assistant 进摘要、ToolResult 留在保留窗 → 下次请求首段出现无主的 `role:"tool"` → OpenAI 兼容端点 **400**；且 `ApexAgentEngine.compressNow()` 把损坏历史经 `memory.save()` **持久化**，此后每轮请求都 400，只能清空对话 | 边界回扩保配对：`while (preserveStart > systemEnd && history[preserveStart] is LlmMessage.ToolResult) preserveStart--` —— 与保留窗内 ToolResult 配对的 assistant 一并保留 |
| **P1-内存泄漏** | `browser/BrowserOverlay.kt:135`、`browser/CyberNeonBallManager.kt:94`、`browser/BrowserEngine.kt:264`（旧实现） | `mainHandler.post { CoroutineScope(Dispatchers.Main).launch { engine.completeHandoff() } }` / `CoroutineScope(Dispatchers.Main).launch { enterHandoffMode() }`（三处同型） | 三个类均为 `@Singleton`（进程级存活）；每次点击浮窗"交还"/每次网页敏感权限请求都 new 一个无父作用域。`enterHandoffMode()/completeHandoff()` 内部是 `stateMutex.withLock` —— 连点 N 次即挂起 N 个无主协程排队抢锁，**永不取消、无法追踪** | 单例持有唯一作用域：`private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`；回调改 `mainScope.launch { ... }`（`Main.immediate` 天然主线程，去掉双重 `mainHandler.post` 包装） |
| **P1-内存泄漏** | `ui/screen/log/LogViewerScreen.kt:478-512`（旧实现） | `val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); scope.launch { ... withContext(Dispatchers.Main) { context.startActivity(Intent.createChooser(...)) } }` —— `context` 为 Compose `LocalContext`（Activity） | 点"导出日志"后立即退出页面/按 Home/旋转屏幕：IO 落盘完成时 Activity 已 destroy —— Activity 实例被后台 Job 延长持有（泄漏），`startActivity` 自销毁态发起静默失败（Android 10+ BAL 限制） | 跨异步边界前捕获应用上下文：`val appContext = context.applicationContext`，落盘/`FileProvider`/`startActivity` 全部改用 `appContext` |
| **P1-内存泄漏** | `ui/screen/agent/AgentChatViewModel.kt:101/113/121`（旧实现） | `taskController.resume()?.let { flow -> currentJob = viewModelScope.launch { flow.collect { handleEvent(it) } } }` —— 覆盖 `currentJob` 前不取消旧 Job（`sendMessage` 在 `:378` 有 `currentJob?.cancel()`，三条 T76 路径缺失） | pause 请求已发出但旧执行流尚未自然收尾时，用户立即点"恢复/重试/继续崩溃任务" → 两个 collect 并发消费 → `handleEvent` 交错调 `_uiState.update`（isLoading 翻转、流式缓冲串轮） | 与 `sendMessage` 对齐，覆盖前取消：`currentJob?.cancel(); currentJob = viewModelScope.launch { flow.collect { handleEvent(it) } }`（三处统一） |
| **P1-内存泄漏** | `platform/terminal/.../buffer/RingTerminalBuffer.kt:124-127`（旧实现） | `override val retainedBytes: Int get() = synchronized(lock) { minOf(writePos.get().toInt(), capacityBytes) }` —— Long 游标直接 `toInt()` | 长寿命终端会话跑高吞吐输出（`yes`/日志刷屏/`cat` 大文件），累计输出越过 2^31 字节 → `toInt()` 回绕为负 → `retainedBytes` 返回**负数**；任何未来基于 retained/capacity 的水位/进度计算全部得到负值（同文件 `oldestCursor` 已是 Long 安全写法，此处漏改） | Long 域取 min 再收窄：`minOf(writePos.get(), capacityBytes.toLong()).toInt()` |
| **P2-兼容性** | `core/llm-adapter/.../StreamingOpenAiClient.kt:347-371`（旧实现） | `val json = Json.parseToJsonElement(body).jsonObject`（无 try）；`u["prompt_tokens"]?.jsonPrimitive?.int ?: 0` | ① baseUrl 指向网关/反代返回 200 + HTML/空体 → `SerializationException` 绕过 `LlmException` 体系 → `ErrorClassifier` 无法归类，重试/降级策略失效；② vLLM/Gemini-OpenAI 代理把 usage 返回浮点 `128.0` → `jsonPrimitive.int` 抛 `NumberFormatException`——choices 解析成功却因统计字段炸掉整个响应（流式路径有 `catch{null}` 兑底，非流式路径完全没有） | ① 解析归一化：`try { ... } catch { throw LlmException.Http(-1, "unparseable response body: ${body.take(200)}") }`；② `int` → `intOrNull` |
| **P2-兼容性** | `ui/screen/permissions/PermissionsScreen.kt:149-156`（旧实现）+ `AndroidManifest.xml:8` | `POST_NOTIFICATIONS` 仅在清单声明；全仓无任何 `requestPermission`/`registerForActivityResult` 调用，卡片仅 `context.openNotificationSettings()` 跳设置页 | Android 13（API 33）+ 全新安装：运行时权限默认拒绝 → 前台服务通知"AI助手运行中"**静默不可见**，用户误以为服务已死；服务仍活着 → 后台耗电投诉 | 卡片 onClick 优先标准请求：`if (Build.VERSION.SDK_INT >= 33 && !notifGranted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else context.openNotificationSettings()`，回调刷新状态 |
| **P2-兼容性** | `app/build.gradle.kts:99-100`（旧实现） vs `gradle/libs.versions.toml:12` | `implementation("dev.rikka.shizuku:api:13.1.0")`（硬编码）vs catalog `shizuku = "13.1.5"`（`platform:privilege` 走 catalog） | Gradle 冲突解析**静默取高版本 13.1.5**——硬编码 pin 实际无效，真版本由 privilege 模块间接决定；一旦移除该模块依赖会无声降级到 13.1.0（Shizuku api/provider 版本不一致时出现 binder 协议不匹配的运行时问题）。与"版本固定提升 CI 复现性"的注释自相矛盾 | 统一走版本目录：`implementation(libs.shizuku.api)` + `implementation(libs.shizuku.provider)`，全仓库单一版本源 |
| **P2-兼容性** | `app/src/main/kotlin/com/apex/agent/attachment/PredictiveAttachmentPreprocessor.kt:96`（旧实现） | `File(targetDir, "${System.currentTimeMillis()}_$fileName")` —— 同毫秒两次调用同名互相覆盖；`fileName` 未过滤 `/` | 500MB 大文件拷贝进行中（数秒窗口）重选同一附件 → 第二路并发拷贝；若落在同一毫秒，两个 `FileOutputStream` 交错写同一路径 → **文件损坏**；恶意/异常 content URI 名称含 `/` → File 解析到不存在的子目录，拷贝失败 | 文件名加 uri 哈希隔离 + 取末段：`File(targetDir, "${System.currentTimeMillis()}_${uri.hashCode()}_${fileName.substringAfterLast('/').ifBlank { "attachment" }}")` |

## 二、已排查、确认健壮的区域（避免重复审计）

| 区域 | 结论 |
| :--- | :--- |
| Flow 收集绑定生命周期 | 全部 Compose 屏均用 `collectAsStateWithLifecycle`，0 处裸 `collectAsState()` |
| PendingIntent 可变性 | 全仓仅 `ApexCoreService.kt:84-87` 一处，已带 `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT` |
| 前台服务类型（API 34） | manifest `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 齐全 |
| desugaring | `java.time`/`java.nio.file` 均为 API 26 可用 = minSdk 26，无需 desugaring |
| RootfsDownloader/RootfsExtractor | 三态续传、fd.sync、tar 校验和、路径穿越防护已充分加固 |
| 依赖 CVE | okhttp 4.12.0（无 CVE-2023-3635 风险）、Coil 2.6.0、Compose BOM 2024.12.01 均为稳定版本 |
| Service 生命周期 | `ApexCoreService.onDestroy` / `ApexAccessibilityService` 均正确 `scope.cancel()` 并清静态引用 |

## 三、遗留风险（本 PR 不修复，登记备忘）

| 风险 | 位置 | 说明 |
| :--- | :--- | :--- |
| `BrowserEngine.onRenderProcessGone` 销毁正被浮窗承载的 WebView | `browser/BrowserEngine.kt:175-191` 与 `BrowserOverlay.attachedWebView` | WAITING_HUMAN 期间渲染进程崩溃 → 引擎侧 `destroy()`，浮窗不知情，黑屏 + 对 destroyed 实例 `removeView`。需要引擎↔浮窗新增 `onWebViewDestroyed` 通知协议，涉及跨类接口变更，建议独立 PR |
| `dependencyLocking { lockAllConfigurations() }` 无 lockfile | `app/build.gradle.kts:112-115` | 锁形同虚设（DEFAULT 模式下无锁文件时每次照常解析）；CI 已用 `--write-locks` 就地生成但未提交。建议维护者跑一次 `./gradlew :app:dependencies --write-locks` 并提交 `app/gradle.lockfile` |
| `security-crypto 1.1.0-alpha06` + 静默明文兜底 | `github/GithubTokenManager.kt:23-36` | alpha 线已停更；Tink 初始化失败时 token 零提示明文落盘。建议至少对 fallback 打点提示，中期换 Keystore + AES-GCM |
| `getExternalStoragePublicDirectory` 废弃 API | `browser/BrowserAgentTools.kt:53-59` | API 29+ 依赖 All-Files-Access；未授权时白名单校验与真实可写性脱节。建议回退 app 专属目录或改用 MediaStore |
| 终端屏幕流收集无可见性降级 | `ui/screen/terminal/TerminalViewModel.kt:175-189` | 无 NavHost 场景下切页后仍持续做 VT 渲染（性能而非崩溃）；建议 `LifecycleResumeEffect` 感知可见性 |

## 四、必现崩溃测试用例（修复前可复现，修复后通过）

### 用例 A（P0：VIBRATE 权限缺失，纯 ADB 即可复现）

```bash
# 前置：安装修复前的 APK（任意 API 级别，无需 root）
adb install -r app-debug.apk

# 1. 启动主服务（球浮窗常驻）
adb shell am start -n com.apex.agent/.MainActivity
adb shell am start-foreground-service -n com.apex.agent/.service.ApexCoreService

# 2. 驱动 Agent 进入 WAITING_HUMAN（人工接管）：
#    触发 browser 工具进入 handoff，或直接用 am 广播模拟引擎状态切换。
#    最简路径：任一需要人工确认的任务（如 browser_show）→ 球切 NEED_HUMAN。
#    NEED_HUMAN → applyState → triggerVibration() → 无 VIBRATE 权限。

# 3. 观察崩溃
adb logcat -d | grep -E "SecurityException|Requires VIBRATE"
# 预期（修复前）：
#   android.view.InflateException / java.lang.SecurityException:
#   Requires VIBRATE permission
#   → 进程 com.apex.agent 死亡（前台服务一并被杀）

# 验证修复：
adb shell dumpsys package com.apex.agent | grep VIBRATE
# 预期：android.permission.VIBRATE: granted=true
```

### 用例 B（P0：ToolOutputTruncator 负数省略计数，UI 步骤）

1. 设置页 → `Max Tool Output Length` 设为 **200**（合法范围 200..100_000）；
2. 重启应用生效 → 新会话发送任一会让工具输出 200~2500 字符的指令（如"读取 /etc/hosts"）；
3. 修复前：LLM 收到的工具输出含 `[... -1550 chars omitted ...]` 且正文重复两遍——可在"工具卡片"展开内容中直接目视确认；修复后：截断产物 ≤ maxChars 预算，省略计数恒 ≥ 0。

### 用例 C（P0：预拷贝孤儿协程，UI 步骤）

1. 选择一个大附件（≥500MB，拷贝耗时 > 3s）；
2. 立即点 ✕ 移除该附件；
3. 修复前：`adb shell ls /data/data/com.apex.agent/files/attachments_pre/` 数秒后仍出现完整拷贝文件（协程跑完）；修复后：`cancel()` 取消 Job，无文件产生，进度流终值为 -1。

---

## 五、修复清单（本 PR 改动）

| # | 文件 | 修复 |
| :-- | :--- | :--- |
| 1 | `app/src/main/AndroidManifest.xml` | 补 `VIBRATE` 权限声明 |
| 2 | `browser/CyberNeonBallManager.kt` | 震动运行时自防御 + 孤儿协程收敛为 `mainScope` |
| 3 | `browser/BrowserOverlay.kt` | `overlayScope` 替代孤儿 `CoroutineScope`，去双重异步 |
| 4 | `browser/BrowserEngine.kt` | `engineScope` 替代孤儿 `CoroutineScope` |
| 5 | `attachment/PredictiveAttachmentPreprocessor.kt` | 拷贝 Job 可取消 + 进行中条目登记防并发重复拷贝 + 文件名隔离/净化 |
| 6 | `ui/screen/agent/AgentChatViewModel.kt` | resume/retry/crash 三路径覆盖 `currentJob` 前先取消 |
| 7 | `ui/screen/log/LogViewerScreen.kt` | 导出改用 applicationContext，杜绝 Activity 跨异步泄漏 |
| 8 | `ui/screen/permissions/PermissionsScreen.kt` | Android 13+ 标准运行时请求 `POST_NOTIFICATIONS` |
| 9 | `platform/terminal/.../RingTerminalBuffer.kt` | `retainedBytes` Long 域取 min，修复 2GiB 溢出 |
| 10 | `core/agent-engine/.../ToolOutputTruncator.kt` | head/tail 构造期收敛 maxChars 预算 + 省略计数 coerce ≥ 0 |
| 11 | `core/agent-engine/.../SlidingWindowCompressor.kt` | 压缩边界回扩，保证 tool_call ↔ tool_result 配对 |
| 12 | `core/llm-adapter/.../StreamingOpenAiClient.kt` | 非流式解析容错（JSON 失败 → `LlmException.Http`；usage 浮点 → `intOrNull`） |
| 13 | `app/build.gradle.kts` | Shizuku 统一走版本目录（13.1.0 硬编码 → catalog 13.1.5） |
