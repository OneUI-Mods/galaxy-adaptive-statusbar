package dev.ryuya.galaxybar

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.abs

/** Crossfades the real battery view; native drawing and adaptive colors remain live. */
internal class ChargingBatteryHandoff(private val battery: View) : AutoCloseable {
    private val originalAlpha = battery.alpha
    private var appliedAlpha = originalAlpha
    private var closed = false

    fun normalBounds(): Rect? {
        val shape = NativeBatteryView.geometry(battery)?.shape ?: return null
        if (battery.width <= 0 || battery.height <= 0) return null
        val matrix = Matrix()
        battery.transformMatrixToGlobal(matrix)
        val bounds = RectF(0f, 0f, battery.width.toFloat(), battery.height.toFloat())
        matrix.mapRect(bounds)
        val window = Rect()
        bounds.roundOut(window)
        return NativeBatteryView.drawingArea(battery, window, shape).takeUnless { it.isEmpty }
    }

    fun apply(entering: Boolean, fraction: Float) {
        if (closed || !fraction.isFinite()) return
        val progress = fraction.coerceIn(0f, 1f)
        // The native return callback hides the old meter until fraction > .95. Its
        // dedicated Compose sibling does not receive that callback at all.
        val alpha = if (entering) progress else ChargingBatteryBlend.alpha(progress)
        appliedAlpha = originalAlpha * alpha
        battery.alpha = appliedAlpha
    }

    override fun close() {
        if (closed) return
        closed = true
        if (abs(battery.alpha - appliedAlpha) < .01f) battery.alpha = originalAlpha
    }
}
