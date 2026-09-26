import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apex.agent"
        minSdk = 26
        // ── 真机红线修复：targetSdk 必须 ≤ 28 ─────────────────────────────
        // targetSdk ≥ 29 的 app 运行在 SELinux untrusted_app 域，Android 10 起
        // 该域禁止 execute app_data_file（W^X 强制）：
        //   - Ubuntu rootfs 解压在 filesDir（TerminalModule: filesDir/rootfs/ubuntu）
        //   - PRoot guest 的 /bin/bash、/usr/bin/apt-get 等 execve → EACCES
        //   - guest 动态库 mmap(PROT_EXEC) 同样被拒
        // → Ubuntu 安装必然失败：下载/解压全成功，死在 RootfsHealthCheck 的
        //   canExecute()（ROOTFS_INVALID "not executable"）或 bootstrap 的
        //   apt-get（exec EACCES）。这是 T72-T82 真机链路一直 NOT VERIFIED
        //   背后的架构级问题，也是"ubuntu 安装不了"的直接根因。
        // Termux 至今钉 targetSdk 28 正是同因。本项目经 GitHub Releases
        // 侧载分发，不受 Play targetSdk 政策约束；若未来上架 Play Store，
        // 需改用 Shizuku/ADB shell 域执行 proot 或 rootfs-as-jniLibs 方案。
        // 参考：developer.android.com/about/versions/10/privacy/changes
        //       （"Execute permission for app home directory" 一节）
        targetSdk = 28
        // v1.4.2：双仓库发布架构升级 —— PR 合并即自动发版（release.yml push main 触发）。
        // CI 通过 -PapexVersionName / -PapexVersionCode 注入最终版本（版本号冲突时
        // 自动追加构建序号，如 1.4.2.1，并自动递增 versionCode）；本地构建走源码值。
        versionCode = (project.findProperty("apexVersionCode") as String?)?.toInt() ?: 9
        versionName = (project.findProperty("apexVersionName") as String?) ?: "1.4.2"

        ndk {
            // T83: 发布 arm64 纯净包（-PapexAbi=arm64-v8a）—— 内置 rootfs 伪 .so
            // 按 ABI 自动过滤，APK 从 ~117MB 降到 ~50MB。缺省（无属性）= 全 ABI
            // universal（含 armeabi-v7a / x86_64，兼容旧设备与模拟器）。
            // 注意：android.injected.abi 会被各模块显式 abiFilters 压过，不可用于此。
            if (project.hasProperty("apexAbi")) {
                abiFilters += project.property("apexAbi").toString().split(",").map { it.trim() }
            }
        }
    }

    buildFeatures {
        compose = true
        // 关于页：版本号（versionName/Code）经 BuildConfig 暴露给设置页
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // ExpiredTargetSdkVersion 的豁免理由（targetSdk=28 是有意为之，见上方红线注释）：
        // targetSdk ≥ 29 的 SELinux untrusted_app 域禁止 execute app_data_file（W^X），
        // PRoot guest（Ubuntu rootfs）将完全不可执行 —— Termux 钉 28 同因。本项目经
        // GitHub Releases 侧载分发，不受 Play targetSdk 政策约束；lintVitalRelease
        // 对 release 构建是 fatal，此检查必须显式关闭（否则 tag 构建永远失败）。
        disable += "ExpiredTargetSdkVersion"
    }

    buildTypes {
        // 发布 APK 以 debug 密钥签名 —— 个人项目无正式 keystore 时保证产物可直接安装；
        // 引入正式签名时替换为 signingConfigs 引用 + 环境变量注入。
        //
        // 【不使用代码混淆】本 APK 明确不做 R8/ProGuard 混淆与资源缩减：
        //  - 持久化大量依赖 kotlinx.serialization 的字段名（ModelProfile / ProviderConfig /
        //    McpServerConfig / Skill manifest），混淆会静默写坏历史数据；
        //  - AIDL 插件跨进程桥、JNI/native 层依赖符号可见性，混淆后难定位问题；
        //  - isShrinkResources=false 保证 assets（如 MCP bridge 脚本）不被误剔除。
        // 若未来启用，必须先补 keep 规则并完成数据迁移验证。
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // P71: PRoot 以可执行二进制（libproot.so 等）随 APK 分发。AGP 默认
        //不解压 .so 到磁盘（extractNativeLibs=false 语义），nativeLibraryDir 下将
        //不存在 proot 文件 —— 必须 legacy 打包（解压到 nativeLibraryDir）才能 exec。
        // Termux/UserLAnd 的标准做法。
        jniLibs {
            useLegacyPackaging = true
            // T83: 内置 Ubuntu rootfs 以伪 .so（tarball）随 jniLibs 分发 —— 非 ELF
            // 对象，release 的 stripReleaseNativeLibs（llvm-strip）会拒绝并使构建失败；
            // keepDebugSymbols 即"跳过 strip"清单，档案逐字节原样进 APK
            //（完整性由三层 SHA-256 链保证，见 BundledRootfsSource.kt）。
            keepDebugSymbols += "**/libubuntu-rootfs.so"
        }
    }
}

dependencies {
    // 内部模块
    implementation(project(":core:agent-engine"))
    implementation(project(":core:llm-adapter"))
    implementation(project(":core:tool-registry"))
    implementation(project(":core:logging"))
    implementation(project(":platform:privilege"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:terminal"))
    // P83: Terminal UI 直接消费 TerminalRenderSnapshot/RenderCell（styled grid 渲染）。
    // platform:terminal 对 :terminal-emulator 是 implementation（不传递），app 需显式声明。
    implementation(project(":terminal-emulator"))
    implementation(project(":platform:cs-mem"))
    // Coding 模式（与 Agent 模式同级别）：编码工具集 / 编码引擎 / 工作区管理
    implementation(project(":core:code-tools"))
    implementation(project(":core:code-engine"))
    implementation(project(":platform:code-workspace"))
    // 逆向 MCP Host（#173）：手机作为 MCP Server（streamable HTTP，纯 JVM）
    implementation(project(":platform:mcp-host"))
    implementation(project(":plugin-sdk:plugin-host"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation("androidx.compose.foundation:foundation:1.7.6")
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // Lucide 图标集（composablehorizons/compose-icons, MIT）：斜杠菜单分类图标更精致
    // 固定 1.1.0：2.x 由 Kotlin 2.2 构建，与本项目 Kotlin 2.0.21 toolchain 元数据不兼容。
    implementation("com.composables:icons-lucide-android:1.1.0")
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // 其他
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)

    // Coil (image loading)
    implementation(libs.coil.compose)
    // 模型品牌图标：SimpleIcons CDN 的 SVG 官方标解码（#174）
    implementation(libs.coil.svg)

    // Security (encrypted SharedPreferences for GitHub token)
    implementation(libs.security.crypto)

    // Shizuku (system privilege access)
    // 混沌审查修复（CR #C3）：硬编码 13.1.0 与版本目录（platform:privilege 引用的
    // libs.shizuku.* = 13.1.5）漂移 —— Gradle 冲突解析静默取高版本，硬编码 pin 实际无效；
    // 一旦移除 privilege 模块依赖会无声降级到 13.1.0（binder 协议不匹配的运行时风险）。
    // 统一走 catalog，保证全仓库单一版本源。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // 赛博霓虹悬浮球：全局低侵入 WindowManager 管理（JitPack，已做仓库过滤+版本锁定）
    implementation(libs.easyfloat)
    // Liquid Glass UI System 底层引擎 —— 仅 ui/glass 包内部使用，业务层经 Glass 组件 API 访问
    implementation(libs.haze)
    // Vico 开源图表库（稳定线 1.13.1）—— 任务历史页「近 7 日任务量」柱状图 +
    // 记忆页类型分布；只引 compose 核心（主题色手动映射，不引 m2/m3 主题模块）
    implementation(libs.vico.compose)
    // 物理弹力手势（SpringAnimation 按压挤压形变 / 吸附）
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// 依赖锁定：固定已解析版本，避免 JitPack/EasyFloat 在不同时刻解析到不同工件，提升 CI 复现性
dependencyLocking {
    lockAllConfigurations()
}

// ═══ P0 修复（构建地雷）：内置 Ubuntu rootfs 档案守卫 ═══
//
// 背景（用户反馈"Ubuntu 根本用不了"的构建期根因）：libubuntu-rootfs.so 是
// ~300MB/ABI 的大档案，.gitignore 排除不入库，只能靠 scripts/fetch_rootfs.sh
// 构建期暂存。此前**没有任何 Gradle task 校验其存在** —— 若暂存缺失（新克隆、
// CI 忘跑 fetch 步骤、本地缓存被清），assembleRelease 照样成功，产出的 APK
// 安装后 BundledRootfsSource.resolve() 必然报
// "ARCHIVE_INVALID: Bundled rootfs archive missing"（recoverable=false）→
// Ubuntu 安装 100% 失败，且问题只暴露在真机运行期，构建期零提示。
//
// 守卫语义（与 scripts/fetch_rootfs.sh 的"宁可构建失败，不可带病打包"一致）：
//  - release 构建：所需 ABI 的档案缺失或 sha256 与 rootfs-bundle.sha256 不符
//    → 构建失败，错误信息给出修复命令；
//  - debug 构建：仅响亮警告（开发迭代速度优先；CI PR 路径只暂存 arm64）；
//  - 所需 ABI = -PapexAbi（逗号分隔）收窄，缺省 = 清单全集（universal）；
//  - 逃生门：-PapexSkipRootfsCheck=true（诊断/实验构建）。
val rootfsManifest = rootProject.layout.projectDirectory.file(
    "platform/terminal/rootfs-bundle.sha256"
).asFile
val rootfsJniLibsBase = rootProject.layout.projectDirectory.dir(
    "platform/terminal/src/main/jniLibs"
)

/** 解析清单：abi → sha256（跳过注释与空行）。 */
fun parseRootfsManifest(): Map<String, String> {
    if (!rootfsManifest.isFile) return emptyMap()
    return rootfsManifest.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val sha = parts.getOrNull(0) ?: return@mapNotNull null
            val path = parts.getOrNull(1) ?: return@mapNotNull null
            val abi = path.split("/").firstOrNull { it.startsWith("arm") || it.startsWith("x86") }
                ?: return@mapNotNull null
            abi to sha
        }
        .toMap()
}

/** 本次构建需要的 ABI 集（apexAbi 收窄，缺省全量）。 */
fun requiredRootfsAbis(): Set<String> {
    val all = parseRootfsManifest().keys
    val narrowed = (project.findProperty("apexAbi") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: return all
    return all.intersect(narrowed.toSet()).ifEmpty { all }
}

tasks.register("checkBundledRootfs") {
    group = "verification"
    description = "P0 guard: bundled Ubuntu rootfs archives must exist and match rootfs-bundle.sha256 (release-critical)."
    doLast {
        if ((project.findProperty("apexSkipRootfsCheck") as String?) == "true") {
            logger.lifecycle("[rootfs-guard] skipped via -PapexSkipRootfsCheck=true")
            return@doLast
        }
        val manifest = parseRootfsManifest()
        if (manifest.isEmpty()) {
            throw GradleException(
                "[rootfs-guard] rootfs-bundle.sha256 缺失或不可解析：${rootfsManifest.absolutePath} —— " +
                    "清单是内置 rootfs 的指纹真值表，缺失意味着仓库损坏，请检查 git 状态。"
            )
        }
        val required = requiredRootfsAbis()
        val problems = mutableListOf<String>()
        for (abi in required.sorted()) {
            val expected = manifest.getValue(abi)
            val f = rootfsJniLibsBase.file("$abi/libubuntu-rootfs.so").asFile
            if (!f.isFile) {
                problems += "$abi: 档案缺失（${f.absolutePath}）"
                continue
            }
            val actual = MessageDigest.getInstance("SHA-256").let { md ->
                f.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                problems += "$abi: 指纹不符（expected=$expected actual=$actual）—— 档案损坏或版本漂移"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("[rootfs-guard] 内置 Ubuntu rootfs 守卫失败 —— 缺失/指纹不符的 ABI：")
                    problems.forEach { appendLine("  - $it") }
                    appendLine("修复：scripts/fetch_rootfs.sh            # 全部 ABI（universal 发布）")
                    appendLine("      scripts/fetch_rootfs.sh --arch arm64-v8a   # 只拉 arm64（本地快速验证）")
                    appendLine("绝不能跳过守卫直接打包：产物安装后 Ubuntu 必然不可用（运行期才暴露）。")
                }
            )
        }
        logger.lifecycle("[rootfs-guard] OK — ${required.sorted()} 的 libubuntu-rootfs.so 就位且指纹一致")
    }
}

tasks.register("warnBundledRootfs") {
    group = "verification"
    description = "Debug-friendly existence check: loudly warn when bundled rootfs archives are missing (no hash, no fail)."
    doLast {
        val manifest = parseRootfsManifest()
        if (manifest.isEmpty()) return@doLast
        val missing = requiredRootfsAbis().filter { abi ->
            !rootfsJniLibsBase.file("$abi/libubuntu-rootfs.so").asFile.isFile
        }
        if (missing.isNotEmpty()) {
            logger.warn(
                "[rootfs-guard] ⚠️ 内置 Ubuntu rootfs 档案缺失（$missing）—— 本 debug 构建的 APK " +
                    "安装后 Ubuntu 终端不可用（BundledRootfsSource 将报 ARCHIVE_INVALID）。" +
                    "如需可用 Ubuntu：scripts/fetch_rootfs.sh --arch arm64-v8a"
            )
        }
    }
}

// release：严格门禁（缺失/指纹不符 = 构建失败）；debug：存在性警告（不阻断迭代）
// ⚠️ 时机：AGP 的 preBuild/preReleaseBuild 在 afterEvaluate 之前尚未注册 ——
// 直接 tasks.named(...) 在脚本执行期即抛 "Task not found"（CI 实测）。
// 挪入 afterEvaluate：任务注册完成后解析，严格性不降级（不存在照样报错）。
project.afterEvaluate {
    tasks.named("preReleaseBuild") { dependsOn("checkBundledRootfs") }
    tasks.named("preBuild") { dependsOn("warnBundledRootfs") }
}
