package com.apex.agent.core.engine.persona

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * ═══ 角色卡 / 人设系统 · 数据模型（4-e）═══
 *
 * 学习自 Operit 的 CharacterCard 体系（角色卡 + Tavern 互通 + 每角色独立
 * 工具白名单）与 RikkaHub 的 SillyTavern 卡导入器（spec 路由 + 字段组合
 * "You are roleplaying as X." + Description/Personality/Scenario 分节），
 * 落地为 apex-agent 的纯 JVM 人设层：
 *
 * ```
 * CharacterCardV2     SillyTavern 卡内部表示（V1 平面 / V2-V3 嵌套均归一到此）
 * LorebookPosition    世界书条目插入位置（ST 兼容子集，带序号）
 * LorebookEntry       世界书条目（关键词触发 + 内容）
 * Lorebook            世界书（character_book），activeEntries() 给出启用序
 * PersonaCard         apex 原生人设（AgentConfig 的 6 个人设字段的载体）
 * PersonaSource       人设来源（手建 / JSON 卡 / PNG 卡 / 通用导入）
 * PersonaMapping      卡字段 → 人设字段的组合规则表（文档化 + 测试锚点）
 * ```
 *
 * **纯 JVM 纪律**：零 Android 依赖——PNG 卡解析在 [PngCharacterCardReader]
 * 手写（不引 metadata-extractor，core 层保持零三方依赖），文件持久化在
 * [PersonaRegistry] 用纯 java.io（镜像 task 包 FileTaskStore 的原子写模式）。
 *
 * **挂点（对齐 AgentConfig 人设 6 字段）**：
 * `PersonaCard.roleDefinition → AgentConfig.roleDefinition`（这个角色是谁）、
 * `rolePrompt → AgentConfig.rolePrompt`（原样拼入 Agent Role 段）、
 * `name → AgentConfig.agentName`（"You are X" 身份行）、firstMessage 由
 * app 层作为开场白消息插入会话，lorebook 经 [PersonaRegistry.applyLorebook]
 * 按关键词触发注入。
 *
 * **防御式解析**：所有 @Serializable 字段带默认值（旧 JSON 安全——缺字段
 * 回退默认而非崩溃）；extensions 与 character_book 用宽容序列化器吸收
 * ST 生态的各种字段形状（布尔写成字符串、entries 用对象映射而非数组等），
 * 形状不对的字段折叠为默认值，绝不让整卡解析崩溃。
 */

/**
 * SillyTavern 角色卡内部表示（V1 / V2 / V3 归一）。
 *
 * V2（spec=chara_card_v2）与 V3（spec=chara_card_v3）的内容字段嵌套在
 * `data` 对象下；V1 是平面结构。两者的 JSON 键名一致（snake_case），因此
 * 归一到同一模型：解析路由见 [CharacterCardParser.parseJson]。
 *
 * @param spec 规格标识（chara_card_v1 / chara_card_v2 / chara_card_v3）
 * @param specVersion 规格版本（"2.0" / "3.0" 等）
 * @param name 角色名（卡的必填核心字段，缺失 → 解析失败）
 * @param description 角色描述（"## Description" 分节素材）
 * @param personality 性格（"## Personality" 分节素材）
 * @param scenario 场景（"## Scenario" 分节素材）
 * @param firstMes 开场白（RikkaHub 中成为 presetMessages 的第一条）
 * @param mesExample 对话示例（当前只保留字段，不注入提示词）
 * @param systemPrompt 卡自带的系统提示词 → 人设 rolePrompt 首段
 * @param postHistoryInstructions 历史后指令（ST "jailbreak" 槽）→ rolePrompt 次段
 * @param alternateGreetings 备选开场白（first_mes 为空时的回退来源）
 * @param tags 标签（来源站分类，导入后保留）
 * @param creator 作者署名
 * @param characterVersion 卡版本号
 * @param extensions 扩展字段——ST 生态里值形状任意（布尔/嵌套对象/数组），
 *   用宽容序列化器全部拍平成字符串（嵌套结构序列化为其 JSON 文本）
 * @param characterBook 世界书（V2 data.character_book；V1 一般没有）
 */
@Serializable
data class CharacterCardV2(
    val spec: String = "chara_card_v2",
    val specVersion: String = "2.0",
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
    @Serializable(with = TolerantStringMapSerializer::class)
    val extensions: Map<String, String> = emptyMap(),
    @Serializable(with = TolerantLorebookSerializer::class)
    @SerialName("character_book")
    val characterBook: Lorebook = Lorebook()
)

/**
 * 世界书条目的插入位置（SillyTavern 兼容子集，RikkaHub 注入位置的精简版）。
 *
 * order 序号即 ST 的 position 数值：BEFORE_CHAR(0) / AFTER_CHAR(1) /
 * BEFORE_AN(2) / AFTER_AN(3)（AN = Author's Note）。ST 的其余位置
 * （system_prompt / at_depth / before_anv3 等）超出本子集，解析时折叠回
 * [BEFORE_CHAR]（默认值兜底，见 [TolerantLorebookSerializer]）。
 */
@Serializable
enum class LorebookPosition(val order: Int) {
    /** 角色描述之前插入（ST position=0 / "before_char"）。 */
    BEFORE_CHAR(0),

    /** 角色描述之后插入（ST position=1 / "after_char"）。 */
    AFTER_CHAR(1),

    /** 作者注释之前插入（ST position=2 / "before_an"）。 */
    BEFORE_AN(2),

    /** 作者注释之后插入（ST position=3 / "after_an"）。 */
    AFTER_AN(3)
}

/**
 * 世界书（lorebook / world book）条目。
 *
 * 触发语义（[PersonaRegistry.applyLorebook] 实现）：任一 key 命中
 * scanDepth 窗口内的消息 → 条目触发，content 注入上下文；键匹配默认
 * 大小写不敏感，[caseSensitive] 打开精确匹配；[insertionOrder] 决定
 * 多条目同时触发时的注入顺序（升序）。
 *
 * @param keys 触发关键词列表（ST 旧字段 "key" 亦被吸收；逗号分隔字符串
 *   形状也会被拆成列表）
 * @param content 触发后注入的正文
 * @param extension ST 的 extension 槽（任意形状）——拍平为字符串保存
 * @param enabled false = 条目停用（永不触发，activeEntries 过滤）
 * @param insertionOrder 插入顺序（ST insertion_order，升序生效）
 * @param caseSensitive true = 关键词区分大小写
 * @param position 插入位置（见 [LorebookPosition]；未知值折叠默认）
 */
@Serializable
data class LorebookEntry(
    val keys: List<String> = emptyList(),
    val content: String = "",
    val extension: String = "",
    val enabled: Boolean = true,
    @SerialName("insertion_order") val insertionOrder: Int = 0,
    @SerialName("case_sensitive") val caseSensitive: Boolean = false,
    val position: LorebookPosition = LorebookPosition.BEFORE_CHAR
)

/**
 * 世界书（卡内嵌 character_book）。
 *
 * ST 生态两种 entries 形状都被 [TolerantLorebookSerializer] 归一为列表：
 * 规格版（entries 为数组）与 SillyTavern 导出版（entries 为以索引字符串
 * 为键的对象映射）。非对象形状的条目直接丢弃（防御：绝不让单个坏条目
 * 崩掉整卡）。
 *
 * @param name 世界书名（data.character_book.name）
 * @param description 世界书描述
 * @param entries 条目列表（按 JSON 出现顺序保留）
 */
@Serializable
data class Lorebook(
    val name: String = "",
    val description: String = "",
    val entries: List<LorebookEntry> = emptyList()
) {
    /**
     * 启用中的条目，按 insertionOrder 升序排列（触发注入顺序）。
     * 相同 order 保持原列表顺序（sortedBy 稳定排序）。
     */
    fun activeEntries(): List<LorebookEntry> =
        entries.filter { it.enabled }.sortedBy { it.insertionOrder }
}

/**
 * 人设来源——导入路径与 UI 展示用（PERSONA 来源徽标 / 导入冲突提示）。
 */
@Serializable
enum class PersonaSource {
    /** app 内手工创建。 */
    MANUAL,

    /** 从 SillyTavern JSON 卡导入。 */
    TAVERN_JSON,

    /** 从 SillyTavern PNG 卡（tEXt/zTXt chunk）导入。 */
    TAVERN_PNG,

    /** 通用导入（其它格式 / 未来扩展的兜底标记）。 */
    IMPORTED
}

/**
 * apex 原生人设模型——AgentConfig 6 个人设字段的持久化载体。
 *
 * 组合规则（RikkaHub 风格，详见 [PersonaMapping.DEFAULT] 与
 * [CharacterCardParser.toPersona]）：
 * - roleDefinition = "You are roleplaying as {name}." + Description /
 *   Personality / Scenario 三个分节；
 * - rolePrompt = system_prompt + post_history_instructions；
 * - firstMessage = first_mes（空则回退首个 alternate greeting）；
 * - lorebook / tags 原样携带。
 *
 * app 层激活人设时拍平进 AgentConfig：
 * `agentName = persona.name`、`roleDefinition = persona.roleDefinition`、
 * `rolePrompt = persona.rolePrompt`（AgentChatViewModel.patchConfig 通道，
 * 引擎保持纯字符串消费不感知本模型——与 AgentRole 相同的模块边界防腐）。
 *
 * @param id 持久化键（PersonaRegistry 文件名主体，[a-z0-9_-]{1,64}）
 * @param name 角色名（AgentConfig.agentName 来源）
 * @param roleDefinition 角色定义（AgentConfig.roleDefinition 来源）
 * @param rolePrompt 用户级提示词（AgentConfig.rolePrompt 来源）
 * @param firstMessage 开场白（会话首条 assistant 消息）
 * @param lorebook 关键词触发的世界书
 * @param tags 标签
 * @param source 来源
 * @param createdAt 创建时间戳（毫秒；0 = 创建时落 clock()）
 * @param updatedAt 最后更新时间戳（毫秒；保存时一律重盖）
 */
@Serializable
data class PersonaCard(
    val id: String,
    val name: String,
    val roleDefinition: String = "",
    val rolePrompt: String = "",
    val firstMessage: String = "",
    val lorebook: Lorebook = Lorebook(),
    val tags: List<String> = emptyList(),
    val source: PersonaSource = PersonaSource.MANUAL,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/**
 * 卡字段 → 人设字段的组合规则（RikkaHub AssistantImporter 组合逻辑的
 * 文档化；[CharacterCardParser.toPersona] 是唯一实现方，二者由测试锚定
 * 保持同步——改组合规则必须同步改这张表）。
 *
 * @param cardField SillyTavern 卡字段名（JSON 键）
 * @param personaField PersonaCard 目标字段名
 * @param rule 组合规则描述
 */
data class PersonaMapping(
    val cardField: String,
    val personaField: String,
    val rule: String
) {
    companion object {
        /**
         * 默认组合规则表（RikkaHub 组合）：
         * 身份行 + 三分节进 roleDefinition；system_prompt 与
         * post_history_instructions 进 rolePrompt；first_mes 进
         * firstMessage（空回退备选开场白）；世界书与标签原样携带。
         */
        val DEFAULT: List<PersonaMapping> = listOf(
            PersonaMapping("name", "roleDefinition", "身份行 \"You are roleplaying as {name}.\""),
            PersonaMapping("description", "roleDefinition", "\"## Description\" 分节，原文照录"),
            PersonaMapping("personality", "roleDefinition", "\"## Personality\" 分节，原文照录"),
            PersonaMapping("scenario", "roleDefinition", "\"## Scenario\" 分节，原文照录"),
            PersonaMapping("system_prompt", "rolePrompt", "rolePrompt 首段"),
            PersonaMapping("post_history_instructions", "rolePrompt", "追加在 system_prompt 之后，空行分隔"),
            PersonaMapping("first_mes", "firstMessage", "开场白；为空时回退首个 alternate_greetings"),
            PersonaMapping("character_book", "lorebook", "世界书原样携带（entries 形状宽容归一）"),
            PersonaMapping("tags", "tags", "标签原样携带")
        )
    }
}

// ═══ 宽容序列化器（防御式解析的载体）═══

/**
 * extensions 字段的宽容序列化器：Map<String, String> 但接受任意值形状。
 *
 * ST 生态的 extensions 值有布尔（"fav": false）、数字、嵌套对象
 * （"depth_prompt": \{ ...\}）、数组等；严格 Map<String, String> 解码会
 * 直接抛异常把整卡拒掉。本序列化器在反序列化前把每个值拍平：
 * - 基本类型 → 其字面内容（布尔 false → "false"）；
 * - JsonNull → ""；
 * - 对象/数组 → 其 JSON 文本（保留可回读的形状）。
 *
 * 序列化方向恒等透传（我们写出的一定是合法字符串表）。
 */
internal object TolerantStringMapSerializer :
    JsonTransformingSerializer<Map<String, String>>(
        MapSerializer(String.serializer(), String.serializer())
    ) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return JsonObject(obj.mapValues { (_, value) -> JsonPrimitive(flattenToString(value)) })
    }

    private fun flattenToString(value: JsonElement): String = when (value) {
        is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}

/**
 * character_book 字段的宽容序列化器：吸收 ST 生态的世界书形状差异。
 *
 * 归一规则（反序列化前逐项应用，全部为纯 JsonElement 变换）：
 * 1. entries 是对象映射（SillyTavern 导出格式，键为 "0"/"1"/...）→
 *    转为数组（值按出现顺序）；
 * 2. 非对象形状的条目（坏数据）→ 丢弃；
 * 3. 旧字段 "key" → "keys"（数组或逗号分隔字符串均接受）；
 * 4. "enabled" / "case_sensitive" 接受字符串形状（"true"/"false"/"1"/"0"）；
 * 5. "insertion_order" / "insertionOrder" 接受字符串数字；
 * 6. "position" 接受数字（ST position 枚举序号）或字符串
 *    （"before_char" 等）；未知值折叠回默认 BEFORE_CHAR（省略键）；
 * 7. "content" / "extension" 拍平为字符串（JsonNull → ""）。
 *
 * 未知键原样保留，由 Json 的 ignoreUnknownKeys 吸收。
 */
internal object TolerantLorebookSerializer :
    JsonTransformingSerializer<Lorebook>(Lorebook.serializer()) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val entriesElement = obj["entries"] ?: return element
        val normalizedEntries: JsonArray = when (entriesElement) {
            is JsonObject -> JsonArray(entriesElement.values.mapNotNull(::normalizeEntry))
            is JsonArray -> JsonArray(entriesElement.mapNotNull(::normalizeEntry))
            else -> return element
        }
        val patched = LinkedHashMap<String, JsonElement>(obj)
        patched["entries"] = normalizedEntries
        return JsonObject(patched)
    }

    /** 单条目归一：非对象 → null（丢弃）。 */
    private fun normalizeEntry(entry: JsonElement): JsonObject? {
        val obj = entry as? JsonObject ?: return null
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in obj) {
            when (key) {
                "key" -> if ("keys" !in obj) out["keys"] = normalizeKeys(value)
                "keys" -> out["keys"] = normalizeKeys(value)
                "content" -> out["content"] = JsonPrimitive(stringValue(value))
                "extension" -> out["extension"] = JsonPrimitive(stringValue(value))
                "enabled" -> out["enabled"] = JsonPrimitive(boolValue(value, default = true))
                "insertion_order", "insertionOrder" -> out["insertion_order"] =
                    JsonPrimitive(intValue(value, default = 0))
                "case_sensitive", "caseSensitive" -> out["case_sensitive"] =
                    JsonPrimitive(boolValue(value, default = false))
                "position" -> positionName(value)?.let { out["position"] = JsonPrimitive(it) }
                else -> out[key] = value
            }
        }
        return JsonObject(out)
    }

    /** keys 归一：数组照录；逗号分隔字符串拆分；其他形状 → 空数组。 */
    private fun normalizeKeys(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> value
        is JsonPrimitive -> JsonArray(
            stringValue(value).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map(::JsonPrimitive)
        )
        else -> JsonArray(emptyList())
    }

    /** position 数字/字符串 → 枚举名；未知 → null（键省略，走默认值）。 */
    private fun positionName(value: JsonElement): String? {
        val primitive = value as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { order ->
            return LorebookPosition.values().firstOrNull { it.order == order }?.name
        }
        return when (primitive.content.trim().lowercase()) {
            "before_char", "before character" -> LorebookPosition.BEFORE_CHAR.name
            "after_char", "after character" -> LorebookPosition.AFTER_CHAR.name
            "before_an", "before author's note" -> LorebookPosition.BEFORE_AN.name
            "after_an", "after author's note" -> LorebookPosition.AFTER_AN.name
            else -> null
        }
    }

    private fun stringValue(value: JsonElement): String =
        if (value is JsonNull) "" else (value as? JsonPrimitive)?.content ?: ""

    private fun boolValue(value: JsonElement, default: Boolean): Boolean {
        val primitive = value as? JsonPrimitive ?: return default
        primitive.booleanOrNull?.let { return it }
        return when (primitive.content.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> default
        }
    }

    private fun intValue(value: JsonElement, default: Int): Int {
        val primitive = value as? JsonPrimitive ?: return default
        return primitive.intOrNull ?: primitive.content.trim().toIntOrNull() ?: default
    }
}

/**
 * 通用 JsonElement 取字符串工具（JsonNull → null；非基本类型 → null）。
 * 解析路由（CharacterCardParser）用它读 spec / spec_version / name 等
 * 头部字段，形状不对一律当 null 处理（折叠，不抛）。
 */
internal fun JsonElement?.stringOrNull(): String? =
    if (this == null || this is JsonNull) null else (this as? JsonPrimitive)?.content
