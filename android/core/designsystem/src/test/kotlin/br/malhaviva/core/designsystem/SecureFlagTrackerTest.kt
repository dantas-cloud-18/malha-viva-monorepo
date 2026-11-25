package br.malhaviva.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureFlagTrackerTest {
    @Test
    fun `liga na primeira e desliga so na ultima`() {
        val calls = mutableListOf<Boolean>()
        val tracker = SecureFlagTracker { calls += it }
        tracker.acquire()
        tracker.acquire()
        tracker.release()
        assertEquals(listOf(true), calls)
        tracker.release()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun `release extra e ignorado`() {
        val calls = mutableListOf<Boolean>()
        val tracker = SecureFlagTracker { calls += it }
        tracker.release()
        assertEquals(emptyList<Boolean>(), calls)
    }
}
