package com.apex.agent.platform.terminal.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T75: GuestUserHome 单元测试 —— 播种（skel / 最小 bashrc 兜底）/幂等/不覆盖。
 */
class GuestUserHomeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfsWithSkel(vararg skelFiles: Pair<String, String>): File {
        val rootfs = tmp.newFolder("rootfs")
        val skel = File(rootfs, "etc/skel")
        skel.mkdirs()
        for ((name, content) in skelFiles) {
            File(skel, name).writeText(content)
        }
        return rootfs
    }

    @Test
    fun `empty home is seeded from rootfs skel`() {
        val rootfs = rootfsWithSkel(".bashrc" to "SKEL-RC", ".profile" to "SKEL-PROFILE")
        val home = GuestUserHome(File(tmp.root, "home"))

        val dir = home.ensureReady(rootfs).getOrThrow()

        assertEquals(File(tmp.root, "home"), dir)
        assertEquals("SKEL-RC", File(dir, ".bashrc").readText())
        assertEquals("SKEL-PROFILE", File(dir, ".profile").readText())
    }

    @Test
    fun `skel subdirectories are seeded recursively`() {
        val rootfs = tmp.newFolder("rootfs")
        val skel = File(rootfs, "etc/skel")
        File(skel, ".config").mkdirs()
        File(skel, ".config/htop").mkdirs()
        File(skel, ".config/htop/htoprc").writeText("htop-config")
        val home = GuestUserHome(File(tmp.root, "home"))

        val dir = home.ensureReady(rootfs).getOrThrow()

        assertEquals("htop-config", File(dir, ".config/htop/htoprc").readText())
    }

    @Test
    fun `rootfs without skel falls back to minimal bashrc`() {
        val rootfs = tmp.newFolder("rootfs") // 无 etc/skel
        val home = GuestUserHome(File(tmp.root, "home"))

        val dir = home.ensureReady(rootfs).getOrThrow()

        val bashrc = File(dir, ".bashrc")
        assertTrue(bashrc.exists())
        val content = bashrc.readText()
        assertTrue(content.contains("PS1"))
        assertTrue(content.contains("alias ll"))
        // 交互守卫（非交互 shell 提前 return）
        assertTrue(content.contains("*i*"))
    }

    @Test
    fun `empty skel directory also falls back to minimal bashrc`() {
        val rootfs = rootfsWithSkel() // skel 存在但为空
        val home = GuestUserHome(File(tmp.root, "home"))

        val dir = home.ensureReady(rootfs).getOrThrow()
        assertTrue(File(dir, ".bashrc").exists())
    }

    @Test
    fun `non-empty home is never touched`() {
        val rootfs = rootfsWithSkel(".bashrc" to "SKEL-RC")
        val homeDir = File(tmp.root, "home")
        homeDir.mkdirs()
        File(homeDir, "user-data.txt").writeText("mine")

        val dir = GuestUserHome(homeDir).ensureReady(rootfs).getOrThrow()

        // 用户文件保留；skel 不播种（home 非空）
        assertEquals("mine", File(dir, "user-data.txt").readText())
        assertFalse(File(dir, ".bashrc").exists())
    }

    @Test
    fun `ensureReady is idempotent`() {
        val rootfs = rootfsWithSkel(".bashrc" to "SKEL-RC")
        val home = GuestUserHome(File(tmp.root, "home"))
        home.ensureReady(rootfs).getOrThrow()

        // 第二次不重复播种（.bashrc 不被覆盖 —— 内容不变即可证明）
        File(File(tmp.root, "home"), ".bashrc").writeText("USER-EDITED")
        home.ensureReady(rootfs).getOrThrow()
        assertEquals("USER-EDITED", File(File(tmp.root, "home"), ".bashrc").readText())
    }
}
