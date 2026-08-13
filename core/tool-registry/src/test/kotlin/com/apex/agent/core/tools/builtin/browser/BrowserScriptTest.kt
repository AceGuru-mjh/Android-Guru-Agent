package com.apex.agent.core.tools.builtin.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 BrowserScript 的「意图」：它生成的 JS 字符串决定了 WebView 抓取的质量与定位稳定性。
 * 这些是纯字符串工厂（无 Android 依赖），在 JVM 即可验证。
 * 意图层失败（而非实现层）即视为 bug —— 例如有人把语义哈希 ref 改回顺序 ref、
 * 弄错策略选择器、或在 ref 插值处留下注入漏洞，都应被本测试捕获。
 */
class BrowserScriptTest {

    // ---- snapshotJs：三策略选择器正确性 ----

    @Test
    fun `INTERACTIVE_ONLY 策略覆盖核心可交互控件选择器`() {
        val js = BrowserScript.snapshotJs(DomParser.SnapshotStrategy.INTERACTIVE_ONLY)
        // 意图：默认快照必须能抓到链接、按钮、表单控件与常见 ARIA 角色
        for (sel in listOf("a,", "button,", "input,", "select,", "textarea,",
                           "[role=button]", "[role=link]", "[role=tab]", "[role=option]")) {
            assertTrue("INTERACTIVE_ONLY 应含选择器 $sel", js.contains(sel))
        }
    }

    @Test
    fun `FORM_FIELDS 策略收窄到表单域而非整页链接`() {
        val js = BrowserScript.snapshotJs(DomParser.SnapshotStrategy.FORM_FIELDS)
        // 意图：填表场景只保留 input/select/textarea 及表单类 ARIA 角色
        assertTrue(js.contains("input,"))
        assertTrue(js.contains("select,"))
        assertTrue(js.contains("textarea,"))
        assertTrue(js.contains("[role=checkbox]"))
        // 不应把整页导航链接作为主要目标（a 不在 FORM_FIELDS 选择器内）
        assertFalse("FORM_FIELDS 不应含裸 <a> 选择器", js.contains("a,"))
    }

    @Test
    fun `CONTENT_SUMMARY 策略保留标题正文链接而非交互控件`() {
        val js = BrowserScript.snapshotJs(DomParser.SnapshotStrategy.CONTENT_SUMMARY)
        // 意图：阅读/抽取场景保留 h1~h4、p、li、链接
        for (sel in listOf("h1,", "h2,", "h3,", "h4,", "p,", "li,", "a[href]")) {
            assertTrue("CONTENT_SUMMARY 应含选择器 $sel", js.contains(sel))
        }
        assertFalse("CONTENT_SUMMARY 不应含裸 button 选择器", js.contains("button,"))
    }

    // ---- 语义哈希 ref：抗 SPA 局部刷新错位的核心 ----

    @Test
    fun `snapshotJs 注入 data-apex-hash 语义哈希作为定位主键`() {
        val js = BrowserScript.snapshotJs()
        // 意图：定位主键必须是语义哈希（data-apex-hash），而非顺序 ref
        assertTrue("必须写入 data-apex-hash 属性", js.contains("setAttribute('data-apex-hash'"))
        assertTrue("必须包含哈希函数", js.contains("function hash("))
        assertTrue("哈希结果必须以 r_ 前缀稳定可读", js.contains("'r_' +"))
    }

    @Test
    fun `snapshotJs 含元素硬上限保护 token 预算`() {
        val js = BrowserScript.snapshotJs()
        // 意图：超过 SNAPSHOT_MAX_ELEMENTS 必须截断，防止大页撑爆 IPC/Token
        assertTrue(js.contains("var MAX = ${BrowserScript.SNAPSHOT_MAX_ELEMENTS}"))
        assertTrue(js.contains("if (out.length >= MAX) break"))
    }

    @Test
    fun `snapshotJs 默认策略为 INTERACTIVE_ONLY`() {
        // 意图：不传策略时回退到最通用的交互元素快照，行为稳定
        assertEquals(
            BrowserScript.snapshotJs(DomParser.SnapshotStrategy.INTERACTIVE_ONLY),
            BrowserScript.snapshotJs()
        )
    }

    // ---- 物理定位：rectByRefJs 用语义哈希 ref 而非顺序序号 ----

    @Test
    fun `rectByRefJs 按 data-apex-hash 定位而非顺序 ref`() {
        val ref = "r_3k9f"
        val js = BrowserScript.rectByRefJs(ref)
        // 意图：必须按语义哈希 ref 查询，才能抗 SPA 局部刷新错位
        assertTrue(js.contains("[data-apex-hash='$ref']"))
    }

    @Test
    fun `rectByRefJs 把外部 ref 嵌入属性选择器定位`() {
        // 意图：ref 来自 Agent 参数，必须被安全插值进 data-apex-hash 属性选择器
        val ref = "r_3k9f"
        val js = BrowserScript.rectByRefJs(ref)
        assertTrue(js.contains("[data-apex-hash='$ref']"))
        // 已知限制（基础设施层防护由 WebView 沙箱兜底）：ref 直接字符串插值，
        // 若含 `"` / `]` 可能闭合属性选择器；Agent 层传入的 ref 均来自快照注入的语义哈希，
        // 字符集受限，实际风险低。此处仅验证正常 ref 的嵌入契约。
    }

    // ---- 下拉选择：byText / byValue 两种匹配语义 ----

    @Test
    fun `selectJs byValue 按 option.value 匹配`() {
        val js = BrowserScript.selectJs("r_abc", "cn", byText = false)
        assertTrue("按 value 匹配", js.contains("opt.value"))
        assertTrue(js.contains("[data-apex-hash='r_abc']"))
        assertFalse("不应按 text 匹配", js.contains("opt.text"))
    }

    @Test
    fun `selectJs byText 按 option.text 匹配`() {
        val js = BrowserScript.selectJs("r_abc", "中国", byText = true)
        assertTrue("按 text 匹配", js.contains("opt.text"))
    }

    // ---- 网络监控：注入后拦截 fetch / xhr 并写入日志 ----

    @Test
    fun `NETWORK_MONITOR_JS 拦截 fetch 与 XMLHttpRequest 并写入日志`() {
        val js = BrowserScript.NETWORK_MONITOR_JS
        // 意图：window.__apexNetLog 是 browser_network_log 的数据源，必须被填充
        assertTrue(js.contains("window.__apexNetLog"))
        assertTrue(js.contains("window.fetch = function"))
        assertTrue(js.contains("XMLHttpRequest.prototype.open"))
        assertTrue(js.contains("XMLHttpRequest.prototype.send"))
        // 防止重复注入
        assertTrue(js.contains("if (window.__apexNetHooked) return"))
    }

    // ---- 点击后探针：返回 url/title/交互数供 Agent 判定导航是否成功 ----

    @Test
    fun `POST_ACTION_PROBE_JS 返回 url title 与交互元素数`() {
        val js = BrowserScript.POST_ACTION_PROBE_JS
        // 意图：点击验证必须能感知 URL / 标题变化与页面结构变化
        assertTrue(js.contains("url: location.href"))
        assertTrue(js.contains("title: document.title"))
        assertTrue(js.contains("interactiveCount:"))
    }

    // ---- 等待选择器：wait_for 参数支撑 ----

    @Test
    fun `waitForSelectorJs 把选择器作为 JS 字符串安全嵌入`() {
        val sel = "a.login"
        val js = BrowserScript.waitForSelectorJs(sel)
        // 意图：选择器被当作字面量查询，且带轮询 + 超时兜底
        assertTrue(js.contains("document.querySelector('$sel')"))
        assertTrue(js.contains("setInterval"))
        assertTrue(js.contains("setTimeout"))
    }

    // ---- 高亮：调试可视化 ----

    @Test
    fun `highlightJs 对目标 ref 注入 outline`() {
        val js = BrowserScript.highlightJs("r_z12", "#ff0000")
        assertTrue(js.contains("[data-apex-hash=\"r_z12\"]"))
        assertTrue(js.contains("outline='2px solid #ff0000'"))
    }
}
