package dev.ryuya.galaxybar

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

open class ContrastTest {
    @Test
    open fun batteryFillIsPartOfPercentageBackground() {
        val filled = Contrast.over(-0x1, -0x1000000, 255)
        val unfilled = Contrast.over(-0x1, -0x1000000, 99)
        assertEquals(-0x1000000, filled)
        assertEquals(-0x636364, unfilled)
        assertEquals(
            -0x1,
            Contrast.choose(doubleArrayOf(Contrast.luminance(filled)), 0, "mean", 0.0),
        )
        assertEquals(
            -0x1000000,
            Contrast.choose(doubleArrayOf(Contrast.luminance(unfilled)), 0, "mean", 0.0),
        )
    }

    @Test
    open fun linearSrgb() {
        assertEquals(
            (0).toDouble(),
            (Contrast.luminance(-0x1000000)).toDouble(),
            (1e-10).toDouble(),
        )
        assertEquals((1).toDouble(), (Contrast.luminance(-0x1)).toDouble(), (1e-10).toDouble())
        assertEquals(
            (.21586).toDouble(),
            (Contrast.luminance(-0x7f7f80)).toDouble(),
            (.00001).toDouble(),
        )
    }

    @Test
    open fun extremes() {
        for (a in arrayOf<String>("robust", "mean", "median", "vote")) {
            assertEquals(
                -0x1,
                Contrast.choose(
                    doubleArrayOf((0).toDouble(), (0).toDouble(), (0).toDouble()),
                    0,
                    a,
                    .15,
                ),
            )
            assertEquals(
                -0x1000000,
                Contrast.choose(
                    doubleArrayOf((1).toDouble(), (1).toDouble(), (1).toDouble()),
                    0,
                    a,
                    .15,
                ),
            )
        }
    }

    @Test
    open fun hysteresisBand() {
        val cross: Double = sqrt(.0525) - .05
        assertEquals(-0x1, Contrast.choose(doubleArrayOf(cross + .001), -0x1, "mean", .15))
        assertEquals(
            -0x1000000,
            Contrast.choose(doubleArrayOf(cross - .001), -0x1000000, "mean", .15),
        )
        assertEquals(-0x1000000, Contrast.choose(doubleArrayOf(.6), -0x1, "mean", .15))
    }

    @Test
    open fun mixedBackgroundUsesWorstContrast() {
        assertEquals(
            -0x1000000,
            Contrast.choose(
                doubleArrayOf(.02, .02, .4, .4, .4, .4, .4, .4, .4, .4),
                0,
                "mean",
                0.0,
            ),
        )
        assertEquals(
            -0x1,
            Contrast.choose(
                doubleArrayOf(.02, .02, .4, .4, .4, .4, .4, .4, .4, .4),
                0,
                "robust",
                0.0,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    open fun emptyFailsClosed() {
        Contrast.choose(DoubleArray(0), 0, "mean", .15)
    }
}
