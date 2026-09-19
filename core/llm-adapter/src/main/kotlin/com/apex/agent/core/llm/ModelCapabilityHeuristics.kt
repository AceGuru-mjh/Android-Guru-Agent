package com.apex.agent.core.llm

/**
 * 基于模型 id 的能力启发式推断。
 *
 * 背景：`GET /models` 端点只返回 `id` + `owned_by`，不带任何能力元数据
 * （是否视觉 / 是否生图 / 是否生视频）。旧实现把能力标记完全留给用户手点
 * FilterChip —— 选了 `gpt-4o` 还要自己记得开 Vision，选了 `cogview-4`
 * 没人告诉它需要开 ImageGen 才能触发 modalities。这里按主流命名约定做
 * **预填**（推断结果只作为初始值，用户仍可在 CapabilityEditor 里改）。
 *
 * 设计约束：
 * - 宁缺毋滥 —— 只匹配高置信度模式，避免把纯文本模型误标成视觉
 *   （如 `gpt-3.5` / `deepseek-chat` / `qwen2.5:7b` 均不命中）；
 * - 大小写不敏感；匹配的是 id 中任意位置的子串 / 前后缀模式；
 * - 独立文件 + 纯函数：无状态、可单测（God-file 预算隔离）。
 */
object ModelCapabilityHeuristics {

    // ── 视觉输入（Vision）──────────────────────────────────────────
    // 主流通用多模态模型命名片段：
    //  - OpenAI: gpt-4o / gpt-4.1 / gpt-4-turbo / chatgpt-4o（gpt-4 / gpt-3.5 是纯文本）
    //  - Anthropic: claude-3+ 全系（sonnet / opus / haiku 均视觉）
    //  - Google: gemini 全系视觉
    //  - Qwen: qwen2.5-vl / qwen3-vl（-vl 后缀）；Qwen-Omni（含 omni）
    //  - GLM: glm-4v / glm-4.5v（4v 片段）；llava / minicpm-v / pixtral /
    //    internvl / yi-vision / step-1v / grok-4 / llama-4
    private val VISION_PATTERNS = listOf(
        "gpt-4o", "gpt-4.1", "gpt-4-turbo", "chatgpt-4o", "gpt-5",
        "claude-3", "claude-4", "claude-opus", "claude-sonnet", "claude-haiku",
        "gemini",
        "-vl", "vl-", "vl:",
        "glm-4v", "glm-4.5v", "glm-4.6v",
        "llava", "minicpm-v", "pixtral", "internvl", "yi-vision", "step-1v",
        "grok-4", "llama-4", "vision", "omni"
    )

    // ── 图片生成（ImageGen）────────────────────────────────────────
    // chat 式生图 / OpenRouter image 输出模型：
    //  - gpt-image-1 / dall-e-3（OpenAI）
    //  - cogview-4（智谱）；seedream / seededit（火山）；kolors（快手）
    //  - flux.1 / sdxl / stable-diffusion / sd3（开源生图系）
    //  - gemini image（nano-banana：gemini-2.0-flash-exp-image-generation 等）
    //  - qwen-image（阿里）；janus（DeepSeek 统一生成）
    private val IMAGE_GEN_PATTERNS = listOf(
        "gpt-image", "dall-e", "dalle",
        "cogview", "seedream", "seededit", "kolors",
        "flux", "sdxl", "stable-diffusion", "sd3", "sd-turbo",
        "-image-generation", "image-generation",
        "qwen-image", "janus"
    )

    // ── 视频生成（VideoGen）────────────────────────────────────────
    // chat 式生视频模型：cogvideox / sora / veo / kling / hailuo / vidu /
    // pixverse / seedance / wanx2（通义万相）；任务型后缀 t2v / i2v / v2v
    private val VIDEO_GEN_PATTERNS = listOf(
        "cogvideo", "sora", "veo", "kling", "hailuo", "vidu",
        "pixverse", "seedance", "wanx2", "wan2",
        "-t2v", "t2v-", "i2v", "v2v",
        "video-generation", "minimax-video", "video-01"
    )

    /** id 是否像视觉模型（预填 vision + imageInput）。 */
    fun inferVision(modelId: String): Boolean = matches(VISION_PATTERNS, modelId)

    /** id 是否像图片生成模型（预填 imageGeneration）。 */
    fun inferImageGeneration(modelId: String): Boolean = matches(IMAGE_GEN_PATTERNS, modelId)

    /** id 是否像视频生成模型（预填 videoGeneration）。 */
    fun inferVideoGeneration(modelId: String): Boolean = matches(VIDEO_GEN_PATTERNS, modelId)

    /**
     * 按推断结果合成能力标记（在现有基础上**只加不减**——用户已手动关掉的
     * 能力不会被启发式重新打开，只补齐新模型明显具备的位）。
     */
    fun enrichCapabilities(modelId: String, current: ModelCapabilities): ModelCapabilities {
        if (modelId.isBlank()) return current
        return current.copy(
            vision = current.vision || inferVision(modelId),
            imageInput = current.imageInput || inferVision(modelId),
            imageGeneration = current.imageGeneration || inferImageGeneration(modelId),
            videoGeneration = current.videoGeneration || inferVideoGeneration(modelId)
        )
    }

    private fun matches(patterns: List<String>, modelId: String): Boolean {
        val id = modelId.lowercase()
        return patterns.any { id.contains(it) }
    }
}
