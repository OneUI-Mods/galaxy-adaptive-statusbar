package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

class ChargingMotionGeometryTest {
    @Test
    fun normalBatteryDoesNotMoveIcons() {
        assertEquals(0f, ChargingMotionGeometry.shift(100f, 110f, 110f, 4f, 1f), 0.0001f)
    }

    @Test
    fun growthKeepsIconsClearOfTheBattery() {
        assertEquals(-24f, ChargingMotionGeometry.shift(100f, 110f, 80f, 4f, 1f), 0.0001f)
    }

    @Test
    fun arbitrarySpringOvershootAndReversalFollowGeometry() {
        val left = listOf(110f, 100f, 80f, 60f, 80f, 100f, 110f)
        val shift = left.map { ChargingMotionGeometry.shift(100f, 110f, it, 4f, 1f) }
        assertEquals(listOf(0f, -4f, -24f, -44f, -24f, -4f, 0f), shift)
    }

    @Test
    fun disappearingBackgroundReturnsIconsWithoutAJump() {
        assertEquals(0f, ChargingMotionGeometry.shift(100f, 110f, 60f, 4f, 0f), 0.0001f)
        assertEquals(-19f, ChargingMotionGeometry.shift(100f, 110f, 60f, 4f, 0.5f), 0.0001f)
    }

    @Test
    fun tinyGrowthDoesNotSuddenlyReserveTheWholeGap() {
        assertEquals(-0.2f, ChargingMotionGeometry.shift(110f, 110f, 109.9f, 4f, 1f), 0.0001f)
    }

    @Test
    fun existingParentReservationIsNotAddedTwice() {
        assertEquals(-4f, ChargingMotionGeometry.shift(80f, 90f, 80f, 4f, 1f), 0.0001f)
    }

    @Test
    fun noGrowthAndAlreadyAvailableSpaceNeverMoveRight() {
        assertEquals(0f, ChargingMotionGeometry.shift(100f, 110f, 120f, 4f, 1f), 0.0001f)
        assertEquals(0f, ChargingMotionGeometry.shift(50f, 110f, 80f, 4f, 1f), 0.0001f)
    }

    @Test
    fun invalidOrNonFiniteGeometryKeepsTheNativePosition() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, ChargingMotionGeometry.shift(value, 110f, 80f, 4f, 1f), 0f)
            assertEquals(0f, ChargingMotionGeometry.shift(100f, value, 80f, 4f, 1f), 0f)
            assertEquals(0f, ChargingMotionGeometry.shift(100f, 110f, value, 4f, 1f), 0f)
            assertEquals(0f, ChargingMotionGeometry.shift(100f, 110f, 80f, value, 1f), 0f)
            assertEquals(0f, ChargingMotionGeometry.shift(100f, 110f, 80f, 4f, value), 0f)
        }
        assertEquals(0f, ChargingMotionGeometry.shift(100f, 110f, 80f, -1f, 1f), 0f)
    }
}
