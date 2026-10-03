package com.apex.agent.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  热更新体系数据模型（hot update pipeline v1）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * ## 背景：为什么需要热更新通道
 *
 * 现有增量链路（vcdiff 补丁 → 合成新 APK → 系统安装器）有一个绕不开的坎：
 * 发布 APK 以 debug 密钥签名（机器本地生成），CI 产物与用户本地构建签名
 * 必然不同 —— 覆盖安装即报「签名冲突」。对分叉构建/本地构建用户，这条
 * 路永远走不通。
 *
 * 而 App 的高频变更（技能 / MCP 目录）本就是**数据驱动 + 运行时加载**：
 * - 技能：SkillRegistry 安装于 `<filesDir>/skills`，版本化幂等升级，
 *   SkillHotReloader 让新工具/新 Prompt 即时生效；
 * - 目录：MCP 精选目录为纯 JSON，市场页按需读取。
 *
 * 于是热更新通道 = **把数据层从 APK 里解耦出来**：CI 判定「本版变更只落
 * 在热载路径」时额外产出一个热更包（ZIP），App 下载 → SHA-256 对账 →
 * 原子落位 `<filesDir>/hot/active` → 释放技能/目录 → 即时生效。
 * **全程零安装器、零签名校验 —— 签名冲突从根上不存在。**
 *
 * ## 版本口径
 *
 * - `packageVersionCode`：PackageManager 报告的已装 APK versionCode（二进制层）；
 * - `appliedTargetVersionCode`：已应用热更包的目标 versionCode（数据层）；
 * - **effectiveVersionCode = max(两者)** —— 更新检查的比对口径。数据层永远
 *   ≥ 二进制层（热更只加数据）；一旦 APK 重装追平（≥ appliedTarget），
 *   热更内容自动退役（新 APK 已随包携带同样数据）。
 *
 * 本文件刻意不触任何 Android API（Build / Context）—— 门控与解析是纯 JVM
 * 逻辑，`app/src/test` 直接可测。
 */

/**
 * version.json 的 `hot` 资产档 —— 热更包（ZIP）的定位与指纹。
 *
 * 由开发仓库 CI 在「data-only 版本」时写入；老客户端 ignoreUnknownKeys
 * 直接忽略，新客户端按 [HotUpdatePolicy.resolve] 判定适用性。
 */
@Serializable
data class HotAsset(
    /**
     * 热更包适用的最低数据层 versionCode。CI 固定取**热通道能力地板**
     * （首个携带热更客户端的 versionCode）：热更包是累积快照（自包含），
     * 对任何热通道客户端皆可应用 —— 签名冲突用户永远无法重装 APK，
     * 基底若随版本推进会把他们永久锁在数据通道之外。
     */
    val baseVersionCode: Int,
    /** 热更包目标 versionCode（== 本清单 versionCode，CI 保证一致）。 */
    val targetVersionCode: Int,
    /** 目标 versionName（展示用；应急重发时形如 `1.4.7-r2`）。 */
    val targetVersionName: String = "",
    override val url: String,
    val sizeBytes: Long = 0L,
    /** 包 ZIP 指纹 —— 应急重发（同 target 新内容）的判重基准。 */
    override val sha256: String? = null
) : UpdateTarget

/**
 * 热更包内清单 `hotmanifest.json` —— schema `apex-hot-v1`。
 *
 * 包内布局（与 APK 热载路径同构）：
 * - `hotmanifest.json` —— 本清单；
 * - `skills` 目录下的 .json 文件 —— 技能清单（apex-skill-v1，与 assets/skills 同构）；
 * - `mcp_catalog` 目录下的 .json 文件 —— MCP 精选目录分类文件。
 *
 * [entries] 逐文件带 SHA-256 —— 外层 ZIP 指纹对账（version.json）之外的第二
 * 道防线：即使 ZIP 被中间人替换为「合法 ZIP + 篡改内容」，逐文件指纹仍会拦截。
 */
object HotPackage {

    /** 包内清单文件名（固定）。 */
    const val MANIFEST_NAME = "hotmanifest.json"

    /** schema 标识（顶层 schema 字段必须等于它才认）。 */
    const val SCHEMA = "apex-hot-v1"

    private val json = Json { ignoreUnknownKeys = true }

    /** 单个包内文件的指纹声明。 */
    @Serializable
    data class Entry(
        /** 相对包根的路径（如 `skills/api-design.json`，正斜杠分隔）。 */
        val path: String,
        val sha256: String
    )

    /** 包内清单模型。 */
    @Serializable
    data class Manifest(
        /** 必填无默认：缺失 schema 的清单直接解码失败（不默许未知来源）。 */
        val schema: String,
        val targetVersionCode: Int,
        val targetVersionName: String = "",
        val entries: List<Entry> = emptyList()
    )

    /**
     * 解析包内清单 —— 防御式：任何形状问题（非 JSON / 缺字段 / schema 不符 /
     * entry 路径非法）一律折叠 null，由调用方判失败。
     *
     * 路径合法性 = 不绝对、无 `..`、无反斜杠（防 zip-slip 的第一道闸；
     * 解压侧 SafeZipExtractor 的 canonical 校验是第二道）。
     */
    fun parse(text: String): Manifest? = runCatching {
        json.decodeFromString<Manifest>(text)
            .takeIf { it.schema == SCHEMA }
            ?.takeIf { manifest ->
                manifest.entries.all { entry -> isSafePath(entry.path) }
            }
    }.getOrNull()

    /** 包内路径合法性（纯函数，测试直测）：必须落在「目录/文件」两段式布局内
     *  （裸文件与空文件名拒绝），且非绝对路径、无上跳、无反斜杠。 */
    fun isSafePath(path: String): Boolean {
        val dir = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        return path.isNotBlank() &&
            !path.startsWith("/") &&
            !path.contains("..") &&
            !path.contains("\\") &&
            dir.isNotEmpty() &&
            dir != path &&
            name.isNotBlank()
    }
}

/**
 * 热更新门控 —— 纯函数决策（版本口径见文件头 KDoc）。
 *
 * 设计为 object + 无 Android 依赖：JUnit 直测，不碰 Robolectric。
 */
object HotUpdatePolicy {

    /**
     * 判定某清单是否对本地提供热更新。
     *
     * 五重门（全过才放行）：
     * 1. `hot.targetVersionCode == manifestVersionCode` —— 清单自洽（防手写
     *    version.json 时 hot 档指向别的版本）；
     * 2. **未应用过**：
     *    - `target > appliedTarget` —— 全新热更包；或
     *    - `target == appliedTarget` 且包 SHA-256 不同 —— **应急修订**
     *      （发布仓库 hotfix 工作流同版本重发热更包，见
     *      Android-Guru-Agent-Release/HOT_UPDATE.md：数据层出了坏内容，
     *      无需 APK 发版即可重发快照，客户端按指纹识别重新应用）；
     *    - `target < appliedTarget` —— 陈旧档，永不回推；
     * 3. `effectiveVersionCode >= hot.baseVersionCode` —— 数据层基底兼容
     *    （effective = max(包 versionCode, appliedTarget)）；
     * 4. URL 非空 —— 防御旧 schema/手写清单缺字段；
     * 5. 同版本重发时清单必须携带指纹（无 sha256 的同版本档无法判重，
     *    保守不推 —— 全新 target 不受此限）。
     *
     * @param hot 清单的 hot 资产档（null = 本版无热更，直接 null）
     * @param manifestVersionCode 清单 versionCode（门 1 的对账基准）
     * @param effectiveVersionCode 本地有效版本（max(包, appliedTarget)）
     * @param appliedTargetVersionCode 已应用热更的目标 versionCode（0=从未）
     * @param appliedPackageSha256 已应用热更包的 ZIP 指纹（null=未记录/未应用）
     */
    fun resolve(
        hot: HotAsset?,
        manifestVersionCode: Int,
        effectiveVersionCode: Int,
        appliedTargetVersionCode: Int,
        appliedPackageSha256: String? = null
    ): HotAsset? = hot?.takeIf {
        it.url.isNotBlank() &&
            it.targetVersionCode == manifestVersionCode &&
            needsApply(it, appliedTargetVersionCode, appliedPackageSha256) &&
            effectiveVersionCode >= it.baseVersionCode
    }

    /** 门 2：全新 target 放行；同 target 凭指纹判重；旧 target 拒绝。 */
    private fun needsApply(
        hot: HotAsset,
        appliedTargetVersionCode: Int,
        appliedPackageSha256: String?
    ): Boolean = when {
        hot.targetVersionCode > appliedTargetVersionCode -> true
        hot.targetVersionCode < appliedTargetVersionCode -> false
        // 同版本重发：有指纹且与已应用的不同才重推（无指纹无法判重，保守跳过）
        else -> !hot.sha256.isNullOrBlank() && hot.sha256 != appliedPackageSha256
    }

    /** 有效版本口径：数据层（appliedTarget）与二进制层（packageVcode）取大。 */
    fun effectiveVersionCode(
        packageVersionCode: Int,
        appliedTargetVersionCode: Int
    ): Int = maxOf(packageVersionCode, appliedTargetVersionCode)

    /**
     * APK 追平判定：新装 APK 的 versionCode ≥ appliedTarget 时，热更内容
     * 已随包携带（或更新），overlay 该退役 —— 返回 true 由 store 执行清理。
     */
    fun isSuperseded(packageVersionCode: Int, appliedTargetVersionCode: Int): Boolean =
        appliedTargetVersionCode > 0 && packageVersionCode >= appliedTargetVersionCode
}
