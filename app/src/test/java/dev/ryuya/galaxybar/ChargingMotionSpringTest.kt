package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

class ChargingMotionSpringTest {
    @Test
    fun suddenReservationMovesOverMultipleFrames() {
        val spring = ChargingMotionSpring()
        val first = spring.step(-150f, 1f / 60)
        assertTrue(first < 0f && first > -150f)
        repeat(60) { spring.step(-150f, 1f / 60) }
        assertEquals(-150f, spring.position, .05f)
    }

    @Test
    fun returningHomeDoesNotSnap() {
        val spring = ChargingMotionSpring()
        repeat(30) { spring.step(-150f, 1f / 60) }
        val first = spring.step(0f, 1f / 60)
        assertTrue(first > -150f && first < 0f)
        repeat(60) { spring.step(0f, 1f / 60) }
        assertTrue(spring.isSettled(0f))
    }

    @Test
    fun reversingTargetPreservesMomentum() {
        val spring = ChargingMotionSpring()
        repeat(3) { spring.step(-150f, 1f / 120) }
        val previous = spring.position
        val next = spring.step(0f, 1f / 120)
        assertTrue(next < previous)
        repeat(120) { spring.step(0f, 1f / 120) }
        assertEquals(0f, spring.position, .05f)
    }

    @Test
    fun frameRatesGiveEquivalentTrajectory() {
        val low = ChargingMotionSpring()
        val high = ChargingMotionSpring()
        repeat(12) { low.step(-150f, 1f / 60) }
        repeat(24) { high.step(-150f, 1f / 120) }
        assertEquals(low.position, high.position, .001f)
    }

    @Test
    fun pausesAndInvalidTimesCannotTeleport() {
        val spring = ChargingMotionSpring()
        assertEquals(0f, spring.step(Float.NaN, .02f), 0f)
        assertEquals(0f, spring.step(-150f, Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, spring.step(-150f, -1f), 0f)
        assertTrue(spring.step(-150f, 30f) > -150f)
    }
}
