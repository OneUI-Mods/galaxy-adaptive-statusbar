package dev.ryuya.galaxybar

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Independent screen/card fades; only native geometry decides when ownership changes. */
class MotionTiming @JvmOverloads constructor(durationScale: Float = 1f) {
    private val durationScale: Float
    private val fixed: MotionTiming.Fade = MotionTiming.Fade(1f)
    private val moving: MotionTiming.Fade = MotionTiming.Fade(0f)
    private val visibility: MotionTiming.Fade = MotionTiming.Fade(1f)
    private var handedOff: Boolean = false
    private var openingLeg: Boolean = false
    private var returningFromOverview: Boolean = false
    private var previousWidth: Float = Float.NaN

    @JvmRecord
    data class Opacity(val fixed: Float, val moving: Float) {
        override fun toString(): String = "Opacity[fixed=$fixed, moving=$moving]"
    }

    init {
        this.durationScale = max(0f, durationScale)
    }

    @JvmOverloads
    fun update(
        opening: Boolean,
        widthRatio: Float,
        remainingMs: Float,
        now: Long,
        overview: Boolean = false,
        interactive: Boolean = false,
    ): MotionTiming.Opacity {
        val wasHandedOff: Boolean = handedOff
        val cardMode: Boolean = overview || interactive
        if (cardMode) returningFromOverview = true
        if (
            !handedOff &&
                (overview ||
                    !opening ||
                    interactive ||
                    widthRatio >= .8f ||
                    (widthRatio >= .45f && remainingMs >= 0 && remainingMs <= MotionTiming.LEAD_MS))
        ) {
            handedOff = true
            openingLeg = opening
            if (!opening || overview || interactive) {
                // A closing window begins at the existing bar's position: transfer without a dip.
                fixed.snap(0f)
                moving.snap(1f)
                if (cardMode) visibility.snap(MotionTiming.cardVisibility(widthRatio))
            } else {
                fixed.to(0f, now, ms(90), 0, true)
                moving.to(1f, now, ms(140), ms(35), false)
            }
        }
        if (handedOff) {
            var direction: Boolean = opening
            if (interactive && !java.lang.Float.isNaN(previousWidth)) {
                val delta: Float = widthRatio - previousWidth
                direction = if (abs(delta) >= .003f) delta > 0 else openingLeg
            }
            val changedDirection: Boolean = direction != openingLeg
            openingLeg = direction
            if (cardMode) {
                fixed.to(0f, now, ms(90), 0, true)
                moving.to(1f, now, ms(120), 0, false)
            } else if (openingLeg) {
                fixed.to(0f, now, ms(90), 0, true)
                // Do not restart or remove the initial stagger on every native frame.
                if (wasHandedOff && changedDirection) moving.to(1f, now, ms(140), 0, false)
            } else {
                // The card bar disappears before it becomes tiny. The home bar arrives later;
                // these independent envelopes deliberately avoid a pair of equally bright clocks.
                moving.to(MotionTiming.smooth((widthRatio - .58f) / .2f), now, ms(80), 0, false)
                fixed.to(MotionTiming.smooth((.60f - widthRatio) / .2f), now, ms(110), 0, false)
            }
            // A reduced window must become completely clear, including while the finger
            // is held still. Geometry selects the target; time completes the fade without
            // requiring more movement. Expansion/reversal starts at the displayed alpha.
            val targetVisibility: Float =
                if (cardMode || returningFromOverview || !openingLeg)
                    MotionTiming.cardVisibility(widthRatio)
                else 1f
            visibility.to(targetVisibility, now, ms(140), 0, false)
        }
        previousWidth = widthRatio
        return opacity(now)
    }

    fun opacity(now: Long): MotionTiming.Opacity {
        return MotionTiming.Opacity(fixed.value(now), moving.value(now) * visibility.value(now))
    }

    fun running(now: Long): Boolean {
        return fixed.running(now) || moving.running(now) || visibility.running(now)
    }

    fun openingLeg(): Boolean {
        return openingLeg
    }

    fun handedOff(): Boolean {
        return handedOff
    }

    private fun ms(duration: Int): Int {
        return Math.round(duration * durationScale)
    }

    private class Fade(value: Float) {
        private var from: Float = 0f
        private var target: Float = 0f
        private var start: Long = 0
        private var duration: Int = 0
        private var easeOut: Boolean = false

        init {
            snap(value)
        }

        fun snap(value: Float) {
            target = value
            from = target
            duration = 0
        }

        fun to(value: Float, now: Long, duration: Int, delay: Int, easeOut: Boolean) {
            if (value == target) return
            from = value(now)
            target = value
            start = now + delay
            this.duration = duration
            this.easeOut = easeOut
        }

        fun running(now: Long): Boolean {
            return from != target && now < start + duration
        }

        fun value(now: Long): Float {
            if (!running(now)) return target
            if (now <= start) return from
            val x = min(1f, (now - start) / duration.toFloat())
            val eased: Float =
                if (easeOut) 1 - (1 - x) * (1 - x) * (1 - x) else MotionTiming.smooth(x)
            return from + (target - from) * eased
        }
    }

    companion object {
        const val LEAD_MS: Int = 250

        @JvmStatic
        private fun cardVisibility(widthRatio: Float): Float {
            return MotionTiming.smooth((widthRatio - .90f) / .09f)
        }

        @JvmStatic
        private fun smooth(v: Float): Float {
            val x = max(0f, min(1f, v))
            return x * x * (3 - 2 * x)
        }
    }
}
