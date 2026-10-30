package dev.ryuya.galaxybar

import kotlin.math.max
import kotlin.math.min

/* Screen-space reservation; drawing remains in Samsung's original privacy view. */
object PrivacySlideGeometry {
    const val ENTER_DURATION_MS: Long = 400

    @kotlin.jvm.JvmStatic
    fun unit(value: Float): Float {
        return if (java.lang.Float.isFinite(value)) max(0f, min(1f, value)) else 0f
    }

    @kotlin.jvm.JvmStatic
    fun shift(
        iconsRight: Float,
        chipLeft: Float,
        chipRight: Float,
        gap: Float,
        reveal: Float,
        alpha: Float,
    ): Float {
        if (
            !java.lang.Float.isFinite(iconsRight) ||
                !java.lang.Float.isFinite(chipLeft) ||
                !java.lang.Float.isFinite(chipRight) ||
                chipRight <= chipLeft ||
                !java.lang.Float.isFinite(gap) ||
                gap < 0
        )
            return 0f
        val progress = PrivacySlideGeometry.unit(reveal)
        val revealedLeft: Float = chipRight + (chipLeft - chipRight) * progress
        return min(0f, revealedLeft - gap * progress - iconsRight) *
            PrivacySlideGeometry.unit(alpha)
    }

    @kotlin.jvm.JvmStatic
    fun otherHide(reasons: Array<Any?>?): Boolean {
        if (reasons == null || reasons.size != 8) return true
        var hidden: Boolean = false
        for (i in reasons.indices) {
            val value = reasons[i] as? Boolean
            if (value == null) return true
            if (i != 1) hidden = hidden or value
        }
        return hidden
    }

    @kotlin.jvm.JvmStatic
    fun fits(iconsLeft: Float, shift: Float, occupiedRight: Float): Boolean {
        return java.lang.Float.isFinite(iconsLeft) &&
            java.lang.Float.isFinite(shift) &&
            java.lang.Float.isFinite(occupiedRight) &&
            iconsLeft + shift >= occupiedRight
    }

    @kotlin.jvm.JvmStatic
    fun combinedShift(chip: Float, dot: Float): Float {
        return min(
            0f,
            min(
                if (java.lang.Float.isFinite(chip)) chip else 0f,
                if (java.lang.Float.isFinite(dot)) dot else 0f,
            ),
        )
    }

    @kotlin.jvm.JvmStatic
    fun entranceOffset(width: Float, progress: Float): Float {
        return if (java.lang.Float.isFinite(width) && width > 0)
            width * (1 - PrivacySlideGeometry.unit(progress))
        else 0f
    }

    @kotlin.jvm.JvmStatic
    fun entranceAlpha(progress: Float): Float {
        return PrivacySlideGeometry.unit((PrivacySlideGeometry.unit(progress) - .15f) / .85f)
    }

    @kotlin.jvm.JvmStatic
    fun dotScale(diameter: Float, density: Float): Float {
        if (
            !java.lang.Float.isFinite(diameter) ||
                !java.lang.Float.isFinite(density) ||
                diameter <= 0 ||
                density <= 0
        )
            return 1f
        return min(1.5f, (diameter + 2 * density) / diameter)
    }

    @kotlin.jvm.JvmStatic
    fun dotShift(iconsRight: Float, dotLeft: Float, gap: Float, alpha: Float): Float {
        if (
            !java.lang.Float.isFinite(iconsRight) ||
                !java.lang.Float.isFinite(dotLeft) ||
                !java.lang.Float.isFinite(gap) ||
                gap < 0
        )
            return 0f
        return min(-gap, dotLeft - gap - iconsRight) * PrivacySlideGeometry.unit(alpha)
    }
}
