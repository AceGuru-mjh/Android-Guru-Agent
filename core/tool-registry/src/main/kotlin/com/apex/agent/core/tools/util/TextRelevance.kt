package com.apex.agent.core.tools.util

/**
 * ═══ 文本相关性评分（BM25-lite，CJK 感知）═══
 *
 * 轻量级相关性打分：为「上下文检索增强」提供统一的排序依据 ——
 * 压缩时的相关性保留（agent-engine · SlidingWindowCompressor）、
 * 会话检索工具的模糊回退（tool-registry · context_search）共用。
 *
 * 设计约束（刻意不引入 embedding / 向量库）：
 * - 纯 JVM、零依赖、纯函数 —— 可单测，Android 端零成本；
 * - 中英混排友好：ASCII 词元 + CJK 二元组（bigram），中文单句也能有效分词；
 * - BM25 打分（k1=1.5 / b=0.75），查询词在候选集中越稀有权重越高 ——
 *   相比朴素词重叠，能压制 "the / 的 / 了" 这类高频停用词。
 *
 * 典型用法：
 * ```
 * val ranked = TextRelevance.rank(query, docs, limit = 5)
 * ranked.forEach { println("#${it.index} score=${it.score} ${docs[it.index].take(40)}") }
 * ```
 */
object TextRelevance {

    /** 排序结果：[index] 指回原列表下标，[score] 越大越相关。 */
    data class Scored(val index: Int, val score: Double)

    // ── BM25 参数（标准推荐值）──
    private const val K1 = 1.5
    private const val B = 0.75

    /** 阈值：低于该分数视为「不相关」（排序裁剪用，0.15 ≈ 至少命中一个中低频词）。 */
    const val MIN_RELEVANT_SCORE = 0.15

    /**
     * 分词：ASCII 字母数字序列（小写化）+ CJK 连续段二元组。
     *
     * - "fetch user profile 获取用户资料" →
     *   [fetch, user, profile, 获取, 取用, 用户, 户资, 资料]；
     * - 单个孤立 CJK 字符（前后无 CJK 邻居）保留为单字词元 ——
     *   "量 / 表 / 键" 这类短输入不至于被整体丢弃。
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val ascii = StringBuilder()
        val cjk = StringBuilder()

        fun flushAscii() {
            if (ascii.isNotEmpty()) {
                tokens.add(ascii.toString().lowercase())
                ascii.setLength(0)
            }
        }

        fun flushCjk() {
            if (cjk.isNotEmpty()) {
                val run = cjk.toString()
                if (run.length == 1) {
                    tokens.add(run)
                } else {
                    for (i in 0 until run.length - 1) tokens.add(run.substring(i, i + 2))
                }
                cjk.setLength(0)
            }
        }

        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF -> cjk.append(ch)
                ch.isLetterOrDigit() -> ascii.append(ch)
                else -> {
                    flushAscii()
                    flushCjk()
                }
            }
        }
        flushAscii()
        flushCjk()
        return tokens
    }

    /**
     * 按与 [query] 的 BM25 相关性对 [docs] 排序，返回前 [limit] 条（分数 ≥
     * [MIN_RELEVANT_SCORE]）。零命中返回空列表 —— 调用方自行决定回退语义。
     */
    fun rank(query: String, docs: List<String>, limit: Int = 5): List<Scored> {
        if (query.isBlank() || docs.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).distinct()
        if (queryTokens.isEmpty()) return emptyList()

        val docTokens = docs.map { tokenize(it) }
        val avgLen = docTokens.sumOf { it.size }.toDouble() / docs.size
        // 文档频率（包含该词元的文档数）→ IDF
        val idf = mutableMapOf<String, Double>()
        for (term in queryTokens) {
            val df = docTokens.count { term in it }
            if (df > 0) idf[term] = kotlin.math.ln(1.0 + (docs.size - df + 0.5) / (df + 0.5))
        }
        if (idf.isEmpty()) return emptyList()

        val scored = docs.indices.mapNotNull { i ->
            val tokens = docTokens[i]
            if (tokens.isEmpty()) return@mapNotNull null
            val tf = mutableMapOf<String, Int>()
            tokens.forEach { tf[it] = (tf[it] ?: 0) + 1 }
            var score = 0.0
            for ((term, idfValue) in idf) {
                val freq = tf[term] ?: continue
                val norm = K1 * (1.0 - B + B * tokens.size / (avgLen.coerceAtLeast(1.0)))
                score += idfValue * freq * (K1 + 1) / (freq + norm)
            }
            if (score >= MIN_RELEVANT_SCORE) Scored(i, score) else null
        }
        return scored.sortedByDescending { it.score }.take(limit.coerceAtLeast(1))
    }

    }
