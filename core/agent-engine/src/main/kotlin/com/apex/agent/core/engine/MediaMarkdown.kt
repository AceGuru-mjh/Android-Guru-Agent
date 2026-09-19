package com.apex.agent.core.engine

/**
 * 多模态输出 → markdown 注入（媒体 chunk 的统一转换点）。
 *
 * 适配层（[com.apex.agent.core.llm.LlmStreamChunk]）把图片/视频模型生成的
 * 媒体以 URL / data-URI 列表透传上来。引擎层不引入新的 AgentEvent 类型 ——
 * 而是把媒体转换成**内联 markdown**，走既有的 [AgentEvent.ResponseChunk]
 * 流式管线直达 UI：
 *
 * - 图片：`![image](url)` —— MarkdownParser 的 Image 节点 → Coil 渲染 + Lightbox；
 * - 视频：`<video src="url"></video>` —— 显式标签（不依赖扩展名判断，
 *   覆盖无扩展名的 OSS 签名 URL），由 MarkdownParser 的 VideoCard 节点渲染。
 *
 * 这样做的好处（相对新增 ImageChunk 事件）：
 * 1. ResponseComplete 的 fullText 自带媒体 markdown，落库 / 会话历史 /
 *    CS-Mem 记忆管线零改动；
 * 2. 下一轮对话模型能以 markdown 形式看到自己上轮生成的媒体，多轮生图
 *    上下文连续；
 * 3. AgentChatViewModel / 流式缓冲 / MarkdownText 全链路复用。
 */
internal object MediaMarkdown {

    /** 图片/视频 → 内联 markdown；无媒体返回 null（零开销直通）。 */
    fun from(images: List<String>, videos: List<String>): String? {
        if (images.isEmpty() && videos.isEmpty()) return null
        return buildString {
            images.forEach { url ->
                append("\n\n![image](")
                append(url)
                append(")\n\n")
            }
            videos.forEach { url ->
                append("\n\n<video src=\"")
                append(url)
                append("\"></video>\n\n")
            }
        }
    }
}
