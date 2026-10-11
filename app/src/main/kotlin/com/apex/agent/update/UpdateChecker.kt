package com.apex.agent.update

import android.os.Build
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

/**
 * 应用内更新检查 —— 双仓库发布架构的客户端侧。
 *
 * 发布仓库（Android-Guru-Agent-Release）main 分支上的 `version.json` 由开发仓库
 * CI 在每次 tag 发布时自动提交；本类拉取该清单并与本地 versionCode 比对。
 *
 * 设计约束：
 * - **零鉴权**：清单走 raw.githubusercontent CDN（公开仓库），无需任何 Token；
 * - **零新依赖**：OkHttp + kotlinx.serialization 均为项目既有栈；
 * - **三态结果**：[UpdateCheckResult] 密封层级让 UI 直接按类型渲染，无散落布尔；
 * - **共享客户端**：默认取 [UpdateHttp.client]（连接池/线程池进程级共享，
 *   避免每次进页新建 OkHttp 实例堆积空闲连接）；
 * - **可取消**：[check] 用 enqueue + invokeOnCancellation —— 离开页面即断流，
 *   不再占用 IO 线程等到超时。
 */
class UpdateChecker(
    private val client: OkHttpClient = UpdateHttp.client
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉取清单并比对版本。协程取消会同步 cancel 底层 OkHttp 调用；
     * 网络异常一律折叠为 [UpdateCheckResult.Failed] —— 检查更新永不打断用户流程。
     */
    suspend fun check(currentVersionCode: Int): UpdateCheckResult = runCatching {
        val request = Request.Builder()
            .url(UPDATE_MANIFEST_URL)
            .header("Cache-Control", "no-cache")
            .build()
        val body = withContext(Dispatchers.IO) { client.awaitBody(request) }
        json.decodeFromString<UpdateManifest>(body)
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

    /**
     * 拉取补丁全量索引（跨版本链式增量的数据源）。
     *
     * 与 [check] 同一网络链路（共享客户端/可取消/超时）；失败折叠 null ——
     * 索引不存在（旧发布仓库）或网络抖动时，调用方回退 version.json 的
     * 单补丁档或全量包，更新链路不因索引缺失而中断。
     */
    suspend fun fetchPatchIndex(): PatchIndex.Model? {
        val request = Request.Builder()
            .url(PATCH_INDEX_URL)
            .header("Cache-Control", "no-cache")
            .build()
        return runCatching {
            val body = withContext(Dispatchers.IO) { client.awaitBody(request) }
            PatchIndex.parse(body)
        }.onFailure { e ->
            AppLogger.instance.warn(
                LogCategory.SYSTEM,
                "UpdateChecker",
                "补丁索引拉取失败（回退单补丁/全量）：${e.message}"
            )
        }.getOrNull()
    }

    /**
     * 按设备 ABI 选最合适的下载地址：arm64 机型出纯净包（~300MB），
     * 其余出 universal（全 3 ABI）；清单缺字段时逐级回退到发布页。
     */
    fun preferredDownloadUrl(manifest: UpdateManifest): String? {
        // v1.4.4 UX 审查：删除死赋值 download（`?:` 早返回后从未被读取）
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

    /** 设备 ABI 对应的发布变体名（与 CI 资产命名/arm64/universal 双档一致）。 */
    fun deviceVariant(): String =
        if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) "arm64" else "universal"

    /**
     * 解析「本地版本 → 清单最新版」的补丁链（跨版本增量核心）。
     *
     * 优先级：patches.json 全量索引链 → version.json 单补丁（仅相邻版本）→
     * null（无增量可用，调用方回退全量）。链总体积超过全量包 60% 时判
     * 定不划算，返回 null（与 CI 补丁体积守卫同阈值）。
     *
     * @param index 补丁索引（[fetchPatchIndex] 的结果，可为 null）
     * @param manifest 本次检查更新命中的清单
     * @param currentVersionName 本地 versionName
     */
    fun resolvePatchChain(
        index: PatchIndex.Model?,
        manifest: UpdateManifest,
        currentVersionName: String
    ): PatchIndex.Chain? {
        val variant = deviceVariant()
        val full = preferredAsset(manifest) ?: return null
        val chain = index?.let {
            PatchIndex.resolveChain(it, currentVersionName, manifest.tag.orEmpty(), variant)
        } ?: preferredPatch(manifest, currentVersionName)?.let { legacy ->
            // 索引缺失时回退 version.json 单补丁档（fromTag 已验证匹配本地版本）
            val toTag = manifest.tag ?: legacy.fromTag
            PatchIndex.Chain(
                listOf(
                    PatchIndex.Entry(
                        variant = variant,
                        fromTag = legacy.fromTag,
                        toTag = toTag,
                        url = legacy.url,
                        sizeBytes = legacy.sizeBytes,
                        sha256 = legacy.sha256
                    )
                ),
                legacy.sizeBytes
            )
        } ?: return null
        // 体积守卫：链总体积 > 60% 全量包 → 增量无意义，直接全量
        if (full.sizeBytes > 0 && chain.totalBytes * 10 > full.sizeBytes * 6) return null
        return chain
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

    /**
     * 判定本清单是否对本地开放热更新通道（纯策略委托，无 Android 依赖）。
     *
     * @param manifest 本次命中的清单
     * @param effectiveVersionCode 本地有效版本（max(包 versionCode, 已应用热更目标)，
     *   由 [HotContentStore.effectiveVersionCode] 提供）
     * @param appliedTargetVersionCode 已应用热更目标（0 = 从未）
     * @param appliedPackageSha256 已应用热更包 ZIP 指纹（null = 未记录）——
     *   发布仓库应急重发同版本包时凭此判重（见 HotUpdatePolicy 门 2）
     */
    fun resolveHotUpdate(
        manifest: UpdateManifest,
        effectiveVersionCode: Int,
        appliedTargetVersionCode: Int,
        appliedPackageSha256: String? = null
    ): HotAsset? = HotUpdatePolicy.resolve(
        manifest.hot, manifest.versionCode, effectiveVersionCode,
        appliedTargetVersionCode, appliedPackageSha256
    )
}

/** 发布仓库 main 分支上版本清单的固定地址（由开发仓库 CI 自动维护）。 */
const val UPDATE_MANIFEST_URL =
    "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/version.json"

/** 发布仓库 main 分支上补丁全量索引的固定地址（跨版本链式增量）。 */
const val PATCH_INDEX_URL = PatchIndex.PATCH_INDEX_URL

/**
 * 更新链路共享 OkHttp 客户端（进程级单例）。
 *
 * OkHttp 官方要求客户端实例共享：每个实例自带连接池 + dispatcher 线程池，
 * 页面级 remember 会在反复进出页时堆积空闲连接/线程（最长滞留 5 分钟）。
 * 更新检查与镜像测速共用本实例（测速的“不跟随重定向”语义在调用点
 * 用 newBuilder 派生，不另起炉灶）。
 */
object UpdateHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

/**
 * 可取消的同步化请求：协程取消 → call.cancel()，响应体自动关闭。
 * （顶级扩展——object 内声明的扩展函数同包也不自动解析，需 import 成员路径）
 */
private suspend fun OkHttpClient.awaitBody(request: Request): String =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = runCatching {
                        if (!it.isSuccessful) error("HTTP ${it.code}")
                        it.body?.string() ?: error("empty manifest body")
                    }
                    cont.resumeWith(text)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

/** 更新检查结果三态 —— UI 按类型渲染，无需解析错误码。 */
sealed interface UpdateCheckResult {
    /** 已是最新（或远端 versionCode 不高于本地）。 */
    data class UpToDate(val latest: UpdateManifest) : UpdateCheckResult

    /** 有新版本 —— [latest] 携带下载地址与发布页。 */
    data class Available(val latest: UpdateManifest) : UpdateCheckResult

    /** 检查失败（网络/解析）—— [reason] 面向用户展示。 */
    data class Failed(val reason: String) : UpdateCheckResult
}

/** 下载目标统一视图：全量 APK（UpdateAsset）与增量补丁（UpdatePatchAsset）共有的
 *  定位字段 —— 让 UI 层的下载入口能以单一类型接待两种资产。 */
interface UpdateTarget {
    val url: String
    val sha256: String?
}

/** 单个发布资产：直链 + 体积 + SHA-256（供下载后校验）。 */
@Serializable
data class UpdateAsset(
    override val url: String,
    val sizeBytes: Long = 0L,
    override val sha256: String? = null
) : UpdateTarget

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
    override val url: String,
    val sizeBytes: Long = 0L,
    override val sha256: String? = null
) : UpdateTarget

/** 补丁矩阵：与 [UpdateDownload] 同构的 arm64 / universal 双变体。 */
@Serializable
data class UpdatePatchMatrix(
    val arm64: UpdatePatchAsset? = null,
    val universal: UpdatePatchAsset? = null
)

/**
 * version.json 清单模型 —— 与开发仓库 release.yml 生成的 schema 一一对应。
 * 全部可选字段 + ignoreUnknownKeys：清单演进（如新增字段）不崩老客户端。
 *
 * v1.4.7 新增字段（老客户端自动忽略）：
 * - [hot]：热更包档（data-only 版本才有；[HotUpdatePolicy.resolve] 判定适用）；
 * - [commitSha]：本版 commit（CI 判定 data-only 的比对基准，热更通道自举用）；
 * - [signingCertSha256]：发布 APK 签名证书 SHA-256 指纹 —— 客户端签名
 *   预检（本地指纹不一致 = 增量/全量覆盖安装必报签名冲突，提前告知
 *   用户改走热更或卸载重装，不再下载 300MB 后才在安装器撞墙）。
 */
@Serializable
data class UpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val tag: String? = null,
    val publishedAt: String? = null,
    val releasePage: String? = null,
    val download: UpdateDownload? = null,
    val patch: UpdatePatchMatrix? = null,
    val hot: HotAsset? = null,
    val commitSha: String? = null,
    val signingCertSha256: String? = null
)
