package com.apex.agent.core.tools.skill

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #166 — 内置技能资产化（[SkillRegistry.installBundled]）单元测试。
 *
 * 锁定五个不变量：
 * 1. 首次释放全量安装且 bundled 标记无条件补齐（资产作者漏写也补）；
 * 2. 二次释放幂等（零新增、不覆盖用户侧文件）；
 * 3. 版本判定：assets 更高才升级、相同/更低跳过；升级保留用户禁用态
 *    （对齐 McpManager.ensureBuiltinServer 的偏好保留先例）；
 * 4. bundled 技能 [SkillRegistry.uninstall] 恒 false（UI 降级为禁用提示），
 *    普通技能卸载不受影响；
 * 5. 单条损坏 JSON 隔离跳过，其余条目照常释放且计数正确。
 *
 * 纯 JVM（JUnit4 + TemporaryFolder），与同包 SkillHotReloaderTest 同构。
 */
class BundledSkillsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var registry: SkillRegistry

    @Before
    fun setUp() {
        skillsDir = tmp.newFolder()
        registry = SkillRegistry(skillsDir)
    }

    // ═══ helpers ═══════════════════════════════════════════════════════

    /** 最小可安装的 prompt 型 manifest（bundled 缺省由调用参数控制）。 */
    private fun manifestJson(
        id: String,
        version: String,
        bundled: Boolean? = null,
        prompt: String = "# ${id}\n\n方法论内容。"
    ): String {
        val bundledField = when (bundled) {
            null -> ""
            true -> ",\n  \"bundled\": true"
            false -> ",\n  \"bundled\": false"
        }
        return """
        {
          "schema": "apex-skill-v1",
          "id": "$id",
          "name": "测试技能 $id",
          "version": "$version",
          "description": "Issue #166 测试用 prompt 型技能",
          "promptInjection": ${Json.encodeToString(prompt)}$bundledField
        }
        """.trimIndent()
    }

    private fun installed(id: String) = registry.getInstalled().firstOrNull { it.manifest.id == id }

    // ═══ 首次释放 / bundled 补齐 ═══════════════════════════════════════

    @Test
    fun `first release installs all and patches bundled flag`() {
        val result = registry.installBundled(
            listOf(
                manifestJson("alpha", "1.0.0"),
                // 资产作者显式漏写 bundled：释放通道必须补齐，否则卸载守卫漏防
                manifestJson("beta", "1.0.0", bundled = false)
            )
        )

        assertEquals(2, result.getOrThrow())
        assertNotNull(installed("alpha"))
        assertTrue("漏写 bundled 的资产经释放通道后必须补齐", installed("beta")!!.manifest.bundled)
        assertTrue("落盘文件存在", File(skillsDir, "alpha.json").exists())
    }

    @Test
    fun `second release is idempotent with zero additions`() {
        registry.installBundled(listOf(manifestJson("alpha", "1.0.0")))

        val result = registry.installBundled(listOf(manifestJson("alpha", "1.0.0")))

        assertEquals("同版本二次释放零新增", 0, result.getOrThrow())
        assertEquals(1, registry.getInstalled().size)
    }

    // ═══ 版本判定与偏好保留 ═══════════════════════════════════════════

    @Test
    fun `higher asset version upgrades and keeps user disabled state`() {
        registry.installBundled(listOf(manifestJson("alpha", "1.0.0")))
        registry.setEnabled("alpha", false)
        assertFalse("禁用态先落定", installed("alpha")!!.enabled)

        val result = registry.installBundled(listOf(manifestJson("alpha", "1.1.0")))

        assertEquals("升级不计入新增", 0, result.getOrThrow())
        val upgraded = installed("alpha")!!
        assertEquals("1.1.0", upgraded.manifest.version)
        assertFalse("用户禁用态跨升级保留", upgraded.enabled)
    }

    @Test
    fun `same or lower asset version is skipped`() {
        registry.installBundled(listOf(manifestJson("alpha", "2.0.0")))

        val result = registry.installBundled(
            listOf(
                manifestJson("alpha", "2.0.0"),  // 相同
                manifestJson("alpha", "1.9.9")   // 更低（同 id 后写入列表，先到的先处理）
            )
        )

        assertEquals(0, result.getOrThrow())
        assertEquals("不降级", "2.0.0", installed("alpha")!!.manifest.version)
    }

    // ═══ 卸载守卫 ═════════════════════════════════════════════════════

    @Test
    fun `bundled skill cannot be uninstalled but normal skill can`() {
        registry.installBundled(listOf(manifestJson("bundled-one", "1.0.0")))
        registry.install(manifestJson("normal-one", "1.0.0")).getOrThrow()

        assertFalse("bundled 恒拒卸载", registry.uninstall("bundled-one"))
        assertNotNull("bundled 仍在", installed("bundled-one"))
        assertTrue("普通技能照常卸载", registry.uninstall("normal-one"))
        assertEquals(null, installed("normal-one"))
    }

    @Test
    fun `bundled field round-trips through disk`() {
        registry.installBundled(listOf(manifestJson("alpha", "1.0.0")))

        // 新实例从盘读回（模拟重启）——bundled 标记必须持久化
        val reloaded = SkillRegistry(skillsDir)

        assertTrue(reloaded.getInstalled().first { it.manifest.id == "alpha" }.manifest.bundled)
    }

    @Test
    fun `legacy manifest without bundled field defaults to false`() {
        registry.install(manifestJson("legacy", "1.0.0")).getOrThrow()

        assertFalse(
            "旧 manifest（无 bundled 字段）反序列化默认 false，可正常卸载",
            installed("legacy")!!.manifest.bundled
        )
        assertTrue(registry.uninstall("legacy"))
    }

    // ═══ 损坏隔离 ═════════════════════════════════════════════════════

    @Test
    fun `corrupt json entry is skipped and others still install`() {
        val result = registry.installBundled(
            listOf(
                "{ this is not valid json ]",
                manifestJson("good-one", "1.0.0")
            )
        )

        assertEquals("坏条目不计入，好条目照常释放", 1, result.getOrThrow())
        assertTrue(result.isSuccess)
        assertNotNull(installed("good-one"))
    }
}
