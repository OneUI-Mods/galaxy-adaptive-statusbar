package dev.ryuya.galaxybar

import kotlin.math.min

/** Reserve only the space currently occupied by the growing native battery background. */
internal object ChargingMotionGeometry {
    fun shift(
        iconsRight: Float,
        normalBatteryLeft: Float,
        expandedLeft: Float,
        gap: Float,
        alpha: Float,
    ): Float {
        if (
            !iconsRight.isFinite() ||
                !normalBatteryLeft.isFinite() ||
                !expandedLeft.isFinite() ||
                !gap.isFinite() ||
                !alpha.isFinite() ||
                gap < 0
        )
            return 0f
        val growth = (normalBatteryLeft - expandedLeft).coerceAtLeast(0f)
        val visibleGrowth = growth * alpha.coerceIn(0f, 1f)
        // Fade the extra gap in with the growth, so the first/last frame cannot jump.
        val separation = min(gap, visibleGrowth)
        return min(0f, normalBatteryLeft - visibleGrowth - separation - iconsRight)
    }
}
