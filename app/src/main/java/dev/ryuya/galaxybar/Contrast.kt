package dev.ryuya.galaxybar

import java.util.Arrays
import kotlin.jvm.JvmStatic
import kotlin.math.pow

/* Platform independent contrast decisions in linear sRGB. */
object Contrast {
    @JvmStatic /* Source-over composition in the sRGB buffer, before converting to relative luminance. */
    fun over(background: Int, foreground: Int, alpha: Int): Int {
        val inverse: Int = 255 - alpha
        val red: Int =
            (((foreground ushr 16) and 255) * alpha +
                ((background ushr 16) and 255) * inverse +
                127) / 255
        val green: Int =
            (((foreground ushr 8) and 255) * alpha +
                ((background ushr 8) and 255) * inverse +
                127) / 255
        val blue: Int = ((foreground and 255) * alpha + (background and 255) * inverse + 127) / 255
        return -0x1000000 or (red shl 16) or (green shl 8) or blue
    }

    private val LINEAR: DoubleArray = DoubleArray(256)

    init {
        for (i in 0..255) {
            val s: Double = i / 255.0
            Contrast.LINEAR[i] = if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
    }

    @JvmStatic
    fun luminance(argb: Int): Double {
        return (.2126 * Contrast.LINEAR[(argb shr 16) and 255] +
            .7152 * Contrast.LINEAR[(argb shr 8) and 255] +
            .0722 * Contrast.LINEAR[argb and 255])
    }

    @JvmStatic
    fun choose(values: DoubleArray, previous: Int, algorithm: String, hysteresis: Double): Int {
        require(values.size != 0) { "No opaque samples" }
        var white: Double
        var black: Double
        if (algorithm == "median" || algorithm == "mean") {
            var l: Double = 0.0
            if (algorithm == "median") {
                Arrays.sort(values)
                l = values[values.size / 2]
            } else {
                for (v in values) l += v
                l /= values.size.toDouble()
            }
            white = 1.05 / (l + .05)
            black = (l + .05) / .05
        } else if (algorithm == "vote") {
            white = 1.0
            black = 1.0
            for (v in values) {
                if (1.05 / (v + .05) > (v + .05) / .05) white++ else black++
            }
        } else {
            // Lower decile contrast: more robust to mixed backgrounds than a mean.
            val w = DoubleArray(values.size)
            val b = DoubleArray(values.size)
            for (i in values.indices) {
                w[i] = 1.05 / (values[i] + .05)
                b[i] = (values[i] + .05) / .05
            }
            Arrays.sort(w)
            Arrays.sort(b)
            white = w[values.size / 10]
            black = b[values.size / 10]
        }
        val ratio: Double = black / white
        if (previous == -0x1000000 && ratio >= 1 / (1 + hysteresis)) return previous
        if (previous == -0x1 && ratio <= 1 + hysteresis) return previous
        return if (ratio >= 1) -0x1000000 else -0x1
    }
}
