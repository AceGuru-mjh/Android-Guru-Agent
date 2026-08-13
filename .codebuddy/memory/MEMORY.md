# MEMORY

## 项目：Android-Guru-Agent 悬浮球视觉
- 项目实际使用的是 `android.webkit.WebView`（系统 WebView，Chromium 内核），并非启动 com.android.chrome 应用（证据：BrowserEngine.kt `WebView(applicationContext())`）。
- 悬浮球方案：保留液态球体底座 `bg_obsidian_glossy`，中心图标用中性 web/globe 图标 `ic_web_core`（替代原 AI 三角 `ic_cyber_core`）。
- 用户偏好：不纠结浏览器商标、快速出成品，优先最小改造量（改 XML/drawable，不动 Kotlin）。
- 浮球由 `CyberNeonBallManager.kt`（EasyFloat）管理，中心图标仅 `findViewById(R.id.imgCoreIcon)`，代码不改 src。
