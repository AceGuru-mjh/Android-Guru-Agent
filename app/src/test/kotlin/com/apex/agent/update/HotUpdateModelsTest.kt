package com.apex.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HotUpdatePolicy] / [HotPackage] 单元测试 —— 热更通道门控与包清单解析全覆盖。
 *
 * 覆盖：五重门控逐项（自洽/未应用或指纹重发/基底兼容/URL/指纹判重）/
 * 全过放行 / 应急重发（同 target 新指纹）判重三态 / 有效版本口径 /
 * APK 追平退役判定 / 包清单合法解析 / 未知字段前向兼容 / schema 拒绝 /
 * 路径非法拒绝（zip-slip 第一道闸）/ 畸形输入折叠 null。
 */
class HotUpdateModelsTest {

    private val hot = HotAsset(
        baseVersionCode = 45,
        targetVersionCode = 46,
        targetVersionName = "1.4.5.3",
        url = "https://example.com/hot_v1.4.5.3.zip",
        sizeBytes = 2048L,
        sha256 = "ab".repeat(32)
    )

    // ── 门控：HotUpdatePolicy.resolve ────────────────────────────────────

    @Test
    fun `resolve passes all four gates`() {
        val resolved = HotUpdatePolicy.resolve(
            hot = hot,
            manifestVersionCode = 46,
            effectiveVersionCode = 45,
            appliedTargetVersionCode = 0
        )
        assertEquals(hot, resolved)
    }

    @Test
    fun `resolve accepts effective raised by prior hot`() {
        // 包 44 + 已热更 45（appliedTarget=45）→ effective=45 ≥ base=45 → 放行
        val resolved = HotUpdatePolicy.resolve(
            hot = hot,
            manifestVersionCode = 46,
            effectiveVersionCode = 45,
            appliedTargetVersionCode = 45
        )
        assertEquals(hot, resolved)
    }

    @Test
    fun `resolve rejects target mismatch with manifest`() {
        // 门 1：hot.targetVersionCode != manifest.versionCode（手写清单漂移）
        assertNull(
            HotUpdatePolicy.resolve(
                hot = hot, manifestVersionCode = 47,
                effectiveVersionCode = 45, appliedTargetVersionCode = 0
            )
        )
    }

    @Test
    fun `resolve rejects already applied target`() {
        // 门 2：已应用过同版且指纹一致（重复检查/已热更）
        assertNull(
            HotUpdatePolicy.resolve(
                hot = hot, manifestVersionCode = 46,
                effectiveVersionCode = 46, appliedTargetVersionCode = 46,
                appliedPackageSha256 = hot.sha256
            )
        )
    }

    // ── 应急重发（发布仓库 hotfix 工作流：同 target 重发新包） ──────────

    @Test
    fun `resolve re-offers same target when package sha differs`() {
        // 发布仓库应急重发：target 不变（46）但包内容修过（新指纹）→ 重新应用
        val resolved = HotUpdatePolicy.resolve(
            hot = hot.copy(sha256 = "cd".repeat(32)),
            manifestVersionCode = 46,
            effectiveVersionCode = 46,
            appliedTargetVersionCode = 46,
            appliedPackageSha256 = hot.sha256
        )
        assertNotNull(resolved)
    }

    @Test
    fun `resolve skips same-target republish without fingerprint`() {
        // 门 5：同 target 但清单未携带指纹 → 无法判重，保守不推
        assertNull(
            HotUpdatePolicy.resolve(
                hot = hot.copy(sha256 = null),
                manifestVersionCode = 46,
                effectiveVersionCode = 46,
                appliedTargetVersionCode = 46,
                appliedPackageSha256 = hot.sha256
            )
        )
    }

    @Test
    fun `resolve rejects stale target older than applied`() {
        // 陈旧档回推（已应用 47，清单还是 46）→ 永不回退
        assertNull(
            HotUpdatePolicy.resolve(
                hot = hot, manifestVersionCode = 46,
                effectiveVersionCode = 47, appliedTargetVersionCode = 47
            )
        )
    }

    @Test
    fun `resolve rejects base below floor`() {
        // 门 3：数据层基底过老（effective 44 < base 45）→ 走增量/全量
        assertNull(
            HotUpdatePolicy.resolve(
                hot = hot, manifestVersionCode = 46,
                effectiveVersionCode = 44, appliedTargetVersionCode = 0
            )
        )
    }

    @Test
    fun `resolve rejects blank url`() {
        // 门 4：旧 schema/手写清单缺 URL
        assertNull(
            HotUpdatePolicy.resolve(
                hot = hot.copy(url = " "),
                manifestVersionCode = 46,
                effectiveVersionCode = 45,
                appliedTargetVersionCode = 0
            )
        )
    }

    @Test
    fun `resolve returns null for absent hot asset`() {
        assertNull(
            HotUpdatePolicy.resolve(
                hot = null, manifestVersionCode = 46,
                effectiveVersionCode = 45, appliedTargetVersionCode = 0
            )
        )
    }

    // ── 有效版本口径 / 退役判定 ──────────────────────────────────────────

    @Test
    fun `effective version takes max of package and applied`() {
        assertEquals(45, HotUpdatePolicy.effectiveVersionCode(45, 0))
        assertEquals(46, HotUpdatePolicy.effectiveVersionCode(45, 46))
        assertEquals(47, HotUpdatePolicy.effectiveVersionCode(47, 46))
        assertEquals(44, HotUpdatePolicy.effectiveVersionCode(44, 40))
    }

    @Test
    fun `supersede only when package catches up applied target`() {
        // 从未热更（0）→ 永不退役（无 overlay 可清）
        assertFalse(HotUpdatePolicy.isSuperseded(46, 0))
        // 包追平热更目标 → 退役
        assertTrue(HotUpdatePolicy.isSuperseded(46, 46))
        assertTrue(HotUpdatePolicy.isSuperseded(47, 46))
        // 包仍落后 → overlay 继续生效
        assertFalse(HotUpdatePolicy.isSuperseded(45, 46))
    }

    // ── 包清单解析：HotPackage.parse ─────────────────────────────────────

    @Test
    fun `parses well-formed package manifest`() {
        val manifest = HotPackage.parse(
            """
            {"schema": "apex-hot-v1", "targetVersionCode": 46,
             "targetVersionName": "1.4.5.3",
             "entries": [
               {"path": "skills/api-design.json", "sha256": "${"ab".repeat(32)}"},
               {"path": "mcp_catalog/browser.json", "sha256": "${"cd".repeat(32)}"}
             ]}
            """.trimIndent()
        )
        assertNotNull(manifest)
        assertEquals(46, manifest!!.targetVersionCode)
        assertEquals("1.4.5.3", manifest.targetVersionName)
        assertEquals(2, manifest.entries.size)
        assertEquals("skills/api-design.json", manifest.entries[0].path)
    }

    @Test
    fun `unknown fields are tolerated`() {
        // schema 演进：新增字段不崩老客户端
        val manifest = HotPackage.parse(
            """
            {"schema": "apex-hot-v1", "targetVersionCode": 46,
             "futureField": {"a": 1}, "entries": [
               {"path": "skills/x.json", "sha256": "${"ab".repeat(32)}", "newField": true}
             ]}
            """.trimIndent()
        )
        assertNotNull(manifest)
    }

    @Test
    fun `rejects wrong schema`() {
        assertNull(
            HotPackage.parse(
                """{"schema": "apex-hot-v2", "targetVersionCode": 46, "entries": []}"""
            )
        )
        assertNull(
            HotPackage.parse("""{"targetVersionCode": 46, "entries": []}""")
        )
    }

    @Test
    fun `rejects unsafe entry paths`() {
        // zip-slip 第一道闸：绝对路径 / 上跳 / 反斜杠 / 根级裸文件
        for (path in listOf(
            "/etc/passwd",
            "../escape.json",
            "skills/../../escape.json",
            "skills\\evil.json",
            "bare.json"
        )) {
            assertNull(
                "path should be rejected: $path",
                HotPackage.parse(
                    """
                    {"schema": "apex-hot-v1", "targetVersionCode": 46,
                     "entries": [{"path": "$path", "sha256": "${"ab".repeat(32)}"}]}
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun `folds malformed json to null`() {
        assertNull(HotPackage.parse("not json at all"))
        assertNull(HotPackage.parse("""{"schema": "apex-hot-v1"}"""))
    }

    // ── 路径合法性直测 ────────────────────────────────────────────────────

    @Test
    fun `safe path accepts normal section layout`() {
        assertTrue(HotPackage.isSafePath("skills/api-design.json"))
        assertTrue(HotPackage.isSafePath("mcp_catalog/browser.json"))
        // 裸文件（无目录前缀）与空文件名拒绝
        assertFalse(HotPackage.isSafePath("bare.json"))
        assertFalse(HotPackage.isSafePath("skills/"))
        assertFalse(HotPackage.isSafePath(""))
    }
}
