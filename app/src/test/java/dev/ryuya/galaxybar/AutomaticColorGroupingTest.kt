package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

class AutomaticColorGroupingTest {
    private fun analyze(vararg luminance: Double): AutomaticColorGrouping.Analysis =
        AutomaticColorGrouping.analyze(
            luminance.map { DoubleArray(32) { _ -> it } },
            0,
            IntArray(luminance.size),
            "robust",
            .15,
        )

    @Test
    fun flatDarkAndLightBackgroundsStayAsWholeParts() {
        val dark = analyze(0.0, .01, .02, .01)
        assertFalse(dark.preferCharacters)
        assertEquals(-1, dark.partColor)
        val light = analyze(1.0, .9, .95, 1.0)
        assertFalse(light.preferCharacters)
        assertEquals(-0x1000000, light.partColor)
    }

    @Test
    fun brightBuildingsAndDarkRoadsOnlySplitWhenIndividualColorsHelp() {
        val mixed = analyze(.01, .9, .02, .85, .01)
        assertTrue(mixed.preferCharacters)
        assertTrue(mixed.urgent)
        assertTrue(mixed.gain > 2)
        assertArrayEquals(intArrayOf(-1, -0x1000000, -1, -0x1000000, -1), mixed.characterColors)
    }

    @Test
    fun readableMidtonesDoNotSplitJustBecausePreferredColorsDiffer() {
        val result = analyze(.16, .18, .20, .17, .19)
        assertFalse(result.preferCharacters)
    }

    @Test
    fun busyTextureAloneDoesNotForceSplitting() {
        val glyph = DoubleArray(100) { if (it % 2 == 0) .01 else .9 }
        val result =
            AutomaticColorGrouping.analyze(List(5) { glyph.clone() }, 0, IntArray(5), "robust", .15)
        assertFalse(result.preferCharacters)
    }

    @Test
    fun isolatedBrightNoiseDoesNotBreakApartOtherwiseDarkText() {
        val glyph = DoubleArray(100) { if (it == 0) 1.0 else .02 }
        val result =
            AutomaticColorGrouping.analyze(List(5) { glyph.clone() }, 0, IntArray(5), "robust", .15)
        assertFalse(result.preferCharacters)
        assertEquals(-1, result.partColor)
    }

    @Test
    fun aSingleGlyphNeverNeedsAGroupingChange() {
        assertFalse(analyze(.01).preferCharacters)
        assertFalse(analyze(.95).preferCharacters)
    }

    @Test
    fun medianAnalysisDoesNotReorderTheCapturedSamples() {
        val sample = doubleArrayOf(.9, .1, .7, .3)
        AutomaticColorGrouping.analyze(listOf(sample), 0, intArrayOf(0), "median", .15)
        assertArrayEquals(doubleArrayOf(.9, .1, .7, .3), sample, 0.0)
    }

    private fun decision(split: Boolean, urgent: Boolean = false) =
        AutomaticColorGrouping.Analysis(-1, intArrayOf(-1, -0x1000000), split, urgent, 1.3)

    @Test
    fun shortLivedMapTextureChangesDoNotFlapTheMode() {
        val state = AutomaticColorGrouping.State()
        for (i in 0..20) assertFalse(state.select(decision(i % 2 == 0), i * 60L))
    }

    @Test
    fun sustainedImprovementSplitsQuicklyAndRejoiningWaitsForSettling() {
        val state = AutomaticColorGrouping.State()
        assertFalse(state.select(decision(true), 0))
        assertFalse(state.select(decision(true), 50))
        assertTrue(state.select(decision(true), 100))
        assertTrue(state.select(decision(false), 200))
        assertTrue(state.select(decision(false), 600))
        assertFalse(state.select(decision(false), 850))
    }

    @Test
    fun clearIllegibilitySplitsImmediately() {
        assertTrue(AutomaticColorGrouping.State().select(decision(true, urgent = true), 0))
    }

    @Test
    fun eachDateOrTimePartHasItsOwnIndependentState() {
        val date = AutomaticColorGrouping.State()
        val time = AutomaticColorGrouping.State()
        assertTrue(date.select(decision(true, urgent = true), 0))
        assertFalse(time.select(decision(false), 0))
    }

    @Test
    fun charactersConvergeBeforeSpansMergeEvenAtTheLongestFadeDuration() {
        val mixed = analyze(.01, .9, .02, .85, .01)
        val readable = analyze(.16, .18, .20, .17, .19)
        assertFalse(readable.preferCharacters)
        val state = AutomaticColorGrouping.State()
        assertTrue(state.select(mixed, 0))
        val fades = mixed.characterColors.map { ColorFade(it) }
        for (now in listOf(0L, 100L, 350L, 500L, 650L)) {
            val individual = state.select(readable, now)
            if (individual) {
                val target = readable.characterPhaseColors()
                fades.forEachIndexed { index, fade ->
                    fade.setDuration(ColorFade.MAX_DURATION_MS, now)
                    fade.setTarget(target[index], now)
                }
            } else {
                assertEquals(650L, now)
                fades.forEach { assertEquals(readable.partColor, it.color(now)) }
            }
        }
        assertFalse(state.characters)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailableBackgroundSamplesCannotBecomeAGroupingDecision() {
        AutomaticColorGrouping.analyze(listOf(doubleArrayOf()), 0, intArrayOf(0), "robust", .15)
    }
}
