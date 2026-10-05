package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * P71: 生产 PRoot 二进制 provider —— 从 APK 的 nativeLibraryDir 定位 libproot.so。
 *
 * 纯 JVM（无 android.* 依赖）：ABI 列表由 app DI 注入（`Build.SUPPORTED_ABIS.toList()`），
 * 版本探测走可注入 lambda（默认真实 exec `<binary> --version`，JVM 测试注入假探针）。
 *
 * verify 内容：
 *  1. 文件存在且可执行
 *  2. ELF 机器类型（读文件头 e_machine，字节级判定，不 exec）与设备支持 ABI 匹配
 *  3. `--version` 输出解析（真实 exec；探针失败 → version 为 null 但二进制仍可用 ——
 *     版本是诊断信息而非硬门槛，ptrace 环境差异不应阻断可用二进制）
 *  4. T91（D5）：argv 能力双探针 → [PRootArgvCapabilities]（**两项独立**实测 ——
 *     CI 实测 Debian 5.4 支持 `--kill-on-exit` 但不支持 `--`，能力正交，不能
 *     耦合在单一方言里）。探针结果按二进制路径记忆化（provider 为 DI 单例，
 *     availability/prepare 每会话都 verify —— 不允许每次 spawn 都多两次 exec）。
 *
 * 探针语义（两项同构）：
 *  - `--kill-on-exit`：exec `<binary> --kill-on-exit --version`，exit 0 = 支持
 *    （不 ptrace，任何环境可用）；
 *  - `--`：exec `<binary> -- <sh> -c true`（带路径回退），exit 0 = 支持。
 *    该探针会真实 exec guest 命令（经 tracer）—— ptrace 受限环境会给出
 *    **假阴性**；假阴性方向安全：省略 `--` 在任何 proot 上均合法（上游形状
 *    是 Termux 方言的子集）。任一路径 exit 0 即判支持（设备 /bin/sh 可能
 *    不存在 → 回退 /system/bin/sh；CI runner 反之）。
 *  - 两探针不可判定（exec 异常/超时）→ 保守省略该项。
 *
 * T92（审计修复）：三探针统一 **先幂等 [PRootHostEnvironment.prepare] 再 exec** ——
 * 此前探针直接 `hostEnv()`，而 hostEnv 契约要求先 prepare；首次安装/清缓存后
 * `libtalloc.so.2` symlink 与 `PROOT_TMP_DIR` 均不存在 → proot exec 因动态链接
 * 失败**确定性非零退出（false，非 null）** → 记忆化 UPSTREAM_SAFE → 整个进程
 * 生命周期内设备 argv 静默丢 `--kill-on-exit` 与 `--`（与「设备 argv 逐字节
 * 不变」的 T91 承诺冲突）。prepare 失败 → 探针返回 null（不可判定，不 exec）。
 */
class NativeLibraryPRootBinaryProvider(
    private val hostEnv: PRootHostEnvironment,
    /** 设备支持的 ABI 列表（app 注入 Build.SUPPORTED_ABIS；空 = 跳过 ABI 检查（JVM 测试））。 */
    private val supportedAbis: () -> List<String> = { emptyList() },
    /** 版本探针：给定二进制 → "--version" 输出首行（或 null）。默认真实 exec。 */
    private val versionProbe: (File) -> String? = { binary -> defaultVersionProbe(binary, hostEnv) },
    /**
     * T91（D5）：`--kill-on-exit` 能力探针：true = 支持，false = 不支持，
     * null = 不可判定（→ 保守省略）。默认真实 exec。
     */
    private val killOnExitProbe: (File) -> Boolean? = { binary ->
        defaultKillOnExitProbe(binary, hostEnv)
    },
    /**
     * T91（D5）：`--` 终结符能力探针（与 kill-on-exit 独立）：true = 支持，
     * false = 不支持，null = 不可判定（→ 保守省略）。默认真实 exec（带
     * shell 路径回退，见类 KDoc —— 假阴性方向安全）。
     */
    private val separatorProbe: (File) -> Boolean? = { binary ->
        defaultSeparatorProbe(binary, hostEnv)
    }
) : PRootBinaryProvider {

    /** T91（D5）：探针结果记忆化（二进制路径 → 能力集；provider 为 DI 单例）。 */
    private val capabilityCache = java.util.concurrent.ConcurrentHashMap<String, PRootArgvCapabilities>()

    override suspend fun locate(): Result<AbsolutePath> = runCatching {
        val f = hostEnv.prootBinary
        if (!f.exists()) {
            error("PRootError:BINARY_NOT_FOUND — ${f.absolutePath}（useLegacyPackaging 未生效或 APK 未打包 proot）")
        }
        AbsolutePath(f.absolutePath)
    }

    override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = runCatching {
        val f = File(binary.value)
        if (!f.exists()) error("PRootError:BINARY_NOT_FOUND — ${binary.value}")
        if (!f.canRead()) error("PRootError:BINARY_NOT_EXECUTABLE — 不可读: ${binary.value}")

        val elfArch = readElfMachine(f)
            ?: error("PRootError:BINARY_NOT_EXECUTABLE — 不是有效 ELF: ${binary.value}")
        val deviceAbis = supportedAbis()
        val abiCompatible = deviceAbis.isEmpty() || deviceAbis.any { abiMatches(it, elfArch) }
        if (!abiCompatible) {
            error(
                "PRootError:ARCHITECTURE_MISMATCH — proot ELF=$elfArch，设备 ABI=$deviceAbis"
            )
        }

        val versionText = try {
            versionProbe(f)
        } catch (e: Exception) {
            null // 探针失败不阻断 —— 版本是诊断信息（见类注释）
        }
        PRootBinaryInfo(
            path = binary,
            version = versionText?.let { parseVersion(it) },
            architecture = elfArch,
            executable = f.canExecute(),
            capabilities = capabilitiesFor(f)
        )
    }

    // ─── T91（D5）：能力双探针（独立实测 + 记忆化） ───

    /**
     * 实测判定二进制能力集（记忆化）。每项探针三态：true = 发该项选项；
     * false / null = 省略（省略在任何 proot 上均合法 —— 保守方向）。
     */
    internal fun capabilitiesFor(binary: File): PRootArgvCapabilities =
        capabilityCache.computeIfAbsent(binary.absolutePath) {
            val killOnExit = runCatching { killOnExitProbe(binary) }.getOrNull()
            val separator = runCatching { separatorProbe(binary) }.getOrNull()
            PRootArgvCapabilities(
                supportsKillOnExit = killOnExit == true,
                supportsOptionSeparator = separator == true
            )
        }

    // ─── ELF 解析（字节级，无 exec —— 在任何环境可跑） ───

    private fun readElfMachine(f: File): CpuArchitecture? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val header = ByteArray(20)
            if (raf.read(header) != 20) return@runCatching null
            // ELF magic + 64/32 位 + 字节序
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) return@runCatching null
            val is64 = header[4] == 2.toByte()
            val isLE = header[5] == 1.toByte()
            val machineOffset = 18
            val m = if (isLE) {
                (header[machineOffset].toInt() and 0xFF) or
                    ((header[machineOffset + 1].toInt() and 0xFF) shl 8)
            } else {
                ((header[machineOffset].toInt() and 0xFF) shl 8) or
                    (header[machineOffset + 1].toInt() and 0xFF)
            }
            when (m) {
                EM_AARCH64 -> CpuArchitecture.ARM64
                EM_ARM -> CpuArchitecture.ARM32
                EM_X86_64 -> CpuArchitecture.X86_64
                EM_386 -> CpuArchitecture.X86
                else -> CpuArchitecture.UNKNOWN
            }
        }
    }.getOrNull()

    private fun abiMatches(abi: String, arch: CpuArchitecture): Boolean = when (abi) {
        "arm64-v8a" -> arch == CpuArchitecture.ARM64
        "armeabi-v7a", "armeabi" -> arch == CpuArchitecture.ARM32
        "x86_64" -> arch == CpuArchitecture.X86_64
        "x86" -> arch == CpuArchitecture.X86
        else -> false
    }

    private fun parseVersion(text: String): PRootVersion {
        // 兼容 "proot version: 5.1.107.92" / "proot-5.1.107" / "5.4.0" 等形态
        val m = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(text) ?: return PRootVersion(null, null, null)
        val (a, b, c) = m.destructured
        return PRootVersion(a.toIntOrNull(), b.toIntOrNull(), c.toIntOrNull())
    }

    companion object {
        private const val EM_ARM = 40
        private const val EM_X86_64 = 62
        private const val EM_AARCH64 = 183
        private const val EM_386 = 3

        /** `--kill-on-exit` 选项字面量（argv 构造/探针单一事实源）。 */
        const val KILL_ON_EXIT_OPTION: String = "--kill-on-exit"

        /** 探针单次有界等待（无界挂起不可判定 —— 与 E2E 探针同款防御）。 */
        private const val PROBE_TIMEOUT_SECONDS = 10L

        /**
         * T92（审计修复）：探针前置 —— 幂等 prepare 成功后才取 host env。
         *
         * prepare 失败（staging 目录/二进制缺失）→ null：环境未就绪属于
         * **不可判定**而非「不支持」，绝不 exec（避免链接失败的确定性非零退出
         * 被误认成 unknown option → 能力被永久记忆化为 false）。
         */
        private fun probeEnv(hostEnv: PRootHostEnvironment): Map<String, String>? =
            hostEnv.prepare().getOrNull()?.let { hostEnv.hostEnv() }

        /** 默认版本探针：真实 exec `<binary> --version`（Android/JVM 通用）。 */
        private fun defaultVersionProbe(binary: File, hostEnv: PRootHostEnvironment): String? {
            return try {
                val envMap = probeEnv(hostEnv) ?: return null
                val pb = ProcessBuilder(listOf(binary.absolutePath, "--version"))
                pb.environment().clear()
                pb.environment().putAll(envMap)
                val proc = pb.start()
                // T94：探针流用完即关 —— 旧实现不关闭 reader，管道 fd 靠 GC
                // 回收；探针在每次 create/refresh 反复执行，fd 缓慢累积。
                val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
                val err = proc.errorStream.bufferedReader().use { it.readText().trim() }
                // T92：有界等待 —— 无界 waitFor 会把一次挂死的 --version 钉死在
                // verify（每次会话创建都调）上，进而钉死 create/availability。
                val exited = proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!exited) {
                    runCatching { proc.destroyForcibly() }
                    null
                } else {
                    out.ifBlank { err }.ifBlank { null }
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * T91（D5）：`--kill-on-exit` 能力探针 —— exec `<binary> --kill-on-exit
         * --version`：exit 0 → true（选项被识别）；非零 → false（unknown
         * option，如上游 5.1.0）；异常/超时 → null。不 ptrace，环境无关。
         */
        private fun defaultKillOnExitProbe(binary: File, hostEnv: PRootHostEnvironment): Boolean? {
            return try {
                val envMap = probeEnv(hostEnv) ?: return null
                val pb = ProcessBuilder(
                    listOf(binary.absolutePath, KILL_ON_EXIT_OPTION, "--version")
                )
                pb.environment().clear()
                pb.environment().putAll(envMap)
                val proc = pb.start()
                // T94：探针流用完即关（同版本探针 —— fd 回收不靠 GC）。
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.errorStream.bufferedReader().use { it.readText() }
                val exited = proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!exited) {
                    runCatching { proc.destroyForcibly() }
                    null
                } else {
                    proc.exitValue() == 0
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * T91（D5）：`--` 终结符能力探针 —— exec `<binary> -- <sh> -c true`：
         * `--` 被识别（Termux 补丁）→ guest `/bin/sh -c true` 经 tracer 真实执行
         * → exit 0 → true；`--` 不被识别（上游）→ proot 立即报 unknown option
         * → 非零 → false。shell 路径回退：设备 Android 的 /bin/sh 可能不存在
         * （→ /system/bin/sh），CI runner 反之 —— 任一路径 exit 0 即判支持。
         *
         * 假阴性方向安全：ptrace 受限环境下 Termux proot 也会失败 → 判 false →
         * 省略 `--` —— 省略形状在任何 proot 上均合法，仅丢失显式分界。
         */
        private fun defaultSeparatorProbe(binary: File, hostEnv: PRootHostEnvironment): Boolean? {
            val envMap = probeEnv(hostEnv) ?: return null
            for (shell in SEPARATOR_PROBE_SHELLS) {
                val supported = try {
                    val pb = ProcessBuilder(
                        listOf(binary.absolutePath, "--", shell, "-c", "true")
                    )
                    pb.environment().clear()
                    pb.environment().putAll(envMap)
                    val proc = pb.start()
                    // T94：探针流用完即关（同版本探针 —— fd 回收不靠 GC）。
                    proc.inputStream.bufferedReader().use { it.readText() }
                    proc.errorStream.bufferedReader().use { it.readText() }
                    val exited = proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    if (!exited) {
                        runCatching { proc.destroyForcibly() }
                        null
                    } else {
                        proc.exitValue() == 0
                    }
                } catch (e: Exception) {
                    null
                }
                if (supported == true) return true
                // false = 该 shell 路径被 proot 明确拒绝（unknown option '--' 或
                // shell 不存在）→ 换下一个路径；null = 不可判定 → 继续尝试其余
                // 路径（全部不可判定才落保守省略）
            }
            // 至少一条路径明确 false（unknown option）→ 不支持；全部不可判定 → 保守省略
            return false
        }

        /** 分隔符探针的 shell 回退序列（设备/桌面双端覆盖）。 */
        private val SEPARATOR_PROBE_SHELLS = listOf("/bin/sh", "/system/bin/sh")
    }
}
