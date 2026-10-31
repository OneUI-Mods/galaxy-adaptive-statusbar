package dev.ryuya.galaxybar

import android.view.Choreographer
import android.view.View
import dev.ryuya.galaxybar.hook.Hooks
import kotlin.math.abs

/** One continuous motion owns the large chip, its persistent dot, and the final return. */
class PrivacyIconSpace
@JvmOverloads
constructor(
    @JvmField val group: View,
    window: View?,
    private val onIdle: () -> Unit = {},
) : Choreographer.FrameCallback {
    private val baseline = group.translationX
    private val clipping = IconClipping.acquire(group, window)
    private val frames = Choreographer.getInstance()
    private val spring = IconMotionSpring()
    private val location = IntArray(2)
    private var applied = baseline
    private var chipShift = 0f
    private var dotShift = 0f
    private var lastFrame = 0L
    private var scheduled = false
    private var closed = false
    private var frameCount = 0
    private var maxStep = 0f

    fun baselineLeft(): Float {
        group.getLocationOnScreen(location)
        return location[0] - group.translationX + baseline
    }

    fun chip(shift: Float) {
        chipShift = validShift(shift)
        schedule()
    }

    fun dot(shift: Float) {
        dotShift = validShift(shift)
        schedule()
    }

    private fun validShift(value: Float) = if (value.isFinite()) value.coerceAtMost(0f) else 0f

    private fun target() = PrivacySlideGeometry.combinedShift(chipShift, dotShift)

    private fun schedule() {
        if (!closed && !scheduled && !spring.isSettled(target())) {
            scheduled = true
            lastFrame = 0L
            frames.postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (closed) return
        try {
            if (!group.isAttachedToWindow) {
                close()
                notifyIdle()
                return
            }
            val elapsed = if (lastFrame == 0L) 0f else (frameTimeNanos - lastFrame) / 1e9f
            lastFrame = frameTimeNanos
            val next = baseline + spring.step(target(), elapsed)
            maxStep = maxOf(maxStep, abs(next - applied))
            applied = next
            group.translationX = applied
            frameCount++
            if (!spring.isSettled(target())) {
                scheduled = true
                frames.postFrameCallback(this)
            } else if (idle()) {
                // Do not release clipping or restore the baseline while the return is moving.
                notifyIdle()
            }
        } catch (error: Throwable) {
            close()
            notifyIdle()
            Hooks.log("GalaxyBar: privacy icon motion stopped: $error")
        }
    }

    private fun notifyIdle() {
        try {
            onIdle()
        } catch (error: Throwable) {
            Hooks.log("GalaxyBar: privacy icon release stopped: $error")
        }
    }

    fun idle() = closed || chipShift == 0f && dotShift == 0f && spring.isSettled(0f)

    fun isClosed() = closed

    fun status() =
        "x=${applied - baseline}; target=${target()}; frames=$frameCount; maxStep=$maxStep"

    fun close() {
        if (closed) return
        closed = true
        frames.removeFrameCallback(this)
        scheduled = false
        if (abs(group.translationX - applied) < .01f) group.translationX = baseline
        clipping.close()
    }
}
