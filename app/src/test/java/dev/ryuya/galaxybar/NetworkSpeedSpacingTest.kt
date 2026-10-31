package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

open class NetworkSpeedSpacingTest {
    @Test
    open fun standardKeepsNativeNegativeSpacing() {
        assertEquals(
            (-5.85f).toFloat(),
            (NetworkSpeedSpacing.extra(-5.85f, 2.925f, 0)).toFloat(),
            (.001f).toFloat(),
        )
    }

    @Test
    open fun adjustmentUsesDensityAndRejectsOverlapProneNegativeValues() {
        assertEquals(
            (5.85f).toFloat(),
            (NetworkSpeedSpacing.extra(-5.85f, 2.925f, 4)).toFloat(),
            (.001f).toFloat(),
        )
        assertEquals(
            (-5.85f).toFloat(),
            (NetworkSpeedSpacing.extra(-5.85f, 2.925f, -2)).toFloat(),
            (.001f).toFloat(),
        )
    }

    @Test
    open fun savedValuesCannotExceedSupportedRange() {
        assertEquals(NetworkSpeedSpacing.MIN_DP, NetworkSpeedSpacing.clamp(Int.MIN_VALUE))
        assertEquals(NetworkSpeedSpacing.MAX_DP, NetworkSpeedSpacing.clamp(Int.MAX_VALUE))
    }
}
