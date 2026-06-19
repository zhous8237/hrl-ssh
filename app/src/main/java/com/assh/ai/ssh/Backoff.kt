package com.assh.ai.ssh

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import kotlin.math.pow

/**
 * Full-jitter 指数退避延时（纯函数）。随机源经构造注入，便于单测用固定值断言确切毫秒。
 *
 * 第 [delayMs] 的 attempt（从 0 起）次重试前等待 `[0, cap)` 内均匀一值，
 * 其中 `cap = min(maxMs, baseMs * factor^attempt)`。抖动是为了避免多次重连同步打到
 * 服务器、撞上 sshd `MaxStartups` / fail2ban——这正是 `closed during identification exchange` 的成因。
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 15_000,
    private val factor: Double = 2.0,
    /** 入参为上界 bound，应返回 `[0, bound)` 内一值；默认线程本地随机。 */
    private val random: (Long) -> Long = { bound -> if (bound <= 0) 0L else ThreadLocalRandom.current().nextLong(bound) }
) {
    fun delayMs(attempt: Int): Long {
        val exp = baseMs.toDouble() * factor.pow(attempt.coerceAtLeast(0))
        val cap = min(maxMs.toDouble(), exp).toLong().coerceAtLeast(1L)
        return random(cap).coerceIn(0L, cap)
    }
}
