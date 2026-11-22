package dev.ryuya.galaxybar

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
    fun choose(values: DoubleArray, previous: Int, algorithm: String, hysteresis: Double): Int =
        ContrastDecisionRuntime.choose(values, previous, algorithm, hysteresis)
}
