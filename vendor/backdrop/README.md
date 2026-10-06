# vendored: backdrop (kyant0/AndroidLiquidGlass)

上游：https://github.com/kyant0/AndroidLiquidGlass —— "A customizable Liquid Glass
effect library for Compose Multiplatform"，作者 Kyant，Apache License 2.0
（完整许可证见本目录 [LICENSE](./LICENSE)）。

## 为什么 vendored 而不是 Maven 依赖

Maven Central 坐标 `io.github.kyant0:backdrop` 的**全部已发布版本**（1.0.0 起）
均以 Kotlin 2.2.21 / Compose 1.9.4+ 编译发布；本仓库工具链为 Kotlin 2.0.21 /
Compose 1.7.6（BOM 2024.12.01）—— Kotlin 2.0 编译器无法消费 Kotlin 2.2 元数据
（binary compatibility 仅支持 +1 minor）。整体升级工具链影响面远超玻璃系统
（KSP / Hilt / 全仓 200+ 文件），故按本仓既有 vendoring 惯例
（:terminal-emulator / :terminal-native 同款模式）源码内嵌。

## 版本

**tag `1.0.0`**（首个正式发布版，纯 Android library 形态：`src/main/java`，
无 KMP source set、无 io.github.kyant0:shapes 依赖 —— 两项都是上游后续版本
才引入的）。27 个源文件、约 2270 行，逐字节来自上游，仅含下列**机械适配**：

## 本仓适配清单（全部不改变行为）

| # | 文件 | 上游写法 | 适配写法 | 原因 |
|---|------|---------|---------|------|
| 1 | `LayerRecorder.kt` + 两个调用点 | `context(node: DelegatableNode)` 扩展 | 显式 `node: DelegatableNode` 参数 | Kotlin 2.0.21 K2 不支持 context receivers |
| 2 | `Shaders.kt` / `RuntimeShaderCache.kt` | `@Language("AGSL")`（org.jetbrains:annotations） | 移除注解 | 纯 IDE 提示注解，免额外依赖 |

上游 API 与 Compose 1.7.6 的兼容性已逐项字节码核实
（`ui-android-1.7.6.aar` + `ui-graphics-android-1.7.6.aar`）：
`requireGraphicsContext` / `observeReads` / `invalidateDraw` / `requireDensity` /
`BlurEffect(RenderEffect,FFI)` / `asComposeRenderEffect` / `asAndroidRenderEffect` /
`asAndroidColorFilter` / `toAndroidTileMode` / `GraphicsLayer.record` /
`rememberGraphicsLayer` / `drawLayer` 全部存在。

## 运行时能力分层（诚实声明）

- API 33+（TIRAMISU）：AGSL `RuntimeShader` —— **lens 折射/色散**（真液态玻璃）+ 高光着色器
- API 31+（S）：`RenderEffect` —— GPU blur 链 / ColorFilter（vibrancy 等）
- API < 31：无 RenderEffect —— `blur()`/`lens()` 静默跳过（库内建门禁）；
  宿主 `GlassSurface` 在此档位直接走 Frosted 降级，不绘制未模糊的 backdrop

## 升级路径

本模块**禁止业务代码直接 import**：唯一消费方是 `app/src/main/kotlin/com/apex/agent/ui/glass/`
（GlassSurface / TerminalGlass 单点集成，换库只改该包）。当仓库工具链升级到
Kotlin ≥ 2.1 + Compose ≥ 1.9 后，可整体删除本模块换回 Maven 坐标
`io.github.kyant0:backdrop`，glass 包 API 形状保持不变。
