package com.apex.agent.core.tools.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrivilegeLadder（权限阶梯知识库）单元测试。
 *
 * 覆盖：
 * 1. normalize —— 三级规范值 + 任意未知串折叠 NORMAL_SHELL（与引擎
 *    prompt 历史 else 分支口径一致）；
 * 2. infoFor —— 每级 CAN/CANNOT/升级指引的关键内容；
 * 3. promptSection —— heading 保留原始串（既有测试/调用方兼容），正文
 *    规范化；ROOT 无 CANNOT 行；含「不许盲目重试」护栏；
 * 4. briefLine —— 编排线单行简介包含 CAN/CANNOT。
 */
class PrivilegeLadderTest {

    // ── normalize ─────────────────────────────────────────────

    @Test
    fun `normalize maps known levels and folds unknown to normal shell`() {
        assertEquals(PrivilegeLadder.LEVEL_ROOT, PrivilegeLadder.normalize("ROOT"))
        assertEquals(PrivilegeLadder.LEVEL_SHIZUKU, PrivilegeLadder.normalize("SHIZUKU"))
        assertEquals(
            PrivilegeLadder.LEVEL_NORMAL_SHELL,
            PrivilegeLadder.normalize("NORMAL_SHELL")
        )
        // 历史调用方可能传 "NORMAL"（见 RolePromptTest）——未知值折叠
        assertEquals(
            PrivilegeLadder.LEVEL_NORMAL_SHELL,
            PrivilegeLadder.normalize("NORMAL")
        )
        // 未知/小写非标准串同样折叠
        assertEquals(
            PrivilegeLadder.LEVEL_NORMAL_SHELL,
            PrivilegeLadder.normalize("shizuku-not-running")
        )
        // 大小写不敏感
        assertEquals(PrivilegeLadder.LEVEL_ROOT, PrivilegeLadder.normalize("root"))
        assertEquals(PrivilegeLadder.LEVEL_SHIZUKU, PrivilegeLadder.normalize(" Shizuku "))
    }

    @Test
    fun `uppercase and blank variants fold safely`() {
        assertEquals(PrivilegeLadder.LEVEL_ROOT, PrivilegeLadder.normalize(" root "))
        assertEquals(
            PrivilegeLadder.LEVEL_NORMAL_SHELL,
            PrivilegeLadder.normalize("")
        )
        assertEquals(
            PrivilegeLadder.LEVEL_NORMAL_SHELL,
            PrivilegeLadder.normalize("SUPERUSER")
        )
    }

    // ── infoFor ───────────────────────────────────────────────

    @Test
    fun `shizuku level knows adb powers and root walls`() {
        val info = PrivilegeLadder.infoFor("SHIZUKU")
        assertEquals(PrivilegeLadder.LEVEL_SHIZUKU, info.level)
        assertTrue(info.can.any { it.contains("pm install") })
        assertTrue(info.can.any { it.contains("input") })
        assertTrue(info.cannot.any { it.contains("/system") && it.contains("ROOT") })
        assertTrue(info.upgradeHint.contains("ROOT"))
    }

    @Test
    fun `normal shell level recommends shizuku upgrade`() {
        val info = PrivilegeLadder.infoFor("NORMAL")
        assertEquals(PrivilegeLadder.LEVEL_NORMAL_SHELL, info.level)
        // 升级指引：Shizuku + 安装地址
        assertTrue(info.upgradeHint.contains("Shizuku"))
        assertTrue(info.upgradeHint.contains("https://shizuku.rikka.app/"))
        assertTrue(info.cannot.any { it.contains("SHIZUKU") })
    }

    @Test
    fun `root level has no cannot lines`() {
        val info = PrivilegeLadder.infoFor("ROOT")
        assertTrue(info.cannot.isEmpty())
        assertTrue(info.can.any { it.contains("su") })
        assertTrue(info.upgradeHint.contains("top"))
    }

    // ── promptSection ─────────────────────────────────────────

    @Test
    fun `prompt heading keeps raw level string for compatibility`() {
        val section = PrivilegeLadder.promptSection("NORMAL")
        // 既有断言（RolePromptTest）：heading 用原始串
        assertTrue(section.startsWith("## Device Privilege Level: NORMAL"))
        // 正文按规范化等级渲染阶梯
        assertTrue(section.contains("NORMAL_SHELL < SHIZUKU < ROOT"))
    }

    @Test
    fun `prompt section contains ladder, lists, upgrade and no-blind-retry guard`() {
        listOf("ROOT", "SHIZUKU", "NORMAL_SHELL").forEach { level ->
            val section = PrivilegeLadder.promptSection(level)
            assertTrue("ladder line missing for $level", section.contains("NORMAL_SHELL < SHIZUKU < ROOT"))
            assertTrue("CAN list missing for $level", section.contains("You CAN:"))
            assertTrue("upgrade hint missing for $level", section.contains("Upgrade path:"))
            assertTrue(
                "no-blind-retry guard missing for $level",
                section.contains("Never blind-retry")
            )
        }
    }

    @Test
    fun `root prompt omits cannot line while others render it`() {
        val root = PrivilegeLadder.promptSection("ROOT")
        assertFalse(root.contains("You CANNOT:"))
        val shizuku = PrivilegeLadder.promptSection("SHIZUKU")
        assertTrue(shizuku.contains("You CANNOT:"))
        val normal = PrivilegeLadder.promptSection("NORMAL_SHELL")
        assertTrue(normal.contains("You CANNOT:"))
    }

    // ── briefLine ─────────────────────────────────────────────

    @Test
    fun `brief line is compact with can and cannot`() {
        val brief = PrivilegeLadder.briefLine("SHIZUKU")
        assertTrue(brief.startsWith("Privilege: SHIZUKU"))
        assertTrue(brief.contains("CAN "))
        assertTrue(brief.contains("CANNOT "))
        // ROOT 无 CANNOT → 用 no permission walls 兜底
        val rootBrief = PrivilegeLadder.briefLine("ROOT")
        assertTrue(rootBrief.contains("no permission walls"))
    }
}
