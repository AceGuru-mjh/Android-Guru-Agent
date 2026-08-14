# 内置浏览器与 DOM 级自动化 —— 最终演进方案（综合裁决版）

> 对标并超越 **Operit**（`com.ai.assistance.operit`）的网页自动化能力。
> 调研日：2026-08-12 ｜ 评审人：Qwen（顶级架构师 / Agent 自动化专家）｜ 状态：**裁决已锁定，待工程收尾**
>
> 本文是《web-browser-automation.md》（设计初稿）与《web-browser-automation-proposal.md》（待评审稿）的**最终综合版**。
> 所有开放问题已由 Qwen 裁决，关键修正（显式握手接管）已采纳。本文档即落地依据。

---

## 0. 一句话结论

Operit 的网页自动化 = 内置 `WebView` + 把渲染后 DOM 精简成「带**稳定引用**的可交互元素列表」让 Agent 直接操作 + 浮窗常驻让人类接管登录/验证码。
本项目方案在 Operit 基础上做四件事实现超越：**语义哈希稳定 Ref**（抗 SPA 局部刷新）、**物理触摸注入**（抗 `isTrusted` 校验）、**JS 层启发式剪枝**（省 token）、**显式握手人工接管**（状态机 + 全局工具锁，零歧义）。

**开发纪律**：第 1~5 节为**当前必须落地**的工程实现；第 6 节「超纲推演」**严禁混入当前代码库**，仅作 12~18 个月技术储备。

---

## 1. 技术基线对齐（2026 范式）

| 维度 | 2024 旧范式（弃） | 2026 新范式（本方案） |
| --- | --- | --- |
| 元素引用 | 顺序索引 `bid=1,2,3`（SPA 局部刷新即失效） | **语义哈希 Ref** `r_<hash>`，由元素不可变特征计算 |
| 事件触发 | `element.click()`（合成事件，绕过事件冒泡链） | **DOM 定位 → Android `dispatchTouchEvent` 物理触摸** |
| DOM 传输 | 每次全量快照 | **JS 层启发式剪枝 + 字符预算**（增量 Diff 为 P2 储备） |
| 人工接管 | 简单浮窗显示 | **状态机驱动的显式握手**（共享 WebView + 全局工具锁） |
| 截图 | 全页长图 | **仅当前视口**（全页长图为未来增强） |

---

## 2. 已裁决的开放问题（最终版）

| # | 问题 | 裁决 | 关键推理 |
| --- | --- | --- | --- |
| ① | 稳定 Ref：用 `data-apex-ref`？跨 iframe？ | **废顺序索引，升级为语义哈希 `data-apex-hash`；暂不做跨 iframe** | SPA 局部刷新会让顺序 `r3` 指错元素；Ref 必须由元素不可变特征算出。跨域 iframe 受沙箱/CORS 限制，标记 P2，当前把 iframe 视为整体交互块 |
| ② | DOM 点击 vs 坐标点击 | **DOM 定位 + 物理触摸注入（混合）** | `el.click()` 是合成事件，React/Vue 与 Cloudflare/Akamai 会校验 `isTrusted` 与完整 `touchstart→touchmove→touchend` 轨迹；底层 `dispatchTouchEvent` 永远最真实 |
| ③ | 摘要压缩：字符预算 vs Tokenizer | **JS 层启发式剪枝 + Kotlin 层字符预算估计；不引 BPE** | 真 BPE 耗 CPU、增包体；在 JS 层过滤 `display:none`、无交互无文本、超深非交互子树，回传已是高纯度骨架 |
| ④ | 浮窗接管：共享 WebView vs 显式握手 | **共享 WebView（物理基础）+ 显式状态机握手（逻辑必须）** | 无状态机时 Agent 会在人类输密码时抢焦点；用 `AGENT_DRIVING → WAITING_HUMAN → HANDOFF_COMPLETE` 状态机 + 全局工具锁消除歧义 |
| ⑤ | 沙箱/CI 验证 | **接受** | WebView 渲染依赖 `android.webkit.*`，JVM 无法模拟；`DomParser` 抽纯 Kotlin 单测，引擎/UI 层靠 CI Instrumented Test |
| ⑥ | 截图时机 | **仅视口截图** | VLM 对 1080×10000 长图注意力稀释、显存溢出；正确模式是 `snapshot→scroll→screenshot` 确认局部 |

### 修正项：自动探针 → 显式完成按钮（Qwen 二次裁定，已采纳）

原方案「上下文感知自动探针监控」**彻底废弃**。理由：隐式推断在复杂生产环境不稳定（误判输入中/已放弃、页面成功标志各异、易误夺控制权）。
**改为**：人类在浮窗 UI 点「**我已完成操作**」按钮，触发显式握手交还 Agent。显式人类确认永远优于隐式机器推断。

---

## 3. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│  Agent (LLM)                                                   │
│   工具: browser_navigate / snapshot / click / input / scroll / screenshot │
│   人工接管: browser_show   (展开浮窗 → 人类登录/过验证)          │
└───────┬───────────────────────────────────┬──────────────────┘
        │ ToolRegistry                        │ 人类真实触控 (同一 WebView)
┌───────▼──────────────────────┐  ┌──────────▼──────────────────────────┐
│  app:browser/BrowserAgentTools │  │  app:browser/BrowserOverlay (浮窗 UI) │
│  (6 个 AgentTool，前置守卫)    │  │   · AGENT_DRIVING: 半透明遮罩防误触    │
│                              │  │   · WAITING_HUMAN: 常驻「我已完成」按钮 │
│  BrowserEngine.assertAgent   │  │   · 展开 BrowserScreen / 收起 FloatingBall │
│  Control() 守卫：锁定期拒绝    │  │                                       │
└───────┬──────────────────────┘  └──────────┬──────────────────────────┘
        │ 调用                                 │ attachActiveWebView(webView)
┌───────▼─────────────────────────────────────▼──────────────────┐
│  app:browser/BrowserEngine  (@Singleton WebView 宿主)            │
│   · 多 session · 历史栈 · 视口截图                                │
│   · 状态机: HIDDEN/AGENT_DRIVING/WAITING_HUMAN/HANDOFF_COMPLETE  │
│   · 语义哈希 ref 注入 · 物理触摸点击 · 全局工具锁                  │
└───────────────┬────────────────────────────────────────────────┘
                │ evaluateJavascript (含启发式剪枝)
┌───────────────▼────────────────────────────────────────────────┐
│  注入 JS → 抓真实 DOM (打 data-apex-hash) → 剪枝后回传骨架 JSON   │
└────────────────────────────────────────────────────────────────┘
```

数据流：`navigate` → `snapshot`（拿 `r_<hash>` 列表）→ `input`+`click`（物理触摸）→ `screenshot` 确认 → 遇登录/验证码 `browser_show` 展开浮窗 → 人类操作 → 点「我已完成」→ 引擎自动 `snapshot` 一次 → Agent 继续。

---

## 4. 核心创新点（超越 Operit 的 4 个落地点）

### 创新一：语义哈希稳定 Ref + 降级定位链
- **痛点**：顺序 `bid` 在 SPA AJAX 局部刷新后瞬间错位。
- **设计**：`ref = "r_" + hash(role + text + domPath + 相对位置)`。快照时给元素打 `data-apex-hash`。点击/输入时按 `querySelector('[data-apex-hash="..."]')` 精确定位。
- **降级链**（DOM 重绘导致 hash 失效时）：
  1. 精确匹配 `data-apex-hash`；
  2. 按快照保存的 `role + text` 在可视区模糊匹配；
  3. 按快照保存的相对坐标，在父容器内做空间邻近度匹配。
- 暂不做跨 iframe 递归（标记 P2）。

### 创新二：物理级触摸事件桥接（Physical Touch Bridge）
- **痛点**：JS `dispatchEvent` 无法触发依赖 `isTrusted=true` 的安全控件（银行密码键盘、滑块验证码）。
- **设计**：`clickElement(ref)` 不再 `el.click()`。流程：`JS 取 BoundingRect` → `换算 WebView 屏幕坐标` → `构造 MotionEvent(DOWN/UP)，DOWN~UP 间 30~80ms 随机延迟模拟按压` → `webView.dispatchTouchEvent()`。
- **意图验证**：无论页面 JS 事件绑定多复杂，底层 Android 触控永远是最真实输入源。

### 创新三：JS 层启发式剪枝 + 字符预算
- **痛点**：全量 DOM 撑爆 Context，噪声大。
- **设计**：`SNAPSHOT_JS` 内直接过滤 `display:none`/`visibility:hidden`、`offsetParent===null`、不可见非交互无文本节点、超深非交互子树；结果已是高纯度交互骨架。Kotlin 层 `DomParser.buildSummary` 再按字符预算裁折叠。
- **硬上限**：单次快照元素数 ≤ 50（Token 预算保护）。

### 创新四：显式握手人工接管（Context-Aware Handoff，修订版）
- **痛点**：人类介入后 Agent 不知何时结束。
- **设计**：`WAITING_HUMAN` 状态锁定所有 Agent 自动化工具；人类在浮窗点「我已完成操作」显式交还；引擎 `completeHandoff()` 触发后自动 `snapshot` 一次作为隐式上下文回灌 Agent。
- **废弃**：原自动探针监控（误判风险高、页面成功标志各异）。

---

## 5. 落地路线图（P0 → P2）

### [P0] 重构 Ref 生成机制
- 改 `BrowserScript.SNAPSHOT_JS`：`stableRef` 改用 `simpleHash(role + text + tag + 相对位置)`，输出 `data-apex-hash="r_<hash>"`。
- `DomParser` 读取 `data-apex-hash` 作为 `ref`（保留 `bid` 仅作展示序号，不作为定位主键）。
- `DomElement` 字段：`ref`（语义哈希）保持，`bid` 降级为展示序号。

### [P0] 物理触摸桥接
- `BrowserEngine.clickElement(ref)`：JS 取 `rect` → 换算坐标 → `MotionEvent` + `dispatchTouchEvent`，DOWN/UP 间随机延迟。
- 提供同步取坐标封装（`evaluateJavascript` 经 `CountDownLatch`/`suspendCancellableCoroutine` 同步化）。
- 坐标换算需叠加 WebView 在屏幕中的绝对偏移（浮窗场景）。

### [P1] 浮窗状态机 + 显式握手 UI
- 新增 `BrowserSessionState` 枚举：`HIDDEN` / `AGENT_DRIVING` / `WAITING_HUMAN` / `HANDOFF_COMPLETE`。
- `BrowserEngine`：`enterHandoffMode()` / `completeHandoff()` / `assertAgentControl()`（抛 `HandoffLockedException`）。
- `BrowserOverlay`：`WAITING_HUMAN` 态常驻「我已完成操作」按钮；`AGENT_DRIVING` 态半透明遮罩防人类误触；地址栏/前进后退在 `WAITING_HUMAN` 隐藏。
- 所有 `BrowserAgentTools` 执行前调用 `assertAgentControl()`，锁定期返回友好 `SYSTEM_LOCKED` 提示。
- `browser_show` 工具：展开浮窗并 `enterHandoffMode()`；人类点完成后 `completeHandoff()` + 自动 `snapshot`。

### [P1] Token 预算与剪枝测试
- CI 用 5 个重型页（淘宝/ X / GitHub / 知乎 / 银行登录）验证快照 ≤ 4096 tokens。

### [P2] 增量 Diff 缓存（储备）
- Android 端 `DomStateCache` 合并 `MutationObserver` 传回的 Diff（当前不做，先留接口）。

### 不变项（沿用既有骨架）
- `BrowserEngine` `@Singleton` + `@ApplicationContext` 构造注入，由 `ApexCoreService` 常驻持有。
- 所有 WebView 操作包 `withContext(Dispatchers.Main)`。
- `evaluateJavascript` 回传 JSON 解包：`json.parseToJsonElement(wrapped).jsonPrimitive.content`。

---

## 6. 工具接口（Agent 视角，最终版）

| 工具 | 参数 | 返回 | 状态机约束 |
| --- | --- | --- | --- |
| `browser_navigate` | `url`, `new_tab?` | 加载后概要 | AGENT_DRIVING |
| `browser_snapshot` | — | 带 `r_<hash>` 可交互元素列表 + 概要 | 锁定期返回 `SYSTEM_LOCKED` |
| `browser_click` | `ref` | 物理触摸点击结果 | 锁定期返回 `SYSTEM_LOCKED` |
| `browser_input` | `ref`, `text` | 填值结果 | 锁定期返回 `SYSTEM_LOCKED` |
| `browser_scroll` | `delta_y?` | 滚动结果 | 锁定期返回 `SYSTEM_LOCKED` |
| `browser_screenshot` | — | PNG base64（视口） | 锁定期返回 `SYSTEM_LOCKED` |
| `browser_show` | `expand?`(默认 true) | 展开/收起浮窗，进入/退出人工接管 | 触发 `enterHandoffMode`/`completeHandoff` |

`browser_snapshot` 返回示例（语义哈希 ref，稳定）：
```
⊕ 页面可交互元素（共 12 个，ref 稳定）：
  [r_a1b2] 链接 首页
  [r_9f3c] 输入框 搜索
  [r_4d7e] 按钮 提交
  …折叠 3 个低优先级元素（用 browser_dump 查看全部）
```

---

## 7. 代码改造清单（对照现状）

| 文件 | 现状 | 改造动作 |
| --- | --- | --- |
| `core/.../browser/BrowserScript.kt` | `stableRef` 用顺序 + 属性哈希打 `data-apex-ref` | 改语义哈希 `r_<hash>`，属性名改 `data-apex-hash`；增强 JS 层剪枝（不可见/非交互/超深过滤）；硬上限 50 |
| `core/.../browser/DomParser.kt` | 读 `data-apex-ref` 作 `ref` | 改读 `data-apex-hash`；`bid` 仅展示序号；summary 用 `ref` |
| `core/.../browser/DomElement.kt` | `ref` 字段已有 | 注释改为「语义哈希」；`bid` 标记展示用途 |
| `app/.../browser/BrowserEngine.kt` | `clickElement` 用 `el.click()`；无状态机 | 改物理触摸注入；新增 `BrowserSessionState` + 状态锁 + `enterHandoffMode`/`completeHandoff`/`assertAgentControl`；`ref` 定位改用 `data-apex-hash` |
| `app/.../browser/BrowserAgentTools.kt` | 6 工具，`bid` 参数 | 参数改 `ref`；各工具头部加 `assertAgentControl()` 守卫；新增 `browser_show` 的握手触发 |
| `app/.../browser/BrowserOverlay.kt` | 已实现 | `WindowManager` 浮窗 + Compose 控制条；与引擎**共享 activeTab WebView**（`onStateChanged` 订阅状态：`WAITING_HUMAN` 自动展开并挂 WebView，其余态收起 detach）；`RECOVERING` 橙色徽章、`AGENT_DRIVING` 蓝、`WAITING_HUMAN` 绿 + 「我已完成操作」按钮；折叠/收起；无 `SYSTEM_ALERT_WINDOW` 权限时静默降级 |
| `app/.../browser/OverlayLifecycleOwner.kt` | 已实现 | 浮窗 ComposeView 独立 `LifecycleOwner`（onCreate/start/resume/pause/stop/destroy） |
| `app/.../browser/CyberNeonBallManager.kt` | 已实现 | **赛博极客·霓虹环流球**（常驻收缩态枢纽）。`EasyFloat` 全局浮窗；高光黑曜石 `bg_obsidian_glossy` + `NeonRingView` 代码绘制等离子环流（默认，零资源依赖）+ 可选 `LottieAnimationView`（`res/raw/anim_neon_<state>.json` 存在时优先加载并运行时改色）；`SpringAnimation` 物理挤压（按下 0.88 弹回）；点击球 toggle 显式握手（`enterHandoffMode`/`completeHandoff`）；状态色 RUNNING 蓝/NEED_HUMAN 金+脉冲+抖动+震动/badge、ERROR 红、SUCCESS 绿；订阅 `BrowserEngine` 多播状态回调 |
| `app/.../browser/NeonRingView.kt` | 已实现 | 代码绘制霓虹等离子光环（多层旋转 SweepGradient + 呼吸透明度），替代 Lottie 二进制资源的默认兜底，零包体 |
| `app/src/main/res/raw/anim_neon_running.json` | 已实现（真实资源） | 从 LottieFiles 公开 CDN 下载的真实免费 Lottie（105KB），Running 态默认加载并染成电光蓝；`warning/error/success` 三态 raw 待补（放入即自动生效） |
| `app/.../di/ToolModule.kt` | 已注册 6 工具 | `browser_show` 加握手；注入 `BrowserOverlay`（P1） |

---

## 8. 超纲推演（严禁进生产代码，仅技术储备）

> ⚠️ 基于 2026 端侧 NPU（>50 TOPS）趋势的 12~18 个月储备方向，**当前严禁混入代码库**。

**端侧 VLM「零 DOM 注入」自动化**：
1. 完全不注入 JS，100% 隐身，免疫 DOM 反爬检测。
2. 端侧量化 VLM（如 Qwen-VL-Edge）直接推理 WebView 截图。
3. 输出 `{"action":"click","target_visual_desc":"蓝色登录按钮","coordinates":[540,1200]}`。
4. 限制：端侧模型密集小文本识别率仍不如直读 DOM，且发热。**当前坚持 DOM 为主、视觉为辅双模态**。

---

## 9. 验证路径

- **JVM 单测**：`DomParserTest` 验证语义哈希 ref 稳定性、交互过滤、摘要压缩意图（字符预算）。
- **CI Instrumented Test**：`BrowserEngine` 物理触摸、浮窗状态机、剪枝 token 消耗（5 重型页 ≤ 4096 tokens）。
- 本机无 `gradlew`，仅 lint；完整编译/单测/仪表化测试依赖 GitHub Actions。

---

## 10. 最终裁定

基础已扎实，按第 3 节 4 个创新点收尾：**先打通「语义哈希 Ref + 物理触摸注入」主线（P0），再补「显式握手浮窗 + 全局工具锁」（P1）**。期待 CI 绿灯后的浮窗接管演示。

---

## 11. P0 缺口审计报告落地状态（2026-08-12 执行）

对标 Qwen 第二轮审计（7 大类 28 缺口），本轮已落地 **P0 全部 6 项 + 部分 P1/P2 增强**。代码改动清单：

| 审计缺口 | 落地位置 | 实现要点 |
| --- | --- | --- |
| #1 页面加载等待 | `BrowserEngine.navigate` | `onPageFinished` 基础等待 + 可选 `wait_for` 元素轮询（10s）+ 15s 超时兜底；工具 `browser_navigate` 新增 `wait_for`/`timeout_ms` |
| #2 动作后验证 | `BrowserEngine.probePage`/`PostActionState` | 点击/输入/选择后对比 URL/标题/可交互元素数；工具返回 `动作完成·URL变化/新增元素` 摘要 |
| #3 JS 弹窗处理 | `BrowserEngine.createWebView` 的 `WebChromeClient` | `onJsAlert/Confirm/Prompt` 接管并自动 confirm，文本存入 `lastDialog` 注入下次 snapshot |
| #4 渲染进程崩溃 | `WebViewClient.onRenderProcessGone` | 崩溃即销毁重建 WebView，置 `RECOVERING` 状态，返回 true 不 crash 宿主 |
| #5 动作空间补全 | `selectOption`/`toggle`/`respondFileChooser` + 工具 `browser_select`/`browser_toggle`/`browser_file_upload` | `<select>` 按 value/文本选；checkbox/radio 走物理触摸；文件选择挂起回调 |
| #6 Cookie 持久化 | `flushCookies()`（`CookieManager.flush`） | 接管完成、导航完成、销毁时强制刷盘；`WebSettings` 开 domStorage |
| （主线）语义哈希 ref | `BrowserScript.SNAPSHOT_JS` 写 `data-apex-hash`；`DomParser`/`DomElement` 读之 | `ref="r_<hash>"`，`bid` 降级仅展示序号（抗 SPA 局部刷新） |
| （主线）物理触摸注入 | `BrowserEngine.clickElement` | `data-apex-hash` 定位 → WebView 坐标 → `dispatchTouchEvent`（DOWN~UP 随机 30~80ms） |
| （主线）显式握手 | `BrowserSessionState` + `enterHandoffMode`/`completeHandoff`/`assertAgentControl` | 工具前置守卫返回 `SYSTEM_LOCKED`；`browser_show` 触发握手 |
| #10 无限滚动（P1 增强） | `BrowserEngine.scroll`/`ScrollResult` | `wait_for_new` 检测新增元素数 |
| #11 安全加固（P1 基线） | `createWebView` | 禁 `allowFileAccess`/`allowContentAccess`/file-from-URL（防 UXSS/路径穿越） |

**未在本轮实现（待 P1/P2 排期）**：#7 重试熔断、#8 上下文窗口管理、#9 可观测性、#12 权限请求、#13 反爬隐身、#14 下载、#15 内存维护、#16 多标签并发上限、#17 Accessibility Tree、#18 网络拦截、#19 任务规划、#20 可编程剪枝。

**验证**：`DomParserTest` 已加「ref 来自语义哈希、不随顺序偏移」意图测试；本机无 gradlew，仅 lint 0 错误，完整编译/单测/仪表化测试依赖 CI。

---

## 12. P1 缺口落地状态（2026-08-12 续）

在 P0 基础上，本轮补齐 **P1 三块**：错误恢复/重试熔断(#7)、可观测性(#9)、上下文窗口管理辅助(#8 轻量版)。悬浮 UI(#4 待建)按用户要求留到最后一步。

| 审计缺口 | 落地位置 | 实现要点 |
| --- | --- | --- |
| #7 错误恢复/重试 | `RetryPolicy.kt`（`RetryPolicy`+`CircuitBreaker`+`withRetry`）；`BrowserEngine` 的 `clickElement`/`inputText`/`selectOption`/`snapshot` 包 `withRetry` | 指数退避（500ms×2^n，上限 5s，最多 3 次）；`ElementNotFoundException`/`TimeoutException`/`WebViewNotRespondingException` 可重试；`HandoffLockedException` 等语义错误**不重试**；连续 5 次失败熔断 30s |
| #9 可观测性 | `BrowserTracer.kt` + `BrowserAgentTools.TracedTool` 包装 | 内存环形缓冲(100 条)记录 工具/参数/结果/耗时/URL/状态；`browser_debug_dump(limit)` 导出最近 N 条 |
| #8 上下文管理（轻量） | `BrowserTracer.contextSummary` + `browser_context_summary` 工具 | 最近 3 步保留详情，更早步骤压成单行；始终保留 URL 关键帧 |

**新增/修改文件**：`app/.../browser/RetryPolicy.kt`（新）、`app/.../browser/BrowserTracer.kt`（新）、`BrowserEngine.kt`（接 withRetry + snapshot 8s 超时 → `WebViewNotRespondingException`）、`BrowserAgentTools.kt`（TracedTool 包装 + `browser_debug_dump`/`browser_context_summary`）、`ToolModule.kt`（`BrowserTracer` 注入，`BrowserEngine` 改由 @Inject 构造提供）、`app/.../test/.../RetryPolicyTest.kt` + `BrowserTracerTest.kt`（意图测试）。

**未做（P1 剩余 / P2）**：#7 的持久化 trace 落盘/OpenTelemetry（仅内存）、#8 的 Agent 框架层完整滑动窗口（本层只提供摘要工具）、#10 已在 P0 做、#11 已在 P0 做、#12 权限请求、#13 反爬隐身、#14 下载、#15 内存维护、#16 多标签并发上限、#17 Accessibility Tree、#18 网络拦截、#19 任务规划、#20 可编程剪枝。**浮窗 UI（BrowserOverlay）已完成**（P1 最后一步，按用户要求延后至此）。

**验证**：`RetryPolicyTest`/`BrowserTracerTest` 验证重试熔断与上下文压缩意图；lint 0 错误；完整编译/测试依赖 CI。
