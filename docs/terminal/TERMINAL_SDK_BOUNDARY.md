# Terminal SDK Boundary — 模块边界审计与独立化路线（T82）

> 问题来源：Termux 的终端能力本身是**可被其他 Android 应用集成的库模块**
> （terminal-view 即 library）。本文回答同一问题：我们的 `platform/terminal`
> 是否已具备独立 SDK/Library 的模块边界？差距在哪？何时物理拆分？

## 1. 审计结论（T82 实测）

### 1.1 源级边界：已经达标

| 检查项 | 结果 |
|---|---|
| `platform/terminal` Kotlin 源中的 `android.*` / `androidx.*` import | **0 处**（145 文件全量扫描） |
| 反向依赖（terminal → app / Compose / Activity / UI 类） | **无** —— 依赖方向严格 `app → platform:terminal` |
| Android 触点 | 全部经构造注入：`Context.filesDir/nativeLibraryDir`（PRootHostEnvironment）、`Build.SUPPORTED_ABIS`（RootfsTarget/PRoot ABI）—— 由 app 的 `TerminalModule` 提供 |
| 测试替身泄漏 | **T82 已修复**：FakeNativePty / FakeLinuxRuntime / FakePackageManager 从 `src/main` 迁至 `src/test`（共 ~645 行不再进 release 产物；androidTest 零引用已验证） |
| 无用构建依赖 | **T82 已修复**：`core:tool-registry`（零 import —— TerminalTool 是本地桥接接口）与 `core.ktx`（零使用）已从 build.gradle.kts 移除 |

### 1.2 保留的 Android 构建面（合法且必要）

- `com.android.library` 插件 + NDK/CMake（`libapex_terminal.so` 从源码构建）
- `jniLibs` 预置 proot 工具链（libproot/libtalloc/libandroid-shmem，legacy 打包以在 nativeLibraryDir 可执行）
- C++ 侧唯一 Android 依赖：`#include <android/log.h>`（hostTest 已有 shim —— 桌面验证可行）
- Hilt/KSP 插件：模块内无 @Inject 使用，仅为与 app 构建图协作保留（后续拆分时可去）

## 2. 包 → 未来模块映射（毕业路线）

物理拆分**推迟到出现第二个消费方**（原则：没有消费者的拆分是投机结构）。
当前包结构已按未来 Gradle 模块边界组织，映射如下：

```
:terminal:api          ← api/            （TerminalSdk + P60 冻结契约 —— T82 起有真实实现）
:terminal:emulator     ← terminal-emulator（已独立 Gradle 模块，纯 JVM）
:terminal:core         ← runtime/ session/ job/ io/ input/ state/ wait/
                          observation/ events/ buffer/ screen/ process/ control/
                          compat/ policy/ intelligence/ errors/ di/ protocol/
:terminal:pty          ← pty/ + src/main/cpp + NativePty.kt（JNI 面）
:terminal:linux        ← linux/ proot/ ubuntu/ pkg/ environment/ network/
                          workspace/ fs/ bridge/ health/ reliability/persistence
:terminal:tools        ← tools/（agent 工具 JSON 契约）
```

拆分不变式（任何 PR 必须保持）：
1. `api` 不 import 任何实现包；
2. `core` 不 import `linux`/`proot`/`ubuntu`（经 ExecutionBackend 抽象反转）；
3. 任何包不 import `android.*`/`androidx.*`（Android 触点只在 app DI）；
4. `tools` 只依赖 `runtime` 门面 + `TerminalTool` 本地桥（不依赖 tool-registry）。

## 3. 当前依赖图（T82 后）

```
app ──▶ platform:terminal ──▶ terminal-emulator（纯 JVM）
 │              │
 │              ├── kotlinx-coroutines / kotlinx-serialization（纯 JVM）
 │              └── NDK + jniLibs（libapex_terminal.so + proot 工具链）
 └──▶ core:* / plugin-sdk / ……（与 terminal 无关）
```

## 4. 验收方式

- 源级零 Android import：`rg "^import (android|androidx)\." platform/terminal/src/main` → 0（CI 可加 gate）
- 主源 kotlinc 纯 JVM 编译：`scripts/compile_terminal_jvm.sh`（本任务引入；kotlinc
  type-check 全模块 —— SDK 化的持续证据）
- release 产物无测试替身：`src/main` 不含 Fake*（T82 起）
