# 内置浏览器网页自动化 —— 完整方案（待评审）

> 目标：对标并超越 **Operit** 的网页自动化能力，给 Android-Guru-Agent 增加「内置 WebView + DOM 级网页 Agent」。
> 状态：设计 + 核心代码骨架已完成，待 Qwen 参谋评审后落地收尾。
> 调研日：2026-08-12

---

## 0. TL;DR（给评审人一句话）

Operit 的网页自动化 = 内置 WebView + 把渲染后 DOM 精简成「带稳定索引的可交互元素列表」让 Agent 直接操作。
本项目已有 44 个 `AgentTool`（含静态 `web_fetch`/`web_search` 和整屏坐标 `UiTools`），但缺「浏览器内 DOM 级操控」这一层。
**本方案补上这层，并额外做了摘要压缩 / 坐标兜底双通道 / 双模态（DOM+截图），比 Operit 更省 token、更稳。**

---

## 1. Operit 原理（确认）

| 能力 | Operit 实现（已读源码确认） |
| --- | --- |
| 内置浏览器 | `android.webkit.WebView`，多 session（`StandardBrowserSessionTools` 管理 `sessions: ConcurrentHashMap`），`WebSessionHistoryStore` 历史栈 |
| 浏览器 Agent | `StandardBrowserSessionTools` + `BrowserToolSupport`：注入 `aria-ref` 给 DOM 元素作**稳定引用**（非每帧重排），快照输出 **YAML**（`role`+`name`，`getByRole`），跨 iframe 递归解析（`browserRefResolverScript`）。Agent 用 `click`/`type`/`scroll`/`navigate`/`back` 操作 |
| **浮窗浏览器（用户重点）** | `WebSessionBrowserHost`：用 **`WindowManager` + `ComposeView`（`TYPE_APPLICATION_OVERLAY`）** 把浏览器浮窗挂到系统窗口之上（非 Activity），自带 `WebSessionOverlayLifecycleOwner` 模拟 Lifecycle/ViewModelStore 让 Compose 活起来；`attachActiveWebView(webView)` 把当前 session 的 WebView attach 进浮窗 |
| 浮窗两形态 | 展开 = `WebSessionBrowserScreen`（地址栏/标签/前进后退/刷新）；收起 = `WebSessionMinimizedIndicator`（圆形/胶囊小浮标，`Icons.Language`，可拖动）。`DeceptiveMinimizedLayout` 用 1×1 测量欺骗布局，使「收起但 WebView 不销毁」 |
| **人工接管 / 过验证** | 浮窗**常驻显示实时 WebView**，人类直接触摸屏幕（输账号、点验证码）= 普通 Android 触控，WebView 自然响应；Agent 工具调用与人类手动操作**共享同一 WebView 实例**。Agent 需要人类时只需展开浮窗（`overlayExpanded=true`），人类完成后 Agent 继续 snapshot/click |
| 视觉补充 | 截图 + OCR / 视觉模型 |
| 扩展 | 用户脚本注入（`WebSessionUserscriptManager`）、`WebSessionPermissionRequestActivity` 处理权限弹窗 |

**核心洞察**：
1. Operit 的强项是「DOM 级精确操作 + 结构化快照（YAML/aria-ref）」，不是"有浏览器"本身。
2. **悬浮球→实时浏览器→人工接管**的本质极简：一个挂在系统窗口上的常驻 WebView 浮窗。Agent 操作的是同一个 WebView，人类也是；两者天然共存，无需复杂 IPC。这就是"登录/过验证"能交给人的原因。

### 1.1 Operit 浮窗浏览器源码地图（实测）

```
core/tools/defaultTool/websession/browser/
  ├─ WebSessionBrowserHost.kt          # WindowManager+ComposeView 浮窗宿主，expand/minimize
  ├─ WebSessionWebViewHost.kt          # 单个 WebView 的宿主封装
  ├─ BrowserWebViewSupport.kt          # WebView 创建/配置/生命周期
  ├─ BrowserPageExecutionSupport.kt    # 页面执行 JS 注入（aria-ref、snapshot）
  ├─ BrowserToolSupport.kt             # 快照/节点模型（BrowserSnapshot/YAML/aria-ref 解析）
  ├─ WebSessionHistoryStore.kt         # 历史栈
  ├─ WebSessionPermissionRequestActivity.kt  # 权限弹窗
  └─ BrowserDownloadSupport.kt         # 下载
core/tools/defaultTool/standard/StandardBrowserSessionTools.kt  # 工具入口 + 多 session 管理
ui/features/websession/browser/
  ├─ WebSessionBrowserScreen.kt        # 展开态完整浏览器 UI
  ├─ WebSessionMinimizedIndicator.kt   # 收起态悬浮球（小窗）
  ├─ WebSessionFloatingTheme.kt
ui/common/browser/BrowserCallbackDialog.kt  # JS alert/confirm/prompt 回调弹窗
```

---

## 2. 本项目现状与缺口

- `WebFetchTool` / `WebSearchTool`：静态 HTTP 抓取（HTML 文本 / DDG 结果），**看不到 JS 渲染后的 DOM，不能交互**。
- `UiTools`（`UiTap`/`UiSwipe`/`UiDump`）：操作**整块手机屏幕坐标**，对动态网页只能"盲猜坐标 + 截图"。
- **缺口 = Operit 的「内置 WebView + DOM 级 Agent」层。**

---

## 3. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│  Agent (LLM)                                                   │
│   工具调用: browser_navigate / snapshot / click / input / ...  │
│   人工接管: browser_show   (展开浮窗，交人类登录/过验证)         │
└───────┬───────────────────────────────────┬──────────────────┘
        │ (ToolRegistry)                      │ 人类触控 (同一 WebView)
┌───────▼──────────────────────┐  ┌──────────▼──────────────────────────┐
│  app:browser/BrowserAgentTools │  │  app:browser/BrowserOverlay (浮窗 UI) │
│  (6 个 AgentTool)             │  │   · 展开: BrowserScreen (地址栏/标签) │
│                              │  │   · 收起: FloatingBall (可拖动小窗)   │
└───────┬──────────────────────┘  └──────────┬──────────────────────────┘
        │ 调用                                 │ attachActiveWebView(webView)
┌───────▼─────────────────────────────────────▼──────────────────┐
│  app:browser/BrowserEngine  (@Singleton WebView 宿主)            │
│   · 多 session   · 历史栈   · 截图   · 按 ref 点击/输入          │
│   · 主线程约束 (Dispatchers.Main)   · aria-ref 稳定引用注入      │
└───────────────┬────────────────────────────────────────────────┘
                │ evaluateJavascript
┌───────────────▼────────────────────────────────────────────────┐
│  注入 JS → 抓真实 DOM (带 aria-ref) → DOM 快照 (YAML + 摘要压缩) │
└────────────────────────────────────────────────────────────────┘
```

数据流：`navigate` → `snapshot`（拿 ref 列表）→ `input`+`click` → `screenshot`（视觉确认）→ 遇登录/验证码 `browser_show` 展开浮窗 → 人类操作后 Agent 继续。

---

## 4. 文件清单（已落地骨架）

### 4.1 core（纯 Kotlin/JVM，无 Android 依赖，可单测）
| 文件 | 内容 |
| --- | --- |
| `core/tool-registry/src/main/kotlin/com/apex/agent/core/tools/builtin/browser/DomElement.kt` | `DomElement`/`Rect`/`PageSnapshot`/`RawDomElement` 数据模型 |
| `core/tool-registry/src/main/kotlin/com/apex/agent/core/tools/builtin/browser/DomParser.kt` | `DomParser.parse()`：DOM→快照 + token 预算摘要压缩（**稳定 ref 而非每帧重排 bid**，对齐 Operit 的 aria-ref 思路） |
| `core/tool-registry/src/main/kotlin/com/apex/agent/core/tools/builtin/browser/BrowserScript.kt` | `SNAPSHOT_JS`（注入抓取 + 给元素打 `data-apex-ref` 稳定引用）、`highlightJs()` |
| `core/tool-registry/src/test/kotlin/com/apex/agent/core/tools/builtin/browser/DomParserTest.kt` | JVM 单测：ref 稳定性 / 交互过滤 / 摘要压缩意图 |

### 4.2 app（Android）
| 文件 | 内容 |
| --- | --- |
| `app/.../browser/BrowserEngine.kt` | `@Singleton` WebView 宿主：多 session/历史/截图/点击/输入/scroll，全部 `Dispatchers.Main` |
| `app/.../browser/BrowserAgentTools.kt` | 6 个 `AgentTool`（含 `browser_show` 展开浮窗），对齐现有风格 |
| `app/.../browser/BrowserOverlay.kt` | **浮窗浏览器 UI**：`WindowManager`+`ComposeView`（`TYPE_APPLICATION_OVERLAY`）挂系统窗口；展开=`BrowserScreen`（地址栏/标签/前进后退），收起=`FloatingBall`（可拖动小窗）。对应 Operit 的 `WebSessionBrowserHost` |
| `app/.../browser/BrowserOverlayLifecycleOwner.kt` | 浮窗内 Compose 的 Lifecycle/ViewModelStore/SavedStateRegistry 模拟（对应 Operit `WebSessionOverlayLifecycleOwner`） |
| `app/.../di/ToolModule.kt` | 提供 `BrowserEngine`/`BrowserAgentTools`/`BrowserOverlay` 并注册工具 |

---

## 5. 工具接口（Agent 看到的）

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `browser_navigate` | `url`, `new_tab?` | 加载后页面概要 |
| `browser_snapshot` | — | 带 `ref` 的可交互元素列表 + 页面概要（YAML 风格） |
| `browser_click` | `ref` | 点击结果 |
| `browser_input` | `ref`, `text` | 填值结果 |
| `browser_scroll` | `delta_y?` | 滚动结果 |
| `browser_screenshot` | — | PNG base64（`data:image/png;base64,...`） |
| **`browser_show`** | `expand?`(默认 true) | 展开/收起浮窗，**交人类登录或过点机验证**；人类操作与 Agent 共享同一 WebView |

`browser_snapshot` 返回示例（已压缩，ref 稳定）：
```
⊕ 页面可交互元素（共 12 个，ref 稳定）：
  [r3] 链接 首页
  [r7] 输入框 搜索
  [r11] 按钮 提交
  …折叠 3 个低优先级元素
```

---

## 6. 比 Operit 强的 4 点（设计意图）

1. **DOM 级 + 坐标兜底双通道**：`DomElement` 保留 `rect`/`isVisible`，既能 `el.click()`，也能在必要时回落 `UiTapTool`（AccessibilityService）。
2. **LLM 摘要压缩**：`DomParser.buildSummary` 按 token 预算裁剪深层非交互节点为 `[折叠 N 项]`，避免整页 HTML 灌 prompt（Operit 仅扁平化）。
3. **复用现有特权/无障碍体系**：`browser_click` 失败 → 自然降级到 `UiTools`，形成"浏览器内 DOM → 全屏坐标"降级链。
4. **双模态理解**：`browser_snapshot`（结构化 DOM）+ `browser_screenshot`（PNG）可同时进 prompt，省 token 又保视觉保真。
5. **（新增）人工接管浮窗**：`browser_show` 复用 Operit 思路——常驻 WebView 浮窗，人类直接触控即可登录/过验证，Agent 与人类共享同一实例，无需复杂 IPC。

---

## 7. 关键实现要点（供评审）

- **WebView 必须在主线程创建**：`BrowserEngine` 所有 WebView 操作包在 `withContext(Dispatchers.Main)`。
- **单例宿主**：`BrowserEngine` 用 `@Singleton` + `@ApplicationContext` 构造注入，由 `ApexCoreService`（前台常驻 Service）持有，可后台驱动。
- **JS 回传解包**：`evaluateJavascript` 回传带外层引号的 JSON 字符串，需 `json.parseToJsonElement(...).jsonPrimitive.content` 解一层再给 `DomParser`。
- **稳定 ref（对齐 Operit aria-ref）**：注入 JS 给每个可交互元素打 `data-apex-ref="r<序号>"`（基于 DOM 遍历顺序），`snapshot` 时回读该属性作为 `ref`，**不随 snapshot 顺序重排**。点击/输入时用 `querySelector('[data-apex-ref="r7"]')` 定位。先不做跨 iframe 递归（Operit 用 `browserRefResolverScript` 做），留作增强。
- **浮窗浏览器（人工接管核心）**：`BrowserOverlay` 用 `WindowManager.addView(ComposeView, LayoutParams(TYPE_APPLICATION_OVERLAY))` 把 WebView 挂系统窗口；收起态用 1×1 测量欺骗布局（`DeceptiveMinimizedLayout` 思路）使 WebView 不销毁。**人类触控 = 普通 Android 事件，与 Agent 工具调用共享同一 WebView，天然共存。**

---

## 8. 待 Qwen 参谋评审的开放问题

1. **（已确认方向）稳定 ref**：实测 Operit 用 `aria-ref` 稳定引用 + YAML 快照，非每帧重排。本方案改用 `data-apex-ref` 同思路，Qwen 是否认可？是否要一步到位做跨 iframe 递归（Operit 的 `browserRefResolverScript`）？
2. **DOM 级点击 vs 坐标点击**：`clickElement(ref)` 用 `el.click()` 派发，对"靠 JS 事件委托 / hover 才出现的菜单"可能不触发。是否应默认对 `<a>`/按钮用坐标（`rect` 中心）点击更稳？还是保留 DOM 级优先 + 失败回落坐标？
3. **摘要压缩策略**：当前按"字符预算"粗略估算 token。是否应接入真实 tokenizer（如 tiktoken 近似）更准？还是字符预算够用？
4. **浮窗人工接管形态**：`browser_show` 展开浮窗交人类登录/过验证——Qwen 认可"Agent 与人类共享同一 WebView 实例"这个极简方案吗？还是要做"Agent 暂停等待人类信号再继续"的显式握手（更可控但更复杂）？
5. **沙箱限制**：本机无 `gradlew`，仅能做 lint 检查；完整编译/单测需在 CI（GitHub Actions）跑。方案是否接受此验证路径？
6. **截图时机**：`browser_screenshot` 截的是当前 WebView 视口（非完整长图）。是否需要做"全页长截图"（分片拼接）？

---

## 9. 路线图（评审通过后）

- [ ] 评审开放问题 → 收尾 `BrowserEngine` / `BrowserAgentTools` / `DomParser`（稳定 ref）
- [ ] `ApexCoreService` 注入 `BrowserEngine` + `BrowserOverlay` 单例（确认常驻）
- [ ] **实现 `BrowserOverlay` 浮窗浏览器（展开/收起/可拖动小窗）+ `browser_show` 工具**（对标 Operit 悬浮球人工接管）
- [ ] CI 跑 `DomParserTest` + 全量 assemble
- [ ] 跨 iframe `data-apex-ref` 递归解析（对标 Operit `browserRefResolverScript`）
- [ ] 用户脚本注入（`UserScriptManager`）
- [ ] 局域网穿透，桌面接管手机 WebView（对标 Operit `lan.md`）
- [ ] DOM 失败自动坐标兜底降级链
- [ ] 截图 OCR 文本回填 `PageSnapshot`（补 canvas/图片内文字）
