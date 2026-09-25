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
        assertEquals("SKEL-PROFILE", File(dir, ".profile").readText())
        // skel 内容保留在前；受管工具链块追加在后（注入不覆盖用户内容）
        val bashrc = File(dir, ".bashrc").readText()
        assertTrue(bashrc.startsWith("SKEL-RC"))
        assertTrue(bashrc.contains(GuestUserHome.BLOCK_END_MARKER))
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
    fun `non-empty home is never re-seeded but gets managed toolchain block`() {
        val rootfs = rootfsWithSkel(".bashrc" to "SKEL-RC")
        val homeDir = File(tmp.root, "home")
        homeDir.mkdirs()
        File(homeDir, "user-data.txt").writeText("mine")

        val dir = GuestUserHome(homeDir).ensureReady(rootfs).getOrThrow()

        // 用户文件保留；skel 不播种（home 非空）。
        assertEquals("mine", File(dir, "user-data.txt").readText())
        // 受管工具链块属于基础设施（标记对包裹 + 幂等），非播种 ——
        // 无 .bashrc 的存量 home 也补一份最小 .bashrc + 块，
        // 否则旧用户装完 JDK 依然没有 JAVA_HOME。
        val bashrc = File(dir, ".bashrc")
        assertTrue(bashrc.exists())
        assertTrue(bashrc.readText().contains(GuestUserHome.BLOCK_END_MARKER))
        assertTrue(bashrc.readText().contains("mine").not())  // 只动 .bashrc
    }

    @Test
    fun `ensureReady is idempotent for user-edited bashrc`() {
        val rootfs = rootfsWithSkel(".bashrc" to "SKEL-RC")
        val home = GuestUserHome(File(tmp.root, "home"))
        home.ensureReady(rootfs).getOrThrow()

        // 用户编辑 .bashrc（含删除受管块）：内容永不丢失/覆盖，
        // 受管块最多被重新追加一次（基础设施保证，见下个用例）。
        File(File(tmp.root, "home"), ".bashrc").writeText("USER-EDITED")
        home.ensureReady(rootfs).getOrThrow()
        val after = File(File(tmp.root, "home"), ".bashrc").readText()
        assertTrue(after.startsWith("USER-EDITED"))
    }

    // ═══ 工具链环境块（Ubuntu 环境探索验证 §7 修复）═══

    @Test
    fun `toolchain env block is injected into seeded home and exports JAVA_HOME`() {
        val rootfs = tmp.newFolder("rootfs") // 无 skel → 最小 bashrc
        val home = GuestUserHome(File(tmp.root, "home"))
        home.ensureReady(rootfs).getOrThrow()

        val content = File(File(tmp.root, "home"), ".bashrc").readText()
        // 块内容：动态 -d 探测 + -z 守卫 + PATH 去重追加
        assertTrue(content.contains("/usr/lib/jvm/default-java"))
        assertTrue(content.contains("JAVA_HOME"))
        assertTrue(content.contains("ANDROID_HOME"))
        assertTrue(content.contains("ANDROID_SDK_ROOT"))
        assertTrue(content.contains("GOROOT"))
        assertTrue(content.contains("-z \""))       // 用户已设置的变量不覆盖
        assertTrue(content.contains("case \":"))     // PATH 追加去重守卫
        // bash 结构自检：if/fi、case/esac 配对（块的语法完整性锚点；
        // GOROOT 的 case/esac 与语句同行内联，用非锚定计数）
        val block = content.substringAfter(">>> apex-toolchain-env")
        assertEquals(3, Regex("(?m)^\\s*if ").findAll(block).count())
        assertEquals(3, Regex("(?m)^\\s*fi$").findAll(block).count())
        assertEquals(2, block.split("case \":").size - 1)
        assertEquals(2, block.split("esac").size - 1)
    }

    @Test
    fun `ensureToolchainEnvBlock is idempotent - no duplicate blocks`() {
        val homeDir = File(tmp.root, "home")
        homeDir.mkdirs()
        val home = GuestUserHome(homeDir)

        home.ensureToolchainEnvBlock()
        val once = File(homeDir, ".bashrc").readText()
        // 锚点用开始标记（块内的结束标记行含同一字符串，出现两次/块）
        assertEquals(1, once.split(">>> apex-toolchain-env").size - 1)

        home.ensureToolchainEnvBlock()
        home.ensureToolchainEnvBlock()
        val thrice = File(homeDir, ".bashrc").readText()
        assertEquals(once, thrice)  // 完全不变（字节级幂等）
    }

    @Test
    fun `existing legacy bashrc gets block appended without losing user content`() {
        val homeDir = File(tmp.root, "home")
        homeDir.mkdirs()
        val legacy = File(homeDir, ".bashrc")
        legacy.writeText("# legacy user rc\nexport MY_VAR=1\n")

        GuestUserHome(homeDir).ensureToolchainEnvBlock()

        val content = legacy.readText()
        assertTrue(content.startsWith("# legacy user rc"))
        assertTrue(content.contains("MY_VAR=1"))
        assertTrue(content.contains(GuestUserHome.BLOCK_END_MARKER))
        // 用户内容在前、受管块在后
        assertTrue(content.indexOf("MY_VAR=1") < content.indexOf(GuestUserHome.BLOCK_END_MARKER))
    }
}
