package com.apex.agent.core.code

import java.io.File

/**
 * # Code Context Provider — 编码会话 JIT 上下文提供者
 *
 * 每轮编码对话发送前，把工作区的实时状态（环境/统计/当前文件）注入系统提示词。
 * core 层只定义契约；实现由 app/platform 层提供（环境探测需要 Android 侧
 * 文件系统与包信息），经 DI 注入 [CodeAgentEngine]。
 */
interface CodeContextProvider {

    /**
     * 生成指定工作区的动态上下文块（非空字符串；探测失败时返回 null ——
     * 调用方跳过该段，不注入噪音）。
     *
     * @param workspaceRoot 当前工作区根（host 绝对路径）
     * @param activeFile 用户当前打开的文件（工作区相对路径，可空）
     */
    fun provide(workspaceRoot: File, activeFile: String?): String?
}
