package dev.ryuya.galaxybar

import kotlin.math.abs
import kotlin.math.exp

/** Never use Samsung's transient capsule stretch as the baseline of a persistent dot. */
internal object PrivacyDotScale {
    fun target(diameter: Float, density: Float, nativeX: Float, nativeY: Float): Float {
        if (
            !nativeX.isFinite() ||
                !nativeY.isFinite() ||
                abs(nativeX - 1f) > .02f ||
                abs(nativeY - 1f) > .02f
        )
            return 1f
        return PrivacySlideGeometry.dotScale(diameter, density)
    }

    fun step(current: Float, target: Float, seconds: Float): Float {
        val from = if (current.isFinite()) current.coerceIn(1f, 1.5f) else 1f
        val to = if (target.isFinite()) target.coerceIn(1f, 1.5f) else 1f
        if (!seconds.isFinite() || seconds <= 0f) return from
        val next = to + (from - to) * exp(-seconds.coerceAtMost(.05f) / .04f)
        return if (abs(next - to) < .001f) to else next.coerceIn(1f, 1.5f)
    }
}
