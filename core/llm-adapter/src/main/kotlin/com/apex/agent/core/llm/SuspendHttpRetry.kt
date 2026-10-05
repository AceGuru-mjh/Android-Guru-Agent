package com.apex.agent.core.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadLocalRandom

/**
 * ═══ #259 — suspend 层 HTTP 重试（替代 OkHttp RetryInterceptor 内的 Thread.sleep）═══
 *
 * **旧实现的两个问题**（RetryInterceptor.delayBackoff 用 Thread.sleep）：
 * 1. **不响应协程取消**：用户点「停止」后 `call.cancel()` 能打断 socket 读写
 *    （IOException），却打不动 `Thread.sleep` —— 取消传播在退避窗口内失效，
 *    界面「停止」最长迟滞 maxRetryDelayMs（10s，服务端 Retry-After 时更久）；
 * 2. **阻塞 OkHttp dispatcher 线程**：退避期间线程被独占，高并发工具调用
 *    （并行子代理）时浪费线程池容量。
 *
 * **Kotlin 官方口径**：协程取消依赖 suspension points 抛
 * `CancellationException`；`delay()` 是可取消的挂起点，`Thread.sleep` 不是。
 * 故重试退避必须在 suspend 上下文用 `delay`。
 *
 * **语义保持**（与旧拦截器逐条对齐）：
 * - `IOException` → 指数退避后重试；**最后一次直接重抛**（不吞真实网络错误）；
 * - 可重试状态码（[LlmConfig.retryOnCodes]）→ 读 `Retry-After` 退避后重试；
 *   **最后一次不 close 响应直接返回**（调用方读取错误体构造 `LlmException.Http`）；
 * - 4xx 等不可重试码 / 成功 → 立即返回；
 * - 退避 = 基数 × 2^attempt 封顶 maxRetryDelayMs，±20% jitter（防雷群）；
 * - `Retry-After` 支持秒数与 HTTP-date（RFC 7231 §7.1.3）两种形态；
 * - **总预算护栏**：`requestTimeoutMs` 经 [withTimeoutOrNull] 包住全部尝试 +
 *   退避（对齐旧 callTimeout「含重试总时长」语义）；预算耗尽抛 `IOException`
 *   （ErrorClassifier 归 ModelUnavailable → 可降级，与旧 InterruptedIOException
 *   分类一致）。callTimeout 降级为单次尝试的硬上限继续保留。
 *
 * 流式安全：仅重试「响应头已返回、尚未发射任何 chunk」的阶段 —— 状态码重试
 * 发生在 SSE 首行读取之前；流中途断开不重试（与旧拦截器语义一致：body 读取
 * 在 chain.proceed 返回之后，本就不进重试）。
 */
internal object SuspendHttpRetry {

    /**
     * 按 [config] 策略执行一次可重试 HTTP 调用。
     *
     * @param call 挂起执行一次 HTTP 请求并返回 [Response]（响应体未消费；
     *        调用方负责最终 close —— 重试路径内部对放弃的响应先 close）
     */
    suspend fun execute(config: LlmConfig, call: suspend () -> Response): Response {
        val response = withTimeoutOrNull(config.requestTimeoutMs) {
            executeAttempts(config, call)
        } ?: throw IOException(
            "LLM 请求总预算耗尽（${config.requestTimeoutMs}ms 含重试与退避），" +
                "已放弃本次调用"
        )
        return response
    }

    /** 重试主循环（无总预算包装，便于单测直接驱动）。 */
    private suspend fun executeAttempts(
        config: LlmConfig,
        call: suspend () -> Response
    ): Response {
        var attempt = 0
        var lastException: IOException? = null
        while (true) {
            val response = try {
                call()
            } catch (e: IOException) {
                lastException = e
                if (attempt >= config.retryCount) throw e
                delay(backoffMs(config, attempt, retryAfterMs = -1L))
                attempt++
                continue
            }

            if (response.isSuccessful || response.code !in config.retryOnCodes) {
                return response
            }
            // 可重试状态码：最后一次不 close —— 直接返回让调用方看到
            // 真实状态码与错误体（构造 LlmException.Http）。
            if (attempt >= config.retryCount) return response
            val retryAfter = parseRetryAfterMs(response.header("Retry-After"))
            response.close()
            delay(backoffMs(config, attempt, retryAfter))
            attempt++
        }
    }

    /**
     * 退避时长（纯计算）：`Retry-After` 优先（封顶 maxRetryDelayMs），
     * 否则基数 × 2^attempt 指数；±20% jitter 防多客户端同步重试。
     */
    internal fun backoffMs(config: LlmConfig, attempt: Int, retryAfterMs: Long): Long {
        val baseMs = if (retryAfterMs > 0) {
            retryAfterMs.coerceAtMost(config.maxRetryDelayMs)
        } else {
            (config.retryDelayMs * (1L shl attempt)).coerceAtMost(config.maxRetryDelayMs)
        }
        val jitter = 0.8 + ThreadLocalRandom.current().nextDouble(0.4)
        return (baseMs * jitter).toLong().coerceAtMost(config.maxRetryDelayMs)
    }

    /**
     * 解析 `Retry-After` 头（P3-k 修复沿用）：秒数（"120"）或 HTTP-date
     * （"Wed, 21 Oct 2026 07:28:00 GMT"）。返回相对当前的毫秒数（已过去 → 0，
     * 可立即重试）；无头 / 不可解析 → -1。
     */
    internal fun parseRetryAfterMs(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        header.toLongOrNull()?.let { return it * 1000L }
        return runCatching {
            // SimpleDateFormat 非线程安全：本函数只在重试路径单协程内调用，
            // 每次新建实例（重试频率低，开销可忽略）。
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            val date = format.parse(header) ?: return -1L
            (date.time - System.currentTimeMillis()).coerceAtLeast(0L)
        }.getOrDefault(-1L)
    }
}
