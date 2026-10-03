package com.apex.agent.update

import android.os.StatFs
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.skill.SafeZipExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  HotUpdateEngine —— 热更新流水线（下载 → 校验 → 原子落位 → 即时生效）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 与 [PatchUpdateEngine]（补丁链 → 合成 APK → 系统安装器）互补的**零安装**
 * 通道：热更包只携带数据层内容（技能 / MCP 目录），应用后即时生效，
 * 全程不碰 PackageManager —— **签名冲突在热更通道上不存在**（这正是
 * debug 密钥签名时代增量重装路径的救命通道，见 docs/hot-update-pipeline.md）。
 *
 * 流水线五段（全部 IO 线程，状态经 [StateListener] 单向上报）：
 *
 * 1. **断点复用**：热更包已完整落盘（体积 + SHA-256 对账）则跳过下载；
 * 2. **下载**：系统 DownloadManager 托管（通知栏进度 / 断点续传 / 进程被杀
 *   不丢），轮询驱动同 [PatchUpdateEngine]；
 * 3. **ZIP 终局对账**：整包 SHA-256 对 version.json `hot` 档指纹；
 * 4. **解包复核**：[SafeZipExtractor]（路径穿越 + zip bomb 防御）解压暂存
 *   → `hotmanifest.json` 解析（schema 校验）→ **逐文件 SHA-256 复核**
 *   （第二道防线：外层指纹对账后内容再被篡改的极端场景）；
 * 5. **落位生效**：[HotContentStore.applyPackage] 原子换位 → 技能经
 *   [SkillReapplier] 立即注入注册表（版本化幂等升级，保持用户启停态；
 *   SkillHotReloader 令新工具即时可用）→ 上报 [State.Applied]。
 *
 * 失败折叠为 [State.Failed]（[FailKind] 供 UI 本地化），不向上抛；
 * MCP 目录为读取时 overlay（市场页下次加载即见新目录），无需主动刷新。
 */
class HotUpdateEngine(
    private val downloader: UpdateDownloader,
    private val store: HotContentStore,
    private val skillReapplier: SkillReapplier
) {

    /**
     * 技能重放出口 —— 引擎不持有 DI 图；由 [UpdateCenter] 以 Hilt EntryPoint
     * 接线（installBundled → notifyChanged → SkillHotReloader 即时生效）。
     * 返回成功安装/升级条数；-1 = 注册表暂不可用（仅留痕，下次启动
     * SkillModule 会重放，热更内容不丢）。
     */
    fun interface SkillReapplier {
        fun reapply(manifestJsons: List<String>): Int
    }

    /** 失败类别 —— UI 据此出本地化文案。 */
    enum class FailKind { SPACE, DOWNLOAD, VERIFY, UNPACK, APPLY }

    /** 流水线状态 —— UI 按类型渲染。 */
    sealed interface State {
        /** 下载热更包（percent 0-100 / 已下载字节）。 */
        data class Downloading(val percent: Int, val bytesSoFar: Long) : State

        /** 校验阶段（ZIP 指纹 + 解包 + 逐文件复核 + 换位，时长不定）。 */
        data object Verifying : State

        /** 热更生效 —— target 版本 / 包内文件数 / 技能注入数。 */
        data class Applied(
            val targetVersionName: String,
            val entries: Int,
            val skillsApplied: Int
        ) : State

        /** 任一环节失败。 */
        data class Failed(val kind: FailKind, val detail: String? = null) : State
    }

    fun interface StateListener {
        fun onStateChanged(state: State)
    }

    private var job: Job? = null

    /** 仍在执行的流水线（按钮防重入）。 */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 启动流水线（幂等：正在跑则先取消旧的）。
     *
     * @param scope 应用级作用域（[UpdateCenter.scope] —— 离开关于页不中断）
     * @param hot version.json `hot` 资产档（已过 [HotUpdatePolicy.resolve] 门控）
     * @param manifest 本次命中的更新清单（target 对账 + tag 记账）
     * @param mirror 已解析的下载镜像（URL 改写）
     * @param listener 状态回调（IO 线程，UI 自行切主线程）
     */
    fun start(
        scope: CoroutineScope,
        hot: HotAsset,
        manifest: UpdateManifest,
        mirror: DownloadMirror,
        listener: StateListener
    ) {
        cancel()
        job = scope.launch(Dispatchers.IO) {
            runFlow(hot, manifest, mirror, listener)
        }
    }

    /** 取消流水线（已下载 ZIP 保留 —— 断点复用资产，成功/换版才回收）。 */
    fun cancel() {
        job?.cancel()
        job = null
    }

    // ── 流水线主体 ───────────────────────────────────────────────────────

    private suspend fun runFlow(
        hot: HotAsset,
        manifest: UpdateManifest,
        mirror: DownloadMirror,
        listener: StateListener
    ) {
        var staging: File? = null
        try {
            store.cleanStaleStaging()
            val fileName = hot.url.substringAfterLast('/')
            val localFile = downloader.localFile(fileName)

            // ── 空间预检：ZIP + 解压产物 + 换位兜底拷贝 ≈ 3×包体 ──────────
            if (hot.sizeBytes > 0) {
                val required = hot.sizeBytes * 3 + (16L shl 20)
                val available = StatFs(downloader.workDirectory().absolutePath).availableBytes
                if (available < required) {
                    listener.onStateChanged(
                        State.Failed(FailKind.SPACE, "need=$required free=$available")
                    )
                    return
                }
            }

            // ── 阶段一：下载（已验证包直接复用 = 断点语义）──────────────────
            if (isZipComplete(localFile, hot)) {
                AppLogger.instance.info(
                    LogCategory.SYSTEM, "HotUpdateEngine",
                    "热更包已就绪（$fileName）—— 跳过下载"
                )
                listener.onStateChanged(State.Downloading(100, localFile.length()))
            } else {
                val enqueued = downloader.enqueue(
                    url = mirror.rewrite(hot.url),
                    fileName = fileName,
                    title = "Apex Agent hot update",
                    isPatch = true,
                    expectedSha256 = hot.sha256
                ) ?: run {
                    listener.onStateChanged(
                        State.Failed(FailKind.DOWNLOAD, "DownloadManager unavailable")
                    )
                    return
                }
                awaitDownload(enqueued.id) { percent, bytes ->
                    listener.onStateChanged(State.Downloading(percent, bytes))
                }
            }
            if (!downloader.verifySha256(localFile, hot.sha256)) {
                runCatching { localFile.delete() }
                listener.onStateChanged(State.Failed(FailKind.VERIFY, fileName))
                return
            }

            // ── 阶段二：解包 + 逐文件复核 ──────────────────────────────────
            listener.onStateChanged(State.Verifying)
            val stage = store.newStagingDir()
            staging = stage
            try {
                SafeZipExtractor.extract(localFile, stage)
            } catch (e: Exception) {
                listener.onStateChanged(State.Failed(FailKind.UNPACK, e.message))
                return
            }
            val packageManifest = parsePackageManifest(stage)
                ?: run {
                    listener.onStateChanged(
                        State.Failed(FailKind.UNPACK, HotPackage.MANIFEST_NAME)
                    )
                    return
                }
            // 包内 target 与 version.json hot 档对账（防两端清单漂移）
            if (packageManifest.targetVersionCode != hot.targetVersionCode ||
                packageManifest.entries.isEmpty()
            ) {
                listener.onStateChanged(State.Failed(FailKind.VERIFY, "target mismatch"))
                return
            }
            for (entry in packageManifest.entries) {
                val entryFile = File(stage, entry.path)
                if (!entryFile.isFile || !verifyFileSha256(entryFile, entry.sha256)) {
                    listener.onStateChanged(State.Failed(FailKind.VERIFY, entry.path))
                    return
                }
            }

            // ── 阶段三：原子落位 + 技能即时注入 ────────────────────────────
            // packageSha256（version.json hot 档）随应用记账 —— 发布仓库应急
            // 重发同版本包时，门控凭指纹判定需要重新应用
            if (!store.applyPackage(stage, packageManifest, manifest.tag, hot.sha256)) {
                listener.onStateChanged(State.Failed(FailKind.APPLY, "overlay swap"))
                return
            }
            staging = null // 已换名为 active，不再按暂存清理
            runCatching { localFile.delete() }
            val skillsApplied = skillReapplier.reapply(store.activeSkillManifests())
            if (skillsApplied < 0) {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "HotUpdateEngine",
                    "技能注册表暂不可用 —— 热更内容已落位，下次启动由 SkillModule 重放"
                )
            }
            AppLogger.instance.info(
                LogCategory.SYSTEM, "HotUpdateEngine",
                "热更新生效：v${packageManifest.targetVersionName}" +
                    "(${packageManifest.targetVersionCode}) · ${packageManifest.entries.size} 文件 · 技能注入 $skillsApplied"
            )
            listener.onStateChanged(
                State.Applied(
                    packageManifest.targetVersionName,
                    packageManifest.entries.size,
                    skillsApplied
                )
            )
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "HotUpdateEngine",
                "热更新失败：${e.message}"
            )
            listener.onStateChanged(
                State.Failed(FailKind.DOWNLOAD, e.message)
            )
        } finally {
            staging?.let { runCatching { it.deleteRecursively() } }
        }
    }

    /** 轮询指定下载直至成功/失败（协程取消即止；与 PatchUpdateEngine 同语义）。 */
    private suspend fun awaitDownload(
        id: Long,
        onTick: (percent: Int, bytes: Long) -> Unit
    ) {
        while (currentCoroutineContext().isActive) {
            val status = downloader.statusOf(id)
            if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) return
            if (status == android.app.DownloadManager.STATUS_FAILED) {
                throw IllegalStateException("download id=$id failed")
            }
            val (percent, bytes) = downloader.progress(id)
            onTick(percent, bytes)
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 热更包完整性判定（体积对账先行，SHA-256 兜底；无指纹时体积即放行）。 */
    private fun isZipComplete(file: File, hot: HotAsset): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (hot.sizeBytes > 0 && file.length() != hot.sizeBytes) return false
        return hot.sha256.isNullOrBlank() || downloader.verifySha256(file, hot.sha256)
    }

    /** 解析包内清单（缺失/损坏/schema 不符 → null）。 */
    private fun parsePackageManifest(stage: File): HotPackage.Manifest? =
        runCatching {
            File(stage, HotPackage.MANIFEST_NAME).takeIf { it.isFile }
                ?.let { HotPackage.parse(it.readText()) }
        }.getOrNull()

    /** 单文件 SHA-256 复核（包内第二道防线）。 */
    private fun verifyFileSha256(file: File, expected: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
            .equals(expected, ignoreCase = true)
    }.getOrDefault(false)

    private companion object {
        const val POLL_INTERVAL_MS = 600L
    }
}
