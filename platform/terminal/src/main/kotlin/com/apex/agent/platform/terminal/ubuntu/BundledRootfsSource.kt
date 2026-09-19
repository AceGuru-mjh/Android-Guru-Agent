package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import java.io.File
import java.io.InputStream

/**
 * T83: Bundled RootFS Source —— Ubuntu rootfs 随 APK 内置（应用内安装 → 内置交付的产品转向）。
 *
 * ## 背景（产品决策 2026-02，升级 2026-09/T84）
 * 运行时从镜像源下载安装（应用内安装）的体验与"内置开箱即用"差距巨大：
 * 国内网络下 30MB 下载 + apt 引导链路慢且脆弱。转向 Operit 的内置思路，
 * 但有自己的创新 —— **构建期固定指纹的可审计内置交付**：
 *
 *   仓库（rootfs-bundle.sha256 + 本类注册表，单一真值互验）
 *     → CI 构建（scripts/build_full_rootfs.sh 把官方 ubuntu-base 扩建为完整
 *        CLI 环境；scripts/fetch_rootfs.sh 双重校验后暂存 jniLibs 伪 .so）
 *     → APK（legacy packaging：安装时 PackageManager 自动解出 nativeLibraryDir/libubuntu-rootfs.so）
 *     → 设备端（本类 open() → 本地拷贝 → SHA-256 复验 → RootfsExtractor 解压 → 配置 → 激活）
 *
 * 基础环境**零网络**：解包（分钟级 —— 完整环境档案 ~300MB+，解压 ~1GB+）
 * 即得 gcc/python3/git/vim/man 开箱即用的完整 Ubuntu 24.04 CLI 环境；
 * essential 包已预装 → bootstrap 经 dpkg-query 校验离线完成（T84），
 * apt 引导降级语义保留给非完整档案场景。
 *
 * ## 交付细节（创新点：ABI 分包）
 * rootfs tarball 以 `libubuntu-rootfs.so` 之名放入 jniLibs/<abi>/ —— 借用
 * Android 的原生库 ABI 过滤：universal APK 含全部 3 ABI（每设备只解出
 * 匹配 ABI 的一份），发布时另出 arm64 纯净包（体积约为 universal 的 1/3，
 * ~300MB+）。本项目本就因
 * PRoot exec-from-nativeLibraryDir 而钉 useLegacyPackaging=true（P71），
 * 安装器会把 jniLibs 条目解出为真实文件 —— 与 libproot.so 同机制。
 *
 * ## 校验链（三层）
 * 1. 构建期：fetch_rootfs.sh 双重校验（脚本固定 SHA-256 + rootfs-bundle.sha256 清单）；
 * 2. resolve() 时：文件存在 + 长度与注册表一致（截断/损坏即刻拒绝）；
 * 3. 拷贝后：RootfsDownloader 单遍 SHA-256 与注册表比对（供 ProvisionerImpl §9 链路，
 *    与官方下载时代完全同构 —— 下游零改动）。
 *
 * §7 继承：绝不静默回退到不兼容架构；§9 继承：placeholder 校验值过不了 resolve。
 */
class BundledRootfsSource(
    /** APK 安装后的原生库目录（含 libproot.so 与 libubuntu-rootfs.so）；JVM 测试注入临时目录。 */
    private val nativeLibraryDir: String
) : RootfsArtifactSource {

    override val sourceKind: RootfsSourceKind = RootfsSourceKind.BUNDLED

    /**
     * 内置工件注册表 —— 与 platform/terminal/rootfs-bundle.sha256 清单互为单一真值
     * （BundledRootfsSourceTest 交叉校验，防两处漂移）。
     *
     * 数据来源（rootfs.yml CI 构建，run #10，2026-09-19 实测，写死保证可复现交付）：
     *   https://github.com/AceGuru-mjh/Android-Guru-Agent/releases/tag/ubuntu-rootfs-24.04.4-full
     *   （builder：scripts/build_full_rootfs.sh —— 官方 ubuntu-base 24.04.4 + 58 包完整
     *   CLI 环境；同 Release 附 rootfs-digests.txt 三字段真值表）
     *
     * 重跑构建（档案内容变）是显式人工变更：更新本表 + rootfs-bundle.sha256 +
     * scripts/fetch_rootfs.sh + 对应测试 —— 镜像内容变了而指纹不变等于静默不可复现。
     */
    private data class BundledArtifact(
        val architecture: CpuArchitecture,
        val jniAbi: String,
        val sha256: String,
        val size: Long,
        /** 解压后字节数（CI digest 报告实测）—— 设备端磁盘预检实测优先，×4 启发式兑底。 */
        val unpackedSize: Long
    )

    private val bundled = listOf(
        BundledArtifact(
            architecture = CpuArchitecture.ARM64,
            jniAbi = "arm64-v8a",
            sha256 = "3b8a82393304e38a5209ad1f2b32e6160506ecfc06dda33f3773b9e6b2e392e2",
            size = 314_655_061L,
            unpackedSize = 1_171_914_752L
        ),
        BundledArtifact(
            architecture = CpuArchitecture.X86_64,
            jniAbi = "x86_64",
            sha256 = "57fb03f916cae40202134594a6ad063167174714e1ad36a50f0575b015b87228",
            size = 324_010_830L,
            unpackedSize = 1_159_856_128L
        ),
        BundledArtifact(
            architecture = CpuArchitecture.ARM32,
            jniAbi = "armeabi-v7a",
            sha256 = "fe4e1a0ccd8d73c376c8ed7281a0ccc60041dfba71d735b163a2e657558e250a",
            size = 294_939_555L,
            unpackedSize = 974_282_752L
        )
    )

    /** jniLibs 伪 .so 文件名（PM 解出到 nativeLibraryDir 后的物理名）。 */
    private val bundleFile: File get() = File(nativeLibraryDir, BUNDLE_LIB_NAME)

    /**
     * T84：注册表指纹查询（**不要求档案在设备上存在** —— 纯查表）。
     * 消费方：UbuntuLifecycleCoordinator.warmUp 的新鲜度迁移核对
     *（已装 rootfs checksum ≠ 注册表 → APK 换档案了，删旧装新）；
     * 以及 RootfsProvisionerImpl 的 AlreadyReady 短路防线。
     * 返回 null = 该 target 无注册表条目（未知分布/版本/架构）。
     */
    fun registryChecksumFor(target: RootfsTarget): String? {
        if (target.distribution != "ubuntu") return null
        val match = bundled.firstOrNull { it.architecture == target.architecture } ?: return null
        return match.sha256.takeIf { isValidSha256(it) }
    }

    override suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact> {
        if (target.distribution != "ubuntu") {
            return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE,
                    "BundledRootfsSource only serves ubuntu (got ${target.distribution})",
                    recoverable = false
                )
            )
        }
        val match = bundled.firstOrNull { it.architecture == target.architecture && target.version == "24.04" }
            ?: return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE,
                    "No bundled Ubuntu ${target.version} rootfs for ${target.architecture}",
                    recoverable = false
                )
            )
        // §9 防线：placeholder 指纹永远过不了 resolve —— 宁可失败，不可谎报可验证。
        if (!isValidSha256(match.sha256)) {
            return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.ARCHIVE_INVALID,
                    "Registry integrity violation: invalid sha256 for ${POINT_VERSION}/${match.architecture}",
                    recoverable = false
                )
            )
        }
        val f = bundleFile
        if (!f.exists() || f.length() == 0L) {
            return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.ARCHIVE_INVALID,
                    "Bundled rootfs archive missing: ${f.absolutePath} — this build was not " +
                        "packaged with the Ubuntu bundle (run scripts/fetch_rootfs.sh before assemble)",
                    recoverable = false
                )
            )
        }
        if (f.length() != match.size) {
            return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.ARCHIVE_INVALID,
                    "Bundled archive size mismatch: expected ${match.size}, got ${f.length()} " +
                        "(${f.absolutePath}) — truncated or replaced; reinstall the APK",
                    recoverable = false
                )
            )
        }
        return Result.success(
            RootfsArtifact(
                id = "ubuntu-${POINT_VERSION}-${match.architecture.name.lowercase()}",
                distribution = "ubuntu",
                version = POINT_VERSION,
                architecture = match.architecture,
                archiveUrl = null,                 // BUNDLED：无网络来源，档案在设备本地
                archiveFormat = ArchiveFormat.TAR_GZ,
                expectedSize = match.size,
                expectedUnpackedSize = match.unpackedSize,   // T84：磁盘预检实测优先
                sha256 = match.sha256,
                sourceKind = sourceKind,
                metadataVersion = 1
            )
        )
    }

    /**
     * 打开内置档案的本地流。offset 续传语义：本地重拷代价极低（~30MB 同盘拷贝），
     * 不做 seek 续传 —— 以 [RangeNotSupportedInputStream] 标记让 RootfsDownloader
     * 丢弃 .part 从头拷贝（与 HTTP 200 服务器不支持 Range 的路径完全同构）。
     */
    override suspend fun open(artifact: RootfsArtifact, offset: Long): Result<InputStream> {
        val f = bundleFile
        if (!f.exists() || f.length() == 0L) {
            return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.ARCHIVE_INVALID,
                    "Bundled rootfs archive missing: ${f.absolutePath}",
                    recoverable = false
                )
            )
        }
        return runCatching {
            if (offset <= 0) f.inputStream()
            else RangeNotSupportedInputStream(f.inputStream())
        }
    }

    companion object {
        /** 内置包版本（完整 point release，与注册表指纹绑定）。 */
        const val POINT_VERSION = "24.04.4"

        /** jniLibs 伪 .so 名（jniLibs/<abi>/libubuntu-rootfs.so → nativeLibraryDir）。 */
        const val BUNDLE_LIB_NAME = "libubuntu-rootfs.so"

        /** 64 位十六进制且非全零 —— placeholder 的唯一判据（自 OfficialUbuntuRootfsSource 迁移）。 */
        fun isValidSha256(s: String?): Boolean {
            if (s == null || s.length != 64) return false
            if (!s.all { it in '0'..'9' || it in 'a'..'f' }) return false
            return s.any { it != '0' }
        }
    }
}

/**
 * §29 test double — serves an in-memory artifact for unit tests.
 * NO network. Production NEVER uses this.（自 OfficialUbuntuRootfsSource.kt 迁移，
 * 该文件已随运行时下载方案一并移除。）
 */
class FakeRootfsSource(
    private val artifact: RootfsArtifact,
    private val archiveBytes: ByteArray
) : RootfsArtifactSource {
    override val sourceKind: RootfsSourceKind = RootfsSourceKind.CUSTOM
    override suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact> =
        if (target.distribution == artifact.distribution &&
            target.version == artifact.version &&
            target.architecture == artifact.architecture) {
            Result.success(artifact)
        } else {
            Result.failure(
                provisioningException(
                    ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE,
                    "FakeRootfsSource only serves ${artifact.distribution}/${artifact.version}/${artifact.architecture}",
                    recoverable = false
                )
            )
        }

    /** 支持 offset（与生产语义对齐；越界返回空流 = 服务器视角的 EOF）。 */
    override suspend fun open(artifact: RootfsArtifact, offset: Long): Result<InputStream> =
        Result.success(
            if (offset <= 0) archiveBytes.inputStream()
            else if (offset >= archiveBytes.size) java.io.ByteArrayInputStream(ByteArray(0))
            else java.io.ByteArrayInputStream(
                archiveBytes.copyOfRange(offset.toInt(), archiveBytes.size)
            )
        )
}
