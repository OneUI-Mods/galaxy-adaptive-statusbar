package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Short color-only transitions; repeated decisions never postpone completion. */
class ColorFade(initial: Int) {
    private var from: Int
    private var target: Int
    private var start: Long = 0
    private var duration: Int = ColorFade.DEFAULT_DURATION_MS

    init {
        target = initial
        from = target
    }

    fun setDuration(milliseconds: Int, now: Long) {
        val next = ColorFade.clampDuration(milliseconds)
        if (duration == next) return
        from = color(now)
        duration = next
        start = now
    }

    fun setTarget(next: Int, now: Long) {
        if (next == target) return
        from = color(now)
        target = next
        start = now
    }

    fun finish() {
        from = target
    }

    fun running(now: Long): Boolean {
        return from != target && now - start < duration
    }

    fun color(now: Long): Int {
        if (!running(now)) return target
        var fraction = max(0f, (now - start) / duration.toFloat())
        // Ease out: establish the new contrast quickly, then settle gently.
        fraction = 1 - (1 - fraction) * (1 - fraction)
        var result: Int = 0
        var shift: Int = 0
        while (shift <= 24) {
            val a: Int = (from ushr shift) and 255
            val b: Int = (target ushr shift) and 255
            result = result or (Math.round(a + (b - a) * fraction) shl shift)
            shift += 8
        }
        return result
    }

    companion object {
        const val DEFAULT_DURATION_MS: Int = 80
        const val MAX_DURATION_MS: Int = 500
        const val STEP_MS: Int = 10

        @JvmStatic
        fun clampDuration(milliseconds: Int): Int {
            val clamped = max(0, min(ColorFade.MAX_DURATION_MS, milliseconds))
            return Math.round(clamped / ColorFade.STEP_MS.toFloat()) * ColorFade.STEP_MS
        }
    }
}
