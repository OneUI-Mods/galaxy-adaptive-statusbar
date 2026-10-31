package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

class ChargingBatteryBlendTest {
    @Test
    fun normalBatteryAppearsBeforeTheLastFivePercent() {
        assertEquals(.5f, ChargingBatteryBlend.alpha(.5f), .001f)
        assertTrue(ChargingBatteryBlend.alpha(.94f) > .9f)
    }

    @Test
    fun endpointsJoinWithoutAnOpacityStep() {
        assertEquals(0f, ChargingBatteryBlend.alpha(0f), 0f)
        assertEquals(1f, ChargingBatteryBlend.alpha(1f), 0f)
        assertTrue(ChargingBatteryBlend.alpha(.001f) < .00001f)
        assertTrue(1f - ChargingBatteryBlend.alpha(.999f) < .00001f)
    }

    @Test
    fun returnNeverReversesOrExceedsFullOpacity() {
        var previous = 0f
        for (frame in 0..120) {
            val alpha = ChargingBatteryBlend.alpha(frame / 120f)
            assertTrue(alpha in previous..1f)
            previous = alpha
        }
    }

    @Test
    fun invalidProgressDoesNotCreateInvalidViewAlpha() {
        assertEquals(0f, ChargingBatteryBlend.alpha(-1f), 0f)
        assertEquals(1f, ChargingBatteryBlend.alpha(2f), 0f)
        assertEquals(0f, ChargingBatteryBlend.alpha(Float.NaN), 0f)
    }
}
