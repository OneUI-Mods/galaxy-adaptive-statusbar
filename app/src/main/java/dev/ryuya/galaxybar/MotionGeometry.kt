package dev.ryuya.galaxybar

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* The renderer and contrast sampler share the same native-window mapping. */
object MotionGeometry {
    @JvmStatic
    fun map(
        frame: MotionFrame,
        sourceWidth: Float,
        originX: Float,
        originY: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): MotionGeometry.Bounds? {
        val scale: Float = frame.width / sourceWidth
        return MotionGeometry.Bounds(
            frame.x + (left - originX) * scale,
            frame.y + (top - originY) * scale,
            frame.x + (right - originX) * scale,
            frame.y + (bottom - originY) * scale,
        )
    }

    @JvmRecord
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        override fun toString(): String =
            "Bounds[left=$left, top=$top, right=$right, bottom=$bottom]"
    }
}
