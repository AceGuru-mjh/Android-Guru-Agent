plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.plugin.host"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // api（原 implementation）：PluginManager 构造签名已改为注入 IApexPluginHost，
    // 该 AIDL 类型必须对 app（经 :plugin-sdk:plugin-host 传递依赖）可见——
    // app 侧的 PluginHostBridge 实现 IApexPluginHost.Stub，需要编译期类型。
    api(project(":plugin-sdk:plugin-api"))
    implementation(project(":core:tool-registry"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
