package dev.ryuya.galaxybar

import kotlin.math.abs
import kotlin.math.exp

/** A critically damped follower; changing the target never resets position or velocity. */
internal class IconMotionSpring {
    var position = 0f
        private set

    private var velocity = 0f

    fun step(target: Float, seconds: Float): Float {
        if (!target.isFinite() || !seconds.isFinite() || seconds <= 0f) return position
        val elapsed = seconds.coerceAtMost(.05f)
        val frequency = 48f
        val displacement = position - target
        val impulse = (velocity + frequency * displacement) * elapsed
        val decay = exp(-frequency * elapsed)
        position = target + (displacement + impulse) * decay
        velocity = (velocity - frequency * impulse) * decay
        if (isSettled(target)) {
            position = target
            velocity = 0f
        }
        return position
    }

    fun isSettled(target: Float) = abs(position - target) < .05f && abs(velocity) < .5f
}
