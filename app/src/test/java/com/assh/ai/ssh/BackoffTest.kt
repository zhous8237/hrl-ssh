package com.assh.ai.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Backoff] 纯函数表驱动测试：注入确定的随机源，断言指数增长 + 封顶 + 抖动落界。
 */
class BackoffTest {

    /** random 注入恒等（取上界）→ delayMs 返回 cap，可断言指数+封顶的数学 */
    private val cap = Backoff(baseMs = 1_000, maxMs = 15_000, factor = 2.0, random = { it })

    @Test
    fun `exponential growth until cap`() {
        assertEquals(1_000, cap.delayMs(0))
        assertEquals(2_000, cap.delayMs(1))
        assertEquals(4_000, cap.delayMs(2))
        assertEquals(8_000, cap.delayMs(3))
        assertEquals(15_000, cap.delayMs(4))   // 16_000 封顶到 15_000
        assertEquals(15_000, cap.delayMs(10))
    }

    @Test
    fun `negative attempt treated as zero`() {
        assertEquals(1_000, cap.delayMs(-3))
    }

    @Test
    fun `zero jitter yields zero delay`() {
        val b = Backoff(random = { 0L })
        assertEquals(0L, b.delayMs(0))
        assertEquals(0L, b.delayMs(7))
    }

    @Test
    fun `real jitter never exceeds global max`() {
        val b = Backoff(baseMs = 1_000, maxMs = 15_000)
        repeat(500) { assertTrue(b.delayMs(it % 12) in 0..15_000) }
    }

    @Test
    fun `real jitter at attempt zero stays within base`() {
        val b = Backoff(baseMs = 1_000, maxMs = 15_000)
        repeat(500) { assertTrue(b.delayMs(0) in 0..1_000) }
    }
}
