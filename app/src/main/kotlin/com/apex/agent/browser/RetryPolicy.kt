package com.apex.agent.browser

import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.delay

/**
 * 浏览器原子操作的错误恢复策略（P1 #7）。
 *
 * 生产级 Agent 不能「无限重试同一操作」也不能「一失败就放弃」。采用
 * 指数退避 + 最大重试 + 熔断器的组合：连续多次失败后暂停一段时间，避免被限流或雪崩。
 *
 * 设计约束：
 * - [HandoffLockedException] 等语义错误**不可重试**（重试无意义，应直接返回锁定提示）。
 * - 仅 [retryableExceptions] 中的瞬态异常（元素未找到、超时、WebView 无响应）才退避重试。
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 500,
    val backoffMultiplier: Double = 2.0,
    val maxDelayMs: Long = 5000,
    val retryableExceptions: Set<Class<out Throwable>> = setOf(
        ElementNotFoundException::class.java,
        java.util.concurrent.TimeoutException::class.java,
        WebViewNotRespondingException::class.java,
    ),
)

/** 元素在 DOM 中找不到（ref 失效或页面未渲染完） */
class ElementNotFoundException(message: String) : Exception(message)

/** WebView 在规定时间内未通过 evaluateJavascript 回调（可能主线程卡死） */
class WebViewNotRespondingException(message: String) : Exception(message)

/**
 * 熔断器：连续失败达到阈值后进入 OPEN 状态，暂停执行一段时间（[resetTimeoutMs]），
 * 之后进入 HALF_OPEN 试探一次，成功则回 CLOSED。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30_000,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    @Volatile private var state: State = State.CLOSED
    private var consecutiveFailures = 0
    @Volatile private var openedAt = 0L

    val currentState: State get() = state

    /** 执行前检查：OPEN 且未到重置时间则抛 [CircuitOpenException] */
    fun acquire() {
        val now = System.currentTimeMillis()
        when (state) {
            State.OPEN -> {
                if (now - openedAt >= resetTimeoutMs) {
                    state = State.HALF_OPEN
                } else {
                    throw CircuitOpenException("浏览器操作熔断器开启中（已连续失败 $failureThreshold 次），${resetTimeoutMs / 1000}s 后重试")
                }
            }
            else -> Unit
        }
    }

    fun onSuccess() {
        consecutiveFailures = 0
        state = State.CLOSED
    }

    fun onFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN
            openedAt = System.currentTimeMillis()
        }
    }
}

class CircuitOpenException(message: String) : Exception(message)

/**
 * 同步执行带重试+熔断的区块（在调用方协程上下文内运行）。
 * - 不可重试异常（非 [RetryPolicy.retryableExceptions]）立即向上抛出。
 * - 熔断开启时立即抛 [CircuitOpenException]，不进入退避。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    breaker: CircuitBreaker,
    block: suspend () -> T,
): T {
    breaker.acquire()
    var lastErr: Throwable? = null
    repeat(policy.maxRetries + 1) { attempt ->
        try {
            val result = block()
            breaker.onSuccess()
            return result
        } catch (e: Throwable) {
            lastErr = e
            // 不可重试异常：直接抛，不计入熔断
            if (policy.retryableExceptions.none { it.isInstance(e) } && e !is CircuitOpenException) {
                throw e
            }
            breaker.onFailure()
            if (attempt < policy.maxRetries) {
                val delayMs = min(policy.maxDelayMs, (policy.initialDelayMs * policy.backoffMultiplier.pow(attempt)).toLong())
                delay(delayMs)
            }
        }
    }
    throw lastErr ?: IllegalStateException("重试失败")
}
