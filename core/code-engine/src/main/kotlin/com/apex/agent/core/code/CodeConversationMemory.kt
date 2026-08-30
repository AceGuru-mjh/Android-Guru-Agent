package com.apex.agent.core.code

import com.apex.agent.core.engine.ConversationMemory

/**
 * Code Mode 的 per-workspace 对话记忆接口（Spec §11）。
 *
 * 不同项目的 Code 会话必须隔离：Project A 的代码上下文不能带到 Project B。
 * 因此 Code engine 不使用全局 [SharedPrefsConversationMemory]（单 key），
 * 而是按 workspaceId 分键：`code_memory_<workspaceId>`。
 *
 * 纯 JVM 接口；Android 实现见
 * [com.apex.agent.platform.code.ws.AndroidCodeWorkspaceMemory]
 * （SharedPrefs per-workspaceId 分文件，挂 [com.apex.agent.platform.code.ws.CodeWorkspaceManager]）。
 */
interface CodeConversationMemory : ConversationMemory {

    /** 当前绑定的 workspaceId。切换 workspace 时调用 [bindWorkspace] 换键。 */
    val activeWorkspaceId: String?

    /**
     * 切换到指定 workspace 的记忆键。
     * - 切换后 [load] 返回该 workspace 的历史
     * - [append]/[save] 写入该 workspace 的存储
     * - [clear] 只清当前 workspace（不影响其他项目）
     * 不存在的 workspaceId 视为新会话（空历史）。
     */
    fun bindWorkspace(workspaceId: String)
}
