package com.apex.agent.platform.csmem.bypass

import com.apex.agent.platform.csmem.actor.MemoryWriterActor
import com.apex.agent.platform.csmem.fingerprint.NodeFingerprint
import com.apex.agent.platform.csmem.model.NodeRole
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.FSMMacro
import com.apex.agent.platform.csmem.store.FSMTransition
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.platform.csmem.store.MigrationMap
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.ShellResult
import com.apex.agent.platform.privilege.UiAction
import com.apex.agent.platform.privilege.UiNode
import com.apex.agent.platform.privilege.UiResult
import com.apex.agent.platform.privilege.UiTreeResult
import com.apex.agent.platform.privilege.ScreenshotResult
import com.apex.agent.platform.privilege.ExecutionVia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BypassExecutionEngine 回归测试（纯 JVM，Fake 替身）。
 *
 * 覆盖三组行为：
 * 1. extractInputText —— input_text 参数三种历史形态的双保险解析
 *    （新蒸馏纯参数 / 旧库完整描述 input_text("hello") / 无引号形态）；
 * 2. 跨版本宏回退 —— App 升级后当前指纹已变，经 migration_map 别名桥
 *    反查旧宏仍可命中并回放（闭环 cs-mem-gaps-spec 缺口 #9 §4）；
 *    同时锁定"不得用旧指纹做 matchRate 复验"的回归缺陷（旧指纹必然
 *    不在当前屏幕，任何阈值下都会误杀迁移宏）；
 * 3. 回放状态验证 —— 转移执行后屏幕未出现预期 toState 指纹时立即失败
 *    交还 LLM（弹窗/广告/页面变体偏离场景）。
 */
class BypassExecutionEngineTest {

    // ─── 1. extractInputText 双保险解析 ─────────────────────────────

    @Test
    fun `extractInputText passes through pure param form`() {
        val engine = newEngine()
        assertEquals("hello", engine.extractInputText("hello"))
        assertEquals("hello", engine.extractInputText("\"hello\""))
        assertEquals("hello world", engine.extractInputText("hello world"))
    }

    @Test
    fun `extractInputText unwraps legacy full description form`() {
        val engine = newEngine()
        assertEquals(
            "旧库完整形态 input_text(\"hello\") 应解出纯文本",
            "hello",
            engine.extractInputText("input_text(\"hello\")")
        )
        assertEquals(
            "旧库无引号形态 input_text(hello) 应解出纯文本",
            "hello",
            engine.extractInputText("input_text(hello)")
        )
        assertEquals(
            "带空格参数应保留原文",
            "order #42",
            engine.extractInputText("input_text(\"order #42\")")
        )
    }

    @Test
    fun `extractInputText malformed parens fall back to raw`() {
        val engine = newEngine()
        // 括号倒置（close 位置先于 open）：indexOf('(') 在 lastIndexOf(')') 之后，
        // 提取分支判定为畸形 → 保守返回原文（不崩溃、不吞参数）。
        assertEquals(
            "括号倒置/不平衡时保守返回原文",
            "input_text)(\"unclosed",
            engine.extractInputText("input_text)(\"unclosed")
        )
    }

    // ─── 2. 跨版本迁移回退闭环 ──────────────────────────────────────

    @Test
    fun `tryBypass recalls macro via migration alias when exact match fails`() = runBlocking {
        // 场景：App 升级使设置按钮指纹 fp_old → fp_new。
        // 旧宏 initialFingerprint = fp_old（精确匹配必然失败），
        // migration_map 记录别名桥 fp_old → fp_new。
        val fpNew = "fingerprint_new_settings_btn"
        val fpOld = "fingerprint_old_settings_btn"
        val terminalFp = fingerprintOf(
            className = "android.widget.EditText",
            resourceId = "com.app:id/edit_query",
            text = "hello"
        )

        val macro = FSMMacro(
            skillId = "skill_legacy_open_search",
            name = "打开搜索",
            description = null,
            initialFingerprint = fpOld,
            terminalFingerprint = terminalFp,
            transitions = listOf(
                FSMTransition(
                    fromState = fpOld,
                    actionType = "input_text",
                    // 旧库存的完整描述形态——回放侧必须解出纯文本
                    actionParams = "input_text(\"hello\")",
                    toState = terminalFp
                )
            ),
            appPackage = "com.example.app",
            successCount = 5
        )

        val store = FakeMemoryGraphStore(
            exactMacros = emptyMap(),
            migrationMacros = mapOf(fpNew to macro),
            migrationMaps = listOf(
                MigrationMap(fpOld, fpNew, matchScore = 0.9f, "1.0.0", "2.0.0")
            )
        )
        val privilege = FakePrivilegeManager(screenNodes = listOf(editQueryNode()))
        val engine = newEngine(store = store, privilege = privilege)

        val result = engine.tryBypass(listOf(fpNew), "com.example.app")

        assertTrue(
            "跨版本场景：别名桥反查应命中旧宏并成功回放，实际: $result",
            result is BypassResult.Succeeded
        )
        assertEquals("回放动作数应为 1", 1, (result as BypassResult.Succeeded).actionCount)
        assertEquals(
            "输入框收到的必须是纯文本而非完整描述字面量",
            listOf(UiAction.InputText("hello")),
            privilege.executedActions
        )
    }

    @Test
    fun `tryBypass exact match executes macro without migration`() = runBlocking {
        val initialFp = "fp_home_entry"
        val terminalFp = fingerprintOf(
            className = "android.widget.EditText",
            resourceId = "com.app:id/edit_query",
            text = "hi"
        )
        val macro = FSMMacro(
            skillId = "skill_exact",
            name = "精确",
            description = null,
            initialFingerprint = initialFp,
            terminalFingerprint = terminalFp,
            transitions = listOf(
                FSMTransition(initialFp, "input_text", "hi", terminalFp)
            ),
            appPackage = "com.example.app",
            successCount = 3
        )
        val store = FakeMemoryGraphStore(
            exactMacros = mapOf(initialFp to macro),
            migrationMacros = emptyMap(),
            migrationMaps = emptyList()
        )
        val privilege = FakePrivilegeManager(screenNodes = listOf(editQueryNode(text = "hi")))
        val engine = newEngine(store = store, privilege = privilege)

        val result = engine.tryBypass(listOf(initialFp, "fp_other"), "com.example.app")

        assertTrue("精确匹配路径应直接执行: $result", result is BypassResult.Succeeded)
        assertEquals(listOf(UiAction.InputText("hi")), privilege.executedActions)
    }

    @Test
    fun `tryBypass returns NotMatched when no macro and no migration`() = runBlocking {
        val store = FakeMemoryGraphStore()
        val engine = newEngine(store = store)

        val result = engine.tryBypass(listOf("fp_unknown"), "com.example.app")

        assertTrue(
            "无宏无映射时交还 LLM（NotMatched）",
            result is BypassResult.NotMatched
        )
    }

    @Test
    fun `tryBypass empty fingerprints short circuits`() = runBlocking {
        val engine = newEngine()
        val result = engine.tryBypass(emptyList(), "com.example.app")
        assertTrue(result is BypassResult.NotMatched)
    }

    // ─── 3. 回放状态偏离防护 ────────────────────────────────────────

    @Test
    fun `tryBypass fails when screen deviates from expected toState`() = runBlocking {
        val initialFp = "fp_home_entry"
        // 预期落点是 edit_query 输入框；但执行后屏幕上出现的是广告弹窗按钮
        val expectedFp = fingerprintOf(
            className = "android.widget.EditText",
            resourceId = "com.app:id/edit_query",
            text = "hello"
        )
        val adFp = fingerprintOf(
            className = "android.widget.Button",
            resourceId = "com.app:id/ad_close",
            text = "关闭广告"
        )
        val macro = FSMMacro(
            skillId = "skill_deviation",
            name = "偏离",
            description = null,
            initialFingerprint = initialFp,
            terminalFingerprint = expectedFp,
            transitions = listOf(
                FSMTransition(initialFp, "input_text", "hello", expectedFp)
            ),
            appPackage = "com.example.app",
            successCount = 2
        )
        val store = FakeMemoryGraphStore(exactMacros = mapOf(initialFp to macro))
        val privilege = FakePrivilegeManager(
            screenNodes = listOf(adCloseNode()) // 屏幕被广告弹窗劫持
        )
        val engine = newEngine(store = store, privilege = privilege)

        val result = engine.tryBypass(listOf(initialFp), "com.example.app")

        assertTrue(
            "状态偏离（弹窗劫持）应立即失败交还 LLM: $result",
            result is BypassResult.Failed
        )
    }

    // ─── 替身与工厂 ──────────────────────────────────────────────────

    private fun newEngine(
        store: MemoryGraphStore = FakeMemoryGraphStore(),
        privilege: PrivilegeManager = FakePrivilegeManager()
    ): BypassExecutionEngine {
        val engine = BypassExecutionEngine(
            privilegeManager = privilege,
            store = store,
            writerActor = MemoryWriterActor(store)
        )
        // 测试不等待真实 UI 稳定窗口
        engine.actionIntervalMs = 0
        return engine
    }

    /** 与 UiTreePruner.prune→SemanticNode.fromRaw 同源的指纹计算（parentHash=null）。 */
    private fun fingerprintOf(className: String, resourceId: String, text: String): String {
        val role = when {
            className.contains("edit", ignoreCase = true) -> NodeRole.INPUT
            className.contains("button", ignoreCase = true) -> NodeRole.BUTTON
            else -> NodeRole.TEXT
        }
        return NodeFingerprint.compute(className, resourceId, text, role, parentHash = null)
    }

    private fun editQueryNode(text: String = "hello") = UiNode(
        className = "android.widget.EditText",
        text = text,
        contentDescription = "",
        resourceId = "com.app:id/edit_query",
        bounds = "[0,0][1080,200]",
        clickable = true,
        scrollable = false
    )

    private fun adCloseNode() = UiNode(
        className = "android.widget.Button",
        text = "关闭广告",
        contentDescription = "",
        resourceId = "com.app:id/ad_close",
        bounds = "[400,1800][680,1900]",
        clickable = true,
        scrollable = false
    )

    /**
     * 内存版 MemoryGraphStore 替身：只实现旁路引擎消费的三个查询
     * （findBestMacro / findMacrosViaMigration / resolveMigration），
     * 其余方法返回默认值（本测试不触达）。
     */
    private class FakeMemoryGraphStore(
        private val exactMacros: Map<String, FSMMacro> = emptyMap(),
        private val migrationMacros: Map<String, FSMMacro> = emptyMap(),
        private val migrationMaps: List<MigrationMap> = emptyList()
    ) : MemoryGraphStore {

        override suspend fun findBestMacro(
            initialFingerprint: String,
            appPackage: String
        ): FSMMacro? = exactMacros[initialFingerprint]

        override suspend fun findMacrosViaMigration(
            currentFingerprint: String,
            appPackage: String
        ): FSMMacro? = migrationMacros[currentFingerprint]

        override suspend fun resolveMigration(oldFingerprint: String): String? =
            migrationMaps.firstOrNull { it.oldFingerprint == oldFingerprint }?.newFingerprint

        // ---- 以下为旁路路径不触达的默认实现 ----
        override suspend fun startEpisode(episodeId: String, goal: String, appPackage: String?, activityName: String?) = Unit
        override suspend fun finishEpisode(episodeId: String, status: String) = Unit
        override suspend fun getEpisode(episodeId: String) = null
        override suspend fun getRecentEpisodes(limit: Int) = emptyList<com.apex.agent.platform.csmem.store.EpisodeSummary>()
        override suspend fun ingestNodes(nodes: List<SemanticNode>, appPackage: String?) = Unit
        override suspend fun getNode(fingerprint: String): SemanticNode? = null
        override suspend fun getNodesByFingerprints(fingerprints: List<String>) = emptyList<SemanticNode>()
        override suspend fun getNodesByRole(role: NodeRole, limit: Int) = emptyList<SemanticNode>()
        override suspend fun searchNodesByText(query: String, limit: Int) = emptyList<SemanticNode>()
        override suspend fun getNodesByVersion(version: String) = emptyList<SemanticNode>()
        override suspend fun ingestEdges(edges: List<com.apex.agent.platform.csmem.model.GraphEdge>, episodeId: String?) = Unit
        override suspend fun getEdgesByEpisode(episodeId: String) = emptyList<com.apex.agent.platform.csmem.model.GraphEdge>()
        override suspend fun getEdgesByFingerprint(fingerprint: String) = emptyList<com.apex.agent.platform.csmem.model.GraphEdge>()
        override suspend fun ingestDelta(delta: com.apex.agent.platform.csmem.model.GraphDelta, appPackage: String?) = Unit
        override suspend fun saveMacro(macro: FSMMacro): Long = 1L
        override suspend fun getMacro(skillId: String): FSMMacro? = null
        override suspend fun getMacroBySkillId(skillId: String): FSMMacro? = null
        override fun observeMacro(skillId: String) = kotlinx.coroutines.flow.emptyFlow<FSMMacro?>()
        override suspend fun getTopMacros(limit: Int) = emptyList<FSMMacro>()
        override suspend fun recordMacroSuccess(skillId: String) = Unit
        override suspend fun recordMacroFailure(skillId: String) = Unit
        override suspend fun crystallizeMacro(skillId: String) = Unit
        override suspend fun recordMigration(maps: List<MigrationMap>) = Unit
        override suspend fun getMigrationMaps(): List<MigrationMap> = migrationMaps
        override suspend fun latestKnownVersion(): String? = null
        override suspend fun deleteEpisode(episodeId: String): Int = 0
        override suspend fun countNodes(): Int = 0
        override suspend fun countMacros(): Int = 0
        override suspend fun decayAllEnergy(decayFactor: Float) = Unit
        override suspend fun pruneLowEnergy(energyThreshold: Float): Int = 0
    }

    /** 权限管理器替身：记录 UI 动作，按需返回固定屏幕。 */
    private class FakePrivilegeManager(
        private val screenNodes: List<UiNode> = emptyList()
    ) : PrivilegeManager {
        val executedActions = mutableListOf<UiAction>()

        private val falseFlow = MutableStateFlow(false)
        override val rootAvailable: StateFlow<Boolean> = falseFlow
        override val shizukuAvailable: StateFlow<Boolean> = falseFlow
        override val accessibilityAvailable: StateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun executeShell(command: String, timeoutMs: Long) =
            ShellResult(true, "", 0, ExecutionVia.NONE)

        override suspend fun executeUiAction(action: UiAction): UiResult {
            executedActions.add(action)
            return UiResult(success = true)
        }

        override suspend fun getUiTree(): UiTreeResult =
            if (screenNodes.isEmpty()) UiTreeResult(success = false)
            else UiTreeResult(success = true, nodes = screenNodes)

        override suspend fun takeScreenshot() = ScreenshotResult(false, null)
        override suspend fun refreshStatus() = Unit
    }
}
