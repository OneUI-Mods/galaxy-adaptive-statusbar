package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.max

/* Keeps one visible handoff across native animator replacements and rejects delayed callbacks. */
class MotionTrack {
    enum class Change {
        IGNORE,
        START,
        CONTINUE,
        END,
    }

    private var current: MotionFrame? = null
    private var sealedSession: Long = 0
    private var endAt: Long = -1

    fun accept(frame: MotionFrame, width: Int, height: Int, now: Long): MotionTrack.Change? {
        if (!frame.valid(width, height, now) || frame.session <= sealedSession)
            return MotionTrack.Change.IGNORE
        if (
            current != null &&
                (frame.session < current!!.session ||
                    (frame.session == current!!.session && frame.time < current!!.time))
        ) {
            return MotionTrack.Change.IGNORE
        }
        if (frame.end) {
            sealedSession = frame.session
            // Cancel/end is commonly followed by a replacement animator on the next frame.
            // Keep the current drawing briefly so that the real bar never flashes in between.
            if (current != null) endAt = now + MotionTrack.END_GRACE_MS
            return MotionTrack.Change.END
        }
        var continuous: Boolean = current != null && frame.session == current!!.session
        if (!continuous && current != null) {
            continuous =
                frame.taskId == current!!.taskId &&
                    frame.time >= current!!.time &&
                    frame.time - current!!.time <= 180 &&
                    Math.abs(frame.x - current!!.x) < width * .12f &&
                    Math.abs(frame.y - current!!.y) < height * .12f &&
                    Math.abs(frame.width - current!!.width) < width * .12f &&
                    Math.abs(frame.height - current!!.height) < height * .12f
        }
        current = frame
        endAt = -1
        return if (continuous) MotionTrack.Change.CONTINUE else MotionTrack.Change.START
    }

    fun current(): MotionFrame? {
        return current
    }

    fun endDue(now: Long): Boolean {
        return endAt >= 0 && now >= endAt
    }

    fun clear() {
        if (current != null) sealedSession = Math.max(sealedSession, current!!.session)
        current = null
        endAt = -1
    }

    companion object {
        const val END_GRACE_MS: Int = 48

        @JvmStatic
        fun newer(a: MotionFrame?, b: MotionFrame): MotionFrame? {
            if (a == null || b.session > a.session) return b
            if (b.session < a.session || b.time < a.time) return a
            if (a.end && b.session == a.session) return a
            return b
        }
    }
}
