package com.apex.agent.core.codetools

import java.io.File

/**
 * # Code Tools — 工作区根解析契约
 *
 * Code 模式的文件工具（code_read / code_edit / …）与 Agent 模式的旧文件工具
 * 最大的差异：**根目录是会话态**。用户在 Code 屏切换/新建 workspace 时，
 * 全部 code_* 工具的解析根随之切换 —— 而工具实例注册在全局 [com.apex.agent.core.tools.ToolRegistry]
 * 里（两模式共享，规则/门控/目录全部互用），不能在构造时固化根路径。
 *
 * 因此根解析以本接口注入（DI 提供 @Singleton 实现），每次工具调用时取当前值：
 *
 * - 返回 `null`：Code 模式尚未初始化任何 workspace —— 工具返回可操作的引导文本
 *   （"先在 Code 屏创建/选择一个工作区"），而不是硬错误；
 * - 返回非 null：必须是一个已存在（或可创建）的目录，工具以它为沙箱根做
 *   [com.apex.agent.core.tools.builtin.FilePathSafety] 同源语义的越界校验。
 *
 * 实现方（app 层 CodeModule → CodeWorkspaceManager）保证：
 * 1. 默认 workspace 与 Agent 文件工具共用同一物理目录
 *    （`<filesDir>/linux/workspaces/default`，即 Ubuntu 会话的 guest /workspace），
 *    两模式看到同一份文件；
 * 2. 切换是原子引用写（@Volatile File?），读侧无锁。
 */
fun interface CodeWorkspaceRoots {

    /** 当前激活的 Code workspace 根目录（host 侧绝对路径）。 */
    fun activeRoot(): File?
}
