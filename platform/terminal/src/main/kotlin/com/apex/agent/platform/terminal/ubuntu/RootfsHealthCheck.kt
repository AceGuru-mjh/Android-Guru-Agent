package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import java.io.File
import java.io.RandomAccessFile

/**
 * T72: RootFS Health Inspector —— READY 的证据，不是装饰。
 *
 * P69 的 validateRootfsLayout 只查 bin/etc/usr/home/tmp + sh/bash 存在。
 * 真实 Ubuntu Base（merged-usr：bin → usr/bin symlink）下这检查甚至
 * 无法区分"symlink 完好"和"bin 是个被 P69 extractor 写坏的空文件"。
 *
 * T72 检查项（全部进入 [RootfsHealthReport]，进而持久化进 metadata）：
 *
 *  FAIL 级（阻断 READY / validate 失败）：
 *   - shell:/bin/sh、shell:/bin/bash —— 经 symlink 链解析后真实存在
 *   - core:/usr/bin/env —— 脚本 shebang 的生命线
 *   - os-release —— 可读且自我声明为 ubuntu
 *   - apt-dir:/etc/apt + sources —— apt 的源配置存在
 *   - apt-bin:/usr/bin/apt、dpkg-bin:/usr/bin/dpkg、dpkg-db:/var/lib/dpkg/status
 *   - resolver:/etc/resolv.conf —— 非空且含 nameserver 行
 *   - arch —— /usr/bin/env 的 ELF e_machine 与目标架构一致
 *     （防 arm32/arm64 rootfs 装错设备 —— PRoot 报 exec 格式错误前就拦住）
 *   - dirs —— /tmp /var /root /home
 *
 *  WARN 级（不阻断，如实入档）：
 *   - ca-certificates 缺失（Ubuntu Base 基线：http 源可用）
 *   - timezone 缺失（fallback UTC）
 */
class RootfsHealthInspector(
    /** 期望架构；null = 不检查（诊断模式）。 */
    private val expectedArch: CpuArchitecture? = null
) {

    fun inspect(root: File): RootfsHealthReport {
        val checks = mutableListOf<RootfsHealthCheck>()

        // ── shells：merged-usr 下 /bin/sh 是 symlink → 解析到真实文件 ──
        checks += shellCheck(root, "/bin/sh")
        checks += shellCheck(root, "/bin/bash")

        // ── core utils ──
        checks += fileCheck(root, "/usr/bin/env", executable = true)

        // ── os-release ──
        checks += osReleaseCheck(root)

        // ── apt 栈 ──
        checks += dirCheck(root, "/etc/apt")
        checks += sourcesCheck(root)
        checks += fileCheck(root, "/usr/bin/apt", executable = true)
        checks += fileCheck(root, "/usr/bin/dpkg", executable = true)
        checks += fileCheck(root, "/var/lib/dpkg/status", executable = false, name = "dpkg-db:/var/lib/dpkg/status")

        // ── resolver ──
        checks += resolverCheck(root)

        // ── 架构（ELF 字节级，不 exec）──
        checks += archCheck(root)

        // ── 基础目录 ──
        checks += dirsCheck(root)

        // ── CA（WARN 级）──
        checks += caCheck(root)

        // ── timezone（WARN 级）──
        checks += tzCheck(root)

        return RootfsHealthReport(checks)
    }

    /** 便捷：全部 FAIL 项的 "name: detail"。 */
    fun failuresOf(root: File): List<String> =
        inspect(root).failures.map { "${it.name}: ${it.detail}" }

    // ─── individual checks ───

    private fun shellCheck(root: File, path: String): RootfsHealthCheck {
        val f = File(root, path.removePrefix("/"))
        return when {
            !f.exists() -> check(path, HealthStatus.FAIL, "missing")
            f.isDirectory -> check(path, HealthStatus.FAIL, "is a directory (extractor bug: symlink mangled?)")
            f.isSymlinkSafe() && !resolvesToExistingFile(root, path) ->
                check(path, HealthStatus.FAIL, "dangling symlink → ${f.symlinkTarget()}")
            f.canExecute() -> check(path, HealthStatus.PASS, "executable${f.symlinkTarget()?.let { " → $it" } ?: ""}")
            else -> check(path, HealthStatus.FAIL, "not executable")
        }
    }

    private fun fileCheck(
        root: File,
        path: String,
        executable: Boolean,
        name: String = path
    ): RootfsHealthCheck {
        val f = File(root, path.removePrefix("/"))
        return when {
            !f.exists() -> check(name, HealthStatus.FAIL, "missing")
            f.isDirectory -> check(name, HealthStatus.FAIL, "is a directory")
            executable && !f.canExecute() -> check(name, HealthStatus.FAIL, "not executable")
            else -> check(name, HealthStatus.PASS, "present (${f.length()} bytes)")
        }
    }

    private fun dirCheck(root: File, path: String): RootfsHealthCheck {
        val f = File(root, path.removePrefix("/"))
        return if (f.isDirectory) check("apt-dir:$path", HealthStatus.PASS, "directory present")
        else check("apt-dir:$path", HealthStatus.FAIL, if (f.exists()) "not a directory" else "missing")
    }

    private fun osReleaseCheck(root: File): RootfsHealthCheck {
        // /etc/os-release 在 Ubuntu Base 是 symlink → ../usr/lib/os-release
        val direct = File(root, "etc/os-release")
        val fallback = File(root, "usr/lib/os-release")
        val f = if (direct.exists()) direct else fallback
        if (!f.exists()) return check("os-release", HealthStatus.FAIL, "missing")
        val text = runCatching { f.readText() }.getOrDefault("")
        return if (text.contains("ubuntu", ignoreCase = true)) {
            val pretty = text.lineSequence()
                .firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.removePrefix("PRETTY_NAME=")?.trim('"') ?: "ubuntu"
            check("os-release", HealthStatus.PASS, pretty)
        } else {
            check("os-release", HealthStatus.FAIL, "no 'ubuntu' declaration")
        }
    }

    private fun sourcesCheck(root: File): RootfsHealthCheck {
        val sourcesList = File(root, "etc/apt/sources.list")
        val sourcesDir = File(root, "etc/apt/sources.list.d")
        val hasList = sourcesList.isFile && sourcesList.readLines().any { 
            it.trim().startsWith("deb ") || it.trim().startsWith("deb-src ") 
        }
        val hasDeb822 = sourcesDir.isDirectory && sourcesDir.listFiles().orEmpty().any {
            it.isFile && it.name.endsWith(".sources") && runCatching { it.readText().contains("URIs:") }.getOrDefault(false)
        }
        return when {
            hasList -> check("apt-sources", HealthStatus.PASS, "sources.list (one-line format)")
            hasDeb822 -> check("apt-sources", HealthStatus.PASS, "sources.list.d (deb822 format)")
            else -> check("apt-sources", HealthStatus.FAIL, "no apt sources configured")
        }
    }

    private fun resolverCheck(root: File): RootfsHealthCheck {
        val f = File(root, "etc/resolv.conf")
        if (!f.isFile) return check("resolver", HealthStatus.FAIL, "missing")
        val servers = f.readLines().map { it.trim() }
            .filter { it.startsWith("nameserver") }
            .map { it.removePrefix("nameserver").trim() }
            .filter { it.isNotEmpty() }
        return if (servers.isNotEmpty()) {
            check("resolver", HealthStatus.PASS, "nameservers: ${servers.joinToString(", ")}")
        } else {
            check("resolver", HealthStatus.FAIL, "no nameserver lines (${f.length()} bytes)")
        }
    }

    private fun archCheck(root: File): RootfsHealthCheck {
        val f = File(root, "usr/bin/env")   // 代表性 ELF：核心包必然存在
        val expected = expectedArch
            ?: return check("arch", HealthStatus.PASS, "unchecked (no expected arch)")
        if (!f.isFile) return check("arch", HealthStatus.FAIL, "/usr/bin/env missing — cannot read ELF")
        val elfArch = readElfMachine(f)
        return when (elfArch) {
            null -> check("arch", HealthStatus.FAIL, "/usr/bin/env is not a valid ELF")
            expected -> check("arch", HealthStatus.PASS, "ELF matches $expected")
            else -> check("arch", HealthStatus.FAIL, "rootfs ELF=$elfArch but expected=$expected")
        }
    }

    private fun dirsCheck(root: File): RootfsHealthCheck {
        val missing = listOf("tmp", "var", "root", "home").filter { !File(root, it).isDirectory }
        return if (missing.isEmpty()) check("dirs", HealthStatus.PASS, "tmp/var/root/home present")
        else check("dirs", HealthStatus.FAIL, "missing: ${missing.joinToString(", ") { "/$it" }}")
    }

    private fun caCheck(root: File): RootfsHealthCheck {
        val f = File(root, "etc/ssl/certs/ca-certificates.crt")
        return when {
            f.isFile && f.length() > 0 -> check("ca-certificates", HealthStatus.PASS, "bundle present (${f.length()} bytes)")
            else -> check("ca-certificates", HealthStatus.WARN, "absent — http apt sources fine; https needs apt install ca-certificates")
        }
    }

    private fun tzCheck(root: File): RootfsHealthCheck {
        val f = File(root, "etc/localtime")
        return if (f.exists()) check("timezone", HealthStatus.PASS, "/etc/localtime present")
        else check("timezone", HealthStatus.WARN, "missing — guest defaults to UTC")
    }

    // ─── helpers ───

    private fun check(name: String, status: HealthStatus, detail: String) =
        RootfsHealthCheck(name = name, status = status, detail = detail)

    /** symlink 且目标（相对 rootfs 解析）存在。 */
    private fun resolvesToExistingFile(root: File, path: String): Boolean {
        val f = File(root, path.removePrefix("/"))
        if (!f.isSymlinkSafe()) return true   // regular file — existence already checked
        val target = f.symlinkTarget() ?: return false
        val resolved = if (target.startsWith("/")) File(root, target.removePrefix("/")) else f.parentFile.resolve(target)
        return resolved.exists()
    }

    private fun File.isSymlinkSafe(): Boolean =
        java.nio.file.Files.isSymbolicLink(this.toPath())

    private fun File.symlinkTarget(): String? = runCatching {
        java.nio.file.Files.readSymbolicLink(this.toPath()).toString()
    }.getOrNull()

    /** ELF e_machine 字节级读取（与 NativeLibraryPRootBinaryProvider 同规则，独立实现避免跨包耦合）。 */
    private fun readElfMachine(f: File): CpuArchitecture? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val header = ByteArray(20)
            if (raf.read(header) != 20) return@runCatching null
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) return@runCatching null
            val isLE = header[5] == 1.toByte()
            val m = if (isLE) {
                (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            } else {
                ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
            }
            when (m) {
                183 -> CpuArchitecture.ARM64
                40 -> CpuArchitecture.ARM32
                62 -> CpuArchitecture.X86_64
                3 -> CpuArchitecture.X86
                243 -> CpuArchitecture.RISCV64
                else -> CpuArchitecture.UNKNOWN
            }
        }
    }.getOrNull()
}
