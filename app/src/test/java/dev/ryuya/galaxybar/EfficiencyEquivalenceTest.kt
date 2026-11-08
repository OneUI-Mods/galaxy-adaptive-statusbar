package dev.ryuya.galaxybar

import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Compare against the previous two-sort and full-region-copy implementations. */
class EfficiencyEquivalenceTest {
    @Test
    fun robustContrastRetainsDecilesHysteresisAndInputOrder() {
        val random = Random(537)
        for (size in listOf(1, 2, 9, 10, 11, 19, 20, 31, 256, 1024)) {
            repeat(30) {
                val samples =
                    DoubleArray(size) {
                        if (random.nextBoolean()) random.nextDouble()
                        else random.nextInt(2).toDouble()
                    }
                val before = samples.clone()
                for (previous in intArrayOf(0, -1, -0x1000000)) {
                    for (hysteresis in doubleArrayOf(0.0, .15, .5)) {
                        assertEquals(
                            originalChoose(samples, previous, hysteresis),
                            Contrast.choose(samples, previous, "robust", hysteresis),
                        )
                    }
                }
                assertArrayEquals(before, samples, 0.0)
            }
        }
    }

    @Test
    fun samplingGridAndOpacityChecksAreUnchangedAcrossStepBoundaries() {
        val width = 320
        val height = 128
        val random = Random(538)
        val pixels = IntArray(width * height) { random.nextInt() or -0x1000000 }
        for (right in listOf(1, 15, 16, 17, 31, 32, 33, 71, width)) {
            for (bottom in listOf(1, 7, 16, 17, 64, height)) {
                val step = max(1, sqrt(right * bottom / 256.0).toInt())
                val expected = mutableListOf<Double>()
                for (y in 0 until bottom step step) {
                    for (x in 0 until right step step) {
                        expected.add(Contrast.luminance(pixels[y * width + x]))
                    }
                }
                assertArrayEquals(
                    expected.toDoubleArray(),
                    BackgroundSamples.read(pixels, width, height, 0, 0, right, bottom),
                    0.0,
                )
            }
        }
    }

    private fun originalChoose(values: DoubleArray, previous: Int, hysteresis: Double): Int {
        val whiteContrasts = DoubleArray(values.size) { 1.05 / (values[it] + .05) }
        val blackContrasts = DoubleArray(values.size) { (values[it] + .05) / .05 }
        whiteContrasts.sort()
        blackContrasts.sort()
        val ratio = blackContrasts[values.size / 10] / whiteContrasts[values.size / 10]
        if (previous == -0x1000000 && ratio >= 1 / (1 + hysteresis)) return previous
        if (previous == -1 && ratio <= 1 + hysteresis) return previous
        return if (ratio >= 1) -0x1000000 else -1
    }
}
