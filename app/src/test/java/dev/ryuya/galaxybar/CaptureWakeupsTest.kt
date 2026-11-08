package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

class CaptureWakeupsTest {
    private class Clock {
        var now = 0L
        val jobs = mutableListOf<Pair<Long, Runnable>>()

        fun post(task: Runnable, delay: Long) {
            jobs.add(now + delay to task)
        }

        fun remove(task: Runnable) {
            jobs.removeAll { it.second === task }
        }

        fun advance(duration: Long) {
            val end = now + duration
            var turns = 0
            while (true) {
                val next = jobs.minByOrNull { it.first } ?: break
                if (next.first > end) break
                check(++turns < 10000) { "Unbounded polling" }
                jobs.remove(next)
                now = next.first
                next.second.run()
            }
            now = end
        }
    }

    @Test
    fun screenSleepOwnsNoTimerAndWakeKeepsTheOriginalCadence() {
        val clock = Clock()
        var visible = true
        var captures = 0
        lateinit var loop: CaptureWakeups
        loop =
            CaptureWakeups(clock::post, clock::remove) {
                if (visible) captures++
                loop.afterTurn(visible, true, true, false, 100)
            }
        loop.request()
        clock.advance(1000)
        assertEquals(11, captures)
        visible = false
        loop.request()
        clock.advance(5 * 3600000L)
        assertTrue(clock.jobs.isEmpty())
        assertEquals(11, captures)
        visible = true
        loop.request()
        clock.advance(1000)
        assertEquals(22, captures)
    }

    @Test
    fun disabledAndErrorStoppedLoopsWakeOnlyOnAnEvent() {
        for (errorStop in listOf(false, true)) {
            val clock = Clock()
            var enabled = errorStop
            var stopped = errorStop
            var turns = 0
            lateinit var loop: CaptureWakeups
            loop =
                CaptureWakeups(clock::post, clock::remove) {
                    turns++
                    loop.afterTurn(true, true, enabled, stopped, 100)
                }
            loop.request()
            clock.advance(3600000)
            assertEquals(1, turns)
            assertTrue(clock.jobs.isEmpty())
            enabled = true
            stopped = false
            loop.request()
            clock.advance(200)
            assertEquals(4, turns)
        }
    }

    @Test
    fun settingsStartupRetriesWhileVisibleAndResumesAfterUnlock() {
        val clock = Clock()
        var visible = true
        var configured = false
        var turns = 0
        lateinit var loop: CaptureWakeups
        loop =
            CaptureWakeups(clock::post, clock::remove) {
                turns++
                loop.afterTurn(visible, configured, false, false, 33)
            }
        loop.request()
        clock.advance(1000)
        assertEquals(3, turns)
        visible = false
        loop.request()
        clock.advance(3600000)
        assertEquals(4, turns)
        visible = true
        loop.request()
        clock.advance(500)
        assertEquals(6, turns)
        configured = true
        loop.request()
        clock.advance(3600000)
        assertEquals(7, turns)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test
    fun paletteWaitUsesCompletionInsteadOfSpinningAndCoalescesRapidRequests() {
        val clock = Clock()
        var turns = 0
        val loop = CaptureWakeups(clock::post, clock::remove) { turns++ }
        repeat(50) {
            loop.request()
            loop.awaitWorker()
        }
        clock.advance(60000)
        assertEquals(0, turns)
        assertTrue(clock.jobs.isEmpty())
        repeat(50) { loop.workerFinished() }
        assertEquals(1, clock.jobs.size)
        clock.advance(0)
        assertEquals(1, turns)
        loop.close()
        loop.workerFinished()
        loop.request()
        clock.advance(60000)
        assertEquals(1, turns)
    }
}
