package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

/* A density-independent adjustment relative to Samsung's native (possibly negative) spacing. */
object NetworkSpeedSpacing {
    const val KEY: String = "network_speed_spacing_dp"
    const val MIN_DP: Int = 0
    const val MAX_DP: Int = 10

    @JvmStatic
    fun clamp(value: Int): Int {
        return max(NetworkSpeedSpacing.MIN_DP, min(NetworkSpeedSpacing.MAX_DP, value))
    }

    @JvmStatic
    fun extra(nativeExtra: Float, density: Float, adjustmentDp: Int): Float {
        return nativeExtra + density * NetworkSpeedSpacing.clamp(adjustmentDp)
    }
}
