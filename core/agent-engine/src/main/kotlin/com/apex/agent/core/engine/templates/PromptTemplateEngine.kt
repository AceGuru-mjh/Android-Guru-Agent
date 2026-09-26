package com.apex.agent.core.engine.templates

import com.apex.agent.core.engine.promptvars.PromptVariableContext
import com.apex.agent.core.engine.promptvars.PromptVariableExpander

/**
 * 渲染结果（防御式：缺必填变量 ok=false + 清单，不抛）。
 *
 * @param ok true = 全部必填变量就位
 * @param text 渲染产物（缺变量时为尽力展开的部分结果）
 * @param missingRequired 缺失的必填变量名（按声明顺序）
 * @param template 命中的模板（id 不存在时为 null）
 */
data class RenderResult(
    val ok: Boolean,
    val text: String,
    val missingRequired: List<String>,
    val template: PromptTemplate?
)

/**
 * ═══ 提示词模板引擎（4-b）═══
 *
 * 把 Operit 式的「模板库 + 变量表单」与 RikkaHub 式的「提示词变量
 * 展开」拼成一条流水线：
 *
 * ```
 * PromptTemplateRegistry（模板 CRUD/持久化）
 *        ↓ get(id)
 * PromptTemplate（声明 variables + content 含 {{var}}）
 *        ↓ 变量值合并（三层优先级）
 * PromptVariableExpander（展开/递归/转义/默认值）
 *        ↓
 * RenderResult（text + missingRequired）
 * ```
 *
 * **变量值合并优先级**（高 → 低）：
 *
 * 1. `overrides`——本次渲染的显式传参（表单输入/斜杠命令参数）；
 * 2. 模板声明的 `variables[].defaultValue`（非空默认值）；
 * 3. 提示词变量注册表解析（内置 19 变量 + 自定义 + 上下文 custom）；
 * 4. 缺失——必填变量进 [RenderResult.missingRequired]，ok=false。
 *
 * 前两层经「enriched context」（copy(custom = context.custom + 默认值
 * + overrides)）注入展开器——复用注册表的既有优先级链，不另起解析
 * 路径。未声明的 {{var}} 同样能被第 3 层解析（内置变量直通）。
 */
class PromptTemplateEngine(
    private val registry: PromptTemplateRegistry,
    private val expander: PromptVariableExpander
) {

    /**
     * 渲染已保存的模板。
     *
     * 模板不存在 → ok=false + template=null；缺必填变量 → ok=false +
     * missingRequired 清单（text 为尽力展开的部分结果，缺位占位符按
     * 展开器策略保留）。渲染命中即自增 usageCount。
     */
    suspend fun render(
        templateId: String,
        overrides: Map<String, String> = emptyMap(),
        context: PromptVariableContext
    ): RenderResult {
        val template = registry.get(templateId)
            ?: return RenderResult(ok = false, text = "", missingRequired = emptyList(), template = null)

        val missing = mutableListOf<String>()
        val declaredDefaults = mutableMapOf<String, String>()
        for (variable in template.variables) {
            if (overrides.containsKey(variable.name)) continue
            if (variable.defaultValue.isNotEmpty()) {
                declaredDefaults[variable.name] = variable.defaultValue
                continue
            }
            // 第 3 层：注册表可解析（含 context.custom）即视为就位
            if (expander.isResolvable(variable.name, context)) continue
            if (variable.required) missing.add(variable.name)
        }

        val enriched = context.copy(
            custom = context.custom + declaredDefaults + overrides
        )
        val text = expander.expand(template.content, enriched)
        registry.incrementUsage(template.id)
        return RenderResult(
            ok = missing.isEmpty(),
            text = text,
            missingRequired = missing,
            template = template
        )
    }

    /**
     * 渲染裸文本（不经存储模板）：overrides 直接注入展开上下文。
     * 输入框「预览展开」与临时拼装场景使用；无 usageCount 副作用。
     */
    suspend fun renderRaw(
        content: String,
        overrides: Map<String, String> = emptyMap(),
        context: PromptVariableContext
    ): RenderResult {
        val enriched = context.copy(custom = context.custom + overrides)
        val text = expander.expand(content, enriched)
        return RenderResult(ok = true, text = text, missingRequired = emptyList(), template = null)
    }
}
