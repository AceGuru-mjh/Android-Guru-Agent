package com.apex.agent.update

import android.app.DownloadManager
import android.content.Context
import android.os.StatFs
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 增量更新流水线编排器 —— 「补丁链下载 → 链式合成 → 校验 → 拉起安装器」
 * 的全自动执行单元，直接消灭旧的「复制 xdelta3 命令去终端手工跑」环节。
 *
 * 职责边界：
 * - **顺序下载**：补丁链逐段入队系统 DownloadManager（通知栏进度、断点
 *   续传、进程被杀不丢），每段完成即 SHA-256 校验，坏一段立即失败；
 * - **断点续传（v1.4.5）**：流水线重启时逐段检查已落盘补丁 —— 体积对账
 *   + SHA-256 对账通过的段直接跳过下载，只补缺失/损坏的段；跨进程重启、
 *   跨页面导航的中断都能从断点继续，不重拉已完成的分段；
 * - **链式合成**：已安装 APK（applicationInfo.sourceDir，只读）+ 补丁 1 →
 *   产物 A；A + 补丁 2 → 产物 B…… 乒乓复用临时文件，峰值磁盘 ≈
 *   2×APK + 补丁体积（开始前做剩余空间预检，不足则劝导全量/清理）；
 * - **双保险校验**：解码器逐窗 Adler32 + 终局 SHA-256 对账 version.json
 *   的 download 指纹（arm64/universal 与补丁链变体严格对应）；
 * - **就绪即停**：合成产物落到公共 `Download/ApexAgent/`（FileProvider
 *   授权 URI），**不直接拉起安装器** —— 先上报 [State.Ready]，由 UI 弹
 *   「立即安装」确认框（用户主导安装时机），点击后经 [launchInstaller]
 *   拉起系统安装器；
 * - **自清理**：开工先清上次残留（链外旧补丁 / 中间产物 / 非目标版本合成
 *   包；**链内补丁保留供断点续传**），成功后清全部补丁与中间产物，只留
 *   最终 APK 供安装器读取。
 *
 * 状态经 [StateListener] 单向上报（IO 线程回调，UI 层自行切主线程）；
 * 全部失败折叠为 [State.Failed]（按 [FailKind] 本地化，不向上抛）——
 * 调用方选择重试增量或回退全量。
 */
class PatchUpdateEngine(
    private val context: Context,
    private val downloader: UpdateDownloader,
    private val decoder: VcdiffDecoder = VcdiffDecoder()
) {

    /** 失败类别 —— UI 据此出本地化文案，detail 供日志/高级折叠。 */
    enum class FailKind { SPACE, DOWNLOAD, VERIFY, DECODE, INSTALLER }

    /** 流水线状态 —— UI 按类型渲染，无需解析错误码。 */
    sealed interface State {
        /** 下载补丁链第 step/steps 段。 */
        data class Downloading(
            val step: Int,
            val steps: Int,
            val percent: Int,
            val bytesSoFar: Long
        ) : State

        /** 合成阶段：第 step/steps 段补丁应用至 percent%。 */
        data class Applying(val step: Int, val steps: Int, val percent: Int) : State

        /** 合成完成且终局 SHA-256 通过 —— 等待用户在 UI 点「立即安装」。 */
        data class Ready(val apk: File, val sizeBytes: Long) : State

        /** 安装器已拉起（[launchInstaller] 成功）。 */
        data class Installed(val apk: File) : State

        /** 任一环节失败。 */
        data class Failed(val kind: FailKind, val detail: String? = null) : State
    }

    fun interface StateListener {
        fun onStateChanged(state: State)
    }

    private var job: Job? = null

    /** 仍在执行的流水线（按钮防重入）。 */
    val isRunning: Boolean get() = job?.isActive == true

    /** 公共下载工作目录（与全量包同目录，同卷做空间预检）。 */
    private val workDir: File
        get() = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "ApexAgent"
        ).apply { mkdirs() }

    /**
     * 启动流水线（幂等：正在跑则先取消旧的）。
     *
     * @param scope 调用方作用域 —— v1.4.5 起传 [UpdateCenter] 的应用级作用域：
     *   离开「关于页」不再中断下载与合成（后台持续 + 断点续传）
     * @param chain 补丁链（[UpdateChecker.resolvePatchChain] 的结果，非空有序）
     * @param manifest 更新清单（终局 SHA-256/体积对账）
     * @param mirror 已解析的下载镜像（URL 改写）
     * @param listener 状态回调（IO 线程）
     */
    fun start(
        scope: CoroutineScope,
        chain: PatchIndex.Chain,
        manifest: UpdateManifest,
        mirror: DownloadMirror,
        listener: StateListener
    ) {
        cancel()
        job = scope.launch(Dispatchers.IO) {
            runFlow(chain, manifest, mirror, listener)
        }
    }

    /** 取消流水线（保留已下载补丁与中间产物 —— 下次 start 从断点继续）。 */
    fun cancel() {
        job?.cancel()
        job = null
        // 不清理：链内已验证补丁是断点续传的资产；仅在成功/换链时回收
    }

    /**
     * 拉起系统安装器安装就绪产物（UI「立即安装」按钮的落地动作）。
     *
     * 与流水线解耦：[State.Ready] 之后用户可能「稍后安装」（甚至离开页面
     * 再回来）—— 本方法不依赖 engine 协程，可随时对已就绪 APK 重发。
     *
     * @param apk [State.Ready.apk] 携带的合成产物
     * @param listener 结果回调（[State.Installed] / [State.Failed]）
     */
    fun launchInstaller(apk: File, listener: StateListener) {
        val launched = runCatching { downloader.installApk(apk) }.getOrDefault(false)
        if (launched) {
            AppLogger.instance.info(
                LogCategory.SYSTEM, "PatchUpdateEngine",
                "安装器已拉起：${apk.name}"
            )
            listener.onStateChanged(State.Installed(apk))
        } else {
            listener.onStateChanged(State.Failed(FailKind.INSTALLER, apk.absolutePath))
        }
    }

    // ── 流水线主体 ───────────────────────────────────────────────────────

    private suspend fun runFlow(
        chain: PatchIndex.Chain,
        manifest: UpdateManifest,
        mirror: DownloadMirror,
        listener: StateListener
    ) {
        try {
            runCatching { cleanStaleIntermediates(chain) }
            val variant = chain.steps.first().variant
            val expectedAsset = assetForVariant(manifest, variant)
            val expectedSize = expectedAsset?.sizeBytes ?: 0L
            val expectedSha = expectedAsset?.sha256

            // ── 空间预检：链合成峰值 ≈ 2×产物 + 补丁（多段链）─────────────
            if (expectedSize > 0) {
                val required = expectedSize * (if (chain.steps.size > 1) 2L else 1L) +
                    chain.totalBytes + (16L shl 20)
                val available = StatFs(workDir.absolutePath).availableBytes
                if (available < required) {
                    listener.onStateChanged(
                        State.Failed(FailKind.SPACE, "need=$required free=$available")
                    )
                    return
                }
            }

            // ── 阶段一：逐段下载补丁链（已验证段跳过 = 断点续传）──────────
            val patchFiles = ArrayList<File>(chain.steps.size)
            for ((index, step) in chain.steps.withIndex()) {
                val number = index + 1
                val fileName = step.url.substringAfterLast('/')
                val segmentFile = downloader.localFile(fileName)
                if (isSegmentComplete(segmentFile, step)) {
                    // 断点续传：该段已完整落盘且指纹对账通过 —— 不重下
                    AppLogger.instance.info(
                        LogCategory.SYSTEM, "PatchUpdateEngine",
                        "断点续传：补丁 $number/${chain.steps.size} 已就绪（$fileName）—— 跳过下载"
                    )
                    listener.onStateChanged(
                        State.Downloading(number, chain.steps.size, 100, segmentFile.length())
                    )
                    patchFiles.add(segmentFile)
                    continue
                }
                val enqueued = downloader.enqueue(
                    url = mirror.rewrite(step.url),
                    fileName = fileName,
                    title = "Apex Agent patch $number/${chain.steps.size}",
                    isPatch = true,
                    expectedSha256 = step.sha256
                ) ?: run {
                    listener.onStateChanged(
                        State.Failed(FailKind.DOWNLOAD, "DownloadManager unavailable")
                    )
                    return
                }
                awaitDownload(enqueued.id) { percent, bytes ->
                    listener.onStateChanged(
                        State.Downloading(number, chain.steps.size, percent, bytes)
                    )
                }
                val file = downloader.localFile(fileName)
                if (!file.exists() || !downloader.verifySha256(file, step.sha256)) {
                    listener.onStateChanged(
                        State.Failed(FailKind.VERIFY, "patch $number: $fileName")
                    )
                    return
                }
                patchFiles.add(file)
            }

            // ── 阶段二：链式合成（乒乓临时文件）────────────────────────────
            val sourceApk = File(
                requireNotNull(context.applicationInfo.sourceDir) {
                    "installed APK path is unavailable"
                }
            )
            var intermediate: File? = null
            var chainSucceeded = false
            try {
                var input: File = sourceApk
                for ((index, patchFile) in patchFiles.withIndex()) {
                    val number = index + 1
                    val isLast = number == patchFiles.size
                    val output = if (isLast) finalApkFile(manifest) else {
                        File(workDir, "ApexAgent-chain-step$number.apk").also {
                            runCatching { it.delete() }
                        }
                    }
                    decoder.decode(
                        source = input.takeIf { it.exists() },
                        patch = patchFile.readBytes(),
                        output = output,
                        expectedSize = if (isLast) expectedSize else 0L,
                        onProgress = { decoded ->
                            val percent = if (expectedSize > 0) {
                                ((decoded * 100) / expectedSize).toInt().coerceIn(0, 100)
                            } else 0
                            listener.onStateChanged(
                                State.Applying(number, patchFiles.size, percent)
                            )
                        }
                    )
                    // 上一段中间产物使命完成（首段输入是已安装 APK，绝不能删）
                    intermediate?.let { previous -> runCatching { previous.delete() } }
                    intermediate = if (isLast) null else output
                    input = output
                }
                chainSucceeded = true
            } finally {
                intermediate?.let { runCatching { it.delete() } }
                if (!chainSucceeded) runCatching { finalApkFile(manifest).delete() }
            }

            // ── 阶段三：终局 SHA-256 对账 → 就绪（安装时机交给用户）──────────
            val finalApk = finalApkFile(manifest)
            if (!downloader.verifySha256(finalApk, expectedSha)) {
                runCatching { finalApk.delete() }
                listener.onStateChanged(State.Failed(FailKind.VERIFY, "final APK"))
                return
            }
            runCatching { patchFiles.forEach { it.delete() } }
            AppLogger.instance.info(
                LogCategory.SYSTEM, "PatchUpdateEngine",
                "增量更新合成完成：${finalApk.name}（${patchFiles.size} 段补丁，等待用户安装）"
            )
            listener.onStateChanged(
                State.Ready(finalApk, expectedAsset?.sizeBytes ?: finalApk.length())
            )
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "PatchUpdateEngine",
                "增量更新失败：${e.message}"
            )
            val kind = when (e) {
                is FlowException -> e.kind
                is VcdiffDecoder.VcdiffFormatException -> FailKind.DECODE
                else -> FailKind.DECODE
            }
            listener.onStateChanged(State.Failed(kind, e.message))
        }
    }

    /** 轮询指定下载直至成功/失败（协程取消即止；PAUSED 等待自动恢复）。 */
    private suspend fun awaitDownload(
        id: Long,
        onTick: (percent: Int, bytes: Long) -> Unit
    ) {
        while (currentCoroutineContext().isActive) {
            val status = downloader.statusOf(id)
            if (status == DownloadManager.STATUS_SUCCESSFUL) return
            if (status == DownloadManager.STATUS_FAILED) {
                // 独立异常类型：下载中断 ≠ 解码失败，UI 文案需分开本地化
                throw FlowException(FailKind.DOWNLOAD, "download id=$id failed")
            }
            val (percent, bytes) = downloader.progress(id)
            onTick(percent, bytes)
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 最终合成 APK 的落点名（FileProvider 可授权安装）。 */
    private fun finalApkFile(manifest: UpdateManifest): File =
        File(workDir, "ApexAgent-v${manifest.versionName}-patched.apk")

    /**
     * 就绪合成产物的预期落点（UI 重入口扫描用 —— 与流水线内 [finalApkFile]
     * 同源同名；存在与否、指纹是否对账由调用方复核）。
     */
    fun readyApkFor(manifest: UpdateManifest): File = finalApkFile(manifest)

    /** 按补丁链变体取全量资产指纹（arm64 链对账 arm64 包）。 */
    private fun assetForVariant(
        manifest: UpdateManifest,
        variant: String
    ): UpdateAsset? {
        val download = manifest.download ?: return null
        return when (variant) {
            "arm64" -> download.arm64 ?: download.universal
            else -> download.universal ?: download.arm64
        }
    }

    /**
     * 补丁段完整性判定（断点续传的门闩）：
     * - 体积对账先行（零成本）：清单带 sizeBytes 时长度不等 = 断段；
     * - SHA-256 全量对账（~50ms/16MB）：防坏段/半段被误认完整；
     * - 旧索引无 SHA：体积对账即放行（与全量包下载同策略）。
     */
    private fun isSegmentComplete(file: File, step: PatchIndex.Entry): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (step.sizeBytes > 0 && file.length() != step.sizeBytes) return false
        return step.sha256.isNullOrBlank() || downloader.verifySha256(file, step.sha256)
    }

    /**
     * 清理旧中间产物：链式合成临时件 / **链外**旧补丁。
     *
     * v1.4.8 持久化修复：**不再删 -patched.apk**（用户辛苦合成的产物）。旧逻辑
     * 开工时删所有 `ApexAgent-v*-patched.apk`，导致用户上次合成的 APK 在启动
     * 新一轮增量时被清 —— 用户想重装只能去 MT 管理器翻。现保留所有合成包，
     * 由关于页「本地更新包」常驻区展示 + 用户手动删。空间不足时空间预检会
     * 提前劝导全量（不会因保留合成包而失败）。
     *
     * 当前链内的 .vcdiff 保留 —— 断点续传资产，成功后由流水线尾部回收。
     */
    private fun cleanStaleIntermediates(chain: PatchIndex.Chain) {
        val chainFiles = chain.steps.map { it.url.substringAfterLast('/') }.toHashSet()
        val files = workDir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            val stale = name.startsWith("ApexAgent-chain-step") ||
                (name.endsWith(".vcdiff") && name !in chainFiles)
            if (stale) runCatching { file.delete() }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 600L
    }

    /** 流水线内部异常 —— 携带失败类别直达 catch 分型（不混入解码异常）。 */
    private class FlowException(
        val kind: FailKind,
        detail: String?
    ) : Exception(detail)
}
