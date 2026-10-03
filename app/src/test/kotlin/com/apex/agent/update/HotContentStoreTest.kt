package com.apex.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [HotContentStore] 单元测试 —— overlay 落位/读取/退役生命周期全覆盖。
 *
 * 覆盖：applyPackage 原子换位 + 记账 / 技能清单与 id 集读取 / 目录文件读取 /
 * 有效版本口径 / APK 追平退役（清 overlay + 清记账）/ 暂存残留清理 /
 * 换入失败回滚（staging 消失 → 旧内容换回，防「让位后丢内容」）。
 *
 * prefs 用手写内存 fake（仓库纪律：无 mock 框架）；[Env] 让同一 filesDir +
 * 同一 prefs 在不同 packageVersionCode 下复用（模拟「热更后重装新 APK」）。
 */
class HotContentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内存版 HotPrefs（真实持久化语义的 fake：clear 全清）。 */
    private class FakePrefs : HotContentStore.HotPrefs {
        var targetVcode = 0
        var targetVname: String? = null
        var tag: String? = null
        var pkgSha: String? = null

        override fun appliedTargetVersionCode(): Int = targetVcode
        override fun appliedTargetVersionName(): String? = targetVname
        override fun appliedTag(): String? = tag
        override fun appliedPackageSha256(): String? = pkgSha

        override fun recordApplied(
            targetVersionCode: Int,
            targetVersionName: String,
            tag: String?,
            packageSha256: String?
        ) {
            targetVcode = targetVersionCode
            targetVname = targetVersionName
            this.tag = tag
            pkgSha = packageSha256
        }

        override fun clearApplied() {
            targetVcode = 0
            targetVname = null
            tag = null
            pkgSha = null
        }
    }

    /** 同一数据目录 + 同一记账在不同包 versionCode 下的复用环境。 */
    private inner class Env {
        val prefs = FakePrefs()
        val filesDir: File = tmp.newFolder()
        fun store(pkgVcode: Int): HotContentStore =
            HotContentStore(filesDir, prefs, pkgVcode)
    }

    /** 造一个「已解压待换位」的 staging 目录（引擎复核后的形态）。 */
    private fun stagingWith(
        root: File,
        skills: Map<String, String> = emptyMap(),
        catalogs: Set<String> = emptySet()
    ): File {
        val stage = File(root, "staging-test").apply { mkdirs() }
        skills.forEach { (name, body) ->
            File(stage, "skills").mkdirs()
            File(File(stage, "skills"), "$name.json").writeText(body)
        }
        if (catalogs.isNotEmpty()) {
            File(stage, "mcp_catalog").mkdirs()
            catalogs.forEach { File(File(stage, "mcp_catalog"), "$it.json").writeText("{}") }
        }
        File(stage, HotPackage.MANIFEST_NAME).writeText(
            """{"schema": "apex-hot-v1", "targetVersionCode": 46,
                "targetVersionName": "1.4.5.3", "entries": []}"""
        )
        return stage
    }

    private val manifest46 = HotPackage.Manifest(
        schema = HotPackage.SCHEMA,
        targetVersionCode = 46,
        targetVersionName = "1.4.5.3",
        entries = listOf(HotPackage.Entry("skills/a.json", "00"))
    )

    private companion object {
        val PKG_SHA_A = "aa".repeat(32)
        val PKG_SHA_B = "bb".repeat(32)
    }

    // ── 应用与读取 ───────────────────────────────────────────────────────

    @Test
    fun `applyPackage swaps staging into active and records state`() {
        val env = Env()
        val store = env.store(45)
        val stage = stagingWith(
            env.filesDir, skills = mapOf("api-design" to """{"id":"api-design"}"""),
            catalogs = setOf("browser")
        )

        assertTrue(store.applyPackage(stage, manifest46, "v1.4.5.3", PKG_SHA_A))

        assertEquals(46, env.prefs.targetVcode)
        assertEquals("1.4.5.3", env.prefs.targetVname)
        assertEquals("v1.4.5.3", env.prefs.tag)
        assertEquals(PKG_SHA_A, env.prefs.pkgSha)
        // overlay 读取：技能清单 / id 集 / 目录文件
        assertEquals(listOf("""{"id":"api-design"}"""), store.activeSkillManifests())
        assertEquals(setOf("api-design"), store.activeSkillIds())
        assertEquals(1, store.activeCatalogFiles().size)
        assertTrue(store.hasActiveContent())
        // staging 已换名为 active —— 原路径消失
        assertFalse(stage.exists())
    }

    @Test
    fun `emergency republish overwrites fingerprint at same target`() {
        // 发布仓库应急重发：同 target 新包 → 指纹记账被覆盖（门控据此重推）
        val env = Env()
        val store = env.store(45)
        val first = stagingWith(env.filesDir, skills = mapOf("a" to """{"id":"a"}"""))
        assertTrue(store.applyPackage(first, manifest46, "v1.4.5.3", PKG_SHA_A))
        assertEquals(PKG_SHA_A, store.appliedPackageSha256())

        val revised = stagingWith(env.filesDir, skills = mapOf("b" to """{"id":"b"}"""))
        assertTrue(store.applyPackage(revised, manifest46, "v1.4.5.3", PKG_SHA_B))
        assertEquals(PKG_SHA_B, store.appliedPackageSha256())
        assertEquals(setOf("b"), store.activeSkillIds())
    }

    @Test
    fun `applyPackage replaces previous active content`() {
        val env = Env()
        val store = env.store(45)
        val first = stagingWith(env.filesDir, skills = mapOf("old" to """{"id":"old"}"""))
        assertTrue(store.applyPackage(first, manifest46, null, null))
        assertEquals(setOf("old"), store.activeSkillIds())

        val second = stagingWith(
            env.filesDir, skills = mapOf("new" to """{"id":"new"}"""), catalogs = setOf("git")
        )
        assertTrue(store.applyPackage(second, manifest46, null, null))
        // 整体替换语义：active 只见新内容，无残留
        assertEquals(setOf("new"), store.activeSkillIds())
        assertEquals(1, store.activeCatalogFiles().size)
        assertEquals("git.json", store.activeCatalogFiles().first().name)
    }

    @Test
    fun `readers are empty before any apply`() {
        val store = Env().store(45)
        assertTrue(store.activeSkillManifests().isEmpty())
        assertTrue(store.activeSkillIds().isEmpty())
        assertTrue(store.activeCatalogFiles().isEmpty())
        assertFalse(store.hasActiveContent())
        assertEquals(45, store.effectiveVersionCode())
        assertNull(store.appliedTargetVersionName())
    }

    @Test
    fun `corrupted skill manifest is skipped defensively`() {
        val env = Env()
        val store = env.store(45)
        val stage = stagingWith(env.filesDir, skills = mapOf("good" to """{"id":"good"}"""))
        assertTrue(store.applyPackage(stage, manifest46, null, null))
        // 模拟半写状态：active/skills 里塞一个损坏 JSON。activeSkillManifests 的
        // 契约是**防御式 IO**（读得动的都返回；JSON 合法性由下游 installBundled
        // 的单条隔离兑底）—— 损坏内容照常读出，计数不变
        val activeSkills = File(File(env.filesDir, "hot"), "active/skills")
        File(activeSkills, "broken.json").writeText("""{not json""")

        val manifests = store.activeSkillManifests()
        assertEquals(2, manifests.size)
        // id 集仍按文件名计（清理白名单语义），包含损坏条目
        assertEquals(setOf("good", "broken"), store.activeSkillIds())
    }

    // ── 有效版本口径 ─────────────────────────────────────────────────────

    @Test
    fun `effective version reflects applied target`() {
        val env = Env()
        val store = env.store(45)
        assertEquals(45, store.effectiveVersionCode())
        env.prefs.recordApplied(46, "1.4.5.3", "v1.4.5.3", null)
        assertEquals(46, store.effectiveVersionCode())
        assertEquals("1.4.5.3", store.appliedTargetVersionName())
    }

    // ── 退役（APK 追平）──────────────────────────────────────────────────

    @Test
    fun `retires overlay when package version catches up`() {
        val env = Env()
        val store = env.store(45)
        val stage = stagingWith(env.filesDir, skills = mapOf("a" to """{"id":"a"}"""))
        assertTrue(store.applyPackage(stage, manifest46, "v1.4.5.3", PKG_SHA_A))
        // 包 45 < applied 46 → overlay 继续生效
        assertTrue(store.hasActiveContent())

        // 模拟「重装全量新 APK」：同一数据目录 + 同一记账，包 versionCode 追平
        val afterInstall = env.store(46)
        assertFalse(afterInstall.hasActiveContent()) // 读取入口触发退役
        assertEquals(0, env.prefs.targetVcode)        // 记账清零
        assertNull(env.prefs.targetVname)
        assertEquals(46, afterInstall.effectiveVersionCode())
    }

    @Test
    fun `no retire while package version is behind`() {
        val env = Env()
        val store = env.store(45)
        val stage = stagingWith(env.filesDir, skills = mapOf("a" to """{"id":"a"}"""))
        assertTrue(store.applyPackage(stage, manifest46, "v1.4.5.3", PKG_SHA_A))
        assertTrue(store.hasActiveContent())
        assertEquals(46, store.effectiveVersionCode())
    }

    // ── 换位失败回滚 ─────────────────────────────────────────────────────

    @Test
    fun `applyPackage rolls back when staging vanishes`() {
        val env = Env()
        val store = env.store(45)
        val first = stagingWith(env.filesDir, skills = mapOf("old" to """{"id":"old"}"""))
        assertTrue(store.applyPackage(first, manifest46, null, null))

        // staging 不存在（异常路径）→ 换入失败回滚：旧内容换回，记账不覆盖
        val ghost = File(env.filesDir, "staging-ghost")
        assertFalse(store.applyPackage(ghost, manifest46, null, null))
        assertEquals(setOf("old"), store.activeSkillIds())
        assertEquals(46, env.prefs.targetVcode)
    }

    // ── 暂存清理 ─────────────────────────────────────────────────────────

    @Test
    fun `cleanStaleStaging removes staging and active-old only`() {
        val env = Env()
        val store = env.store(45)
        val stage = stagingWith(env.filesDir, skills = mapOf("a" to """{"id":"a"}"""))
        assertTrue(store.applyPackage(stage, manifest46, null, null))

        // 模拟中断残留：staging-* 与 active-old
        val hotRoot = File(env.filesDir, "hot")
        File(hotRoot, "staging-leftover").apply { mkdirs() }
            .let { File(it, "x.json").writeText("x") }
        File(hotRoot, "active-old").apply { mkdirs() }

        store.cleanStaleStaging()
        val names = hotRoot.listFiles()!!.map { it.name }
        assertFalse(names.any { it.startsWith("staging-") })
        assertFalse(names.contains("active-old"))
        // active 内容不受清理影响
        assertEquals(setOf("a"), store.activeSkillIds())
    }
}
