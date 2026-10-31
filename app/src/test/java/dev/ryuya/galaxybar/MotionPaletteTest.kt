package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

open class MotionPaletteTest {
    @Test
    open fun homeColorsDoNotSampleTheTinyOpeningAppBeforeHandoff() {
        assertEquals(
            MotionPalette.Source.FROZEN,
            MotionPalette.choose(false, true, MotionTiming.Opacity(1f, 0f)),
        )
        assertEquals(
            MotionPalette.Source.FROZEN,
            MotionPalette.choose(true, true, MotionTiming.Opacity(.5f, .02f)),
        )
        assertEquals(
            MotionPalette.Source.WINDOW,
            MotionPalette.choose(true, true, MotionTiming.Opacity(.02f, .15f)),
        )
    }

    @Test
    open fun closingSamplesHomeWhenTheMovingBarHasFaded() {
        assertEquals(
            MotionPalette.Source.WINDOW,
            MotionPalette.choose(true, false, MotionTiming.Opacity(0f, 1f)),
        )
        assertEquals(
            MotionPalette.Source.SCREEN,
            MotionPalette.choose(true, false, MotionTiming.Opacity(.1f, .1f)),
        )
        assertEquals(
            MotionPalette.Source.SCREEN,
            MotionPalette.choose(true, false, MotionTiming.Opacity(1f, 0f)),
        )
    }

    @Test
    open fun dimOverviewAndReturnKeepSamplingTheCard() {
        assertEquals(
            MotionPalette.Source.WINDOW,
            MotionPalette.choose(true, false, MotionTiming.Opacity(0f, .35f)),
        )
        assertEquals(
            MotionPalette.Source.WINDOW,
            MotionPalette.choose(true, true, MotionTiming.Opacity(0f, .35f)),
        )
    }

    @Test
    open fun openingHasOnePaletteTransferAndClosingEndsAtTheHomePalette() {
        val timing = MotionTiming()
        timing.update(true, .85f, 200f, 100)
        var source = MotionPalette.Source.FROZEN
        var switches: Int = 0
        for (now in 100..400) {
            val next = MotionPalette.choose(true, true, timing.opacity(now.toLong())!!)
            if (next != source) switches++
            source = next!!
        }
        assertEquals(1, switches)
        assertEquals(MotionPalette.Source.WINDOW, source)
        timing.update(false, .1f, -1f, 400)
        assertEquals(
            MotionPalette.Source.SCREEN,
            MotionPalette.choose(true, false, timing.opacity(600)!!),
        )
    }
}
