package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import java.io.File

/**
 * T76: Ubuntu Sources List —— 架构感知、幂等的 apt 源配置器。
 *
 * Ubuntu Base 24.04 tarball 自带正确的 deb822 源（`/etc/apt/sources.list.d/ubuntu.sources`）：
 *  - arm64 → ports.ubuntu.com/ubuntu-ports
 *  - amd64 → archive.ubuntu.com/ubuntu
 *
 * T72 的 RootfsConfigurator 不触碰 sources（幂等靠"不改"）。但 rootfs 损坏 /
 * sources 被误删 / 手动改坏的恢复场景需要一个能**重新生成正确 sources** 的工具。
 * 本类即此工具 —— 幂等写入正确的架构相关 sources，不重复追加（T76 §9）。
 *
 * 架构映射（T76 §9）：
 * ```
 * arm64 / arm32 → ports.ubuntu.com/ubuntu-ports  noble noble-updates noble-security
 * x86_64 / x86  → archive.ubuntu.com/ubuntu      noble noble-updates noble-security
 * ```
 *
 * 安全：绝不生成 `Acquire::https::Verify-Peer false`（T76 §8）。默认源用 HTTP
 * （Ubuntu Base 默认）—— HTTPS 需要 ca-certificates 先装好，由 bootstrap 负责。
 */
class UbuntuSourcesList(
    /** Ubuntu 发行版代号（24.04 = noble）。 */
    private val codename: String = DEFAULT_CODENAME
) {

    /** 配置结果。 */
    data class SourcesResult(
        val written: Boolean,
        val architecture: CpuArchitecture,
        val mirrorHost: String,
        val mirrorPath: String,
        val components: List<String>,
        val filePath: String,
        val actions: List<String>
    )

    /**
     * 幂等确保 sources 正确。
     *
     * 策略：
     *  1. 读既有 sources（deb822 `ubuntu.sources` 或旧式 `sources.list`）。
     *  2. 若已含正确 mirror host + codename → 跳过（幂等，不重复追加）。
     *  3. 若缺失/错误 → 写 deb822 格式 `ubuntu.sources`（不删旧 sources.list，
     *     避免破坏用户自定义；apt 优先读 deb822）。
     */
    fun ensure(rootfsDir: File, arch: CpuArchitecture): SourcesResult {
        val mirror = mirrorFor(arch)
        val components = DEFAULT_COMPONENTS
        val actions = mutableListOf<String>()

        val sourcesD = File(rootfsDir, SOURCES_D)
        if (!sourcesD.isDirectory) {
            sourcesD.mkdirs()
            actions.add("created $SOURCES_D")
        }

        val deb822File = File(sourcesD, UBUNTU_SOURCES_FILE)
        val existing = if (deb822File.isFile) deb822File.readText() else ""
        val expectedHost = mirror.host
        val expectedCodename = codename

        // 已含正确 mirror + codename → 幂等跳过
        if (existing.contains(expectedHost) && existing.contains("Codename: $expectedCodename")) {
            return SourcesResult(
                written = false,
                architecture = arch,
                mirrorHost = mirror.host,
                mirrorPath = mirror.path,
                components = components,
                filePath = deb822File.absolutePath,
                actions = listOf("sources already correct (host=$expectedHost, codename=$expectedCodename) — skipped")
            )
        }

        // 写 deb822 格式（Ubuntu 24.04 标准）
        val content = buildDeb822Content(mirror, expectedCodename, components, arch)
        val tmp = File(sourcesD, "$UBUNTU_SOURCES_FILE.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(deb822File)) {
            tmp.copyTo(deb822File, overwrite = true)
            tmp.delete()
        }
        actions.add("wrote deb822 sources (host=$expectedHost, codename=$expectedCodename, components=$components)")

        return SourcesResult(
            written = true,
            architecture = arch,
            mirrorHost = mirror.host,
            mirrorPath = mirror.path,
            components = components,
            filePath = deb822File.absolutePath,
            actions = actions
        )
    }

    /** 读取当前 sources 摘要（诊断用，不修改）。 */
    fun inspect(rootfsDir: File): SourcesInspection {
        val sourcesD = File(rootfsDir, SOURCES_D)
        val deb822File = File(sourcesD, UBUNTU_SOURCES_FILE)
        val oldList = File(rootfsDir, "etc/apt/sources.list")
        val files = mutableListOf<String>()
        if (deb822File.isFile) files.add(deb822File.absolutePath)
        if (oldList.isFile) files.add(oldList.absolutePath)
        val content = files.mapNotNull { f -> runCatching { File(f).readText() }.getOrNull() }.joinToString("\n")
        val hosts = mutableListOf<String>()
        if (content.contains("ports.ubuntu.com")) hosts.add("ports.ubuntu.com")
        if (content.contains("archive.ubuntu.com")) hosts.add("archive.ubuntu.com")
        if (content.contains("security.ubuntu.com")) hosts.add("security.ubuntu.com")
        return SourcesInspection(
            present = files.isNotEmpty(),
            files = files,
            mirrorHosts = hosts.distinct(),
            usesHttps = content.contains("https://"),
            codename = Regex("Codename: (\\S+)").find(content)?.groupValues?.getOrNull(1)
                ?: Regex("$codename ").find(content)?.value?.trim()
        )
    }

    data class SourcesInspection(
        val present: Boolean,
        val files: List<String>,
        val mirrorHosts: List<String>,
        val usesHttps: Boolean,
        val codename: String?
    )

    private fun mirrorFor(arch: CpuArchitecture): Mirror = when (arch) {
        CpuArchitecture.ARM64, CpuArchitecture.ARM32 -> Mirror(
            host = "ports.ubuntu.com",
            path = "ubuntu-ports"
        )
        CpuArchitecture.X86_64, CpuArchitecture.X86 -> Mirror(
            host = "archive.ubuntu.com",
            path = "ubuntu"
        )
        else -> Mirror(
            host = "archive.ubuntu.com",
            path = "ubuntu"
        )
    }

    private fun buildDeb822Content(
        mirror: Mirror,
        codename: String,
        components: List<String>,
        arch: CpuArchitecture
    ): String {
        val archStr = when (arch) {
            CpuArchitecture.ARM64 -> "arm64"
            CpuArchitecture.ARM32 -> "armhf"
            CpuArchitecture.X86_64 -> "amd64"
            CpuArchitecture.X86 -> "i386"
            else -> "amd64"
        }
        val compStr = components.joinToString(" ")
        return buildString {
            appendLine("## Ubuntu $codename sources — generated by Android-Guru-Agent T76 (UbuntuSourcesList)")
            appendLine("## Architecture: $archStr  Mirror: ${mirror.host}/${mirror.path}")
            appendLine("Types: deb")
            appendLine("URIs: http://${mirror.host}/${mirror.path}")
            appendLine("Suites: $codename $codename-updates $codename-security")
            appendLine("Components: $compStr")
            appendLine("Architectures: $archStr")
            appendLine("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg")
        }
    }

    data class Mirror(val host: String, val path: String)

    companion object {
        const val DEFAULT_CODENAME = "noble"
        const val SOURCES_D = "etc/apt/sources.list.d"
        const val UBUNTU_SOURCES_FILE = "ubuntu.sources"
        val DEFAULT_COMPONENTS = listOf("main", "universe", "restricted", "multiverse")
    }
}
