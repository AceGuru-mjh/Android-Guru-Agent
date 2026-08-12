package com.apex.agent.core.tools.skill

/**
 * 技能依赖关系解析器。
 *
 * 从失败项目 [Apex-agent] 的 DependencyResolver 改写而来（原依赖 SkillMetadata）。
 * 适配本项目的 [SkillManifest]（依赖字段来自 manifest.dependencies），移除 Android Log 依赖。
 *
 * 提供两类能力：
 * 1. 校验 —— [detectCycles]（找出所有环，错误信息精确）、[validateDependencies]（缺失依赖）。
 * 2. 排序 —— [topologicalSort] / [resolve]（校验通过后返回加载顺序，被依赖者在前）。
 */
object SkillDependencyResolver {

    /**
     * 返回所有循环依赖链。每条链为首尾相接的 id 列表，如 [a, b, a]。
     * 无环时返回空列表。采用三色标记 + 路径栈，错误信息精确指向真实环路。
     */
    fun detectCycles(skills: List<SkillManifest>): List<List<String>> {
        val byId = skills.associateBy { it.id }
        val state = mutableMapOf<String, Color>() // WHITE=未访问, GRAY=在路径上, BLACK=已完成
        val stack = ArrayDeque<String>()           // 当前 DFS 路径
        val cycles = mutableSetOf<List<String>>()

        fun visit(id: String) {
            state[id] = Color.GRAY
            stack.addLast(id)

            byId[id]?.dependencies.orEmpty().forEach { dep ->
                when (state[dep]) {
                    Color.GRAY -> {
                        // dep 在当前路径上 -> 从 dep 到栈顶构成环
                        val start = stack.indexOf(dep)
                        val cycle = stack.drop(start) + dep
                        cycles.add(cycle)
                    }
                    null, Color.WHITE -> if (dep in byId) visit(dep)
                    else -> { /* BLACK：已处理，跳过 */ }
                }
            }

            stack.removeLast()
            state[id] = Color.BLACK
        }

        skills.forEach { if (state[it.id] == null) visit(it.id) }
        return cycles.toList()
    }

    /**
     * 校验单个技能能否安装：其依赖是否都已存在于 [available]。
     * @return 缺失的依赖 id 列表（空表示可安装）
     */
    fun validateDependencies(manifest: SkillManifest, available: Set<String>): List<String> {
        return manifest.dependencies.filter { it !in available }
    }

    /**
     * 拓扑排序，被依赖者在前。
     * 不校验环或缺失依赖——调用前应先用 [detectCycles]/[validateDependencies] 校验。
     */
    fun topologicalSort(skills: List<SkillManifest>): List<SkillManifest> {
        val byId = skills.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<SkillManifest>()

        fun visit(id: String) {
            if (id in visited) return
            visited.add(id)
            byId[id]?.dependencies.orEmpty().forEach { if (it in byId) visit(it) }
            byId[id]?.let { result.add(it) }
        }

        skills.forEach { visit(it.id) }
        return result
    }

    /**
     * 解析并按加载顺序返回（被依赖者在前）。
     * 先校验：存在环或缺失依赖时抛 [IllegalStateException]，错误信息给出精确环链。
     */
    fun resolve(skills: List<SkillManifest>): List<SkillManifest> {
        val cycles = detectCycles(skills)
        if (cycles.isNotEmpty()) {
            val pretty = cycles.joinToString(" ; ") { it.joinToString(" -> ") }
            throw IllegalStateException("Circular skill dependency detected: $pretty")
        }

        val byId = skills.associateBy { it.id }
        val declaredIds = skills.map { it.id }.toSet()
        skills.forEach { skill ->
            skill.dependencies.forEach { dep ->
                if (dep !in declaredIds && dep !in byId) {
                    throw IllegalStateException(
                        "Missing skill dependency: '${dep}' required by '${skill.id}'"
                    )
                }
            }
        }

        return topologicalSort(skills)
    }

    private enum class Color { WHITE, GRAY, BLACK }
}
