package com.apex.agent.core.engine

import com.apex.agent.core.llm.ImageContent

/**
 * 用户多模态输入。
 *
 * AgentEngine 不再只接收纯文本，而是接收一个结构化输入：
 * - [text]：用户文本。
 * - [images]：图片内容，直接进入 `LlmMessage.User.images`，由 Vision-capable
 *   LLM 识别。base64 编码，已压缩。
 * - [files]：非图片附件，作为文件上下文提供给 Agent（路径 + 元信息），Agent
 *   可用 `read_file` / `search_files` 等工具进一步读取。
 *
 * 保留 `execute(String)` 旧接口用于兼容；内部委托给 `execute(UserInput.text(s))`。
 */
data class UserInput(
    val text: String,
    val images: List<ImageContent> = emptyList(),
    val files: List<FileRef> = emptyList()
) {
    companion object {
        /** 纯文本输入的便捷构造。 */
        fun text(text: String): UserInput = UserInput(text = text)
    }
}

/**
 * 文件附件引用。
 *
 * 当前阶段不要求模型直接读取二进制，而是把文件路径、类型、大小作为上下文
 * 告诉 Agent，由 Agent 决定是否用工具读取。图片附件走 [UserInput.images]
 * 直接 Vision，不走这里。
 */
data class FileRef(
    val name: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long
)
