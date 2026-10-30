package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

open class ColorFadeTest {
    @Test
    open fun finishesBeforeNextTenHzCapture() {
        val fade = ColorFade(ColorFadeTest.BLACK)
        fade.setTarget(ColorFadeTest.WHITE, 1000)
        assertEquals(ColorFadeTest.BLACK, fade.color(1000))
        val middle = fade.color(1040)
        assertTrue((middle and 255) > 127 && (middle and 255) < 255)
        assertEquals(255, middle ushr 24)
        assertEquals(ColorFadeTest.WHITE, fade.color(1080))
        assertFalse(fade.running(1080))
    }

    @Test
    open fun repeatedHighRateDecisionDoesNotRestartFade() {
        val fade = ColorFade(ColorFadeTest.WHITE)
        fade.setTarget(ColorFadeTest.BLACK, 1000)
        fade.setTarget(ColorFadeTest.BLACK, 1033)
        fade.setTarget(ColorFadeTest.BLACK, 1066)
        assertEquals(ColorFadeTest.BLACK, fade.color(1080))
        assertFalse(fade.running(1080))
    }

    @Test
    open fun reversalStartsFromDisplayedColorWithoutJump() {
        val fade = ColorFade(ColorFadeTest.BLACK)
        fade.setTarget(ColorFadeTest.WHITE, 1000)
        val visible = fade.color(1033)
        fade.setTarget(ColorFadeTest.BLACK, 1033)
        assertEquals(visible, fade.color(1033))
        assertTrue((fade.color(1050) and 255) < (visible and 255))
        assertEquals(ColorFadeTest.BLACK, fade.color(1113))
    }

    @Test
    open fun firstColorAndDisabledAnimationAreImmediate() {
        val fade = ColorFade(ColorFadeTest.WHITE)
        assertEquals(ColorFadeTest.WHITE, fade.color(0))
        assertFalse(fade.running(0))
        fade.setTarget(ColorFadeTest.BLACK, 1000)
        fade.finish()
        assertEquals(ColorFadeTest.BLACK, fade.color(1001))
        assertFalse(fade.running(1001))
    }

    @Test
    open fun selectedDurationIsUsedInsteadOfDefault() {
        val fade = ColorFade(ColorFadeTest.BLACK)
        fade.setDuration(500, 1000)
        fade.setTarget(ColorFadeTest.WHITE, 1000)
        assertTrue(fade.running(1080))
        assertTrue(fade.running(1499))
        assertEquals(ColorFadeTest.WHITE, fade.color(1500))
        assertFalse(fade.running(1500))
    }

    @Test
    open fun zeroDurationImmediatelyCompletesRunningAndFutureTransitions() {
        val fade = ColorFade(ColorFadeTest.BLACK)
        fade.setTarget(ColorFadeTest.WHITE, 1000)
        fade.setDuration(0, 1020)
        assertEquals(ColorFadeTest.WHITE, fade.color(1020))
        assertFalse(fade.running(1020))
        fade.setTarget(ColorFadeTest.BLACK, 1030)
        assertEquals(ColorFadeTest.BLACK, fade.color(1030))
        assertFalse(fade.running(1030))
    }

    @Test
    open fun changingDurationPreservesVisibleColorAndRepeatedSettingsDoNotRestart() {
        val fade = ColorFade(ColorFadeTest.BLACK)
        fade.setTarget(ColorFadeTest.WHITE, 1000)
        val visible = fade.color(1020)
        fade.setDuration(300, 1020)
        assertEquals(visible, fade.color(1020))
        fade.setDuration(300, 1100)
        assertEquals(ColorFadeTest.WHITE, fade.color(1320))
        assertFalse(fade.running(1320))
    }

    @Test
    open fun durationIsBoundedAndAlignedWithSliderSteps() {
        assertEquals(0, ColorFade.clampDuration(Int.MIN_VALUE))
        assertEquals(500, ColorFade.clampDuration(Int.MAX_VALUE))
        assertEquals(80, ColorFade.clampDuration(80))
        assertEquals(90, ColorFade.clampDuration(85))
    }

    companion object {
        private const val BLACK: Int = -0x1000000
        private const val WHITE: Int = -0x1
    }
}
