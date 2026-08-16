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
 * These are the 7 deps the old UI shipped. Adjust as needed; the Runtime itself knows
 * nothing about these — they're purely the provisioner's concern.
 */
object DepCatalog {
    val ALL: List<DepItem> = listOf(
        DepItem(
            id = "jdk17", name = "JDK 17", group = DepGroup.GENERAL,
            installOfficial = "winget install Microsoft.OpenJDK.17",
            installMirror = "scoop install adopt17-hotspot",
            checkCommand = "java -version"
        ),
        DepItem(
            id = "git", name = "Git", group = DepGroup.GENERAL,
            installOfficial = "winget install Git.Git",
            installMirror = "scoop install git",
            checkCommand = "git --version"
        ),
        DepItem(
            id = "gradle", name = "Gradle 8.10", group = DepGroup.GENERAL,
            installOfficial = "winget install Gradle.Gradle",
            installMirror = "scoop install gradle",
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
