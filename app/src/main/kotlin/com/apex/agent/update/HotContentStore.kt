package com.apex.agent.update

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  HotContentStore —— 热更内容落位与读取（overlay 层唯一真相源）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 目录布局（全在应用私有 `filesDir`，任意进程可读写，无需任何权限）：
 *
 * ```
 * filesDir/hot/
 *   active/                 ← 当前生效的 overlay（原子换入）
 *     skills 目录下的 .json  ←   技能清单（apex-skill-v1）
 *     mcp_catalog 目录下的 .json ← MCP 精选目录分类文件
 *   staging-<nanoTime>/     ← 解压暂存（应用成功即换名，失败即整体删除）
 *   active-old/             ← 换名过渡位（换入成功后立即清理）
 * ```
 *
 * ## 原子换位（仓库纪律：tmp + renameTo，rename 失败直写目标兜底）
 *
 * `apply` = staging → active 的目录级换名三步舞：
 * 1. `active` 存在则先 renameTo `active-old`（让出目标名）；
 * 2. `staging` renameTo `active`（瞬时完成，读者要么见旧要么见新，无中间态）；
 * 3. 清理 `active-old`。第 2 步 rename 失败（极端：跨挂载点/被占用）时，
 *    退化为 `copyRecursively` 直写 + 事后清理 —— 内容正确性优先于原子性。
 *
 * ## 退役（supersede）
 *
 * APK 路径追平（`packageVersionCode >= appliedTarget`）后，新 APK 的
 * assets 已携带 ≥ 热更内容 —— overlay 继续挂着只会让「数据层版本」虚高，
 * 掩盖真正需要安装的更新。所有读取入口先过 [ensureRetired]，追平即：
 * 清 `active` 目录 + 清应用记录（prefs 归零）。
 *
 * ## 测试友好
 *
 * 构造只依赖 `filesDir` + [HotPrefs] + `packageVersionCode` —— 不直接持
 * Context；prefs 抽接口，纯 JVM 单测注入内存 fake（TemporaryFolder 供目录）。
 */
class HotContentStore(
    private val filesDir: File,
    private val prefs: HotPrefs,
    private val packageVersionCode: Int
) {

    /** 应用状态持久化 —— 抽接口供纯 JVM 单测注入内存实现。 */
    interface HotPrefs {
        fun appliedTargetVersionCode(): Int
        fun appliedTargetVersionName(): String?
        fun appliedTag(): String?
        fun appliedPackageSha256(): String?
        fun recordApplied(
            targetVersionCode: Int,
            targetVersionName: String,
            tag: String?,
            packageSha256: String?
        )
        fun clearApplied()
    }

    /** SharedPreferences 实现（生产用；文件 `apex_hot_update_prefs`）。 */
    class SharedPrefs(context: Context) : HotPrefs {
        private val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun appliedTargetVersionCode(): Int =
            sp.getInt(KEY_TARGET_VCODE, 0)

        override fun appliedTargetVersionName(): String? =
            sp.getString(KEY_TARGET_VNAME, null)

        override fun appliedTag(): String? = sp.getString(KEY_TAG, null)

        override fun appliedPackageSha256(): String? =
            sp.getString(KEY_PKG_SHA, null)

        override fun recordApplied(
            targetVersionCode: Int,
            targetVersionName: String,
            tag: String?,
            packageSha256: String?
        ) {
            sp.edit()
                .putInt(KEY_TARGET_VCODE, targetVersionCode)
                .putString(KEY_TARGET_VNAME, targetVersionName)
                .putString(KEY_TAG, tag)
                .putString(KEY_PKG_SHA, packageSha256)
                .apply()
        }

        override fun clearApplied() {
            sp.edit().clear().apply()
        }

        private companion object {
            const val PREFS = "apex_hot_update_prefs"
            const val KEY_TARGET_VCODE = "applied_target_vcode"
            const val KEY_TARGET_VNAME = "applied_target_vname"
            const val KEY_TAG = "applied_tag"
            const val KEY_PKG_SHA = "applied_package_sha256"
        }
    }

    private val hotRoot: File get() = File(filesDir, "hot")
    private val activeDir: File get() = File(hotRoot, "active")

    // ═══ 状态查询 ═══════════════════════════════════════════════════════

    /** 已应用热更的目标 versionCode（0 = 从未热更）。读取前先退役检查。 */
    fun appliedTargetVersionCode(): Int {
        ensureRetired()
        return prefs.appliedTargetVersionCode()
    }

    /** 已应用热更的目标 versionName（null = 从未；展示层标注用）。 */
    fun appliedTargetVersionName(): String? {
        ensureRetired()
        return prefs.appliedTargetVersionName()
    }

    /** 已应用热更包的 ZIP 指纹（null = 未记录 —— 应急重发判重基准）。 */
    fun appliedPackageSha256(): String? {
        ensureRetired()
        return prefs.appliedPackageSha256()
    }

    /** 有效版本口径（max(包, appliedTarget)）—— 更新检查的比对基准。 */
    fun effectiveVersionCode(): Int =
        HotUpdatePolicy.effectiveVersionCode(
            packageVersionCode, appliedTargetVersionCode()
        )

    /** 当前是否有生效中的 overlay 内容。 */
    fun hasActiveContent(): Boolean {
        ensureRetired()
        return activeDir.isDirectory && activeDir.listFiles()?.isNotEmpty() == true
    }

    // ═══ overlay 读取（数据层消费方入口）══════════════════════════════

    /**
     * 生效中的技能清单原始 JSON 列表 —— 喂给
     * `SkillRegistry.installBundled`（版本化幂等升级，保持用户启停态）。
     *
     * 防御式 IO：单文件损坏跳过并留痕，不向上抛（仓库纪律）。
     */
    fun activeSkillManifests(): List<String> {
        ensureRetired()
        val dir = File(activeDir, "skills")
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { file.readText() }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "HotContentStore",
                        "热更技能清单读取失败（跳过）：${file.name} — ${it.message}"
                    )
                }
                .getOrNull()
        }
    }

    /**
     * 生效中的技能 id 集 —— SkillModule 的 pruneStaleBundled 白名单合并项
     * （防「热更装上的技能被下次启动的 assets 白名单反向清理」）。
     */
    fun activeSkillIds(): Set<String> {
        ensureRetired()
        val dir = File(activeDir, "skills")
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.toSet() ?: emptySet()
    }

    /**
     * 生效中的 MCP 目录分类文件 —— 市场页目录层的**全量替换集**
     * （热更包携带目标版完整目录快照；存在 overlay 时 assets 目录整体让位，
     * 支持上游删除分类文件的场景）。
     */
    fun activeCatalogFiles(): List<File> {
        ensureRetired()
        val dir = File(activeDir, "mcp_catalog")
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    // ═══ 应用与退役 ═════════════════════════════════════════════════════

    /**
     * 把已校验的解包目录换位为 active overlay，并记录应用状态。
     *
     * 调用前提：staging 内容已通过 [HotPackage.Manifest] 逐文件指纹复核
     * （引擎负责）—— 本方法只做换位与记账，不再碰内容。
     *
     * @param stagingDir 引擎解压并通过复核的暂存目录（换入后归 store 所有）
     * @param manifest 热更包内清单（target 与 version.json 对账过）
     * @param tag 发布 tag（记账展示用）
     * @param packageSha256 包 ZIP 指纹（version.json hot 档；应急重发的判重基准，
     *   可空 = 清单未携带 —— 同版本重发将无法判重，门控保守跳过）
     * @return 换位成功与否（失败时 staging 保留由调用方清理）
     */
    fun applyPackage(
        stagingDir: File,
        manifest: HotPackage.Manifest,
        tag: String?,
        packageSha256: String?
    ): Boolean {
        hotRoot.mkdirs()
        val old = File(hotRoot, "active-old")
        runCatching { old.deleteRecursively() }
        // 三步换名舞：让位 → 换入 → 清理；rename 失败退化直写兜底
        val activeExists = activeDir.exists()
        val vacated = !activeExists || activeDir.renameTo(old)
        val movedIn = if (vacated) {
            if (stagingDir.renameTo(activeDir)) {
                true
            } else {
                // rename 失败兜底：直写目标（copyRecursively 对不存在源返回
                // true 的空真陷阱在此显式堵死 —— 源必须是目录才谈得上拷贝）
                val copied = stagingDir.isDirectory && runCatching {
                    stagingDir.copyRecursively(activeDir, overwrite = true)
                }.getOrDefault(false)
                if (copied) runCatching { stagingDir.deleteRecursively() }
                copied
            }
        } else false
        if (!movedIn) {
            // 换入失败：旧内容原样换回（若曾让位），调用方按失败处理
            if (vacated && old.exists()) runCatching { old.renameTo(activeDir) }
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "HotContentStore",
                "热更 overlay 换位失败：${stagingDir.name}"
            )
            return false
        }
        runCatching { old.deleteRecursively() }
        prefs.recordApplied(
            manifest.targetVersionCode, manifest.targetVersionName, tag, packageSha256
        )
        AppLogger.instance.info(
            LogCategory.SYSTEM, "HotContentStore",
            "热更 overlay 已生效：target v${manifest.targetVersionName}" +
                "(${manifest.targetVersionCode}) · ${manifest.entries.size} 文件"
        )
        return true
    }

    /**
     * APK 追平退役 —— 读取入口幂等调用（见类 KDoc「退役」节）。
     * 清理失败只留痕不抛（下次读取重试）。
     */
    fun ensureRetired() {
        if (!HotUpdatePolicy.isSuperseded(
                packageVersionCode, prefs.appliedTargetVersionCode()
            )
        ) {
            return
        }
        runCatching {
            hotRoot.deleteRecursively()
            prefs.clearApplied()
        }.onFailure {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "HotContentStore",
                "热更退役清理失败（下次重试）：${it.message}"
            )
        }
    }

    /** 暂存目录工厂（引擎每次应用前新建，成功换名/失败整体删除）。 */
    fun newStagingDir(): File = File(hotRoot, "staging-${System.nanoTime()}")

    /** 残留暂存清理（引擎开工前扫一遍 staging-* 与 active-old）。 */
    fun cleanStaleStaging() {
        val files = hotRoot.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith("staging-") || file.name == "active-old") {
                runCatching { file.deleteRecursively() }
            }
        }
    }
}
