package com.apex.agent.core.engine.templates

import kotlinx.serialization.Serializable

/**
 * 引用变量名扫描（预编译，全实例共享）：与 promptvars 包的展开器词法
 * 保持一致——跳过转义分支、捕获名称组、忽略默认值段与空白。
 */
private val REFERENCE_REGEX: Regex = Regex(
    """\\[{]{2}|[{]{2}\s*([a-zA-Z][a-zA-Z0-9_]*)"""
)

/** 模板 ID 规范：小写字母/数字/下划线/连字符，1-64 位（防路径穿越与超长）。 */
val TEMPLATE_ID_PATTERN: Regex = Regex("[a-z0-9_-]{1,64}")

/** 模板分类（内置模板覆盖前五类；用户自建默认 CUSTOM）。 */
@Serializable
enum class TemplateCategory {
    CODING,
    WRITING,
    ANALYSIS,
    TRANSLATION,
    PRODUCTIVITY,
    CUSTOM
}

/**
 * 模板声明的变量（表单提示 + 必填校验 + 默认值）。
 *
 * @param name 变量名（与内容里的 {{name}} 对应）
 * @param description 用途描述（表单占位提示）
 * @param required true = 渲染时缺失则判失败并列出
 * @param defaultValue 非空默认值 = overrides 缺位时的取值
 */
@Serializable
data class TemplateVariable(
    val name: String,
    val description: String = "",
    val required: Boolean = true,
    val defaultValue: String = ""
)

/**
 * 提示词模板（学习 Operit 的 prompt/config 库与 apex 的 ModePresets
 * 模式：core 层 @Serializable 数据类 + 文件持久化 + 内置不可删可复制）。
 *
 * 全字段带默认值——旧版本 JSON 反序列化安全（ignoreUnknownKeys +
 * 缺字段回退默认），与 [PromptTemplateRegistry] 的宽容读策略配合。
 *
 * @param id 稳定标识（内置以 builtin_ 前缀；用户模板自定义 slug）
 * @param name 展示名
 * @param description 用途说明
 * @param category 分类（UI 分组）
 * @param content 模板正文（可含 {{var}} 与 {{var|default}}）
 * @param variables 声明的变量（渲染表单 + 必填校验）
 * @param tags 检索标签
 * @param isBuiltIn true = 内置模板（不可删除；重播种可还原）
 * @param createdAt 创建时间戳（0 = 未记录）
 * @param updatedAt 最近修改时间戳（save 时由注册表盖戳）
 * @param usageCount 被渲染次数（引擎 render 成功后自增）
 */
@Serializable
data class PromptTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val category: TemplateCategory = TemplateCategory.CUSTOM,
    val content: String,
    val variables: List<TemplateVariable> = emptyList(),
    val tags: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val usageCount: Long = 0
) {
    /**
     * 扫描 content 实际引用的变量名（规范小写集合；转义占位符不计）。
     * 引擎与测试用它校验「声明变量与正文引用一致」。
     */
    fun referencedVariables(): Set<String> {
        val seen = LinkedHashSet<String>()
        for (m in REFERENCE_REGEX.findAll(content)) {
            if (m.value.startsWith("\\")) continue
            seen.add(m.groupValues[1].lowercase())
        }
        return seen
    }

    companion object {
        /** 内置模板 id 前缀（删除保护 + UI 锁标判定，对齐 ModePreset 惯例）。 */
        const val BUILTIN_ID_PREFIX = "builtin_"

        /** 生成新用户模板 id（时间戳 + 随机后缀，跨进程基本不碰撞）。 */
        fun newId(): String =
            "tpl_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

/** 持久化信封（单文件 templates.json 的顶层结构）。 */
@Serializable
data class TemplateLibrary(
    val templates: List<PromptTemplate> = emptyList(),
    val schemaVersion: Int = 1
)

/**
 * ═══ 内置模板库（10 套，中文正文）═══
 *
 * 学习 Operit 的 FunctionalPrompts/SystemPromptConfig 内置指令哲学：
 * 开箱即用的「角色 + 硬性约束 + 输出格式」三段式；每套声明变量清单，
 * 正文用 {{var}} 引用——经 [PromptTemplateEngine] 渲染时与提示词变量
 * 引擎的内置变量（模型/时间/设备等）无缝拼接。
 *
 * 不可删除（注册表拒绝）；reseedBuiltIns(force=true) 可还原被改动的
 * 内容。id 全部满足 [TEMPLATE_ID_PATTERN]。
 */
object BuiltinPromptTemplates {

    val ALL: List<PromptTemplate> = listOf(
        PromptTemplate(
            id = "builtin_code_review",
            name = "代码审查",
            description = "按严重度分级输出结构化评审结论",
            category = TemplateCategory.CODING,
            tags = listOf("代码", "评审", "质量"),
            isBuiltIn = true,
            content = """
                你是一位资深代码评审专家。请对下面的代码进行严格评审。

                - 代码语言：{{language}}
                - 重点关注：{{focus}}

                待评审代码：
                {{code}}

                请按以下结构输出：
                1. 总体结论：通过 / 有条件通过 / 需修改
                2. 问题清单：按严重程度分级（🔴 阻断 / 🟡 重要 / 🟢 次要），每条包含位置、原因与具体修复建议
                3. 关键修复示例：仅在与结论相关时给出
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("code", "待评审的代码片段", required = true),
                TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定"),
                TemplateVariable("focus", "重点关注维度", required = false, defaultValue = "正确性、安全性、性能与可维护性")
            )
        ),
        PromptTemplate(
            id = "builtin_commit_message",
            name = "提交信息生成",
            description = "从变更说明生成规范的 Git 提交信息",
            category = TemplateCategory.PRODUCTIVITY,
            tags = listOf("Git", "提交", "规范"),
            isBuiltIn = true,
            content = """
                请为下面的代码变更生成一条 Git 提交信息。

                - 提交风格：{{style}}

                变更内容：
                {{changes}}

                要求：
                1. 首行为不超过 50 字符的摘要，形如「类型(范围): 概述」
                2. 空一行后写详细说明，解释为什么这样改而不是罗列 diff
                3. 使用中文书写，专有名词与标识符保持原文
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("changes", "变更说明或 diff 内容", required = true),
                TemplateVariable("style", "提交风格", required = false, defaultValue = "conventional commits")
            )
        ),
        PromptTemplate(
            id = "builtin_translate",
            name = "专业翻译",
            description = "保留格式与术语的双语互译",
            category = TemplateCategory.TRANSLATION,
            tags = listOf("翻译", "双语"),
            isBuiltIn = true,
            content = """
                请将下面的文本翻译为{{target_language}}。

                - 领域：{{domain}}

                要求：
                1. 保留原文的格式、语气与专有名词
                2. 术语遵循该领域的主流社区译法，不逐字硬译
                3. 只输出译文，不附加任何解释
                4. 混合语言文本需全部归一到目标语言

                原文：
                {{text}}
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("text", "待翻译文本", required = true),
                TemplateVariable("target_language", "目标语言（如：英文）", required = true),
                TemplateVariable("domain", "文本所属领域", required = false, defaultValue = "通用")
            )
        ),
        PromptTemplate(
            id = "builtin_summarize",
            name = "内容总结",
            description = "一句话结论 + 要点列表的结构化总结",
            category = TemplateCategory.ANALYSIS,
            tags = listOf("总结", "提炼"),
            isBuiltIn = true,
            content = """
                请总结下面的内容。

                - 总结视角：{{perspective}}
                - 长度要求：{{length}}

                要求：
                1. 先给一句话结论，再列 3-5 条要点
                2. 保留关键数字、名称与结论，不添加原文没有的信息
                3. 要点按重要性排序

                原文：
                {{content}}
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("content", "待总结的原文", required = true),
                TemplateVariable("perspective", "总结视角", required = false, defaultValue = "客观中立"),
                TemplateVariable("length", "长度要求", required = false, defaultValue = "200 字以内")
            )
        ),
        PromptTemplate(
            id = "builtin_refactor_plan",
            name = "重构方案",
            description = "分步可验证的重构计划与风险清单",
            category = TemplateCategory.CODING,
            tags = listOf("重构", "架构"),
            isBuiltIn = true,
            content = """
                请为下面的代码制定重构方案。

                - 重构目标：{{goal}}
                - 约束条件：{{constraints}}

                现状代码：
                {{code}}

                输出要求：
                1. 现状问题诊断（按影响排序）
                2. 分步重构方案（每步可独立验证）
                3. 每步的风险与回滚方式
                4. 重构完成后的验证清单
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("code", "现状代码", required = true),
                TemplateVariable("goal", "重构目标", required = true),
                TemplateVariable("constraints", "约束条件（兼容性/性能/范围）", required = false, defaultValue = "无")
            )
        ),
        PromptTemplate(
            id = "builtin_bug_report",
            name = "BUG 报告",
            description = "整理为结构化缺陷报告并给出排查建议",
            category = TemplateCategory.CODING,
            tags = listOf("缺陷", "排查"),
            isBuiltIn = true,
            content = """
                请根据以下信息整理一份结构化的 BUG 报告。

                - 运行环境：{{environment}}
                - 复现步骤：
                {{reproduce}}
                - 期望行为：{{expected}}
                - 实际行为：{{actual}}
                - 症状描述：{{symptom}}

                输出要求：包含标题、严重程度评估、可能原因分析（按可能性排序）与下一步排查建议。
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("symptom", "症状一句话描述", required = true),
                TemplateVariable("reproduce", "复现步骤", required = true),
                TemplateVariable("expected", "期望行为", required = true),
                TemplateVariable("actual", "实际行为", required = true),
                TemplateVariable("environment", "运行环境（设备/系统/版本）", required = false, defaultValue = "未提供")
            )
        ),
        PromptTemplate(
            id = "builtin_api_design",
            name = "API 设计",
            description = "端点清单 + 请求响应结构 + 错误码设计",
            category = TemplateCategory.CODING,
            tags = listOf("API", "设计"),
            isBuiltIn = true,
            content = """
                请为以下需求设计一组 API。

                - API 风格：{{style}}
                - 需求描述：{{requirement}}

                输出要求：
                1. 资源与端点清单（方法、路径、一句话简述）
                2. 每个端点的请求与响应结构（字段、类型、必填、示例）
                3. 错误码设计（码、含义、触发条件）
                4. 分页、鉴权、幂等等横切关注点说明
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("requirement", "业务需求描述", required = true),
                TemplateVariable("style", "API 风格", required = false, defaultValue = "RESTful")
            )
        ),
        PromptTemplate(
            id = "builtin_test_cases",
            name = "测试用例",
            description = "等价类先行、单行为单测的用例生成",
            category = TemplateCategory.CODING,
            tags = listOf("测试", "单元测试"),
            isBuiltIn = true,
            content = """
                请为下面的代码编写单元测试。

                - 测试框架：{{framework}}

                被测代码：
                {{code}}

                要求：
                1. 先列出等价类与边界条件，再写测试
                2. 覆盖正常路径、边界值与异常路径
                3. 每个测试只验证一个行为，命名说明意图
                4. 不依赖网络与真实文件系统（必要时手写 fake）
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("code", "被测代码", required = true),
                TemplateVariable("framework", "测试框架", required = false, defaultValue = "JUnit")
            )
        ),
        PromptTemplate(
            id = "builtin_tech_doc",
            name = "技术文档",
            description = "面向新成员的概念型技术文档",
            category = TemplateCategory.WRITING,
            tags = listOf("文档", "写作"),
            isBuiltIn = true,
            content = """
                请围绕「{{topic}}」撰写一篇技术文档。

                - 目标读者：{{audience}}

                结构要求：
                1. 一句话概述与适用场景
                2. 背景与动机（解决什么问题）
                3. 核心概念与工作原理
                4. 使用示例（可运行的代码或命令）
                5. 常见问题与注意事项

                参考代码（如有）：
                {{code}}
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("topic", "文档主题", required = true),
                TemplateVariable("audience", "目标读者", required = false, defaultValue = "新加入团队的开发者"),
                TemplateVariable("code", "参考代码", required = false, defaultValue = "无")
            )
        ),
        PromptTemplate(
            id = "builtin_regex_explain",
            name = "正则解释",
            description = "逐段拆解正则表达式并给出匹配示例",
            category = TemplateCategory.ANALYSIS,
            tags = listOf("正则", "解释"),
            isBuiltIn = true,
            content = """
                请解释下面的正则表达式。

                - 正则表达式：{{pattern}}
                - 示例文本：{{sample}}

                输出要求：
                1. 整体作用一句话
                2. 逐段拆解：每段语法 + 含义
                3. 匹配与不匹配的示例各至少一个
                4. 可能的性能陷阱（回溯灾难等）与简化建议
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("pattern", "待解释的正则表达式", required = true),
                TemplateVariable("sample", "示例文本", required = false, defaultValue = "无")
            )
        )
    )

    /** 已知内置模板 id 集合（导入防御与 isBuiltIn 规范化判定）。 */
    val BUILTIN_IDS: Set<String> = ALL.map { it.id }.toSet()

    /** 是否为已知内置模板 id。 */
    fun isKnownBuiltInId(id: String): Boolean = id in BUILTIN_IDS
}
