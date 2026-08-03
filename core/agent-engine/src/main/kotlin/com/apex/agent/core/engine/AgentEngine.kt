package com.apex.agent.core.engine

import kotlinx.coroutines.flow.Flow

/**
 * Agent引擎：核心循环 Plan → Act → Observe → Reflect
 * 纯Kotlin，零Android依赖
 */
interface AgentEngine {
    /**
     * 执行用户任务
     * @param input 用户输入
     * @return Agent事件流
     */
    fun execute(input: String): Flow<AgentEvent>
    
    /**
     * 停止当前执行
     */
    suspend fun abort()
}
