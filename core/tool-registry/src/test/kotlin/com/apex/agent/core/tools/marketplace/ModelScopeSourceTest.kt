package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelScopeSource] 单测（手写 fake OkHttp 拦截器，不联网；仓库约定无
 * mock 框架）。
 *
 * 覆盖 P2-5 修复：超过 64KB 的 SKILL.md 走**截断读取** —— frontmatter
 * 永远在文件头部，截断头部照样解析，条目不再因超限从目录里静默消失；
 * 同时钉住目录树 → 技能条目的映射（id / name / description / files 归属）。
 */
class ModelScopeSourceTest {

    /** fake：git/trees 目录树请求回固定夹具，其余（raw 文件）回 [rawBody]。 */
    private fun fakeHttpClient(rawBody: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val isTreeCall = chain.request().url.encodedPath.contains("/git/trees/")
                val (body, type) = if (isTreeCall) {
                    TREES_JSON to "application/json"
                } else {
                    rawBody to "text/markdown"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fake")
                    .body(body.toResponseBody(type.toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun `oversized skill markdown is truncated for frontmatter instead of dropped`() = runBlocking {
        // 70KB 正文 + 头部 frontmatter：超过 64KB 的 frontmatter 探测上限
        val skillMd = buildString {
            append("---\n")
            append("name: Big Skill\n")
            append("description: 超长技能描述\n")
            append("---\n")
            repeat(70 * 1024) { append('x') }
        }
        val source = ModelScopeSource(fakeHttpClient(skillMd))

        val result = source.listSkills()

        // P2-5：旧实现 fetchRaw 超 64KB 返回 null → 条目被丢弃、目录缺货；
        // 截断后 frontmatter（永远在文件头部）照常解析，条目保留
        assertTrue(result.isSuccess)
        val skills = result.getOrThrow()
        assertEquals(1, skills.size)
        val skill = skills[0]
        assertEquals("ms-big", skill.id)
        assertEquals("Big Skill", skill.name)
        assertEquals("超长技能描述", skill.description)
        assertEquals("skills/ms-big/SKILL.md", skill.path)
        // 文件清单归属该技能目录（含 SKILL.md 自身与 references 资源）
        assertEquals(
            listOf("skills/ms-big/SKILL.md", "skills/ms-big/references/api.md"),
            skill.files
        )
    }

    @Test
    fun `skill without frontmatter is kept with id fallback name`() = runBlocking {
        // 无 frontmatter 标记的 SKILL.md：条目保留，name 回退 id
        // （与「抓取失败」同口径 —— 单条失败不拖垮整个目录）
        val source = ModelScopeSource(fakeHttpClient("只是一段没有 frontmatter 的正文。"))

        val result = source.listSkills()

        assertTrue(result.isSuccess)
        val skills = result.getOrThrow()
        assertEquals(1, skills.size)
        assertEquals("ms-big", skills[0].id)
        assertEquals("ms-big", skills[0].name)
        assertEquals("", skills[0].description)
    }

    private companion object {
        /** 目录树最小夹具：一个技能目录（SKILL.md + 一个 reference 资源）。 */
        private const val TREES_JSON = """
            {"tree": [
                {"type": "blob", "path": "skills/ms-big/SKILL.md"},
                {"type": "blob", "path": "skills/ms-big/references/api.md"},
                {"type": "tree", "path": "skills/ms-big"}
            ]}
        """
    }
}
