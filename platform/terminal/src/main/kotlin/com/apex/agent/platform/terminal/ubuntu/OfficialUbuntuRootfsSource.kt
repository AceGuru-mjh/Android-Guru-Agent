package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * T72: Official Ubuntu RootFS Source —— 真实发布信息。
 *
 * 数据来源（2026-02 实测，写死以保证可复现构建）：
 *   https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS
 *   （附 SHA256SUMS.gpg 签名；checksum 与本地下载文件逐一复核过）
 *
 * §7: never silently falls back to an incompatible architecture。
 * §9: every artifact carries a REAL SHA-256 —— placeholder（全零/短于 64）
 *     在 resolve() 即被拒绝，绝不进入下载阶段。
 *
 * 版本策略：锁 point release（24.04.4）。cdimage 的无 point-release 路径
 * （ubuntu-base-24.04-base-arm64.tar.gz）已不存在 —— 现目录只有
 * 24.04.3 / 24.04.4 命名。point 升级（24.04.5+）是**显式**的人工变更
 * （更新本表 + 对应测试），不做运行时自动跟随 —— 镜像内容变了而
 * checksum 不变等于静默不可复现。
 */
class OfficialUbuntuRootfsSource : RootfsArtifactSource {

    override val sourceKind: RootfsSourceKind = RootfsSourceKind.OFFICIAL_MIRROR

    // §6: known-good artifact registry —— 来自官方 SHA256SUMS 的逐字节真值。
    private data class KnownArtifact(
        val version: String,          // major.minor（RootfsTarget 语义）
        val pointVersion: String,     // 完整 point release（文件名/追溯用）
        val architecture: CpuArchitecture,
        val url: String,
        val sha256: String,
        val size: Long
    )

    private val known = listOf(
        KnownArtifact(
            version = "24.04",
            pointVersion = "24.04.4",
            architecture = CpuArchitecture.ARM64,
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            sha256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            size = 29_870_567L
        ),
        KnownArtifact(
            version = "24.04",
            pointVersion = "24.04.4",
            architecture = CpuArchitecture.X86_64,
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
            sha256 = "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58",
            size = 29_989_394L
        )
    )

    override suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact> {
        if (target.distribution != "ubuntu") {
            return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE,
                    "OfficialUbuntuRootfsSource only serves ubuntu (got ${target.distribution})",
                    recoverable = false
                )
            )
        }
        val match = known.firstOrNull { it.version == target.version && it.architecture == target.architecture }
            ?: return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE,
                    "No Ubuntu ${target.version} rootfs for ${target.architecture}",
                    recoverable = false
                )
            )
        // T72 防线：placeholder checksum 永远过不了 resolve —— 宁可失败，
        // 不可让"已知校验值" silently 谎报可验证。
        if (!isValidSha256(match.sha256)) {
            return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.ARCHIVE_INVALID,
                    "Registry integrity violation: invalid sha256 for ${match.pointVersion}/${match.architecture}",
                    recoverable = false
                )
            )
        }
        return Result.success(
            RootfsArtifact(
                id = "ubuntu-${match.pointVersion}-${match.architecture.name.lowercase()}",
                distribution = "ubuntu",
                version = match.pointVersion,     // 完整 point 版本进 descriptor
                architecture = match.architecture,
                archiveUrl = match.url,
                archiveFormat = ArchiveFormat.TAR_GZ,
                expectedSize = match.size,
                sha256 = match.sha256,
                sourceKind = sourceKind,
                metadataVersion = 1
            )
        )
    }

    override suspend fun open(artifact: RootfsArtifact, offset: Long): Result<InputStream> {
        val url = artifact.archiveUrl
            ?: return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.ARCHIVE_INVALID,
                    "Artifact has no archive URL",
                    recoverable = false
                )
            )
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                // T72: 真正的断点续传 —— .part 存在时从 offset 起。
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = conn.responseCode
            when {
                // 206 Partial Content —— 断点续传成立
                code == 206 -> conn.inputStream
                // 200 OK —— 服务器不支持 Range（或忽略）；标记流告诉调用方
                // “这是完整内容而非续传片段”，调用方（RootfsDownloader）据此丢弃 .part。
                code == 200 -> RangeNotSupportedInputStream(conn.inputStream)
                // 416 Range Not Satisfiable —— .part 已 >= 文件长度（坏档）
                code == 416 -> throw provisioningException(
                    ProvisioningErrorCode.DOWNLOAD_FAILED,
                    "HTTP 416 Range Not Satisfiable (offset=$offset, url=$url) — stale .part, must restart",
                    recoverable = true
                )
                else -> throw provisioningException(
                    ProvisioningErrorCode.NETWORK_FAILURE,
                    "HTTP $code for $url",
                    recoverable = true
                )
            }
        }
    }

    companion object {
        /** 64 位十六进制且非全零 —— placeholder 的唯一判据。 */
        fun isValidSha256(s: String?): Boolean {
            if (s == null || s.length != 64) return false
            if (!s.all { it in '0'..'9' || it in 'a'..'f' }) return false
            return s.any { it != '0' }
        }
    }
}

/**
 * §29 test double — serves an in-memory artifact for unit tests.
 * NO network. Production NEVER uses this.
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

    /** 支持 offset（与生产 HTTP Range 语义对齐；越界返回空流 = 服务器视角的 EOF）。 */
    override suspend fun open(artifact: RootfsArtifact, offset: Long): Result<InputStream> =
        Result.success(
            if (offset <= 0) archiveBytes.inputStream()
            else if (offset >= archiveBytes.size) java.io.ByteArrayInputStream(ByteArray(0))
            else java.io.ByteArrayInputStream(
                archiveBytes.copyOfRange(offset.toInt(), archiveBytes.size)
            )
        )
}
