package com.apex.agent.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size

/**
 * ═══ 模型/服务商品牌图标（#174）═══
 *
 * 三级解析（`ModelBrands.resolve`）：
 * 1. **模型 ID 模式匹配** —— gpt→OpenAI、claude→Anthropic、qwen→通义、
 *    llama→Meta…（OpenRouter 等聚合商托管的三方模型也能显示真实品牌）；
 * 2. **服务商 id 直配** —— 17 个内置 Provider 全覆盖；
 * 3. **通用兜底** —— 中性渐变 + 首字母（自定义 Provider / 未识别模型，
 *    首字母取显示名首个 ASCII 字母，永不出空白）。
 *
 * 图标源：SimpleIcons CDN（`https://cdn.simpleicons.org/{slug}/ffffff`，
 * SVG 白色官方品牌标）——经 coil-svg 解码，内存+磁盘双缓存，离线时
 * 渐变底+首字母兜底（[BrandLogo] 内部处理，调用方无感）。
 *
 * 本文件把**纯映射逻辑**（[ModelBrand]/[ModelBrands]）与 Compose 渲染
 * （[BrandLogo]）放在一起但互不依赖——JVM 单测直接测映射，无需 Android。
 */
data class ModelBrand(
    /** 品牌稳定 id（"openai" / "qwen" / "meta"…） */
    val id: String,
    /** 兜底首字母（网络图标失败/离线时显示） */
    val initial: String,
    /** 品牌渐变双色（ARGB Long）——圆形底，官方品牌主色系 */
    val colors: List<Long>,
    /** SimpleIcons slug（null = 无官方标，纯字母兜底） */
    val iconSlug: String? = null
) {
    /** 白色官方标 CDN 地址；无 slug 时为 null（直接走字母兜底） */
    val iconUrl: String?
        get() = iconSlug?.let { "https://cdn.simpleicons.org/$it/ffffff" }
}

/**
 * 品牌注册表（纯 Kotlin，JVM 可测）。
 *
 * provider 表覆盖全部 17 个内置服务商；model 模式表覆盖主流模型族——
 * 匹配规则：小写包含 + 边界感知（"gpt" 命中 gpt-4o / chatgpt；"o1" 带
 * 边界防误伤 "mo1"/"lo1" 这类无意义串）。
 */
object ModelBrands {

    // ── 内置服务商品牌（providerId → 品牌）──────────────────────────
    private val openai = ModelBrand("openai", "O", listOf(0xFF10A37F, 0xFF0D8A6A), "openai")
    private val anthropic = ModelBrand("anthropic", "A", listOf(0xFFD97757, 0xFFB85C3E), "anthropic")
    private val google = ModelBrand("google", "G", listOf(0xFF4285F4, 0xFF9B72CB), "googlegemini")
    private val deepseek = ModelBrand("deepseek", "D", listOf(0xFF4D6BFE, 0xFF3555E8), "deepseek")
    private val openrouter = ModelBrand("openrouter", "O", listOf(0xFF333333, 0xFF666666), "openrouter")
    private val ollama = ModelBrand("ollama", "O", listOf(0xFF222222, 0xFF555555), "ollama")
    private val lmstudio = ModelBrand("lmstudio", "L", listOf(0xFF3A3A3C, 0xFF6E6E73))
    private val vllm = ModelBrand("vllm", "V", listOf(0xFF0F766E, 0xFF14B8A6))
    private val siliconflow = ModelBrand("siliconflow", "S", listOf(0xFF6D28D9, 0xFF8B5CF6))
    private val zhipu = ModelBrand("zhipu", "Z", listOf(0xFF2454FF, 0xFF4D7AFF), "zhipu-ai")
    private val moonshot = ModelBrand("moonshot", "M", listOf(0xFF1F2937, 0xFF4B5563))
    private val qwen = ModelBrand("qwen", "Q", listOf(0xFF615CED, 0xFF8B5CF6))
    private val volcark = ModelBrand("volcark", "V", listOf(0xFF1664FF, 0xFF3D7EFF), "volcengine")
    private val hunyuan = ModelBrand("hunyuan", "H", listOf(0xFF0052D9, 0xFF266FE8))
    private val xai = ModelBrand("xai", "X", listOf(0xFF111111, 0xFF3A3A3A), "xai")
    private val groq = ModelBrand("groq", "G", listOf(0xFFF55036, 0xFFF97316), "groq")

    /** providerId → 品牌（17 内置全量；未命中返回 null 走通用兜底） */
    private val byProvider: Map<String, ModelBrand> = mapOf(
        "openai" to openai,
        "anthropic" to anthropic,
        "google" to google,
        "deepseek" to deepseek,
        "openrouter" to openrouter,
        "ollama" to ollama,
        "lmstudio" to lmstudio,
        "vllm" to vllm,
        "siliconflow" to siliconflow,
        "zhipu" to zhipu,
        "moonshot" to moonshot,
        "dashscope" to qwen,          // 阿里百炼主推通义
        "volcark" to volcark,
        "hunyuan" to hunyuan,
        "xai" to xai,
        "groq" to groq,
        // custom_openai：用户自定义端点，无品牌 → 通用兜底（不注册）
    )

    // ── 模型 ID 模式（模型族 → 品牌；聚合商托管也认）────────────────
    private data class ModelPattern(val token: String, val brand: ModelBrand, val boundary: Boolean = false)

    private val meta = ModelBrand("meta", "M", listOf(0xFF0866FF, 0xFF3B8BFF), "meta")
    private val mistral = ModelBrand("mistral", "M", listOf(0xFFFA500F, 0xFFFF7000), "mistralai")
    private val cohere = ModelBrand("cohere", "C", listOf(0xFF39594D, 0xFF5F7F6F), "cohere")
    private val microsoft = ModelBrand("microsoft", "M", listOf(0xFF0078D4, 0xFF2B88D8), "microsoft")
    private val amazon = ModelBrand("amazon", "A", listOf(0xFFFF9900, 0xFFFFB84D), "amazonaws")
    private val baidu = ModelBrand("baidu", "B", listOf(0xFF2932E1, 0xFF4A5AF5), "baidu")
    private val yi = ModelBrand("yi", "Y", listOf(0xFF003425, 0xFF0A5C45))
    private val minimax = ModelBrand("minimax", "M", listOf(0xFFEF4326, 0xFFF96D55))
    private val iflytek = ModelBrand("iflytek", "S", listOf(0xFF0077C8, 0xFF2B95D6))

    private val modelPatterns: List<ModelPattern> = listOf(
        // OpenAI 系（o1/o3/o4-mini 用边界匹配防误伤）
        ModelPattern("gpt", openai), ModelPattern("chatgpt", openai),
        ModelPattern("o1", openai, boundary = true),
        ModelPattern("o3", openai, boundary = true),
        ModelPattern("o4", openai, boundary = true),
        ModelPattern("codex", openai),
        // Anthropic / Google
        ModelPattern("claude", anthropic),
        ModelPattern("gemini", google), ModelPattern("gemma", google),
        // 国内系
        ModelPattern("deepseek", deepseek),
        ModelPattern("qwen", qwen), ModelPattern("qwq", qwen), ModelPattern("qvq", qwen),
        ModelPattern("glm", zhipu), ModelPattern("codegeex", zhipu),
        ModelPattern("kimi", moonshot), ModelPattern("moonshot", moonshot),
        ModelPattern("doubao", volcark),
        ModelPattern("hunyuan", hunyuan),
        ModelPattern("ernie", baidu),
        ModelPattern("yi-", yi), ModelPattern("minimax", minimax),
        ModelPattern("spark", iflytek),
        // 开源系（OpenRouter / Ollama / vLLM 托管也认）
        ModelPattern("llama", meta), ModelPattern("mistral", mistral),
        ModelPattern("mixtral", mistral), ModelPattern("ministral", mistral),
        ModelPattern("codestral", mistral),
        ModelPattern("command-r", cohere),
        ModelPattern("phi-", microsoft),
        ModelPattern("nova-", amazon), ModelPattern("grok", xai),
    )

    /** 服务商 id 直配（未命中 null） */
    fun byProviderId(providerId: String): ModelBrand? = byProvider[providerId]

    /** 模型 ID 模式匹配（未命中 null）。空/空白 id 恒 null。 */
    fun byModelId(modelId: String): ModelBrand? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        return modelPatterns.firstOrNull { p ->
            if (p.boundary) {
                // 边界匹配：token 出现在词首（前面是开头/数字后缀边界），
                // 如 "o1-mini"、"o3" 命中；"mo1" 不命中。
                val idx = id.indexOf(p.token)
                idx >= 0 && (idx == 0 || !id[idx - 1].isLetterOrDigit())
            } else {
                id.contains(p.token)
            }
        }?.brand
    }

    /**
     * 三级解析：模型模式 > 服务商直配 > null（调用方走通用兜底）。
     *
     * 模型族优先——OpenRouter 托管的 llama 显示 Meta 标而非 OpenRouter 标。
     */
    fun resolve(providerId: String, modelId: String): ModelBrand? =
        byModelId(modelId) ?: byProviderId(providerId)

    /**
     * 通用兜底品牌：中性渐变 + 首字母。
     *
     * @param initial 建议传入服务商/模型显示名；取首个 ASCII 字母大写，
     *   无 ASCII（纯中文等）时回落 "★"。
     */
    fun genericFor(displayName: String?): ModelBrand {
        val letter = displayName?.trim()
            ?.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' }
            ?.uppercase() ?: "★"
        return ModelBrand("generic_$letter", letter, listOf(0xFF64748B, 0xFF94A3B8))
    }
}

/**
 * 品牌图标通用组件：品牌渐变圆底 + SimpleIcons 白色官方标（SVG，双缓存），
 * 加载失败/离线/无 slug 时显示品牌首字母——**永不出空白**。
 *
 * @param brand 目标品牌（null = 通用兜底，用 [fallbackInitial] 生成）
 * @param size 图标直径
 * @param fallbackInitial brand 为 null 时的兜底显示名（服务商/模型名）
 */
@Composable
fun BrandLogo(
    brand: ModelBrand?,
    size: Dp,
    modifier: Modifier = Modifier,
    fallbackInitial: String? = null
) {
    val resolved = brand ?: ModelBrands.genericFor(fallbackInitial)
    val gradient = Brush.linearGradient(resolved.colors.map { Color(it) })

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(gradient),
        contentAlignment = Alignment.Center
    ) {
        val url = resolved.iconUrl
        if (url == null) {
            BrandInitial(resolved.initial, size)
        } else {
            var showFallback by remember(url) { mutableStateOf(false) }
            val context = LocalContext.current
            val request = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(Size(96, 96))
                    .build()
            }
            if (showFallback) {
                BrandInitial(resolved.initial, size)
            } else {
                AsyncImage(
                    model = request,
                    contentDescription = null, // 装饰性图标：外层有语义标签
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(size * 0.20f),
                    onState = { state ->
                        // 加载失败 → 首字母兜底（网络不可达 / CDN 404 / 解码失败）；
                        // Loading 不处理，仅最终失败时切换避免闪烁
                        if (state is AsyncImagePainter.State.Error) {
                            showFallback = true
                        }
                    }
                )
            }
        }
    }
}

/** 渐变底上的白色品牌首字母（字号随图标尺寸缩放）。 */
@Composable
private fun BrandInitial(initial: String, size: Dp) {
    Text(
        text = initial,
        color = Color.White,
        fontSize = (size.value * 0.42f).sp,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium
    )
}
