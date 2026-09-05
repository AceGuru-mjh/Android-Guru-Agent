package com.apex.agent.core.engine

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolRisk

/**
 * Prompt construction for every [ApexAgentEngine] mode.
 *
 * Extracted from [ApexAgentEngine] (single-responsibility split): the engine
 * drives the mode state machines; prompt text composition is a pure,
 * side-effect-free concern and lives here as static functions.
 *
 * All builders are deterministic functions of their inputs — no engine state
 * is read — which makes them trivially testable in isolation.
 */
internal object EnginePrompts {

    /**
     * Build the system prompt shared by all modes: identity, device privilege
     * level, mode-specific behaviour rules, thinking instructions, visible
     * tool inventory, active skill injections, session context and the
     * file-operation / output-management rulebook.
     *
     * @param privilegeLevel current privilege ("ROOT" / "SHIZUKU" / anything
     *   else → normal shell guidance)
     * @param visibleTools tool list AFTER the enabledToolIds whitelist filter
     * @param skillPrompts active skill prompt injections (may be empty)
     */
    fun buildSystemPrompt(
        config: AgentConfig,
        privilegeLevel: String,
        visibleTools: List<AgentTool>,
        skillPrompts: List<String>
    ): String {
        val thinking = config.thinkingLevel.toPromptInstruction()
        return buildString {
            appendLine("You are Apex Agent, an AI assistant running on an Android device.")
            appendLine("You have access to tools for: shell commands, file operations, web browsing, memory, and device control.")
            appendLine()

            // ═══ 权限等级（让 Agent 知道什么能做、什么不能做）═══
            appendLine("## Device Privilege Level: $privilegeLevel")
            when (privilegeLevel) {
                "ROOT" -> {
                    appendLine("You have ROOT access. You can execute any command with su.")
                    appendLine("Full system access: /system, /data, mount, SELinux, iptables, etc.")
                }
                "SHIZUKU" -> {
                    appendLine("You have SHIZUKU (ADB-level) access — shell user uid=2000.")
                    appendLine("You CAN: pm install/uninstall, am start/stop, settings put/get, dumpsys,")
                    appendLine("          input tap/swipe/text/keyevent, screencap, read/write /sdcard/, getprop.")
                    appendLine("You CANNOT: modify /system, access other apps' /data/data, mount, iptables,")
                    appendLine("           modify SELinux, or ptrace other processes.")
                }
                else -> {
                    appendLine("You have NORMAL SHELL access only (no Root, no Shizuku).")
                    appendLine("Limited to: basic file ops in /sdcard and your own sandbox.")
                    appendLine("Suggest the user install Shizuku (https://shizuku.rikka.app/) for more capabilities.")
                }
            }
            appendLine()

            when (config.mode) {
                AgentMode.PLAN -> {
                    appendLine("## Mode: PLAN")
                    appendLine("You are in planning mode. Analyze the task and produce a detailed execution plan.")
                    appendLine("Do NOT execute any tools yet. Only output the plan as JSON.")
                }
                AgentMode.SPEC -> {
                    appendLine("## Mode: SPEC")
                    appendLine("You are in spec mode. Analyze the task and produce a detailed requirement specification")
                    appendLine("(goal, requirements, constraints, acceptance criteria, deliverables).")
                    appendLine("Do NOT execute any tools yet. Only output the spec as JSON.")
                }
                AgentMode.REFLECTION -> {
                    appendLine("## Mode: REFLECTION")
                    appendLine("You are in reflection mode. Quality matters more than speed: after drafting an answer,")
                    appendLine("the engine will ask you to review it critically, then revise it into the final output.")
                    appendLine("When drafting, aim for completeness, correctness, and clarity.")
                }
                AgentMode.HUMAN_ASSIST -> {
                    appendLine("## Mode: HUMAN_ASSIST (human-in-the-loop)")
                    appendLine("You are in human-assisted mode. Whenever the task involves MULTIPLE viable options —")
                    appendLine("different approaches, multiple targets/apps/files, ambiguous intent, risky or irreversible")
                    appendLine("actions, or user preference — you MUST call ask_user_choice BEFORE proceeding and wait")
                    appendLine("for the user's selection. NEVER guess when the choice materially changes the result.")
                    appendLine("Keep questions short and provide 2-6 clear options. If the user skips, pick the safest")
                    appendLine("reasonable default or stop and explain.")
                }
                AgentMode.CUSTOM -> {
                    appendLine("## Mode: CUSTOM")
                    appendLine("You are in custom mode. Follow the user's custom instructions below in addition to the")
                    appendLine("general rules. Custom instructions take priority over generic behavior guidance.")
                }
                AgentMode.BUILD -> {
                    appendLine("## Mode: BUILD")
                    appendLine("You are in build mode. Act directly. Use tools when needed.")
                    appendLine("Be efficient: prefer fewer steps, verify results between calls.")
                }
            }
            // 自定义模式：附加用户指令（拼入 system prompt）。
            if (config.mode == AgentMode.CUSTOM && !config.customInstruction.isNullOrBlank()) {
                appendLine()
                appendLine("## Custom Instructions")
                appendLine(config.customInstruction)
            }
            if (thinking.isNotBlank()) {
                appendLine()
                appendLine("## Thinking Instructions")
                appendLine(thinking)
            }
            // 「函数调用」白名单：system prompt 工具清单与实际下发的 ToolDefinition 保持一致
            // Tool System v2：按类别分组 + 高风险 ⚠ 标记 —— 40+ 工具的字母序长列表
            // 对模型只是噪音；分组清单让模型更快定位"这类任务该用哪类工具"。
            appendLine()
            appendLine("## Available Tools (${visibleTools.size})")
            val byCategory = visibleTools.groupBy { it.metadata.category }
                .toSortedMap(compareBy { it.order })
            byCategory.forEach { (category, tools) ->
                appendLine("### ${category.label} (${tools.size})")
                tools.sortedBy { it.id }.forEach { tool ->
                    val firstLine = tool.description
                        .lineSequence()
                        .firstOrNull()
                        ?.trim()
                        ?.take(160)
                        ?: ""
                    val riskMark = if (tool.metadata.risk == ToolRisk.HIGH) " ⚠️HIGH-RISK" else ""
                    appendLine("- ${tool.id}: $firstLine$riskMark")
                }
            }
            if (visibleTools.any { it.metadata.risk == ToolRisk.HIGH }) {
                appendLine()
                appendLine("Tools marked ⚠️HIGH-RISK are destructive or irreversible. The user will be")
                appendLine("asked to confirm before their first execution this session; after a denial,")
                appendLine("do NOT retry the same tool — propose an alternative approach instead.")
            }

            // Skill prompt 注入
            if (skillPrompts.isNotEmpty()) {
                appendLine()
                appendLine("## Active Skills")
                skillPrompts.forEach { prompt ->
                    appendLine(prompt)
                    appendLine()
                }
            }

            // 会话级动态上下文（当前时间 / 用户规则 / 结构化输出 / 联网搜索指令等），
            // 由 UI 层在每次发送前组装，任意模式下生效。
            if (config.additionalSystemContext.isNotBlank()) {
                appendLine()
                appendLine("## Session Context")
                appendLine(config.additionalSystemContext.trim())
            }
            appendLine()
            appendLine("## File Operation Strategy")
            appendLine("1. DISCOVER: Use glob_files or list_files to find relevant files")
            appendLine("2. UNDERSTAND: Use read_file (first 80 lines) to see structure")
            appendLine("3. LOCATE: Use search_files to find specific code/config")
            appendLine("4. READ: Use read_file with 'around' to see target area")
            appendLine("5. EDIT: Use edit_file with search-replace (never blind overwrite)")
            appendLine("6. VERIFY: Use read_file again to confirm changes are correct")
            appendLine()
            appendLine("## Output Management")
            appendLine("- All tools limit output. Check truncation notices.")
            appendLine("- For large outputs, use pagination (offset, page, scroll)")
            appendLine("- Prefer targeted queries over broad ones")
            appendLine("- Use shell pipes (| head, | grep, | tail) to pre-filter")
            appendLine()
            appendLine("## Rules")
            appendLine("- Use the most appropriate tool for each task (prefer specific tools over raw shell).")
            appendLine("- Always verify command output before proceeding.")
            appendLine("- If a command fails, analyze the error and try an alternative approach.")
            appendLine("- Keep prose concise; let tool output speak for itself.")
            appendLine("- Use ask_user_choice when the task is ambiguous, multiple targets/actions exist, an action is risky or irreversible, or user preference is required. Do NOT guess when the answer materially changes the result.")
            appendLine("- When calling ask_user_choice: keep the question short, provide 2-6 clear options, set allow_custom=true unless only fixed choices are valid. If the user skips or rejects, pick the safest reasonable default or stop.")
        }
    }

    // ═══════════════════════════════════════════════════════
    // PLAN mode prompt builders
    // ═══════════════════════════════════════════════════════

    fun buildPlanPrompt(input: String, tools: List<AgentTool>): String = buildString {
        appendLine("Analyze this task and create a detailed execution plan:")
        appendLine()
        appendLine("Task: $input")
        appendLine()
        appendLine("Available tools:")
        tools.forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description.take(120)}")
        }
        appendLine()
        appendLine("Output a JSON plan with EXACTLY this structure (no prose, no markdown fences):")
        appendLine(
            """
            {
              "goal": "<one-sentence goal>",
              "reasoning": "<why this approach>",
              "risk_level": "low|medium|high|critical",
              "estimated_tool_calls": <int>,
              "steps": [
                {
                  "index": 0,
                  "description": "<what this step does>",
                  "tool": "<tool_id or null>",
                  "estimated_args": "<rough args as string, may be null>",
                  "depends_on": []
                }
              ]
            }
            """.trimIndent()
        )
    }

    fun buildStepExecutionPrompt(
        plan: ExecutionPlan,
        step: PlanStep,
        stepIndex: Int
    ): String = buildString {
        appendLine("Execute step ${stepIndex + 1} of the plan:")
        appendLine("Step: ${step.description}")
        step.toolName?.let { appendLine("Suggested tool: $it") }
        step.estimatedArgs?.let { appendLine("Suggested args: $it") }
        appendLine()
        appendLine("Full plan context:")
        plan.steps.forEach { s ->
            val marker = if (s.index == stepIndex) "→ " else "  "
            appendLine("$marker${s.index + 1}. ${s.description}")
        }
        appendLine()
        appendLine("Execute this step now using the appropriate tool. Be concise.")
    }

    fun buildReflectionPrompt(plan: ExecutionPlan): String = buildString {
        appendLine("The following plan has been executed:")
        appendLine("Goal: ${plan.goal}")
        plan.steps.forEach { step ->
            appendLine("  ${step.index + 1}. ${step.description}")
        }
        appendLine()
        appendLine(
            "Summarize what was accomplished in 2-4 sentences. Note any issues, " +
                "partial completions, or follow-ups the user should know about."
        )
    }

    // ═══════════════════════════════════════════════════════
    // SPEC mode prompt builders
    // ═══════════════════════════════════════════════════════

    fun buildSpecPrompt(input: String, tools: List<AgentTool>): String = buildString {
        appendLine("Analyze this task and create a detailed requirement specification:")
        appendLine()
        appendLine("Task: $input")
        appendLine()
        appendLine("Available tools:")
        tools.forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description.take(120)}")
        }
        appendLine()
        appendLine("Output a JSON spec with EXACTLY this structure (no prose, no markdown fences):")
        appendLine(
            """
            {
              "goal": "<one-sentence goal>",
              "reasoning": "<why this approach / key design decisions>",
              "risk_level": "low|medium|high|critical",
              "estimated_tool_calls": <int>,
              "requirements": ["<functional requirement 1>", "..."],
              "constraints": ["<constraint 1>", "..."],
              "acceptance_criteria": ["<how to verify success 1>", "..."],
              "deliverables": ["<concrete deliverable 1>", "..."]
            }
            """.trimIndent()
        )
        appendLine()
        appendLine("Be specific: each requirement/criterion must be verifiable. Empty arrays are allowed but avoid them when possible.")
    }

    fun buildSpecStepPrompt(
        spec: ExecutionSpec,
        stepText: String,
        stepIndex: Int
    ): String = buildString {
        appendLine("Execute deliverable ${stepIndex + 1} of the spec:")
        appendLine("Deliverable: $stepText")
        appendLine()
        appendLine("Full spec context:")
        appendLine("Goal: ${spec.goal}")
        if (spec.requirements.isNotEmpty()) {
            appendLine("Requirements:")
            spec.requirements.forEach { appendLine("  - $it") }
        }
        if (spec.constraints.isNotEmpty()) {
            appendLine("Constraints:")
            spec.constraints.forEach { appendLine("  - $it") }
        }
        if (spec.acceptanceCriteria.isNotEmpty()) {
            appendLine("Acceptance criteria:")
            spec.acceptanceCriteria.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("Deliver this item now using the appropriate tools. Verify against the acceptance criteria. Be concise.")
    }

    fun buildSpecReflectionPrompt(spec: ExecutionSpec): String = buildString {
        appendLine("The following spec has been executed:")
        appendLine("Goal: ${spec.goal}")
        spec.deliverables.forEachIndexed { index, d ->
            appendLine("  ${index + 1}. $d")
        }
        appendLine()
        appendLine(
            "Summarize what was delivered in 2-4 sentences. Report any unmet acceptance " +
                "criteria, issues, or follow-ups the user should know about."
        )
    }

    // ═══════════════════════════════════════════════════════
    // Reflection mode prompt builders
    // ═══════════════════════════════════════════════════════

    fun buildReviewPrompt(draft: String): String = buildString {
        appendLine("You are a strict reviewer. Critically evaluate the following draft answer:")
        appendLine()
        appendLine("--- DRAFT START ---")
        appendLine(draft)
        appendLine("--- DRAFT END ---")
        appendLine()
        appendLine(
            "Check for: factual errors, logical gaps, incomplete steps, unclear or ambiguous " +
                "wording, missing edge cases, and deviations from the user's request. " +
                "Output ONLY the review: a concise list of concrete, actionable issues " +
                "(max 6 items). Do not rewrite the answer here."
        )
    }

    fun buildRevisePrompt(draft: String, review: String, round: Int): String = buildString {
        appendLine("Revise the draft answer below to address the reviewer's issues.")
        appendLine("Round $round revision.")
        appendLine()
        appendLine("--- DRAFT START ---")
        appendLine(draft)
        appendLine("--- DRAFT END ---")
        appendLine()
        appendLine("--- REVIEW START ---")
        appendLine(review)
        appendLine("--- REVIEW END ---")
        appendLine()
        appendLine(
            "Output ONLY the final revised answer (complete, self-contained, no meta commentary). " +
                "Fix every actionable issue in the review while preserving what was already good."
        )
    }
}
