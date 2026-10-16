package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

public class MotionFrameTest {
  private MotionFrame frame(long time, float x, float width) {
    return new MotionFrame(10, time, true, 42, false, x, 100, width, 2000, 1, 200, 40, false);
  }

  @Test
  public void acceptsCurrentFullscreenAndSpringOvershoot() {
    assertTrue(frame(1000, 0, 1440).valid(1440, 3120, 1010));
    assertTrue(frame(1000, -50, 1540).valid(1440, 3120, 1010));
  }

  @Test
  public void rejectsStaleOrFutureFramesBeforeTheyCanHideTheRealBar() {
    assertFalse(frame(1000, 0, 1440).valid(1440, 3120, 1300));
    assertFalse(frame(1500, 0, 1440).valid(1440, 3120, 1000));
  }

  @Test
  public void rejectsNonFiniteAndOutOfBoundsGeometry() {
    assertFalse(frame(1000, Float.NaN, 1000).valid(1440, 3120, 1010));
    assertFalse(frame(1000, 0, Float.POSITIVE_INFINITY).valid(1440, 3120, 1010));
    assertFalse(frame(1000, 0, 0).valid(1440, 3120, 1010));
    assertFalse(frame(1000, 4000, 1000).valid(1440, 3120, 1010));
  }

  @Test
  public void terminationDoesNotNeedWindowGeometry() {
    assertTrue(
        new MotionFrame(10, 1000, false, 0, false, 0, 0, 0, 0, 0, -1, 0, true)
            .valid(1440, 3120, 1010));
  }
}
