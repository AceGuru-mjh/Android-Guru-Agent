package com.apex.agent.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Response parsing for the PLAN / SPEC modes of [ApexAgentEngine].
 *
 * Extracted from [ApexAgentEngine] (single-responsibility split): turning an
 * LLM response (possibly fenced or prose-wrapped) into a structured
 * [ExecutionPlan] / [ExecutionSpec] — with conservative fallbacks — is a pure
 * function family, separate from engine loop mechanics.
 *
 * Parsing philosophy: never throw. A malformed response degrades to a
 * single-step fallback plan / goal-only fallback spec that still records the
 * raw response in its reasoning for observability.
 */
internal object EngineResponseParsers {

    // ═══════════════════════════════════════════════════════
    // Plan parsing
    // ═══════════════════════════════════════════════════════

    fun parseExecutionPlan(response: String, originalTask: String): ExecutionPlan {
        val jsonStr = extractJsonFromResponse(response) ?: return fallbackPlan(response, originalTask)
        return try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject

            val goal = json["goal"]?.jsonPrimitive?.contentOrNull ?: originalTask
            val reasoning = json["reasoning"]?.jsonPrimitive?.contentOrNull
                ?: "Auto-generated plan (LLM did not provide reasoning)."
            val estimatedToolCalls = json["estimated_tool_calls"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 1
            val riskLevel = json["risk_level"]?.jsonPrimitive?.contentOrNull
                ?.let { parseRiskLevel(it) } ?: RiskLevel.MEDIUM

            val stepsArray = json["steps"]?.jsonArray ?: emptyList()
            val steps = stepsArray.mapIndexed { i, el ->
                val obj = el.jsonObject
                PlanStep(
                    index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: i,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: "Step ${i + 1}",
                    toolName = obj["tool"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    estimatedArgs = obj["estimated_args"]?.jsonPrimitive?.contentOrNull,
                    dependsOn = (obj["depends_on"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                        ?: emptyList()
                )
            }.ifEmpty {
                listOf(PlanStep(0, "Execute: $originalTask", null, null))
            }

            ExecutionPlan(
                goal = goal,
                steps = steps,
                estimatedToolCalls = estimatedToolCalls,
                riskLevel = riskLevel,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            fallbackPlan(response, originalTask)
        }
    }

    fun extractJsonFromResponse(response: String): String? {
        // Try fenced ```json ... ``` first.
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(response)
        val candidate = fenced?.groupValues?.get(1)?.trim() ?: response.trim()
        // Find first '{' and last '}' to extract the JSON object.
        val first = candidate.indexOf('{')
        val last = candidate.lastIndexOf('}')
        if (first < 0 || last < 0 || last <= first) return null
        return candidate.substring(first, last + 1)
    }

    fun parseRiskLevel(s: String): RiskLevel = when (s.lowercase().trim()) {
        "low" -> RiskLevel.LOW
        "medium" -> RiskLevel.MEDIUM
        "high" -> RiskLevel.HIGH
        "critical" -> RiskLevel.CRITICAL
        else -> RiskLevel.MEDIUM
    }

    fun fallbackPlan(response: String, originalTask: String): ExecutionPlan = ExecutionPlan(
        goal = originalTask,
        steps = listOf(PlanStep(0, "Execute: $originalTask", null, null)),
        estimatedToolCalls = 1,
        riskLevel = RiskLevel.MEDIUM,
        reasoning = "Could not parse LLM's plan JSON; falling back to single-step execution. " +
            "Raw LLM response kept for reference in the engine log.\n\n$response".take(500)
    )

    // ═══════════════════════════════════════════════════════
    // Spec parsing
    // ═══════════════════════════════════════════════════════

    fun parseExecutionSpec(response: String, originalTask: String): ExecutionSpec {
        val jsonStr = extractJsonFromResponse(response) ?: return fallbackSpec(response, originalTask)
        return try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject

            val goal = json["goal"]?.jsonPrimitive?.contentOrNull ?: originalTask
            val reasoning = json["reasoning"]?.jsonPrimitive?.contentOrNull
                ?: "Auto-generated spec (LLM did not provide reasoning)."
            val estimatedToolCalls = json["estimated_tool_calls"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 1
            val riskLevel = json["risk_level"]?.jsonPrimitive?.contentOrNull
                ?.let { parseRiskLevel(it) } ?: RiskLevel.MEDIUM

            fun parseList(key: String): List<String> = (json[key] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList()

            val requirements = parseList("requirements")
            val constraints = parseList("constraints")
            val acceptanceCriteria = parseList("acceptance_criteria")
            val deliverables = parseList("deliverables")

            if (requirements.isEmpty() && acceptanceCriteria.isEmpty() && deliverables.isEmpty()) {
                return fallbackSpec(response, originalTask)
            }

            ExecutionSpec(
                goal = goal,
                requirements = requirements,
                constraints = constraints,
                acceptanceCriteria = acceptanceCriteria,
                deliverables = deliverables,
                estimatedToolCalls = estimatedToolCalls,
                riskLevel = riskLevel,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            fallbackSpec(response, originalTask)
        }
    }

    fun fallbackSpec(response: String, originalTask: String): ExecutionSpec = ExecutionSpec(
        goal = originalTask,
        requirements = listOf("完成：$originalTask"),
        acceptanceCriteria = listOf("任务目标达成，结果可直接使用或验证。"),
        deliverables = listOf(originalTask),
        estimatedToolCalls = 1,
        riskLevel = RiskLevel.MEDIUM,
        reasoning = "Could not parse LLM's spec JSON; falling back to goal-only execution. " +
            "Raw LLM response kept for reference in the engine log.\n\n$response".take(500)
    )
}
