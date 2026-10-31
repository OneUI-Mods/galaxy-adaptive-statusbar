package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

class PrivacyDotScaleTest {
    @Test
    fun capturedGreenBlobCannotBecomeAnEnlargementBaseline() {
        assertEquals(1f, PrivacyDotScale.target(12f, 2.925f, 10.832831f, 1.4875f), 0f)
        assertEquals(1f, PrivacyDotScale.target(12f, 2.925f, 7.28f, 1f), 0f)
    }

    @Test
    fun onlyTheNativeRoundDotReceivesTheSmallUniformEnlargement() {
        assertEquals(1.4875f, PrivacyDotScale.target(12f, 2.925f, 1f, 1f), .001f)
        assertEquals(1f, PrivacyDotScale.target(12f, 2.925f, 1f, 2f), 0f)
        assertEquals(1f, PrivacyDotScale.target(12f, 2.925f, Float.NaN, 1f), 0f)
    }

    @Test
    fun repeatedFramesDoNotMultiplyTheEnlargement() {
        var scale = 1f
        repeat(1000) { scale = PrivacyDotScale.step(scale, 1.4875f, 1f / 120) }
        assertEquals(1.4875f, scale, .001f)
        assertTrue(PrivacyDotScale.step(10.83f, 15f, .02f) <= 1.5f)
    }

    @Test
    fun enlargedCircleAppearsContinuouslyAtBothFrameRates() {
        val first = PrivacyDotScale.step(1f, 1.4875f, 1f / 120)
        assertTrue(first > 1f && first < 1.4875f)
        var sixty = 1f
        var fast = 1f
        repeat(6) { sixty = PrivacyDotScale.step(sixty, 1.4875f, 1f / 60) }
        repeat(12) { fast = PrivacyDotScale.step(fast, 1.4875f, 1f / 120) }
        assertEquals(sixty, fast, .001f)
    }

    @Test
    fun invalidTimingAndDimensionsNeverProduceAnInvalidTransform() {
        assertEquals(1f, PrivacyDotScale.target(0f, 3f, 1f, 1f), 0f)
        assertEquals(1f, PrivacyDotScale.step(1f, Float.NaN, .1f), 0f)
        assertEquals(1f, PrivacyDotScale.step(Float.NaN, 1.5f, Float.NaN), 0f)
    }
}
