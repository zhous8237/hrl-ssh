package com.assh.ai.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 标记**可重试的瞬时错误**：限流（429）、服务端 5xx、或模型返回空响应（魔搭等的
 * `choices:null` 伪装）。由 [retryOnTransient] 捕获并退避重试；用尽后转成普通
 * [LlmException] 抛出，交给上层（[com.assh.ai.SshAgentEngine] 软失败、保连接可续）。
 */
class RetryableLlmException(message: String, cause: Throwable? = null) : LlmException(message, cause)

/**
 * 串行请求节流器：保证相邻两次请求间隔 ≥ 调用 [await] 时传入的最小间隔。
 *
 * 间隔随当前模型配置的 RPM（每分钟请求上限）变化，故不在构造时固定、改为每次 await 传入。
 * Agent 的 LLM 请求本就串行（逐个 await），这里只补最小间隔把每分钟请求数压到 provider 配额
 * 以下，主动避免触发限流（而非撞了 429 再退避）。持锁期间 `delay`——请求串行，无真正竞争。
 */
class RequestThrottle {
    private val mutex = Mutex()
    private var lastAtMs = 0L

    /** [minIntervalMs] 每次传入：保证与上次请求间隔 ≥ 该值；<=0 表示不限制 */
    suspend fun await(minIntervalMs: Long) {
        if (minIntervalMs <= 0) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = minIntervalMs - (now - lastAtMs)
            if (wait in 1..minIntervalMs) delay(wait)
            lastAtMs = System.currentTimeMillis()
        }
    }
}

/**
 * 执行 [block]，遇到 [RetryableLlmException] 固定间隔退避重试：最多 [maxRetries] 次，
 * 每次间隔 [retryDelayMs]（非指数——按用户要求"每次 30 秒"）。`delay` 可被任务取消打断。
 *
 * 重试用尽后，把最后一次异常转成可读 [LlmException] 重抛（不再是 Retryable，避免上层再误判）。
 * @param onRetry 每次准备重试前回调（attempt 从 1 计），供日志/提示，默认空。
 */
suspend fun <T> retryOnTransient(
    maxRetries: Int,
    retryDelayMs: Long,
    onRetry: (attempt: Int, e: RetryableLlmException) -> Unit = { _, _ -> },
    block: suspend () -> T
): T {
    var last: RetryableLlmException? = null
    repeat(maxRetries + 1) { i ->
        try {
            return block()
        } catch (e: RetryableLlmException) {
            last = e
            if (i < maxRetries) {
                onRetry(i + 1, e)
                delay(retryDelayMs)
            }
        }
    }
    val e = last
    throw LlmException(e?.message ?: "请求多次重试仍失败", e)
}
