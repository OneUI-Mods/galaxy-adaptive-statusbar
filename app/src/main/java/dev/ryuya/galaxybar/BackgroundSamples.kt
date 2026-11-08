package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.sqrt

/* Only fully resolved background pixels may participate in contrast decisions. */
object BackgroundSamples {
    @JvmStatic
    fun read(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): DoubleArray? {
        require(!(width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong())) {
            "Invalid background buffer"
        }
        require(!(left < 0 || top < 0 || right > width || bottom > height)) {
            "Invalid sample bounds"
        }
        if (left >= right || top >= bottom) throw BackgroundSamples.Unavailable("Empty background")
        val area = (right - left) * (bottom - top)
        val step = max(1, (sqrt(area / 256.0)).toInt())
        // Allocate exactly the sampled grid, not the full region followed by a second copy.
        val columns = (right - left - 1) / step + 1
        val rows = (bottom - top - 1) / step + 1
        val values = DoubleArray(columns * rows)
        var count = 0
        var y: Int = top
        while (y < bottom) {
            var x: Int = left
            while (x < right) {
                val pixel: Int = pixels[y * width + x]
                if ((pixel ushr 24) < 250)
                    throw BackgroundSamples.Unavailable("Background not yet opaque")
                values[count++] = Contrast.luminance(pixel)
                x += step
            }
            y += step
        }
        return values
    }

    class Unavailable(reason: String?) : IllegalStateException(reason)
}
