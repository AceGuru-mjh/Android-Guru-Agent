import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.apex.agent.terminalview"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // View 层不参与任何 release 混淆链（library 消费者 app 已有 ProGuard 规则）。
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

// KGP 2.2：kotlinOptions DSL 已 deprecation（KGP 3.0 移除）—— 迁移到
// 项目级 compilerOptions DSL（语义等价：等价于对全部 Kotlin 编译任务生效）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 复用 terminal-emulator 的纯数据模型（RenderCell / TerminalRenderSnapshot /
    // TerminalKey / KeyModifiers）—— 模型单一事实源，杜绝重复定义带来的漂移。
    implementation(project(":terminal-emulator"))

    // 零第三方依赖：View 层只用 android.graphics / android.view / android.text。

    testImplementation(libs.junit)
}
