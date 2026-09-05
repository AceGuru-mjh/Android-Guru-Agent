package com.apex.agent.environment

/**
 * A developer-environment dependency (JDK / Gradle / SDK / NDK / etc.).
 *
 * Spec ref: ATR 2.0 Final Spec §43 (EnvironmentProvisioner extraction)
 *
 * Extracted from the old `TerminalViewModel.kt` which mixed terminal settings + blacklist +
 * dependency installation + session lifecycle into one class. This data class is the pure
 * dependency descriptor; the install orchestration lives in [EnvironmentProvisioner].
 *
 * @param id             stable identifier (e.g. "jdk17")
 * @param name           human-readable name
 * @param group          GENERAL (cross-platform) or ANDROID (Android-specific)
 * @param installOfficial shell command for official install (e.g. winget/scoop/sdkmanager)
 * @param installMirror   shell command for mirror install (China mirror, etc.)
 * @param checkCommand    shell command to verify install (e.g. "java -version")
 */
data class DepItem(
    val id: String,
    val name: String,
    val group: DepGroup,
    val installOfficial: String,
    val installMirror: String,
    val checkCommand: String
) {
    fun installCommand(useMirror: Boolean): String =
        if (useMirror) installMirror else installOfficial
}

enum class DepGroup { GENERAL, ANDROID }

/**
 * Canonical dependency list (migrated from old TerminalViewModel.depItems).
 *
 * v2 修复：旧清单的「官方源」是 Windows 的 winget/scoop——在 Android PTY 里执行
 * 必然 `command not found`，功能 100% 不可用。现在统一改为 Ubuntu（proot）环境内
 * 真实可执行的 apt 命令；镜像源对应清华 apt 源（需先配置 sources.list，见
 * platform/terminal/ubuntu 的 rootfs 镜像切换工具）。
 */
object DepCatalog {
    val ALL: List<DepItem> = listOf(
        DepItem(
            id = "jdk17", name = "JDK 17", group = DepGroup.GENERAL,
            installOfficial = "apt-get update && apt-get install -y openjdk-17-jdk-headless",
            installMirror = "apt-get update && apt-get install -y openjdk-17-jdk-headless",
            checkCommand = "java -version"
        ),
        DepItem(
            id = "git", name = "Git", group = DepGroup.GENERAL,
            installOfficial = "apt-get update && apt-get install -y git",
            installMirror = "apt-get update && apt-get install -y git",
            checkCommand = "git --version"
        ),
        DepItem(
            id = "gradle", name = "Gradle 8.10", group = DepGroup.GENERAL,
            installOfficial = "apt-get update && apt-get install -y gradle",
            installMirror = "apt-get update && apt-get install -y gradle",
            checkCommand = "gradle --version"
        ),
        DepItem(
            id = "android-sdk", name = "Android SDK cmdline-tools", group = DepGroup.ANDROID,
            installOfficial = "sdkmanager \"cmdline-tools;latest\"",
            installMirror = "sdkmanager --proxy=http --proxy_host=mirrors.tuna.tsinghua.edu.cn \"cmdline-tools;latest\"",
            checkCommand = "sdkmanager --version"
        ),
        DepItem(
            id = "ndk", name = "NDK 27.0.12077973", group = DepGroup.ANDROID,
            installOfficial = "sdkmanager \"ndk;27.0.12077973\"",
            installMirror = "sdkmanager \"ndk;27.0.12077973\"",
            checkCommand = "ndk-build --version"
        ),
        DepItem(
            id = "platform-tools", name = "Platform Tools (adb)", group = DepGroup.ANDROID,
            installOfficial = "sdkmanager \"platform-tools\"",
            installMirror = "sdkmanager \"platform-tools\"",
            checkCommand = "adb --version"
        ),
        DepItem(
            id = "build-tools", name = "Build-Tools 35.0.0", group = DepGroup.ANDROID,
            installOfficial = "sdkmanager \"build-tools;35.0.0\"",
            installMirror = "sdkmanager \"build-tools;35.0.0\"",
            checkCommand = "aapt --version"
        )
    )
}
