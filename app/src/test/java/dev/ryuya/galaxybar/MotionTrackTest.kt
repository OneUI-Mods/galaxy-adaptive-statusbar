package dev.ryuya.galaxybar

import org.junit.Assert.*
import org.junit.Test

open class MotionTrackTest {
    private fun frame(
        session: Long,
        time: Long,
        task: Int,
        opening: Boolean,
        width: Float,
    ): MotionFrame? {
        return MotionFrame(
            session,
            time,
            opening,
            task,
            true,
            false,
            0f,
            0f,
            width,
            2400f,
            1f,
            -1f,
            30f,
            false,
        )
    }

    private fun end(session: Long, time: Long): MotionFrame? {
        return MotionFrame(session, time, false, 0, false, false, 0f, 0f, 0f, 0f, 0f, -1f, 0f, true)
    }

    private fun send(track: MotionTrack, f: MotionFrame): MotionTrack.Change? {
        return track.accept(f, 1440, 3120, f.time)
    }

    @Test
    open fun reversalAndSpringBounceKeepTheSameDrawing() {
        val track = MotionTrack()
        assertEquals(MotionTrack.Change.START, send(track, frame(10, 100, 42, true, 1000f)!!))
        assertEquals(MotionTrack.Change.CONTINUE, send(track, frame(10, 116, 42, false, 900f)!!))
        assertEquals(MotionTrack.Change.CONTINUE, send(track, frame(10, 132, 42, true, 930f)!!))
        assertEquals(MotionTrack.Change.CONTINUE, send(track, frame(10, 148, 42, true, 850f)!!))
        assertEquals((850).toDouble(), (track.current()!!.width).toDouble(), (0).toDouble())
    }

    @Test
    open fun cancelThenReplacementInheritsTheHandoffWithoutRestoringTheBar() {
        val track = MotionTrack()
        send(track, frame(10, 100, 42, true, 1000f)!!)
        assertEquals(MotionTrack.Change.END, send(track, end(10, 110)!!))
        assertFalse(track.endDue(130))
        assertEquals(MotionTrack.Change.CONTINUE, send(track, frame(11, 132, 42, false, 980f)!!))
        assertFalse(track.endDue(200))
        assertEquals(MotionTrack.Change.IGNORE, send(track, end(10, 140)!!))
        assertFalse(track.endDue(250))
    }

    @Test
    open fun completionWithoutReplacementRestoresAfterTheShortGracePeriod() {
        val track = MotionTrack()
        send(track, frame(10, 100, 42, true, 1440f)!!)
        send(track, end(10, 110)!!)
        assertFalse(track.endDue(157))
        assertTrue(track.endDue(158))
        track.clear()
        assertNull(track.current())
        assertEquals(MotionTrack.Change.IGNORE, send(track, frame(10, 170, 42, true, 1440f)!!))
    }

    @Test
    open fun differentTaskOrDiscontinuousGeometryGetsAFreshDrawing() {
        val track = MotionTrack()
        send(track, frame(10, 100, 42, true, 1000f)!!)
        assertEquals(MotionTrack.Change.START, send(track, frame(11, 116, 43, false, 1000f)!!))
        assertEquals(MotionTrack.Change.START, send(track, frame(12, 132, 43, true, 200f)!!))
        assertEquals(MotionTrack.Change.START, send(track, frame(13, 500, 43, true, 200f)!!))
    }

    @Test
    open fun staleFramesAndOldEndsCannotEraseAReplacement() {
        val track = MotionTrack()
        send(track, frame(11, 150, 42, true, 1100f)!!)
        assertEquals(MotionTrack.Change.IGNORE, send(track, frame(11, 140, 42, true, 1000f)!!))
        assertEquals(MotionTrack.Change.IGNORE, send(track, frame(10, 160, 42, false, 900f)!!))
        assertEquals(MotionTrack.Change.IGNORE, send(track, end(10, 170)!!))
        assertEquals((1100).toDouble(), (track.current()!!.width).toDouble(), (0).toDouble())
    }

    @Test
    open fun pendingFramesKeepNewSessionsAndTerminalOrdering() {
        val newer = frame(11, 150, 42, true, 1100f)
        assertEquals(newer, MotionTrack.newer(newer, end(10, 160)!!))
        val end = end(11, 160)
        assertEquals(end, MotionTrack.newer(end, frame(11, 165, 42, true, 1000f)!!))
        assertEquals(newer, MotionTrack.newer(null, newer!!))
    }

    @Test
    open fun heldGestureStaysLiveUntilExplicitCancellation() {
        val track = MotionTrack()
        send(track, frame(10, 100, 42, false, 900f)!!)
        var now: Int = 175
        while (now < 2000) {
            assertEquals(
                MotionTrack.Change.CONTINUE,
                send(track, frame(10, now.toLong(), 42, false, 900f)!!),
            )
            assertFalse(track.endDue(now.toLong()))
            now += 75
        }
        track.clear()
        assertEquals(MotionTrack.Change.IGNORE, send(track, frame(10, 2010, 42, false, 900f)!!))
    }
}
