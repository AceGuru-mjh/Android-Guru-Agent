package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.io.File
import java.io.RandomAccessFile

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
 */
class NativeLibraryPRootBinaryProvider(
    private val hostEnv: PRootHostEnvironment,
    /** 设备支持的 ABI 列表（app 注入 Build.SUPPORTED_ABIS；空 = 跳过 ABI 检查（JVM 测试））。 */
    private val supportedAbis: () -> List<String> = { emptyList() },
    /** 版本探针：给定二进制 → "--version" 输出首行（或 null）。默认真实 exec。 */
    private val versionProbe: (File) -> String? = { binary -> defaultVersionProbe(binary, hostEnv) }
) : PRootBinaryProvider {

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
            executable = f.canExecute()
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

        /** 默认探针：真实 exec `<binary> --version`（Android/JVM 通用）。 */
        private fun defaultVersionProbe(binary: File, hostEnv: PRootHostEnvironment): String? {
            return try {
                val envMap = hostEnv.hostEnv()
                val pb = ProcessBuilder(listOf(binary.absolutePath, "--version"))
                pb.environment().clear()
                pb.environment().putAll(envMap)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val err = proc.errorStream.bufferedReader().readText().trim()
                proc.waitFor()
                out.ifBlank { err }.ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }
    }
}
