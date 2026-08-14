# 内置浏览器网页自动化（WebBrowser Automation）

> 对标并超越 **Operit** 的网页自动化能力。
> 调研日期：2026-08-12

---

## 1. Operit 是怎么做的（原理调研）

| 能力 | Operit 实现 |
| --- | --- |
| 内置浏览器 | 基于 `android.webkit.WebView`，自维护 `TabManager`（多标签）、`HistoryManager`、`BookmarkManager`、`DownloadManager`、`UserScriptManager` |
| 浏览器 Agent | `BrowserAgent` + `BrowserTools`：把当前页面 DOM 树（`DomParser`）精简成带 `data-bid` 索引的交互元素列表，Agent 通过 `click_element` / `input_text` / `scroll` / `navigate` / `go_back` 直接操作 DOM 级节点 |
| 视觉补充 | 截图 + OCR / 视觉模型理解渲染结果 |
| 扩展 | 用户脚本注入（`UserScriptManager`）、局域网穿透（`lan.md`）让桌面接管浏览器 |

**核心洞察**：Operit 的强项不是"有浏览器"，而是**「DOM 级精确操作 + 结构化快照」**——Agent 看到的是精简后的可交互元素（带稳定索引），而不是整屏像素坐标，也不是原始 HTML。

## 2. 我们的现状与缺口

- `WebFetchTool` / `WebSearchTool`：静态 HTTP 抓取（拿 HTML 文本 / DDG 结果），**看不到 JS 渲染后的 DOM**，也不能交互。
- `UiTools`（`UiTap`/`UiSwipe`/`UiDump`）：操作**整个手机屏幕坐标**，对动态网页只能"盲猜坐标 + 截图"。
- **中间缺的正是 Operit 的「内置 WebView 浏览器 + DOM 级网页 Agent」这一层。**

## 3. 我们的设计（不输给他，且更强）

### 架构

```
app:browser/
  ├─ BrowserEngine          @Singleton，无界面 WebView 宿主（多标签 + 历史 + JS桥 + 截图）
  └─ BrowserAgentTools      6 个 AgentTool（严格对齐现有 ToolRegistry 风格）
core:tool-registry/browser/
  ├─ DomElement / PageSnapshot   纯 Kotlin 数据模型（可单测）
  ├─ DomParser                   DOM→快照解析 + token 预算摘要压缩（纯 Kotlin）
  └─ BrowserScript               注入 WebView 的 JS 抓取/高亮片段
```

### 比 Operit 强的 4 个点

1. **DOM 级 + 坐标兜底双通道**：`DomElement` 保留 `rect` / `isVisible`，既能按 `bid` 精确 `el.click()`，也能在必要时用 `UiTools` 坐标兜底；Operit 仅依赖 DOM。
2. **LLM 摘要压缩**：`DomParser.buildSummary` 按 token 预算裁剪深层非交互节点为 `[折叠 N 项]`，避免把整页灌进 prompt（Operit 的 `DomParser` 仅做扁平化）。
3. **复用现有特权/无障碍体系**：`browser_click` 失败可自然回落到 `UiTapTool`（AccessibilityService），形成"浏览器内 DOM → 全屏坐标"的降级链。
4. **双模态理解**：`browser_snapshot`（结构化 DOM）+ `browser_screenshot`（PNG base64）可同时进 prompt，既省 token 又保视觉保真。

### 工具清单（6 个，注册进 ToolRegistry）

| 工具 | 作用 |
| --- | --- |
| `browser_navigate` | 打开 URL（可新标签），返回加载后页面概要 |
| `browser_snapshot` | 抓当前页 DOM 结构化快照（带稳定 `bid`） |
| `browser_click` | 按 `bid` 精确点击（DOM 级） |
| `browser_input` | 向 `bid` 输入框填文本 |
| `browser_scroll` | 页面滚动 |
| `browser_screenshot` | 整页截图 PNG base64 |

## 4. 接入方式（零侵入）

- `BrowserEngine` 用 `@Singleton` + 构造器注入 `@ApplicationContext`，由 `ApexCoreService`（前台常驻 Service）持有即可后台驱动。
- `ToolModule.provideToolRegistry` 中 `browserAgentTools.all().forEach { register(SafeAgentTool(it)) }`，与现有 44 工具完全同构。
- `core:tool-registry` 新增 `browser/` 包为**纯 Kotlin（JVM）**，无 Android 依赖，已配 `DomParserTest`（JVM 单测，验证 bid 分配 / 交互过滤 / 摘要压缩意图）。

## 5. 使用流程（Agent 视角）

```
1. browser_navigate {"url":"https://example.com"}
2. browser_snapshot          → 拿到带 bid 的元素列表
3. browser_input   {"bid":3,"text":"查询词"}
4. browser_click   {"bid":7} → 提交/进入
5. browser_screenshot        → 视觉确认渲染/验证码
6. browser_scroll  {"delta_y":600} → 翻看下方
```

## 6. 后续可增强（路线图）

- [ ] `BrowserEngine` 多标签 UI（Compose 浮窗，类似 Operit 内置浏览器界面）。
- [ ] 用户脚本注入（`UserScriptManager`）。
- [ ] 局域网穿透，允许桌面浏览器接管手机内 WebView（对标 Operit `lan.md`）。
- [ ] 与 `UiTools` 打通"DOM 失败自动坐标兜底"的降级链。
- [ ] 截图 OCR 文本回填到 `PageSnapshot`，弥补纯 DOM 抓不到的 canvas/图片内文字。

## 7. 验证

- `DomParserTest`（JVM）：`gradlew :core:tool-registry:testDebugUnitTest --tests "*DomParserTest"`。
- 端到端需 Android 运行环境（WebView 必须在有 Looper 的主线程创建，`BrowserEngine` 已用 `Dispatchers.Main` 约束）。
