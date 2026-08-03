plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.bundles.plugins)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "apex.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "apex.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "apex.android.compose"
            implementationClass = "ComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "apex.android.hilt"
            implementationClass = "HiltConventionPlugin"
        }
    }
}
