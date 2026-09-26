package com.apex.agent.update

import android.os.Build
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 应用内更新检查 —— 双仓库发布架构的客户端侧。
 *
 * 发布仓库（Android-Guru-Agent-Release）main 分支上的 `version.json` 由开发仓库
 * CI 在每次 tag 发布时自动提交；本类拉取该清单并与本地 versionCode 比对。
 *
 * 设计约束：
 * - **零鉴权**：清单走 raw.githubusercontent CDN（公开仓库），无需任何 Token；
 * - **零新依赖**：OkHttp + kotlinx.serialization 均为项目既有栈；
 * - **三态结果**：[UpdateCheckResult] 密封层级让 UI 直接按类型渲染，无散落布尔。
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉取清单并比对版本。IO 切换在内部完成，调用方只需在协程作用域内调用。
     * 网络异常一律折叠为 [UpdateCheckResult.Failed] —— 检查更新永不打断用户流程。
     */
    suspend fun check(currentVersionCode: Int): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(UPDATE_MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("empty manifest body")
                json.decodeFromString<UpdateManifest>(body)
            }
        }.fold(
            onSuccess = { manifest ->
                AppLogger.instance.info(
                    LogCategory.SYSTEM,
                    "UpdateChecker",
                    "更新清单拉取成功：远端 v${manifest.versionName}(${manifest.versionCode}) / 本地 ($currentVersionCode)"
                )
                if (manifest.versionCode > currentVersionCode) {
                    UpdateCheckResult.Available(manifest)
                } else {
                    UpdateCheckResult.UpToDate(manifest)
                }
            },
            onFailure = { e ->
                AppLogger.instance.warn(
                    LogCategory.SYSTEM,
                    "UpdateChecker",
                    "更新检查失败：${e.message}"
                )
                UpdateCheckResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        )
    }

    /**
     * 按设备 ABI 选最合适的下载地址：arm64 机型出纯净包（~300MB），
     * 其余出 universal（全 3 ABI）；清单缺字段时逐级回退到发布页。
     */
    fun preferredDownloadUrl(manifest: UpdateManifest): String? {
        val download = manifest.download ?: return manifest.releasePage
        return preferredAsset(manifest)?.url ?: manifest.releasePage
    }

    /**
     * 按设备 ABI 选资产对象（体积/SHA 供 UI 展示与下载后校验）。
     */
    fun preferredAsset(manifest: UpdateManifest): UpdateAsset? {
        val download = manifest.download ?: return null
        val arm64Device = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        return when {
            arm64Device -> download.arm64 ?: download.universal
            else -> download.universal ?: download.arm64
        }
    }

    /**
     * 按设备 ABI 选增量补丁，并验证适用性：清单的 `fromTag` 必须等于本地
     * `v{versionName}`（补丁只能从「上一版」打到「本版」；跳版安装只能全量）。
     *
     * 返回 null = 无可用补丁（首次发布 / rootfs 大改被 CI 丢弃 / 跨多版）。
     */
    fun preferredPatch(
        manifest: UpdateManifest,
        currentVersionName: String
    ): UpdatePatchAsset? {
        val patch = manifest.patch ?: return null
        val arm64Device = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        val asset = when {
            arm64Device -> patch.arm64 ?: patch.universal
            else -> patch.universal ?: patch.arm64
        } ?: return null
        return asset.takeIf { it.fromTag == "v$currentVersionName" }
    }
}

/** 发布仓库 main 分支上版本清单的固定地址（由开发仓库 CI 自动维护）。 */
const val UPDATE_MANIFEST_URL =
    "https://raw.githubusercontent.com/AceGuru-mjh/Android-Guru-Agent-Release/main/version.json"

/** 更新检查结果三态 —— UI 按类型渲染，无需解析错误码。 */
sealed interface UpdateCheckResult {
    /** 已是最新（或远端 versionCode 不高于本地）。 */
    data class UpToDate(val latest: UpdateManifest) : UpdateCheckResult

    /** 有新版本 —— [latest] 携带下载地址与发布页。 */
    data class Available(val latest: UpdateManifest) : UpdateCheckResult

    /** 检查失败（网络/解析）—— [reason] 面向用户展示。 */
    data class Failed(val reason: String) : UpdateCheckResult
}

/** 单个发布资产：直链 + 体积 + SHA-256（供下载后校验）。 */
@Serializable
data class UpdateAsset(
    val url: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null
)

/** 下载矩阵：arm64 纯净包 / universal 全 ABI 包。 */
@Serializable
data class UpdateDownload(
    val arm64: UpdateAsset? = null,
    val universal: UpdateAsset? = null
)

/** 增量补丁资产：`fromTag` 声明适用基础版本（xdelta3 VCDIFF）。 */
@Serializable
data class UpdatePatchAsset(
    val fromTag: String,
    val url: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null
)

/** 补丁矩阵：与 [UpdateDownload] 同构的 arm64 / universal 双变体。 */
@Serializable
data class UpdatePatchMatrix(
    val arm64: UpdatePatchAsset? = null,
    val universal: UpdatePatchAsset? = null
)

/**
 * version.json 清单模型 —— 与开发仓库 release.yml 生成的 schema 一一对应。
 * 全部可选字段 + ignoreUnknownKeys：清单演进（如新增字段）不崩老客户端。
 */
@Serializable
data class UpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val tag: String? = null,
    val publishedAt: String? = null,
    val releasePage: String? = null,
    val download: UpdateDownload? = null,
    val patch: UpdatePatchMatrix? = null
)
