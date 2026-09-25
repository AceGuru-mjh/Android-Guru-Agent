package com.apex.agent.mcp.builtin.memory

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #150 — KnowledgeGraphStore 单测（纯 JVM，任务书 §测试指定）。
 *
 * 覆盖：创建/读取、幂等合并、关系去重、观察追加去重 + 未知实体报错、
 * 检索（三种字段命中 + 关系双端过滤 + 大小写不敏感）、级联删除、
 * 持久化往返（跨实例）、损坏文件兜底（留档 + 空图启动 + 可恢复写入）。
 */
class KnowledgeGraphStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): KnowledgeGraphStore = KnowledgeGraphStore(tmp.newFolder())

    // ═══ 创建 / 读取 ═══

    @Test
    fun `create entities then read graph returns snapshot`() {
        val store = newStore()
        val outcome = store.createEntities(listOf(
            GraphEntity("apex", "project", listOf("Android 端 Agent 应用")),
            GraphEntity("mcp", "protocol")
        ))

        assertEquals(listOf("apex", "mcp"), outcome.created)
        assertTrue(outcome.merged.isEmpty())

        val graph = store.readGraph()
        assertEquals(2, graph.entities.size)
        assertEquals("apex", graph.entities[0].name)
        assertEquals(listOf("Android 端 Agent 应用"), graph.entities[0].observations)
        assertEquals(emptyList<String>(), graph.entities[1].observations)
        assertTrue(graph.relations.isEmpty())
        assertEquals(2, store.entityCount())
    }

    @Test
    fun `recreating existing entity merges observations without duplicates`() {
        val store = newStore()
        store.createEntities(listOf(GraphEntity("apex", "project", listOf("obs-1", "obs-2"))))

        // 幂等决策：同名实体不重建，observations 保序去重合并（entityType 保留旧值）
        val outcome = store.createEntities(listOf(
            GraphEntity("apex", "ignored-type", listOf("obs-2", "obs-3")),
            GraphEntity("fresh", "thing", listOf("n1"))
        ))

        assertEquals(listOf("fresh"), outcome.created)
        assertEquals(listOf("apex"), outcome.merged)

        val apex = store.readGraph().entities.single { it.name == "apex" }
        assertEquals(listOf("obs-1", "obs-2", "obs-3"), apex.observations)
        assertEquals("project", apex.entityType)
    }

    @Test
    fun `recreating existing entity with empty observations is a no-op`() {
        val store = newStore()
        store.createEntities(listOf(GraphEntity("a", "x", listOf("o1"))))

        val outcome = store.createEntities(listOf(GraphEntity("a", "x")))

        assertTrue(outcome.created.isEmpty())
        assertTrue(outcome.merged.isEmpty())
        assertEquals(listOf("o1"), store.readGraph().entities.single().observations)
    }

    // ═══ 关系 ═══

    @Test
    fun `duplicate relations are skipped`() {
        val store = newStore()
        store.createEntities(listOf(GraphEntity("a", "x"), GraphEntity("b", "x")))
        val relation = GraphRelation("a", "b", "depends-on")

        val first = store.createRelations(listOf(relation))
        val second = store.createRelations(listOf(relation, relation))

        assertEquals(1, first.added)
        assertEquals(0, first.duplicates)
        assertEquals(0, second.added)
        assertEquals(2, second.duplicates)
        assertEquals(1, store.relationCount())
    }

    // ═══ 观察 ═══

    @Test
    fun `add observations appends with dedupe and throws on unknown entity`() {
        val store = newStore()
        store.createEntities(listOf(GraphEntity("a", "x", listOf("o1"))))

        assertEquals(2, store.addObservations("a", listOf("o2", "o1", "o3", "  ")))
        assertEquals(
            listOf("o1", "o2", "o3"),
            store.readGraph().entities.single().observations
        )

        assertThrows(IllegalArgumentException::class.java) {
            store.addObservations("ghost", listOf("x"))
        }
    }

    // ═══ 检索 ═══

    @Test
    fun `search nodes matches name, type and observations case-insensitively`() {
        val store = newStore()
        store.createEntities(listOf(
            GraphEntity("apex", "project", listOf("内置 web search 能力")),
            GraphEntity("search-mcp", "server", listOf("duckduckgo 回退")),
            GraphEntity("unrelated", "thing", listOf("nothing here"))
        ))
        store.createRelations(listOf(
            GraphRelation("apex", "search-mcp", "uses"),
            GraphRelation("apex", "unrelated", "ignores")
        ))

        // "search" 同时命中 apex 的观察与 search-mcp 的名字（大小写不敏感）
        val hit = store.searchNodes("SEARCH")
        assertEquals(setOf("apex", "search-mcp"), hit.entities.map { it.name }.toSet())
        // 关系只保留两端都命中的边（apex→unrelated 被过滤）
        assertEquals(listOf("uses"), hit.relations.map { it.relationType })

        // entityType 命中路径
        assertEquals(1, store.searchNodes("server").entities.size)

        // 空查询按"包含匹配恒真"语义返回全图
        assertEquals(3, store.searchNodes("").entities.size)
    }

    // ═══ 删除 ═══

    @Test
    fun `delete entities removes entity and cascades its relations`() {
        val store = newStore()
        store.createEntities(listOf(
            GraphEntity("a", "x"), GraphEntity("b", "x"), GraphEntity("c", "x")
        ))
        store.createRelations(listOf(
            GraphRelation("a", "b", "r1"),
            GraphRelation("b", "c", "r2"),
            GraphRelation("a", "c", "r3")
        ))

        val outcome = store.deleteEntities(listOf("b", "ghost"))

        assertEquals(1, outcome.deleted)
        assertEquals(listOf("ghost"), outcome.missing)

        val graph = store.readGraph()
        assertEquals(setOf("a", "c"), graph.entities.map { it.name }.toSet())
        // r1（a-b）与 r2（b-c）随 b 级联消失，r3 保留
        assertEquals(listOf("r3"), graph.relations.map { it.relationType })
    }

    // ═══ 持久化 ═══

    @Test
    fun `state survives store recreation (persistence roundtrip)`() {
        val dir = tmp.newFolder()
        val first = KnowledgeGraphStore(dir)
        first.createEntities(listOf(
            GraphEntity("apex", "project", listOf("obs-1")),
            GraphEntity("kotlin", "language")
        ))
        first.createRelations(listOf(GraphRelation("apex", "kotlin", "written-in")))
        first.addObservations("kotlin", listOf("coroutines"))

        val second = KnowledgeGraphStore(dir)
        val graph = second.readGraph()
        assertEquals(2, graph.entities.size)
        assertEquals(1, graph.relations.size)
        val kotlin = graph.entities.single { it.name == "kotlin" }
        assertEquals(listOf("coroutines"), kotlin.observations)

        // 落盘文件确实存在且为 JSON
        assertTrue(File(dir, "memory.json").isFile)
    }

    @Test
    fun `corrupt memory json falls back to empty graph and keeps backup`() {
        val dir = tmp.newFolder()
        File(dir, "memory.json").writeText("{ 损坏的 JSON 内容")

        val store = KnowledgeGraphStore(dir)
        assertEquals(0, store.entityCount())
        assertTrue(File(dir, "memory.json.corrupt").isFile)

        // 恢复路径：随后的正常写入会覆盖坏的主文件
        store.createEntities(listOf(GraphEntity("x", "t")))
        assertEquals(1, KnowledgeGraphStore(dir).entityCount())
    }
}
