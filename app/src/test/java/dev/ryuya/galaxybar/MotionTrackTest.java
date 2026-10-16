package dev.ryuya.galaxybar;

import static dev.ryuya.galaxybar.MotionTrack.Change.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class MotionTrackTest {
  private MotionFrame frame(long session, long time, int task, boolean opening, float width) {
    return new MotionFrame(
        session, time, opening, task, true, false, 0, 0, width, 2400, 1, -1, 30, false);
  }

  private MotionFrame end(long session, long time) {
    return new MotionFrame(session, time, false, 0, false, false, 0, 0, 0, 0, 0, -1, 0, true);
  }

  private MotionTrack.Change send(MotionTrack track, MotionFrame f) {
    return track.accept(f, 1440, 3120, f.time());
  }

  @Test
  public void reversalAndSpringBounceKeepTheSameDrawing() {
    MotionTrack track = new MotionTrack();
    assertEquals(START, send(track, frame(10, 100, 42, true, 1000)));
    assertEquals(CONTINUE, send(track, frame(10, 116, 42, false, 900)));
    assertEquals(CONTINUE, send(track, frame(10, 132, 42, true, 930)));
    assertEquals(CONTINUE, send(track, frame(10, 148, 42, true, 850)));
    assertEquals(850, track.current().width(), 0);
  }

  @Test
  public void cancelThenReplacementInheritsTheHandoffWithoutRestoringTheBar() {
    MotionTrack track = new MotionTrack();
    send(track, frame(10, 100, 42, true, 1000));
    assertEquals(END, send(track, end(10, 110)));
    assertFalse(track.endDue(130));
    assertEquals(CONTINUE, send(track, frame(11, 132, 42, false, 980)));
    assertFalse(track.endDue(200));
    assertEquals(IGNORE, send(track, end(10, 140)));
    assertFalse(track.endDue(250));
  }

  @Test
  public void completionWithoutReplacementRestoresAfterTheShortGracePeriod() {
    MotionTrack track = new MotionTrack();
    send(track, frame(10, 100, 42, true, 1440));
    send(track, end(10, 110));
    assertFalse(track.endDue(157));
    assertTrue(track.endDue(158));
    track.clear();
    assertNull(track.current());
    assertEquals(IGNORE, send(track, frame(10, 170, 42, true, 1440)));
  }

  @Test
  public void differentTaskOrDiscontinuousGeometryGetsAFreshDrawing() {
    MotionTrack track = new MotionTrack();
    send(track, frame(10, 100, 42, true, 1000));
    assertEquals(START, send(track, frame(11, 116, 43, false, 1000)));
    assertEquals(START, send(track, frame(12, 132, 43, true, 200)));
    assertEquals(START, send(track, frame(13, 500, 43, true, 200)));
  }

  @Test
  public void staleFramesAndOldEndsCannotEraseAReplacement() {
    MotionTrack track = new MotionTrack();
    send(track, frame(11, 150, 42, true, 1100));
    assertEquals(IGNORE, send(track, frame(11, 140, 42, true, 1000)));
    assertEquals(IGNORE, send(track, frame(10, 160, 42, false, 900)));
    assertEquals(IGNORE, send(track, end(10, 170)));
    assertEquals(1100, track.current().width(), 0);
  }

  @Test
  public void pendingFramesKeepNewSessionsAndTerminalOrdering() {
    MotionFrame newer = frame(11, 150, 42, true, 1100);
    assertEquals(newer, MotionTrack.newer(newer, end(10, 160)));
    MotionFrame end = end(11, 160);
    assertEquals(end, MotionTrack.newer(end, frame(11, 165, 42, true, 1000)));
    assertEquals(newer, MotionTrack.newer(null, newer));
  }

  @Test
  public void heldGestureStaysLiveUntilExplicitCancellation() {
    MotionTrack track = new MotionTrack();
    send(track, frame(10, 100, 42, false, 900));
    for (int now = 175; now < 2000; now += 75) {
      assertEquals(CONTINUE, send(track, frame(10, now, 42, false, 900)));
      assertFalse(track.endDue(now));
    }
    track.clear();
    assertEquals(IGNORE, send(track, frame(10, 2010, 42, false, 900)));
  }
}
