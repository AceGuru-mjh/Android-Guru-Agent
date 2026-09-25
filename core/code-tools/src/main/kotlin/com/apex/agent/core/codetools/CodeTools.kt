package com.apex.agent.core.codetools

import com.apex.agent.core.codetools.tools.CodeEditTool
import com.apex.agent.core.codetools.tools.CodeGlobTool
import com.apex.agent.core.codetools.tools.CodeGrepTool
import com.apex.agent.core.codetools.tools.CodeReadTool
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.codetools.tools.CodeWriteTool
import com.apex.agent.core.tools.AgentTool

/**
 * Code 模式工具集聚合入口（ToolModule 注册用）。
 *
 * 所有文件类工具共享同一个 [CodeWorkspaceRoots]（当前激活工作区根）；code_todo
 * 为会话态单例（CodeModule 提供 @Singleton 后传入，保证 UI snapshot 与工具
 * 执行看到同一份状态）。
 */
object CodeTools {

    /**
     * 构建全部 code_* 工具。
     *
     * @param roots 工作区根解析器（DI 单例）
     * @param todo 共享的 todo 工具实例（DI @Singleton；null 时新建 —— 仅测试用）
     */
    fun all(roots: CodeWorkspaceRoots, todo: CodeTodoTool? = null): List<AgentTool> = listOf(
        CodeReadTool(roots),
        CodeEditTool(roots),
        CodeWriteTool(roots),
        CodeGrepTool(roots),
        CodeGlobTool(roots),
        todo ?: CodeTodoTool()
    )
}
