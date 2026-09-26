package com.apex.agent.core.engine.persona

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * ═══ SillyTavern 角色卡解析器（4-e）═══
 *
 * 学习自 RikkaHub 的 AssistantImporter（spec 字段路由 TAVERN_PARSERS 策略
 * 表：chara_card_v2 / chara_card_v3 两个解析器 + V1 平面回退）与 Operit
 * 的 TavernCharacterCard 兼容层，落地为纯 JVM 的单对象解析入口：
 *
 * ```
 * parseJson(json)      V1 平面 / V2-V3 嵌套路由 → CharacterCardV2（归一）
 * toPersona(card, ...) RikkaHub 组合 → PersonaCard（roleDefinition / rolePrompt /
 *                      firstMessage / lorebook / tags）
 * ```
 *
 * **路由规则**（[parseJson]）：
 * - spec == chara_card_v2 / chara_card_v3，或无 spec 但根对象带 data
 *   对象 → 嵌套路由（内容字段取自 data，spec 头取自根，根缺失回退 data）；
 * - spec == chara_card_v1 / 1.0，或无 spec 也无 data → V1 平面路由；
 * - spec 为未知值且无 data 对象 → [CardParseError.UnknownSpec]；
 * - JSON 解析失败 / 根不是对象 / data 字段形状损坏 → [CardParseError.NotJson]；
 * - 任何路由解析出的 name 为空 → [CardParseError.MissingName]。
 *
 * **防御式纪律**：Lenient Json（ignoreUnknownKeys + coerceInputValues），
 * 字段形状差异由模型层的宽容序列化器吸收（extensions / character_book），
 * 一切失败折叠进 Result 的类型化错误——绝不向上抛。
 */
object CharacterCardParser {

    private val LENIENT = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /** 内容字段嵌套在 data 对象下的规格。 */
    private val NESTED_SPECS = setOf("chara_card_v2", "chara_card_v3")

    /** 显式声明为 V1 平面结构的规格。 */
    private val FLAT_SPECS = setOf("chara_card_v1", "1.0")

    /**
     * 解析 SillyTavern 卡 JSON（V1 平面 / V2 / V3），失败折叠为携带
     * [CardParseError] 的 Result.failure（不抛异常）。
     *
     * V2 声明嵌套但 data 对象缺失/损坏时回退平面解析（防御：吸收部分
     * 导出工具的畸形输出）；平面路由同样宽容——一切缺字段走默认值，
     * 只有 name 缺失是硬错误（卡无名即无意义）。
     */
    fun parseJson(json: String): Result<CharacterCardV2> {
        if (json.isBlank()) {
            return Result.failure(CardParseException(CardParseError.NotJson, "empty input"))
        }
        val root = try {
            LENIENT.parseToJsonElement(json)
        } catch (e: Exception) {
            return Result.failure(CardParseException(CardParseError.NotJson, e.message))
        }
        val obj = root as? JsonObject
            ?: return Result.failure(CardParseException(CardParseError.NotJson, "root is not a JSON object"))
        val spec = obj["spec"].stringOrNull()
        val hasDataObject = obj["data"] is JsonObject
        return when {
            spec in NESTED_SPECS || (spec == null && hasDataObject) -> parseNested(obj, spec)
            spec in FLAT_SPECS || spec == null -> parseFlat(obj, spec)
            hasDataObject -> parseNested(obj, spec)
            else -> Result.failure(
                CardParseException(CardParseError.UnknownSpec, "spec=$spec")
            )
        }
    }

    /**
     * 卡 → 人设组合（RikkaHub AssistantImporter 的组合逻辑）：
     *
     * - roleDefinition：身份行 "You are roleplaying as {name}." + 非空的
     *   Description / Personality / Scenario 三个 "## " 分节（空段落整体
     *   省略，不产生空标题）；
     * - rolePrompt：system_prompt 与 post_history_instructions 空行相接
     *   （两者皆空则空串——AgentConfig 空字段 = 行为零变化）；
     * - firstMessage：first_mes；为空回退首个非空 alternate greeting；
     * - lorebook / tags：原样携带；
     * - 时间戳：createdAt / updatedAt 均取 clock()（保存时 Registry 会
     *   重盖 updatedAt）；source 默认 [PersonaSource.IMPORTED]，导入入口
     *   （importTavernJson / importTavernPng）再覆盖为具体来源。
     *
     * 组合规则的文档化契约见 [PersonaMapping.DEFAULT]。
     */
    fun toPersona(card: CharacterCardV2, id: String, clock: () -> Long): PersonaCard {
        val now = clock()
        return PersonaCard(
            id = id,
            name = card.name,
            roleDefinition = composeRoleDefinition(card),
            rolePrompt = composeRolePrompt(card),
            firstMessage = card.firstMes.trim().ifBlank {
                card.alternateGreetings.firstOrNull { it.isNotBlank() }?.trim() ?: ""
            },
            lorebook = card.characterBook,
            tags = card.tags,
            source = PersonaSource.IMPORTED,
            createdAt = now,
            updatedAt = now
        )
    }

    // ═══ 内部：路由与组合 ═══

    /** 嵌套路由：内容字段取自 data 对象，spec 头取根优先。 */
    private fun parseNested(obj: JsonObject, spec: String?): Result<CharacterCardV2> {
        val dataObj = obj["data"] as? JsonObject
            // 声明嵌套但 data 缺失/非对象 → 平面回退（spec 保真透传）
            ?: return parseFlat(obj, spec)
        val card = try {
            LENIENT.decodeFromJsonElement(CharacterCardV2.serializer(), dataObj)
        } catch (e: Exception) {
            return decodeFailure(dataObj, e)
        }
        val merged = card.copy(
            spec = obj["spec"].stringOrNull() ?: card.spec,
            specVersion = obj["spec_version"].stringOrNull() ?: card.specVersion
        )
        return validateName(merged)
    }

    /** 平面路由（V1）：整卡字段直接在根对象上。 */
    private fun parseFlat(obj: JsonObject, spec: String?): Result<CharacterCardV2> {
        val card = try {
            LENIENT.decodeFromJsonElement(CharacterCardV2.serializer(), obj)
        } catch (e: Exception) {
            return decodeFailure(obj, e)
        }
        val merged = card.copy(
            spec = spec ?: "chara_card_v1",
            specVersion = obj["spec_version"].stringOrNull() ?: "1.0"
        )
        return validateName(merged)
    }

    /**
     * 字段形状损坏时的错误折叠：容器里有 name → 数据损坏（NotJson 语义，
     * 附带异常摘要）；没有 name → MissingName（无名卡优先报缺名）。
     */
    private fun decodeFailure(container: JsonObject, e: Exception): Result<CharacterCardV2> {
        return if (container["name"].stringOrNull().isNullOrBlank()) {
            Result.failure(CardParseException(CardParseError.MissingName, e.message))
        } else {
            Result.failure(CardParseException(CardParseError.NotJson, e.message))
        }
    }

    /** name 空白 → MissingName（硬错误）。 */
    private fun validateName(card: CharacterCardV2): Result<CharacterCardV2> {
        return if (card.name.isBlank()) {
            Result.failure(CardParseException(CardParseError.MissingName))
        } else {
            Result.success(card)
        }
    }

    /** roleDefinition 组合：身份行 + 非空分节（RikkaHub 组合）。 */
    private fun composeRoleDefinition(card: CharacterCardV2): String {
        val sections = listOf(
            "## Description" to card.description.trim(),
            "## Personality" to card.personality.trim(),
            "## Scenario" to card.scenario.trim()
        ).filter { it.second.isNotEmpty() }
        if (sections.isEmpty()) {
            return "You are roleplaying as ${card.name.trim()}."
        }
        val body = sections.joinToString("\n\n") { (heading, text) -> "$heading\n$text" }
        return "You are roleplaying as ${card.name.trim()}.\n\n$body"
    }

    /** rolePrompt 组合：system_prompt + post_history_instructions 空行相接。 */
    private fun composeRolePrompt(card: CharacterCardV2): String {
        return listOf(card.systemPrompt.trim(), card.postHistoryInstructions.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }
}

/**
 * 类型化解析错误（任务书约定：sealed 错误折叠进 Result 消息）。
 *
 * - [NotJson]：输入不是合法卡 JSON（解析失败 / 根非对象 / 字段形状损坏）；
 * - [MissingName]：卡缺少必填的 name 字段（或为空白）；
 * - [UnknownSpec]：spec 声明了未知规格且无 data 对象可回退。
 */
sealed class CardParseError {
    /** 输入不是合法卡 JSON。 */
    object NotJson : CardParseError()

    /** 缺少必填字段 name。 */
    object MissingName : CardParseError()

    /** spec 未知且无 data 回退。 */
    object UnknownSpec : CardParseError()

    /** 人类可读描述（折叠进异常消息，测试断言锚点）。 */
    fun describe(): String = when (this) {
        NotJson -> "input is not a valid SillyTavern card JSON document"
        MissingName -> "card is missing the required field 'name'"
        UnknownSpec -> "card declares an unknown spec version"
    }
}

/**
 * 解析失败异常：携带 [error] 类型化错误 + detail 摘要（cause message），
 * 作为 Result.failure 的载体向上折叠（调用方只看消息或类型，不 catch
 * 具体异常类）。
 */
class CardParseException(
    val error: CardParseError,
    detail: String? = null
) : Exception(
    if (detail.isNullOrBlank()) error.describe() else error.describe() + " (" + detail + ")"
)
