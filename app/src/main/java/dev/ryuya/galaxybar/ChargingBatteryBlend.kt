package dev.ryuya.galaxybar

internal object ChargingBatteryBlend {
    /** Zero slope at each end avoids a visible step when the native view is restored. */
    fun alpha(progress: Float): Float {
        if (!progress.isFinite()) return 0f
        val t = progress.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
