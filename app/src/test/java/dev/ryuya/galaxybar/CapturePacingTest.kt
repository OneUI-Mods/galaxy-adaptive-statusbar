package dev.ryuya.galaxybar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePacingTest {
    @Test
    fun healthyCaptureKeepsTheConfiguredStartToStartInterval() {
        val pacing = CapturePacing()
        assertEquals(0L, pacing.delayMs(0, 33))
        pacing.completed(107, 7.0, true)
        assertEquals(26L, pacing.delayMs(107, 33))
        assertEquals(0L, pacing.delayMs(133, 33))
        assertEquals(193L, pacing.delayMs(107, 200))
    }

    @Test
    fun repeatedImmediateRequestsCannotBypassAnExpensiveCapture() {
        val pacing = CapturePacing()
        pacing.completed(1000, 25.0, true)
        for (now in 1000L until 2000L step 16L) {
            assertTrue(pacing.delayMs(now, 33) > 0)
        }
        assertEquals(0L, pacing.delayMs(2000, 33))
        assertEquals(1000L, pacing.minimumRestMs())
    }

    @Test
    fun verySlowCaptureRestsAfterCompletionWithoutCatchUpBursts() {
        val pacing = CapturePacing()
        pacing.completed(5000, 44.1, true)
        assertEquals(2000L, pacing.delayMs(5000, 33))
        assertEquals(0L, pacing.delayMs(7000, 33))
        pacing.completed(7200, 200.0, true)
        assertEquals(5000L, pacing.delayMs(7200, 200))
    }

    @Test
    fun recoveryRequiresSixConsecutiveCheaperSuccessfulCaptures() {
        val pacing = CapturePacing()
        pacing.completed(0, 25.0, true)
        repeat(5) { pacing.completed(1000L + it * 1000, 5.0, true) }
        assertEquals(1000L, pacing.minimumRestMs())
        pacing.completed(6000, 5.0, true)
        assertEquals(0L, pacing.minimumRestMs())
        assertEquals(28L, pacing.delayMs(6000, 33))
    }

    @Test
    fun recoveryUsesTheSlowestSampleInsteadOfTheLastFastSample() {
        val pacing = CapturePacing()
        pacing.completed(0, 200.0, true)
        for ((index, cost) in listOf(25.0, 5.0, 5.0, 5.0, 5.0, 5.0).withIndex()) {
            pacing.completed((index + 1L) * 5000, cost, true)
        }
        assertEquals(1000L, pacing.minimumRestMs())
    }

    @Test
    fun failedCapturesCannotClearBackoffOrCountTowardsRecovery() {
        val pacing = CapturePacing()
        pacing.completed(0, 25.0, true)
        repeat(5) { pacing.completed(1000L + it * 1000, 5.0, true) }
        pacing.completed(6000, 0.0, false)
        pacing.completed(7000, 5.0, true)
        assertEquals(1000L, pacing.minimumRestMs())
        val unavailable = CapturePacing()
        unavailable.completed(1000, 0.0, false)
        assertEquals(250L, unavailable.delayMs(1000, 33))
    }

    @Test
    fun anotherSlowCaptureResetsRecovery() {
        val pacing = CapturePacing()
        pacing.completed(0, 25.0, true)
        repeat(5) { pacing.completed(1000L + it * 1000, 5.0, true) }
        pacing.completed(6000, 25.0, true)
        pacing.completed(7000, 5.0, true)
        assertEquals(1000L, pacing.minimumRestMs())
    }

    @Test
    fun invalidMeasurementsRemainBoundedAndDoNotPoisonScheduling() {
        val pacing = CapturePacing()
        pacing.completed(1000, Double.NaN, false)
        assertEquals(5000L, pacing.delayMs(1000, 33))
        assertEquals(0L, pacing.delayMs(6000, 33))
    }
}
