package com.apex.agent.platform.terminal.ubuntu

import java.io.File

/**
 * T72: RootFS Configurator —— 把一个"刚解压出来的 Ubuntu Base"配置成
 * "PRoot 里能真正跑起来的 Ubuntu"。
 *
 * P69 的 configureBasicEnv 只 mkdir 了 tmp/home/root/workspace —— 真实
 * Ubuntu Base tarball 解压后的阻断点全部没有处理：
 *
 *  1. /etc/resolv.conf 是空文件 → guest 内 DNS 解析全灭 → apt update 必败。
 *  2. /etc/hosts 是空文件 → localhost 反解失败（apt/sudo 等会卡警告）。
 *  3. /etc/hostname 空 → bash 提示符出现 "(none)"。
 *  4. CA certificates 完全缺失（Ubuntu Base 不含 ca-certificates 包）→
 *     https 源不可用；http 源不受影响。
 *  5. apt 的工作目录（lists/partial、archives/partial、dpkg updates…）
 *     Ubuntu Base 自带，但删除重装/异常中断后可能缺——幂等确保。
 *
 * 策略（诚实优先）：
 *  - DNS：优先注入（Android 生产由 DI 传系统 DNS）；其次复制 host
 *    /etc/resolv.conf（CI/Linux 有效）；最后 fallback 公共 DNS 并记录
 *    warning —— 绝不假装"配置好了"而不说明来源。
 *  - CA：host 有 bundle（CI/Linux）就复制进 rootfs（真实可用）；没有
 *    （Android）就保留缺失并记录 warning（http apt 源照常工作）。
 *    绝不伪造空 bundle 冒充已配置。
 *  - 所有写入幂等：已存在且非空的非托管文件不覆盖（用户/上游改动优先）。
 */
class RootfsConfigurator(
    /** 注入的 DNS 服务器（Android 生产：系统 DNS；测试：任意）。空 = 未提供。 */
    private val dnsServers: () -> List<String> = { emptyList() },
    /** host 侧 CA bundle（默认 CI/Linux 路径；Android 上不存在 → null）。 */
    private val hostCaBundle: () -> File? = { File("/etc/ssl/certs/ca-certificates.crt").takeIf { it.isFile && it.length() > 0 } },
    /** guest 主机名。 */
    private val hostname: String = DEFAULT_HOSTNAME,
    /**
     * T82（Termux 基线 §3.5）：写入 /etc/locale.gen 的 locale 行
     *（如 "zh_CN.UTF-8 UTF-8"、"en_US.UTF-8 UTF-8"）。bootstrap 安装 `locales`
     * 包时 postinst 自动 locale-gen 全部列出项 —— 无需额外执行步骤。
     * 空 = 不写（历史行为）。默认 LANG 仍 C.UTF-8（不变更语义，仅**可用性**扩展）。
     */
    private val localeGen: List<String> = emptyList(),
    /**
     * T82（基线 §3.6）：Android 时区 id（如 "Asia/Shanghai"）。写入
     * /etc/timezone + /etc/localtime 符号链接；`tzdata`（essential）postinst
     * 读取 /etc/timezone 完成生效。null = 不写（UTC 回退，历史行为）。
     */
    private val timezone: String? = null
) {

    /** 配置结果 —— 全部动作与 warning 进入返回值（可断言、可入日志），不靠 println。 */
    data class ConfigureReport(
        val actions: List<String>,
        val warnings: List<String>
    ) {
        val ok: Boolean get() = actions.isNotEmpty() || warnings.isEmpty()
    }

    fun configure(root: File): ConfigureReport {
        val actions = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ── 1. /etc/resolv.conf —— DNS 是 apt/网络的第一阻断点 ──
        val resolv = File(root, "etc/resolv.conf")
        val dns = resolveDnsServers(warnings)
        if (!hasContent(resolv)) {
            resolv.parentFile?.mkdirs()
            resolv.writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")
            actions.add("resolv.conf: wrote nameservers ${dns.joinToString(",")}")
        } else {
            actions.add("resolv.conf: kept existing (${resolv.length()} bytes)")
        }

        // ── 2. /etc/hosts ──
        val hosts = File(root, "etc/hosts")
        if (!hasContent(hosts)) {
            hosts.parentFile?.mkdirs()
            hosts.writeText(
                """
                127.0.0.1	localhost
                ::1		localhost ip6-localhost ip6-loopback
                127.0.1.1	$hostname
                """.trimIndent() + "\n"
            )
            actions.add("hosts: wrote localhost + $hostname")
        } else {
            actions.add("hosts: kept existing")
        }

        // ── 3. /etc/hostname ──
        val hostnameFile = File(root, "etc/hostname")
        if (!hasContent(hostnameFile)) {
            hostnameFile.parentFile?.mkdirs()
            hostnameFile.writeText("$hostname\n")
            actions.add("hostname: wrote '$hostname'")
        } else {
            actions.add("hostname: kept existing '${hostnameFile.readText().trim()}'")
        }

        // ── 4. locale（C.UTF-8 内建于 glibc，无需生成）──
        val localeDefault = File(root, "etc/default/locale")
        if (!localeDefault.isFile) {
            localeDefault.parentFile?.mkdirs()
            localeDefault.writeText("LANG=\"C.UTF-8\"\n")
            actions.add("locale: wrote LANG=C.UTF-8")
        }
        // T82：/etc/locale.gen —— locales 包 postinst 自动生成（zh_CN.UTF-8 等）。
        //    幂等：仅当列出的行不都在既有文件中时追加。
        if (localeGen.isNotEmpty()) {
            val localeGenFile = File(root, "etc/locale.gen")
            val existingLines = if (localeGenFile.isFile) localeGenFile.readLines().toSet() else emptySet()
            val missing = localeGen.filterNot { it in existingLines }
            if (missing.isNotEmpty()) {
                localeGenFile.parentFile?.mkdirs()
                localeGenFile.appendText(missing.joinToString("\n", prefix = if (localeGenFile.isFile) "\n" else "", postfix = "\n"))
                actions.add("locale.gen: appended ${missing.joinToString(",")}")
            } else {
                actions.add("locale.gen: already contains ${localeGen.joinToString(",")}")
            }
        }

        // ── 5. apt/dpkg 工作目录幂等确保 ──
        val aptDirs = listOf(
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/triggers",
            "var/cache/apt/archives/partial",
            "var/log/apt"
        )
        var madeDirs = 0
        for (d in aptDirs) {
            val f = File(root, d)
            if (!f.isDirectory) {
                if (f.mkdirs()) madeDirs++ else warnings.add("apt-dir: cannot create /$d")
            }
        }
        actions.add("apt-dirs: ${aptDirs.size - warnings.count { it.startsWith("apt-dir") }} present, $madeDirs created")

        // ── 6. CA certificates：host 有就复制；没有就如实标注 ──
        val guestCa = File(root, "etc/ssl/certs/ca-certificates.crt")
        val caSource = hostCaBundle()
        when {
            hasContent(guestCa) -> actions.add("ca-certificates: kept existing bundle (${guestCa.length()} bytes)")
            caSource != null && caSource.isFile && caSource.length() > 0 -> {
                guestCa.parentFile?.mkdirs()
                // 复制真实 bundle（~200KB，流式）
                caSource.inputStream().use { input ->
                    guestCa.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                // Debian/Ubuntu 的 SSL 布局惯例：/usr/lib/ssl/certs → /etc/ssl/certs
                val usrLibSsl = File(root, "usr/lib/ssl")
                if (!usrLibSsl.isDirectory) usrLibSsl.mkdirs()
                actions.add("ca-certificates: copied host bundle (${guestCa.length()} bytes)")
            }
            else -> warnings.add(
                "ca-certificates: ABSENT — http apt sources work unchanged; " +
                    "https requires `apt install ca-certificates` inside the guest"
            )
        }

        // ── 7. timezone：Ubuntu Base 自带 /etc/localtime → Etc/UTC ──
        //    T82：注入 Android 时区（/etc/timezone + /etc/localtime 符号链接）。
        //    tzdata（T82 起进 essential）postinst 读取 /etc/timezone 生效。
        if (timezone != null && timezone.isNotBlank()) {
            val tzFile = File(root, "etc/timezone")
            if (!tzFile.isFile || tzFile.readText().trim() != timezone.trim()) {
                tzFile.parentFile?.mkdirs()
                tzFile.writeText("$timezone\n")
                actions.add("timezone: wrote /etc/timezone=$timezone")
            }
            val localtimeFile = File(root, "etc/localtime")
            val zoneTarget = File(root, "usr/share/zoneinfo/$timezone")
            // 符号链接目标在 tzdata 安装后才存在 —— 提前创建（dangling 到安装完成，
            // postinst/下次 configure 会收敛；如实记录）。
            runCatching {
                if (localtimeFile.exists()) localtimeFile.delete()
                java.nio.file.Files.createSymbolicLink(
                    localtimeFile.toPath(),
                    zoneTarget.toPath()
                )
                if (zoneTarget.isFile) actions.add("timezone: /etc/localtime -> $timezone")
                else actions.add("timezone: /etc/localtime -> $timezone (dangling until tzdata installed)")
            }.onFailure {
                warnings.add("timezone: failed to link /etc/localtime — ${it.message}")
            }
        } else {
            val localtime = File(root, "etc/localtime")
            if (!localtime.exists()) {
                warnings.add("timezone: /etc/localtime missing — guest falls back to UTC")
            } else {
                actions.add("timezone: /etc/localtime present")
            }
        }

        // ── 8. P69 原有的基础目录（bind 点）──
        for (d in listOf("tmp", "home", "root", "workspace")) {
            val f = File(root, d)
            if (!f.isDirectory) f.mkdirs()
        }
        // /tmp 必须可写（sticky 1777）
        File(root, "tmp").setWritable(true, false)
        File(root, "tmp").setExecutable(true, false)
        actions.add("base-dirs: tmp/home/root/workspace ensured (tmp world-writable)")

        return ConfigureReport(actions, warnings)
    }

    /**
     * DNS 决策链：注入 > host resolv.conf > 公共 DNS fallback（带 warning）。
     */
    private fun resolveDnsServers(warnings: MutableList<String>): List<String> {
        val injected = dnsServers().filter { it.isNotBlank() }
        if (injected.isNotEmpty()) return injected.distinct()

        val hostResolv = File("/etc/resolv.conf")
        if (hostResolv.isFile) {
            val servers = hostResolv.readLines()
                .map { it.trim() }
                .filter { it.startsWith("nameserver") }
                .map { it.removePrefix("nameserver").trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            if (servers.isNotEmpty()) return servers.distinct()
        }
        warnings.add(
            "resolv.conf: no injected DNS and no host /etc/resolv.conf — " +
                "falling back to public resolvers (8.8.8.8, 1.1.1.1); " +
                "production Android should inject system DNS at DI time"
        )
        return listOf("8.8.8.8", "1.1.1.1")
    }

    private fun hasContent(f: File): Boolean = f.isFile && f.length() > 0

    companion object {
        const val DEFAULT_HOSTNAME = "android-guru"
    }
}
