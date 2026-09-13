# 浮窗浏览器 Chrome 层 —— 「给人用的浏览器界面」（P2）

## 动机

此前浮窗（BrowserOverlay）在人工接管（WAITING_HUMAN）时只有一块信息控制条：
状态徽章 + 标题/URL + 折叠/收起。人类被要求「接管操作」，却没有可操作的东西——
没有地址栏、没有标签页、没有前进/刷新、没有页内查找，JS 弹窗与网页权限在
引擎侧被「自动 confirm / 默认 deny」直接消化，人完全不在环。

本次交付在引擎之上补齐完整的浏览器外观（chrome）与交互层：人工接管期间，
人类获得一个真正的浏览器；交还之后，Agent 继续无头驱动。全部界面为原创设计，
不参考任何第三方实现的代码或视觉资产。

## 架构

```
ApexCoreService ──注入──> BrowserOverlay（浮窗宿主：窗口/生命周期/WebView 容器）
                              │ BrowserChrome(gateway, handoffBanner, pageSlot)
                              ▼
                     ┌─────────────────────────────┐
                     │  chrome/（引擎无关 UI 层）    │  地址胶囊即进度条 / 标签条
                     │  BrowserChromeController     │  状态机（撤销宽限/联想/查找防抖）
                     │  快照合成 SnapshotComposer    │  TabSet(低频) + Live(高频) → EngineSnapshot
                     └──────────────┬──────────────┘
                                    │ BrowserEngineGateway（唯一边界契约）
                     ┌──────────────▼──────────────┐
                     │  chrome/bridge/（接线层）     │
                     │  ApexChromeWiring            │  真实接线器（本 PR 新增）
                     │  NeonChromeWiring            │  链式 client + 快照自动合成
                     │  ChainingClients             │  WebViewClient/WebChromeClient 链式接管
                     │  DialogRequestRouter         │  JS 弹窗 + 权限路由（人机双通道）
                     │  AgentDialogPolicy / 权限记忆 │  安全默认 + origin 持久化
                     └──────────────┬──────────────┘
                                    │ TabOperations（六个一行方法）
                                    ▼
                          BrowserEngine（零行为改动，仅增量接口）
```

- **chrome/ 不 import 任何 WebView/引擎类型**：可 @Preview、可 JVM 单测，引擎重构零波及；
- **ChainingClients 链式接管**：不替换引擎已有 client，而是包一层——引擎自动化回调
  （onPageFinished 注入、scheme 白名单、SSL 拦截、渲染进程恢复）全部转发保留，
  仅接管 JS 弹窗 / 网页权限 / 地理定位 / 进度标题采集；
- **快照频率分层**：标签结构（低频，resync 拉取）与进度/标题/URL（高频，链式回调）
  由 SnapshotComposer 合成单一 EngineSnapshot，UI 只消费一个 StateFlow。

## 能力清单（人工接管期间人类可做的事）

| 能力 | 组件 | 说明 |
| --- | --- | --- |
| 地址胶囊即进度条 | ChromeBar / AddressEditPane | URL 拆分显示（host+路径）、编辑态联想（历史/剪贴板粘贴即走/打开的标签）、加载进度直接渲染在胶囊描边上 |
| 多标签 | TabStrip / TabSwitcherSheet | 横向标签条（单标签自动隐藏）+ 卡片总览（网格）；新建/切换/关闭/关闭全部 |
| 撤销式关闭 | Controller | 关标签有 4s 宽限期，Snackbar 撤销；宽限在 chrome 层消化，引擎仍收立即 close |
| 前进/后退/刷新/停止 | ChromeBar | 引擎 canGoBack/canGoForward 实时驱动可用性 |
| 页内查找 | FindBar | findAllAsync + 匹配计数 + 上一个/下一个 |
| 桌面模式 | 溢出菜单 | UA 切换 + reload，首次 bind 自动捕获原始 UA |
| JS 弹窗人机双通道 | JsDialogHost | 真实弹窗由人裁决；「交给 Agent 决定」转 AgentDialogPolicy 异步裁决；排队逐个处理，恰好一次回放 |
| 网页权限三档 | PermissionRequestHost | 拒绝 / 仅此一次 / 总是允许（按 origin 持久化，SharedPreferences） |
| 下载 | DownloadShelf / DownloadsSheet | 系统 DownloadManager 轮询进度、取消/重试/跳转系统下载 UI；入队回写引擎 lastDownload 保持 Agent 工具可读 |
| 溢出菜单 | ChromeOverflowMenu | 复制链接 / 在外部浏览器打开 / 用户脚本面板（注册表插槽） |

## 人机协同语义（本层的核心差异点）

- **WAITING_HUMAN（人在）**：浮窗展开完整 chrome；弹窗/权限由人裁决；
  接管横幅常驻（「我已完成操作，交还 Agent」）。
- **AGENT 态（无人值守）**：弹窗走 AgentDialogPolicy 安全默认——
  ALERT 自动确认（不承载选择、无副作用）；CONFIRM 默认拒绝；PROMPT 不接管按取消；
  网页权限一律安全拒绝（对齐引擎旧 deny 语义）。任何裁决回写 engine.lastDialog，
  Agent snapshot 注入语义不变。
- **状态联动**：浏览器被使用（球出现）→ 点球接管（chrome 展开，链式接管 +
  标签 resync）→ 交还（浮窗收起，引擎继续后台驱动）。

## 引擎增量（纯添加，默认行为零变化）

- `newTabImmediate(url)`：主线程同步建页（suspend newTab 复用同一路径）；
- `tabsSnapshot()` / `recentHistory(limit)`：chrome 数据源只读口；
- `closeAllTabs()`：chrome「关闭全部」直通；
- `reportDialogForContext(...)` / `reportDownload(...)`：chrome 裁决/下载回写
  lastDialog / lastDownload（Agent 工具语义保持）。

## 浮窗安全（坑位记录）

- 不使用 AlertDialog / ModalBottomSheet：二者底层创建 android.app.Dialog，
  需要宿主窗口 token，而 overlay 用 app context 无 token（BadTokenException）。
  全部浮层改为自绘（`ChromeScrimDialog` / `ChromeBottomSheet`，见 ChromeDialogSurface.kt）。
- BackHandler 在浮窗中先探测 `LocalOnBackPressedDispatcherOwner`（无 Activity 返回栈
  的 ComposeView 上不注册）。
- 软键盘：overlay 窗口加 `SOFT_INPUT_ADJUST_RESIZE`，地址栏编辑不被键盘遮挡。
- WebView 容器 topMargin 由 chrome 头部实测高度（onGloballyPositioned）实时驱动，
  页面内容永不遮挡地址栏/标签条。

## 验证

- `:app:compileDebugKotlin` BUILD SUCCESSFUL（全模块 + Hilt KSP）；
- `:app:testDebugUnitTest` BUILD SUCCESSFUL：
  - ChromeLogicTest 15 / JsDialogBookkeepingTest 12 / SnapshotComposerTest 7 /
    AgentDialogPolicyTest 10 / PermissionGateTest 12 —— 合计 56 用例 0 失败；
  - 既有 BrowserTracerTest / RetryPolicyTest / slash 全部保持绿。

## 后续可扩展（不阻塞本 PR）

- 页面缩略图（TabCardView 已留卡片版式）；
- 历史联想接引擎 history 之外的持久存储（historySource 插槽已留）；
- Agent PROMPT 答案委托（answerPrompt 插槽接 LLM）；
- 标签条手势切换（横向滑动）。
