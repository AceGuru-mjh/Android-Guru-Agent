package com.apex.agent.platform.terminal.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T84：UbuntuAptPackageManager.parseBatchInstalledStatus 纯函数解析测试。
 *
 * 输入形状 = `dpkg-query -W -f='${binary:Package}\t${Provides}\t${db:Status-Abbrev}'` 真实输出：
 *   `gawk\tawk\tii  `（已装实体 + Provides 虚包 awk）
 *   `bash\t\tii  `（已装、无 provides）
 *   `ripgrep\t\tun  `（已知未装）
 *   `vim\t\trc  `（卸载留配置 —— 不算已装）
 *   未知/虚包名触发 stderr warning + 退出码 1，但 stdout 仍含其余记录（部分输出解析）。
 * 消费方：完整 rootfs 的离线 bootstrap 短路 + install 磁盘预检真实缺失数。
 */
class UbuntuAptBatchStatusParsingTest {

    private fun parse(stdout: String, packages: List<String>) =
        UbuntuAptPackageManager.parseBatchInstalledStatus(stdout, packages)

    @Test fun `all installed maps to true`() {
        val out = "bash\t\tii  1:5.2pl15-2\n" +
            "ca-certificates\t\tii  1\n" +
            "curl\t\tii  1\n"
        val r = parse(out, listOf("bash", "ca-certificates", "curl"))
        assertTrue(r.values.all { it })
        assertEquals(3, r.size)
    }

    @Test fun `virtual package provided by installed entity counts as installed`() {
        // essential 清单含虚包 awk（由 gawk Provides）—— dpkg-query 按包名查不到它
        val out = "gawk\tawk\tii  1:5.2pl15\n"
        val r = parse(out, listOf("awk", "gawk"))
        assertTrue("virtual 'awk' must resolve via gawk's Provides", r["awk"]!!)
        assertTrue(r["gawk"]!!)
    }

    @Test fun `versioned provides fragments are filtered`() {
        // 版本化 provides 形如 "awk (= 1:5.2)" —— "(=" 与 "1:5.2)" 不是包名形状
        val out = "gawk\tawk (= 1:5.2pl15)\tii  \n"
        val r = parse(out, listOf("awk"))
        assertTrue("versioned provides token must still resolve 'awk'", r["awk"]!!)
    }

    @Test fun `un and rc statuses are not installed`() {
        val out = "bash\t\tii  1\n" +
            "ripgrep\t\tun  <无>\n" +
            "vim\t\trc  2:9.1\n"
        val r = parse(out, listOf("bash", "ripgrep", "vim"))
        assertTrue(r["bash"]!!)
        assertFalse("un (known not installed) must be false", r["ripgrep"]!!)
        assertFalse("rc (removed, config remains) must be false", r["vim"]!!)
    }

    @Test fun `package missing from partial output maps to false`() {
        // dpkg-query 对未知/虚包名：stderr warning + 退出码 1，stdout 仍含其余记录
        val out = "bash\t\tii  1\n"
        val r = parse(out, listOf("bash", "git"))
        assertTrue(r["bash"]!!)
        assertFalse("package absent from dpkg-query output must be false", r["git"]!!)
    }

    @Test fun `provides of NOT-installed package does not count`() {
        val out = "gawk\tawk\tun  <无>\n"
        val r = parse(out, listOf("awk"))
        assertFalse("provides of uninstalled entity must not count", r["awk"]!!)
    }

    @Test fun `empty stdout means nothing installed`() {
        val r = parse("", listOf("bash", "git"))
        assertTrue(r.values.none { it })
    }

    @Test fun `garbage lines are skipped not fatal`() {
        val out = "dpkg-query: warning: parsing package record\n" +
            " \n" +
            "bash\t\tii  1\n" +
            "no-tab-line\n"
        val r = parse(out, listOf("bash"))
        assertTrue(r["bash"]!!)
    }

    @Test fun `empty request yields empty map`() {
        assertTrue(parse("bash\t\tii 1", emptyList()).isEmpty())
    }
}
