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
        val values = DoubleArray((right - left) * (bottom - top))
        var count: Int = 0
        val step = max(1, (sqrt(values.size / 256.0)).toInt())
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
        return values.copyOf(count)
    }

    class Unavailable(reason: String?) : IllegalStateException(reason)
}
