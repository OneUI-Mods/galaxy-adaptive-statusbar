package dev.ryuya.galaxybar

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.abs

/** Crossfades the real battery view; native drawing and adaptive colors remain live. */
internal class ChargingBatteryHandoff(private val battery: View, private val label: View?) :
    AutoCloseable {
    private val originalAlpha = battery.alpha
    private var appliedAlpha = originalAlpha
    private var closed = false
    private val originalLabelX = label?.translationX ?: 0f
    private var appliedLabelX = originalLabelX
    private var returning = false
    private var progress = 0f
    private val matrix = Matrix()
    private val bounds = RectF()
    private val matrixValues = FloatArray(9)

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
        returning = !entering
        this.progress = progress
        // The native return callback hides the old meter until fraction > .95. Its
        // dedicated Compose sibling does not receive that callback at all.
        val alpha = if (entering) progress else ChargingBatteryBlend.alpha(progress)
        appliedAlpha = originalAlpha * alpha
        battery.alpha = appliedAlpha
    }

    fun onFrame() {
        val text = label ?: return
        if (closed || !returning || !NativeBatteryView.supports(battery)) return
        val shape = NativeBatteryView.geometry(battery)?.shape ?: return
        val percent = shape.percent ?: return
        val parent = text.parent as? View ?: return
        matrix.reset()
        battery.transformMatrixToGlobal(matrix)
        bounds.set(percent)
        bounds.offset((battery.width - shape.width) / 2f, (battery.height - shape.height) / 2f)
        matrix.mapRect(bounds)
        val targetCenter = bounds.centerX()
        matrix.reset()
        parent.transformMatrixToGlobal(matrix)
        matrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        if (abs(scale) < .001f) return
        // Native legacy glyph placement has no bounds for the Compose battery. Align the
        // live charging label to the measured native percentage instead of showing two
        // numbers at different positions during their crossfade.
        text.translationX = originalLabelX
        matrix.reset()
        text.transformMatrixToGlobal(matrix)
        bounds.set(0f, 0f, text.width.toFloat(), text.height.toFloat())
        matrix.mapRect(bounds)
        appliedLabelX =
            originalLabelX +
                (targetCenter - bounds.centerX()) / scale * ChargingBatteryBlend.motion(progress)
        text.translationX = appliedLabelX
    }

    override fun close() {
        if (closed) return
        closed = true
        if (abs(battery.alpha - appliedAlpha) < .01f) battery.alpha = originalAlpha
        label?.let {
            if (abs(it.translationX - appliedLabelX) < .01f) it.translationX = originalLabelX
        }
    }
}
