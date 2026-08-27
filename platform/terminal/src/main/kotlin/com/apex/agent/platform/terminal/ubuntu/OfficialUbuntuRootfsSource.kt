package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PR #69 §6/§7: Official Ubuntu RootFS Source.
 *
 * Resolves Ubuntu 24.04 ARM64 (and X86_64) rootfs artifacts from the official
 * Ubuntu cloud-images / Ubuntu Base mirror. P69 first cut: returns the
 * known-good Ubuntu 24.04 ARM64 manifest. Real download URLs are NOT
 * hardcoded into PRoot or Session code — they live HERE, in the source
 * abstraction, so adding Debian/Alpine/Custom later is additive.
 *
 * §7: never silently falls back to an incompatible architecture. If the
 * target architecture is not one this source serves, resolve() fails with
 * UNSUPPORTED_ARCHITECTURE.
 *
 * §9: every artifact carries a SHA-256. The provisioner refuses to install
 * an unverifiable artifact (UNVERIFIED) unless explicitly relaxed.
 *
 * Production URLs: the real Ubuntu cloud-images rootfs tarballs. P69 first
 * cut uses placeholder checksums (the provisioner + downloader are fully
 * functional; the URLs/checksums are filled in when the real artifact is
 * published to the mirror). Tests use FakeRootfsSource (no network).
 */
class OfficialUbuntuRootfsSource : RootfsArtifactSource {

    override val sourceKind: RootfsSourceKind = RootfsSourceKind.OFFICIAL_MIRROR

    // §6: known-good artifact registry. In production this is maintained
    // alongside release tags; here it's a small table the tests can inspect.
    private data class KnownArtifact(
        val version: String,
        val architecture: CpuArchitecture,
        val url: String,
        val sha256: String,
        val size: Long
    )

    // P69: Ubuntu 24.04 ARM64 is the primary target. X86_64 is included for
    // completeness and CI (GitHub Actions ubuntu-24.04 runs x86_64). Other
    // versions/architectures resolve to UNSUPPORTED_ARCHITECTURE (§7).
    private val known = listOf(
        KnownArtifact(
            version = "24.04",
            architecture = CpuArchitecture.ARM64,
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz",
            // PLACEHOLDER checksum — replaced with the real published SHA-256
            // when the artifact is fetched the first time. The downloader +
            // verifier are fully functional; tests use FakeRootfsSource.
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            size = 40L * 1024 * 1024   // ~40MB compressed
        ),
        KnownArtifact(
            version = "24.04",
            architecture = CpuArchitecture.X86_64,
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-amd64.tar.gz",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            size = 45L * 1024 * 1024
        )
    )

    override suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact> {
        val match = known.firstOrNull { it.version == target.version && it.architecture == target.architecture }
            ?: return Result.failure(
                provisioningException(
                    ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE,
                    "No Ubuntu ${target.version} rootfs for ${target.architecture}",
                    recoverable = false
                )
            )
        return Result.success(
            RootfsArtifact(
                id = "ubuntu-${match.version}-${match.architecture.name.lowercase()}",
                distribution = "ubuntu",
                version = match.version,
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

    override suspend fun open(artifact: RootfsArtifact): Result<InputStream> {
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
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw provisioningException(
                    ProvisioningErrorCode.NETWORK_FAILURE,
                    "HTTP $code for $url",
                    recoverable = true
                )
            }
            conn.inputStream
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
    override suspend fun open(artifact: RootfsArtifact): Result<InputStream> =
        Result.success(archiveBytes.inputStream())
}
