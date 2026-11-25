package br.malhaviva.feature.trains.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    private fun backoff(random: Double) = Backoff(random = { random })

    @Test
    fun `without jitter the schedule doubles up to the cap`() {
        // random = 1.0 -> topo do intervalo
        val top = backoff(1.0)
        val schedule = (1..8).map { top.delayMs(it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), schedule)
    }

    @Test
    fun `lowest jitter is half of the ceiling`() {
        val low = backoff(0.0)
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L), (1..6).map { low.delayMs(it) })
    }

    @Test
    fun `jitter stays inside half to full ceiling for any draw`() {
        for (attempt in 1..10) {
            val ceiling = minOf(30_000L, 1_000L shl (attempt - 1))
            listOf(0.0, 0.25, 0.5, 0.99).forEach { draw ->
                val d = backoff(draw).delayMs(attempt)
                assertTrue("attempt=$attempt draw=$draw d=$d", d in ceiling / 2..ceiling)
            }
        }
    }

    @Test
    fun `retry-after is a floor but is capped at one minute`() {
        assertEquals(5_000L, backoff(0.0).delayMs(1, retryAfterMs = 5_000))
        assertEquals(2_000L, backoff(1.0).delayMs(2, retryAfterMs = 100))
        assertEquals(60_000L, backoff(0.0).delayMs(1, retryAfterMs = 3_600_000))
    }

    @Test
    fun `huge attempt counts do not overflow`() {
        assertEquals(30_000L, backoff(1.0).delayMs(10_000))
        assertEquals(1_000L, backoff(1.0).delayMs(0))
    }
}
