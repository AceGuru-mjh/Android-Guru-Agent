package com.apex.agent.core.llm.share

import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import kotlinx.serialization.Serializable

/**
 * # Provider Share — 二维码分享数据模型（Task 4-f）
 *
 * 设计来源（对标分析结论，详见 worklog 2-b §9 / 2-c）：
 *
 * - **RikkaHub** `ProviderSetting.encodeForShare`（ui ShareSheet.kt）：
 *   `"ai-provider:v1:" + Base64(JSON(provider.copyProvider(models = emptyList())))`
 *   ——只导连接配置（key / baseUrl / 请求头），models 剥离；无压缩、无信封、
 *   无脱敏开关，解码端 require 直接抛异常。
 * - **apex 适配**（本包）：
 *   1. 分享单元从「单个 Provider」扩为 Provider + 挂载的 [ModelProfile] 档位
 *      列表——apex 设置中心里 Profile 是一等公民（采样 / 推理 / 超时全在
 *      档位上），只给端点不给档位等于只分享了一半；
 *   2. 载荷可能达数 KB → 引入 [ShareEnvelope] 信封 + GZIP 压缩
 *      （编解码见 [ProviderShareCodec]）；
 *   3. 密钥红线：[ProviderSharePayload.includesKeys] 显式记录是否携带明文
 *      key；默认导出即脱敏（apiKeys 清空），带 key 导出必须显式 opt-in；
 *   4. 解码全程防御式：任何形状的坏输入折叠为 [ShareCodecError] 分型错误，
 *      绝不向上抛（对齐仓库「防御式 IO」纪律；RikkaHub 是 require 抛异常）。
 *
 * 序列化纪律：除 provider 本体外全部字段带默认值，旧版本 JSON 缺字段可
 * 无损加载（kotlinx-serialization 缺省回退），对齐 ModelProfile.kt 的演进
 * 约定。编解码器用 encodeDefaults=false 的紧凑 Json——等于默认值的字段
 * 不进载荷，字节数更小（QR 容量友好）。
 */
@Serializable
data class ProviderSharePayload(
    /** 载荷自描述 schema 名（防串包：其他 App 的 provider JSON 会被拒收）。 */
    val schema: String = SCHEMA,
    /** 载荷内容格式版本（当前 1；与信封 [ShareEnvelope.version] 相互独立）。 */
    val version: Int = CURRENT_VERSION,
    /** 被分享的 Provider 连接配置（id / baseUrl / 请求头 / 能力标记）。 */
    val provider: ProviderConfig,
    /** 随行模型档位列表（可空——只分享连接配置时）。 */
    val profiles: List<ModelProfile> = emptyList(),
    /** 导出时刻（epoch ms；0 = 未记录）。 */
    val exportedAt: Long = 0,
    /** 是否包含明文 API Key——导入端据此提示「此码含密钥」。 */
    val includesKeys: Boolean = false,
) {
    companion object {
        /** 载荷 schema 常量（区别于 RikkaHub 的 "ai-provider" 家族）。 */
        const val SCHEMA = "apex-provider"

        /** 当前载荷版本。 */
        const val CURRENT_VERSION = 1
    }

    /** 是否携带模型档位（导入端提示「将创建 N 个模型档案」用）。 */
    val hasProfiles: Boolean
        get() = profiles.isNotEmpty()
}

/**
 * 分享信封——外层包装（恒小，永不压缩）。
 *
 * 分层（自外向内，完整图见 [ProviderShareCodec] KDoc）：
 *
 * - URI = "apex-provider:v1:" + Base64(信封 JSON)；
 * - [data] = Base64(载荷 JSON) 或 Base64(gzip(载荷 JSON))。
 *
 * 为什么需要信封：RikkaHub 无压缩，前缀后直接就是 Base64(JSON)。一旦引入
 * GZIP，「是否压缩过」这个标志必须**带外**传输——它不可能写进被压缩的
 * JSON 里（先有鸡还是先有蛋）。信封就是这层带外元数据，同时承载
 * format / version 供未来格式演进：解不开就明确报错，绝不瞎猜格式。
 *
 * 字段全部必填（无默认值）——信封是我们自己的私有小协议，缺字段即视为
 * 非法信封（解码折叠为 [ShareCodecError.Malformed]），不做宽容填充。
 */
@Serializable
data class ShareEnvelope(
    /** 信封格式标识（当前 [FORMAT]；解码端严格比对）。 */
    val format: String,
    /** 信封包装格式版本（当前 1；与 URI 前缀 v1、载荷 version 三者独立）。 */
    val version: Int,
    /** [data] 是否为「Base64(gzip(载荷 JSON))」。false = Base64(载荷 JSON)。 */
    val compressed: Boolean,
    /** Base64 编码的载荷字节（标准 RFC 4648 字母表，含填充）。 */
    val data: String,
) {
    companion object {
        /** 信封格式标识常量。 */
        const val FORMAT = "apex-share"

        /** 当前信封版本。 */
        const val CURRENT_VERSION = 1
    }
}

/**
 * 分享编解码失败——分型错误（plain sealed class，值语义，非异常）。
 *
 * 与 RikkaHub 的 require 抛异常不同，apex 把全部失败模式折叠成可判别的
 * 值类型，调用方（导入流程 UI）按子类给出精确提示：
 *
 * - [Malformed]          —— URI 结构层坏：缺前缀 / 前缀后无内容 /
 *                           信封 Base64 非法 / 信封 JSON 解不开 /
 *                           信封 format 不认识；
 * - [CorruptPayload]     —— 信封合法但载荷层坏：data Base64 非法 /
 *                           gzip 流损坏（截断 / 魔数错 / 解压超限）/
 *                           载荷 JSON 解不开；
 * - [WrongSchema]        —— 载荷解出来了，但 schema 不是 apex 的
 *                           （典型场景：误扫了别家 App 的供应商码）；
 * - [UnsupportedVersion] —— 信封或载荷版本不在支持范围（未来格式）。
 *
 * 每个 [message] 都是可直接展示的人读句（含原因 / 期望值，绝不含密钥材料）。
 */
sealed class ShareCodecError {
    /** 人读错误句（UI 原样展示 / 日志留痕）。 */
    abstract val message: String

    /** URI 结构层不合法。 */
    data class Malformed(val reason: String) : ShareCodecError() {
        override val message: String
            get() = "Malformed provider share: $reason"
    }

    /** 信封或载荷版本不被支持。 */
    data class UnsupportedVersion(val version: Int) : ShareCodecError() {
        override val message: String
            get() = "Unsupported provider share version $version (supported: ${ProviderSharePayload.CURRENT_VERSION})"
    }

    /** 载荷 schema 不匹配（不是 apex-provider 载荷）。 */
    data class WrongSchema(val got: String) : ShareCodecError() {
        override val message: String
            get() = "Wrong provider share schema \"$got\" (expected \"${ProviderSharePayload.SCHEMA}\")"
    }

    /** 信封合法但载荷数据层损坏。 */
    data class CorruptPayload(val reason: String) : ShareCodecError() {
        override val message: String
            get() = "Corrupt provider share payload: $reason"
    }
}

/**
 * [ShareCodecError] 的异常桥。
 *
 * kotlin 的 [Result] failure 只能装 [Throwable]，而 [ShareCodecError] 按规格
 * 是 plain sealed class（值语义、可直接 is 断言 / 拷贝比较）。这层桥是纯
 * 管道：[error] 原样携带分型结果，异常 message 透传人读句，绝不附加堆栈
 * 语义。调用方一律经 [shareCodecErrorOrNull] 取回分型错误，不直接碰本类。
 */
class ShareDecodeException(val error: ShareCodecError) : RuntimeException(error.message)

/**
 * 从 [ProviderShareCodec.decode] 的结果里取回分型错误（成功返回 null）。
 *
 * 用法：
 * `decode(uri).shareCodecErrorOrNull() is ShareCodecError.Malformed`
 */
fun Result<ProviderSharePayload>.shareCodecErrorOrNull(): ShareCodecError? =
    exceptionOrNull()?.let { it as? ShareDecodeException }?.error
