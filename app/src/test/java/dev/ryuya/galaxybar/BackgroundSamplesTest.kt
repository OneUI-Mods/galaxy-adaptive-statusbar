package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

open class BackgroundSamplesTest {
    @Test
    open fun aMissingPixelRejectsTheWholeSampleRatherThanTreatingItAsBlack() {
        try {
            BackgroundSamples.read(intArrayOf(-0x1, 0x00000000, -0x1000000), 3, 1, 0, 0, 3, 1)
            fail("Unresolved frame must not produce a contrast decision")
        } catch (expected: BackgroundSamples.Unavailable) {
            assertArrayEquals(
                doubleArrayOf((1).toDouble(), (0).toDouble()),
                BackgroundSamples.read(intArrayOf(-0x1, -0x1000000), 2, 1, 0, 0, 2, 1),
                0.0,
            )
        }
    }

    @Test
    open fun onlyTheRequestedRegionHasToBeOpaque() {
        assertArrayEquals(
            doubleArrayOf((1).toDouble()),
            BackgroundSamples.read(intArrayOf(0x00000000, -0x1), 2, 1, 1, 0, 2, 1),
            0.0,
        )
    }

    @Test(expected = BackgroundSamples.Unavailable::class)
    open fun emptyIntersectionIsTemporary() {
        BackgroundSamples.read(intArrayOf(-0x1), 1, 1, 0, 0, 0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    open fun aMalformedBufferRemainsARealError() {
        BackgroundSamples.read(intArrayOf(-0x1), 2, 1, 0, 0, 1, 1)
    }

    @Test(expected = BackgroundSamples.Unavailable::class)
    open fun partiallyTransparentPixelsAreNotLuminanceSamples() {
        BackgroundSamples.read(intArrayOf(-0x6000001), 1, 1, 0, 0, 1, 1)
    }

    @Test
    open fun theExistingOpacityThresholdIsPreserved() {
        assertArrayEquals(
            doubleArrayOf((1).toDouble()),
            BackgroundSamples.read(intArrayOf(-0x5000001), 1, 1, 0, 0, 1, 1),
            0.0,
        )
    }
}
