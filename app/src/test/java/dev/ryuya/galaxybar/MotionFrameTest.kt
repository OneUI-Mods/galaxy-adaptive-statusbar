package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

open class MotionFrameTest {
    private fun frame(time: Long, x: Float, width: Float): MotionFrame? {
        return MotionFrame(
            10,
            time,
            true,
            42,
            false,
            false,
            x,
            100f,
            width,
            2000f,
            1f,
            200f,
            40f,
            false,
        )
    }

    @Test
    open fun acceptsCurrentFullscreenAndSpringOvershoot() {
        assertTrue(frame(1000, 0f, 1440f)!!.valid(1440, 3120, 1010))
        assertTrue(frame(1000, -50f, 1540f)!!.valid(1440, 3120, 1010))
    }

    @Test
    open fun rejectsStaleOrFutureFramesBeforeTheyCanHideTheRealBar() {
        assertFalse(frame(1000, 0f, 1440f)!!.valid(1440, 3120, 1300))
        assertFalse(frame(1500, 0f, 1440f)!!.valid(1440, 3120, 1000))
    }

    @Test
    open fun rejectsNonFiniteAndOutOfBoundsGeometry() {
        assertFalse(frame(1000, Float.NaN, 1000f)!!.valid(1440, 3120, 1010))
        assertFalse(frame(1000, 0f, Float!!.POSITIVE_INFINITY)!!.valid(1440, 3120, 1010))
        assertFalse(frame(1000, 0f, 0f)!!.valid(1440, 3120, 1010))
        assertFalse(frame(1000, 4000f, 1000f)!!.valid(1440, 3120, 1010))
    }

    @Test
    open fun terminationDoesNotNeedWindowGeometry() {
        assertTrue(
            MotionFrame(10, 1000, false, 0, false, false, 0f, 0f, 0f, 0f, 0f, -1f, 0f, true)
                .valid(1440, 3120, 1010)
        )
    }
}
