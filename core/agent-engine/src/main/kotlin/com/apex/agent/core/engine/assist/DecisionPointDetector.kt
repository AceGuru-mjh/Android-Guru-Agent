package com.apex.agent.core.engine.assist

/**
 * ═══ 人工辅助模式的决策点检测器（#168）═══
 *
 * HUMAN_ASSIST 模式此前只有提示词差异（system prompt 要求模型主动调用
 * ask_user_choice），但模型经常**先写出「方案 A … 方案 B …」的对比文本再
 * 顺口带一个问句**，而不是真的调用工具——纯文本轮次一结束引擎就
 * ResponseComplete 收尾，人工介入落空。
 *
 * 本检测器在引擎的纯文本响应分支上做**后置兜底**：从 assistant 文本中
 * 识别「多方案待决」结构，检出即由 [HumanAssistFlow] 自动转
 * ask_user_choice 闭环（UserInputRequired → 等待 → 用户选择回填）。
 *
 * ## 识别规则（中英双语）
 *
 * 1. **编号方案模式**（结构化最强，优先）：
 *    - 中文：`方案一 / 方案1 / 方案A / 方案（B）：…` —— 前缀「方案」+ 单字符
 *      编号（中文数字 / 阿拉伯数字 / 拉丁字母），后接 `：` `:` `、` 或直接空格；
 *    - 英文：`Option A: … / option 1 … / Approach B — …`（大小写不敏感）；
 *    - 独立编号行：`A. xxx` / `B) xxx` / `1) xxx` / `2. xxx`（行首锚定，
 *      避免「方案 a 和 b」这类内联字母被误切）；
 *    - emoji 序号：`① ② ③` 与 `1️⃣ 2️⃣ 3️⃣`（keycap 由数字 + U+FE0F +
 *      U+20E3 三个 code point 组成，正则以字面量书写）。
 *    每个方案提取 label（方案名/首句）与 detail（后续句子，至多 80 字符）。
 *    **要求至少命中 2 个不同编号**才构成决策点——单个方案的陈述不算。
 *
 * 2. **疑问选择模式**（对比连词 + 问句）：
 *    - 中文：`……还是……？`（含「还是」且整句以问号收尾）；
 *    - 英文：`should I … or …?` / `do you want … or …?` / `either … or …?`；
 *    - 短语级 `A 还是 B` / `A or B`（问句内以 or/还是 分隔的两个名词短语）。
 *    提取连词两端的候选项作为两个 [DecisionOption]。
 *
 * 3. **显式请求降级**：文本含「需要你确认 / 请选择 / 请确认 / 你希望 /
 *    which do you prefer / please choose / please confirm」等显式人工决策
 *    请求，但规则 1/2 都没有提取出结构化选项 → 降级为双选项
 *    （继续 / 停止），保证「模型明说要人拍板」的场合绝不静默通过。
 *
 * ## 排除规则
 *
 * - **代码块内容不检测**：fenced（``` … ```）与行内（` … `）代码先整体
 *   剥离——代码里出现 `option 1` / `a or b` 是常态，检测会大量误报；
 * - **单个方案陈述不算**（无对比连词、只有一个编号方案）——模型只是
 *   在陈述它的做法，没有把选择权交给用户；
 * - 空文本 / 纯空白直接返回 null。
 *
 * 返回 null = 无决策点，引擎照常 ResponseComplete 收尾。
 */
object DecisionPointDetector {

    /** 检测出的单个候选项：key = 编号或序号，label = 展示名，detail = 补充说明。 */
    data class DecisionOption(
        val key: String,
        val label: String,
        val detail: String = ""
    )

    /** 检测出的决策点：question + 结构化选项 + 触发依据（日志/UI 可解释）。 */
    data class DecisionPoint(
        val question: String,
        val options: List<DecisionOption>,
        val rationale: String
    )

    /** detail 提取上限：选项补充说明最多保留的字符数（长方案截断展示）。 */
    private const val MAX_DETAIL_CHARS = 80

    /** label 提取上限：过长的方案名截断（单选项展示空间有限）。 */
    private const val MAX_LABEL_CHARS = 60

    /** 一次决策点最多保留的选项数（防「1. 2. 3. … 20.」长清单刷屏）。 */
    private const val MAX_OPTIONS = 6

    // ═══════════════════════════════════════════════════════
    // 规则 1：编号方案模式
    // ═══════════════════════════════════════════════════════

    /** 中文方案编号：`方案一：` / `方案1、` / `方案A ` / `方案（B）`。 */
    private val zhSchemeRegex = Regex(
        """方案\s*[（(]?\s*([一二三四五六七八九十0-9A-Za-z])\s*[)）]?\s*[：:、.．\->—-]?\s*"""
    )

    /** 英文方案编号：`Option A:` / `option 1` / `Approach B —`（忽略大小写）。 */
    private val enOptionRegex = Regex(
        """\b(?:option|approach|choice)\s+([A-Da-d1-4])\b\s*[：:.\->—-]?\s*""",
        RegexOption.IGNORE_CASE
    )

    /** 独立编号行：行首 `A. xxx` / `B) xxx` / `1) xxx` / `2. xxx`（MULTILINE 行首锚定）。 */
    private val letteredLineRegex = Regex(
        """(?m)^[ \t]*(?:([A-Da-d])\s*[.、)）]|\*?\s*([1-4])\s*[.、)）])\s*(.+)$"""
    )

    /** 圆圈序号：`① xxx` / `②：xxx`。 */
    private val circledRegex = Regex("""([①②③④⑤⑥])\s*[：:.]?\s*(.+)""")

    /** keycap 序号：`1️⃣ xxx`（字面量 = 数字 + U+FE0F 变体选择符 + U+20E3 组合键帽）。 */
    private val keycapRegex = Regex("""([1-9])\uFE0F\u20E3\s*[：:.]?\s*(.+)""")

    // ═══════════════════════════════════════════════════════
    // 规则 2：疑问选择模式
    // ═══════════════════════════════════════════════════════

    /** 中文对比连词 + 问句（句内含「还是」且整句以中/英问号收尾）。 */
    private val zhEitherOrRegex = Regex("""([^。！？!?\n]{2,60}?)[还]是([^。！？!?\n]{2,60}?)[？?]""")

    /** 英文对比连词问句（should I / do you want / either … or …?）。 */
    private val enEitherOrRegex = Regex(
        """(?:should\s+(?:i|we)|do\s+you\s+(?:want|prefer)|either)\b(.{2,80}?)[，,]?\s+or\s+(.{2,80}?)[？?]""",
        RegexOption.IGNORE_CASE
    )

    // ═══════════════════════════════════════════════════════
    // 规则 3：显式人工决策请求（降级触发词）
    // ═══════════════════════════════════════════════════════

    /** 中文显式请求词。 */
    private val zhExplicitRequestRegex =
        Regex("""(需要你(确认|选择|决定)|请(选择|确认|决定|指示)|你(希望|倾向|想要)(我|哪种)|等你(确认|选择))""")

    /** 英文显式请求词。 */
    private val enExplicitRequestRegex = Regex(
        """(which\s+(do|would)\s+you\s+(prefer|like|choose)|please\s+(choose|confirm|decide|select)|let\s+me\s+know\s+(which|your)\s+(option|choice|preference)|awaiting\s+your\s+(confirmation|choice|decision))""",
        RegexOption.IGNORE_CASE
    )

    /** 代码块剥离：fenced（```…```，可带语言标注）与行内（`…`）。 */
    private val codeBlockRegex = Regex("""```[\s\S]*?```|`[^`\n]*`""")

    /**
     * 检测 assistant 文本是否含决策点。
     *
     * @param assistantText 本轮 assistant 的完整纯文本回复（已流式落定）
     * @return 检出则返回 [DecisionPoint]；无决策点返回 null
     */
    fun detect(assistantText: String): DecisionPoint? {
        if (assistantText.isBlank()) return null
        // 排除规则：代码块内容不参与检测（正则剥离 → 占位空行）
        val cleanText = codeBlockRegex.replace(assistantText, "\n")
        if (cleanText.isBlank()) return null

        // 优先级：结构化方案 > 疑问选择 > 显式请求降级。
        detectNumberedSchemes(cleanText)?.let { return it }
        detectEitherOrQuestion(cleanText)?.let { return it }
        return detectExplicitRequestFallback(cleanText)
    }

    // ═══════════════════════════════════════════════════════
    // 规则 1 实现：编号方案
    // ═══════════════════════════════════════════════════════

    /**
     * 编号方案提取：四类编号标记各扫一遍，按 (编号种类, 编号值) 去重
     * （同一方案可能同时命中 `方案A` 与 `A. xxx` 行首标记），收集 ≥2 个
     * 不同编号即构成决策点。
     */
    private fun detectNumberedSchemes(text: String): DecisionPoint? {
        val found = linkedMapOf<String, DecisionOption>()

        // 1a. 中文「方案X」标记（匹配点之后的文本 = label 起点）
        collectLabeledSchemes(
            text,
            zhSchemeRegex,
            keyTransform = { m -> "zh:${m.groupValues[1].uppercase()}" },
            keyDisplay = { m -> m.groupValues[1] },
            found
        )

        // 1b. 英文 Option/Approach/Choice 标记
        collectLabeledSchemes(
            text,
            enOptionRegex,
            keyTransform = { m -> "en:${m.groupValues[1].uppercase()}" },
            keyDisplay = { m -> m.groupValues[1].uppercase() },
            found
        )

        // 1c. 行首独立编号（A. / B) / 1) / 2.）
        letteredLineRegex.findAll(text).forEach { m ->
            val keyChar = (m.groupValues[1].ifEmpty { m.groupValues[2] })
            val label = m.groupValues[3].trim()
            if (keyChar.isNotBlank() && label.isNotBlank()) {
                found.putIfAbsent("line:${keyChar.uppercase()}", optionOf(keyChar.uppercase(), label))
            }
        }

        // 1d. 圆圈序号 ①②③（每行一个）
        circledRegex.findAll(text).forEach { m ->
            val key = m.groupValues[1]
            val label = m.groupValues[2].trim()
            if (label.isNotBlank()) {
                found.putIfAbsent("circled:$key", optionOf(circleKeyToLabel(key), label))
            }
        }

        // 1e. keycap 序号 1️⃣2️⃣3️⃣
        keycapRegex.findAll(text).forEach { m ->
            val key = m.groupValues[1]
            val label = m.groupValues[2].trim()
            if (label.isNotBlank()) {
                found.putIfAbsent("keycap:$key", optionOf(key, label))
            }
        }

        if (found.size < 2) return null
        val options = found.values.take(MAX_OPTIONS)
        return DecisionPoint(
            question = synthesizeQuestion(text),
            options = options,
            rationale = "numbered-schemes(${found.keys.joinToString(",")})"
        )
    }

    /** 「方案X」/「Option X」类标记收集：匹配点后取同句/同行文本作 label。 */
    private fun collectLabeledSchemes(
        text: String,
        regex: Regex,
        keyTransform: (MatchResult) -> String,
        keyDisplay: (MatchResult) -> String,
        found: MutableMap<String, DecisionOption>
    ) {
        regex.findAll(text).forEach { m ->
            val after = text.substring(m.range.last + 1).trimStart()
            if (after.isBlank()) return@forEach
            val key = keyTransform(m)
            if (!found.containsKey(key)) {
                found[key] = optionOf(keyDisplay(m), after)
            }
        }
    }

    /**
     * 从标记后的原始文本切出 label（同句/同行首段）与 detail（同句后续，
     * ≤80 字符）。
     *
     * 同行约束：方案列表通常一方案一行，跨行取 detail 会把「下一个方案的
     * 全文」当成上一方案的补充说明（语义污染 + 选项菜单串行）。
     */
    private fun optionOf(key: String, rawText: String): DecisionOption {
        // 先截到行尾：label/detail 都不跨行。
        val lineEnd = rawText.indexOf('\n')
        val sameLine = if (lineEnd < 0) rawText else rawText.substring(0, lineEnd)
        // 句边界：中英文句号/分号/问叹号；无边界则整段当 label。
        val firstSentenceEnd = sameLine.indexOfFirst { it in "。！？!?;；" }
        val label = if (firstSentenceEnd < 0) {
            sameLine.trim().take(MAX_LABEL_CHARS)
        } else {
            sameLine.take(firstSentenceEnd).trim().take(MAX_LABEL_CHARS)
        }
        val detail = if (firstSentenceEnd < 0) "" else {
            sameLine.substring(firstSentenceEnd + 1).trim().take(MAX_DETAIL_CHARS)
        }
        return DecisionOption(
            key = key,
            label = label.ifBlank { sameLine.trim().take(MAX_LABEL_CHARS) },
            detail = detail
        )
    }

    /** 圆圈序号 → 展示编号（① → "1"）。 */
    private fun circleKeyToLabel(circled: String): String = when (circled) {
        "①" -> "1"; "②" -> "2"; "③" -> "3"; "④" -> "4"; "⑤" -> "5"; "⑥" -> "6"
        else -> circled
    }

    // ═══════════════════════════════════════════════════════
    // 规则 2 实现：疑问选择
    // ═══════════════════════════════════════════════════════

    /** 「A 还是 B？」/「should I … or …?」：提取两端候选作双选项。 */
    private fun detectEitherOrQuestion(text: String): DecisionPoint? {
        zhEitherOrRegex.find(text)?.let { m ->
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            if (left.isNotBlank() && right.isNotBlank() && left != right) {
                return DecisionPoint(
                    question = m.value.trim(),
                    options = listOf(
                        DecisionOption(key = "A", label = clipPhrase(left)),
                        DecisionOption(key = "B", label = clipPhrase(right))
                    ),
                    rationale = "zh-either-or-question"
                )
            }
        }
        enEitherOrRegex.find(text)?.let { m ->
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            if (left.isNotBlank() && right.isNotBlank() && left != right) {
                return DecisionPoint(
                    question = m.value.trim(),
                    options = listOf(
                        DecisionOption(key = "A", label = clipPhrase(left)),
                        DecisionOption(key = "B", label = clipPhrase(right))
                    ),
                    rationale = "en-either-or-question"
                )
            }
        }
        return null
    }

    /** 疑问选择两端短语裁剪：去掉句首问句引导词残留，限长展示。 */
    private fun clipPhrase(phrase: String): String {
        var s = phrase.trim()
        // 中文引导词残留（「用」「选」「采用」等 1-2 字动词开头且后接名词短语）
        for (lead in listOf("使用", "采用", "安装", "选择", "先", "直接")) {
            if (s.startsWith(lead) && s.length > lead.length + 1) {
                s = s.removePrefix(lead)
                break
            }
        }
        return s.take(MAX_LABEL_CHARS)
    }

    // ═══════════════════════════════════════════════════════
    // 规则 3 实现：显式请求降级
    // ═══════════════════════════════════════════════════════

    /**
     * 显式人工决策请求但没有结构化选项 → 降级双选项（继续 / 停止）。
     * question 取命中原句（截断到 120 字符），保证用户看到模型的原话。
     */
    private fun detectExplicitRequestFallback(text: String): DecisionPoint? {
        val zhMatch = zhExplicitRequestRegex.find(text)
        val enMatch = enExplicitRequestRegex.find(text)
        val match = zhMatch ?: enMatch ?: return null
        val sentence = sentenceAround(text, match.range.first).take(120)
        return DecisionPoint(
            question = sentence.ifBlank { match.value },
            options = listOf(
                DecisionOption(key = "continue", label = "继续 / Continue"),
                DecisionOption(key = "stop", label = "停止并说明 / Stop & explain")
            ),
            rationale = "explicit-request-fallback(${match.value.take(30)})"
        )
    }

    /** 定位 offset 所在句子（句号/问叹号/换行切分）。 */
    private fun sentenceAround(text: String, offset: Int): String {
        val start = text.lastIndexOfAny(charArrayOf('。', '！', '？', '!', '?', '\n'), startIndex = offset)
            .let { if (it < 0) 0 else it + 1 }
        val end = text.indexOfAny(charArrayOf('。', '！', '？', '!', '?', '\n'), startIndex = offset)
            .let { if (it < 0) text.length else it + 1 }
        return if (start < end) text.substring(start, end).trim() else ""
    }

    // ═══════════════════════════════════════════════════════
    // question 合成
    // ═══════════════════════════════════════════════════════

    /**
     * 编号方案模式下合成问题文本：优先取方案列表前的引导句（含问号或
     * 「如下/以下」的句子）；找不到则用中性合成句（HumanAssistFlow 会
     * 在 question 之上再拼选项编号清单）。
     */
    private fun synthesizeQuestion(text: String): String {
        // 按行找含问号的末行（模型常在方案列表尾部另起一行问「你倾向哪个？」；
        // 行级切分避免把整个方案列表吞进 question）。
        val questionSentence = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull { it.endsWith("？") || it.endsWith("?") }
        if (questionSentence != null) return questionSentence.take(120)
        // 找「如下/以下」引导行
        val leadLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("如下") || it.contains("以下") || it.contains("following") }
        if (leadLine != null) return leadLine.take(120)
        return "检测到多个候选方案，请选择一个继续："
    }
}
