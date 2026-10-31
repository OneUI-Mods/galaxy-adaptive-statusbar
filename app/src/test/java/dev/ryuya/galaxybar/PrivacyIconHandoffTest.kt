package dev.ryuya.galaxybar

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PrivacyIconHandoffTest {
    @Test
    fun chipToDotUsesOneContinuousPositionEvenWhenTheDotArrivesLate() {
        val spring = IconMotionSpring()
        repeat(60) { spring.step(-120f, 1f / 60) }
        var previous = spring.position
        var maximumStep = 0f
        for (frame in 0..60) {
            val chip = if (frame < 24) -120f * (1f - frame / 24f) else 0f
            val dot = if (frame < 26) 0f else -12f
            val position = spring.step(PrivacySlideGeometry.combinedShift(chip, dot), 1f / 60)
            maximumStep = maxOf(maximumStep, abs(position - previous))
            previous = position
        }
        assertTrue(maximumStep < 10f)
        assertEquals(-12f, spring.position, .05f)
    }

    @Test
    fun dotMeasurementChangesDoNotTeleportTheIconGroup() {
        val spring = IconMotionSpring()
        repeat(40) { spring.step(-80f, 1f / 120) }
        val before = spring.position
        val first = spring.step(-12f, 1f / 120)
        assertTrue(first > before && first < -12f)
        val measured = spring.step(-22f, 1f / 120)
        assertTrue(abs(measured - first) < 10f)
        repeat(100) { spring.step(-22f, 1f / 120) }
        assertEquals(-22f, spring.position, .05f)
    }

    @Test
    fun releasingTheLastReservationWaitsForTheReturnToSettle() {
        val spring = IconMotionSpring()
        repeat(50) { spring.step(-12f, 1f / 60) }
        spring.step(0f, 1f / 60)
        assertFalse(spring.isSettled(0f))
        assertTrue(spring.position < 0f)
        repeat(60) { spring.step(0f, 1f / 60) }
        assertTrue(spring.isSettled(0f))
        assertEquals(0f, spring.position, 0f)
    }
}
