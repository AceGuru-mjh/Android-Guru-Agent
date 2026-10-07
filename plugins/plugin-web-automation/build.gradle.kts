plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.apex.agent.plugin.webautomation"
    compileSdk = 35

    // ═══ 宿主同源发布签名（v1.4.9 插件分发链路）══════════════════════
    //
    // 背景：宿主 PluginManager 的信任门（签名门）要求插件 APK 与宿主 APK
    // 签名指纹完全一致，否则拒绝注册工具并解绑。此前 CI 只构建 :app，
    // 插件 APK 从未产出/分发 —— 「市场 → 插件」页永远显示「未发现插件」。
    // 修法：插件与宿主共用同一份入库 keystore（keystore/apex-release.jks，
    // 凭据 name=meng411722 / key=meng411722，与 app/build.gradle.kts 同源），
    // release.yml 构建后作为 Release 资产分发，侧载安装即被宿主发现并加载。
    // 同一 keystore 亦保证插件覆盖安装升级无签名冲突。
    signingConfigs {
        create("release") {
            val keystoreFile = (project.findProperty("apexKeystoreFile") as String?)
                ?: System.getenv("APEX_KEYSTORE_FILE")
                ?: rootProject.file("keystore/apex-release.jks").absolutePath
            storeFile = file(keystoreFile)
            storePassword = (project.findProperty("apexKeystorePassword") as String?)
                ?: System.getenv("APEX_KEYSTORE_PASSWORD") ?: "meng411722"
            keyAlias = (project.findProperty("apexKeyAlias") as String?)
                ?: System.getenv("APEX_KEY_ALIAS") ?: "meng411722"
            keyPassword = (project.findProperty("apexKeyPassword") as String?)
                ?: System.getenv("APEX_KEY_PASSWORD") ?: "meng411722"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.apex.agent.plugin.webautomation"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":plugin-sdk:plugin-api"))
    implementation(libs.core.ktx)
    implementation(libs.serialization.json)
}
