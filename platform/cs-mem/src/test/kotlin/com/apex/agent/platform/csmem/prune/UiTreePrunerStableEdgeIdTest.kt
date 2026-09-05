package com.apex.agent.platform.csmem.prune

import com.apex.agent.platform.csmem.model.NodeRole
import com.apex.agent.platform.csmem.model.SemanticNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UiTreePruner.stableEdgeId 回归测试。
 *
 * 背景（v3 修复）：旧实现的边 ID 是帧内自增计数器（e_0, e_1…每帧重置），
 * 导致 (a) DifferentialIngestor 按边 ID 集合做差分时语义错乱——帧 N 的 e_3
 * 与帧 N+1 的 e_3 可能连接完全不同的节点，"新增边"检测随边数波动误报；
 * (b) EdgeDao.deleteByLabels 按标签删除会跨 Episode 误删同名边。
 *
 * 内容哈希（源指纹+目标指纹+关系类型 → SHA-256 前 16 位）使：
 * 1. 同一条边在任意两帧中得到相同 ID（帧稳定性）
 * 2. 不同拓扑关系得到不同 ID（内容敏感性）
 * 3. 源/目标顺序敏感（有向性）
 * 4. ID 格式恒为 e_ + 16 位十六进制
 */
class UiTreePrunerStableEdgeIdTest {

    // ─── 1. 纯函数性质 ───────────────────────────────────────────────

    @Test
    fun `deterministic across repeated calls`() {
        val a = UiTreePruner.stableEdgeId("fp_a", "fp_b", "parent_child")
        val b = UiTreePruner.stableEdgeId("fp_a", "fp_b", "parent_child")
        assertEquals("同一输入必须得到同一边 ID", a, b)
    }

    @Test
    fun `format is e_prefix plus 16 hex chars`() {
        val id = UiTreePruner.stableEdgeId("fp_a", "fp_b", "parent_child")
        assertTrue(
            "边 ID 应形如 e_<16位十六进制>，实际: $id",
            Regex("^e_[0-9a-f]{16}$").matches(id)
        )
    }

    @Test
    fun `content sensitive - different target yields different id`() {
        val a = UiTreePruner.stableEdgeId("fp_a", "fp_b", "parent_child")
        val b = UiTreePruner.stableEdgeId("fp_a", "fp_c", "parent_child")
        assertNotEquals("目标指纹不同 → 边 ID 不同", a, b)
    }

    @Test
    fun `content sensitive - different metadata yields different id`() {
        val a = UiTreePruner.stableEdgeId("fp_a", "fp_b", "parent_child")
        val b = UiTreePruner.stableEdgeId("fp_a", "fp_b", "horizontal_adjacent")
        assertNotEquals("关系类型不同 → 边 ID 不同", a, b)
    }

    @Test
    fun `direction sensitive - reversed edge yields different id`() {
        val a = UiTreePruner.stableEdgeId("fp_a", "fp_b", "parent_child")
        val b = UiTreePruner.stableEdgeId("fp_b", "fp_a", "parent_child")
        assertNotEquals("源/目标交换（有向边反向）→ 边 ID 不同", a, b)
    }

    /**
     * 字段拼接以 `|` 分隔。若实现退化为不加分隔符的字符串拼接，
     * ("ab","c") 与 ("a","bc") 会碰撞——此用例锁定分隔语义。
     */
    @Test
    fun `no collision between shifted field boundaries`() {
        val a = UiTreePruner.stableEdgeId("ab", "c", "parent_child")
        val b = UiTreePruner.stableEdgeId("a", "bc", "parent_child")
        assertNotEquals("字段边界漂移（ab|c vs a|bc）不应产生相同哈希", a, b)
    }

    // ─── 2. 帧稳定性（差分语义的前提）──────────────────────────────

    @Test
    fun `same tree across frames yields identical edge ids`() {
        val frame1 = generateSpatialEdgesFromFixedTree()
        val frame2 = generateSpatialEdgesFromFixedTree()

        assertEquals("两帧边数应一致", frame1.size, frame2.size)
        assertEquals(
            "同一拓扑在任意两帧中必须产生完全相同的边 ID 集合（DifferentialIngestor 增量语义前提）",
            frame1.map { it.id }.toSet(),
            frame2.map { it.id }.toSet()
        )
    }

    @Test
    fun `same nodes in different insertion order yield identical edge id set`() {
        // 帧 A：children 顺序 [x, y]；帧 B：同一父节点的 children 顺序 [y, x]。
        // 父子边与顺序无关 → 边 ID 集合应一致（集合语义，而非列表语义）。
        val treeA = parentNode(children = listOf(childNode("fp_x"), childNode("fp_y")))
        val treeB = parentNode(children = listOf(childNode("fp_y"), childNode("fp_x")))

        val idsA = UiTreePruner.generateSpatialEdges(listOf(treeA)).map { it.id }.toSet()
        val idsB = UiTreePruner.generateSpatialEdges(listOf(treeB)).map { it.id }.toSet()
        assertEquals("兄弟顺序调换不应改变父子边 ID 集合", idsA, idsB)
    }

    @Test
    fun `grown tree adds new edge ids without perturbing existing ones`() {
        // 差分场景：帧 N 有 2 个子节点，帧 N+1 新增 1 个子节点。
        // 正确行为：旧边 ID 原样保留（集合不变），新边仅追加。
        // 旧实现（计数器）下帧 N 的 e_1 与帧 N+1 的 e_1 可能连接不同节点。
        val before = UiTreePruner.generateSpatialEdges(
            listOf(parentNode(children = listOf(childNode("fp_x"), childNode("fp_y"))))
        ).map { it.id }.toSet()

        val after = UiTreePruner.generateSpatialEdges(
            listOf(parentNode(children = listOf(childNode("fp_x"), childNode("fp_y"), childNode("fp_z"))))
        ).map { it.id }.toSet()

        assertTrue("新增子节点后旧边必须全部保留", after.containsAll(before))
        assertEquals("应恰好新增一条父子边", before.size + 1, after.size)
    }

    // ─── helpers ────────────────────────────────────────────────────

    private fun generateSpatialEdgesFromFixedTree() =
        UiTreePruner.generateSpatialEdges(
            listOf(
                parentNode(
                    listOf(
                        childNode("fp_login_btn"),
                        parentNode(
                            listOf(
                                childNode("fp_user_input"),
                                childNode("fp_pass_input")
                            ),
                            fingerprint = "fp_container_inner"
                        )
                    )
                )
            )
        )

    private fun parentNode(
        children: List<SemanticNode>,
        fingerprint: String = "fp_container_root"
    ) = SemanticNode(
        fingerprint = fingerprint,
        role = NodeRole.UNKNOWN,
        textHint = null,
        resourceId = "com.app:id/root",
        className = "android.widget.FrameLayout",
        bounds = android.graphics.Rect(0, 0, 1080, 2400),
        domDepth = 0,
        isInteractive = false,
        children = children
    )

    private fun childNode(fingerprint: String) = SemanticNode(
        fingerprint = fingerprint,
        role = NodeRole.BUTTON,
        textHint = "btn",
        resourceId = "com.app:id/$fingerprint",
        className = "android.widget.Button",
        bounds = android.graphics.Rect(0, 0, 100, 100),
        domDepth = 1,
        isInteractive = true
    )
}
