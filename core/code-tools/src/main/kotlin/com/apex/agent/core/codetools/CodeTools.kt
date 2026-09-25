package com.apex.agent.core.codetools

import com.apex.agent.core.codetools.diagnostics.CodeDiagnostics
import com.apex.agent.core.codetools.tools.CodeCheckTool
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
 * 执行看到同一份状态）；code_edit / code_write 在注入 [diagnostics] 时对写入
 * 内容做即时诊断回注（Issue #148），code_check 始终可用（未注入时自带默认
 * 诊断引擎实例）。
 */
object CodeTools {

    /**
     * 构建全部 code_* 工具。
     *
     * @param roots 工作区根解析器（DI 单例）
     * @param todo 共享的 todo 工具实例（DI @Singleton；null 时新建 —— 仅测试用）
     * @param diagnostics 编辑/写工具的诊断回注引擎（null 时禁用回注；
     *   code_check 不受影响 —— null 时自建默认实例）
     */
    fun all(
        roots: CodeWorkspaceRoots,
        todo: CodeTodoTool? = null,
        diagnostics: CodeDiagnostics? = null
    ): List<AgentTool> = listOf(
        CodeReadTool(roots),
        CodeEditTool(roots, diagnostics),
        CodeWriteTool(roots, diagnostics),
        CodeGrepTool(roots),
        CodeGlobTool(roots),
        CodeCheckTool(roots, diagnostics ?: CodeDiagnostics()),
        todo ?: CodeTodoTool()
    )
}
