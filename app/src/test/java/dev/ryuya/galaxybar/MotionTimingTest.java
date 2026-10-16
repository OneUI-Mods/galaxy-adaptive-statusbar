package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

public class MotionTimingTest {
  @Test
  public void normalOpeningHandoffBeginsInTheLastQuarterSecond() {
    MotionTiming t = new MotionTiming();
    assertEquals(1, t.update(true, .6f, 300, 100).fixed(), 0);
    assertFalse(t.handedOff());
    t.update(true, .7f, 250, 150);
    assertTrue(t.handedOff());
    assertEquals(0, t.update(true, .95f, 100, 250).fixed(), 0);
    assertEquals(1, t.update(true, .95f, 100, 250).moving(), 0);
  }

  @Test
  public void shortAnimationDoesNotDrawATinyStatusBarInsideAnIcon() {
    MotionTiming t = new MotionTiming();
    assertEquals(0, t.update(true, .1f, 100, 100).moving(), 0);
    assertFalse(t.handedOff());
    t.update(true, .5f, 60, 150);
    assertTrue(t.handedOff());
  }

  @Test
  public void homeUpSpringWithoutKnownDurationUsesRealWindowGeometry() {
    MotionTiming t = new MotionTiming();
    t.update(true, .6f, -1, 100);
    assertFalse(t.handedOff());
    t.update(true, .82f, -1, 150);
    assertTrue(t.handedOff());
    assertEquals(1, t.update(true, 1.02f, -1, 250).moving(), 0);
  }

  @Test
  public void closingFollowsTheWindowThenRevealsTheHomeBar() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100);
    assertEquals(1, t.update(false, .9f, -1, 200).moving(), 0);
    MotionTiming.Opacity end = t.update(false, .2f, -1, 300);
    assertEquals(1, end.fixed(), 0);
    assertEquals(0, end.moving(), 0);
  }

  @Test
  public void bouncingSpringDoesNotRestartItsFade() {
    MotionTiming t = new MotionTiming();
    t.update(true, .81f, -1, 100);
    float before = t.update(true, .9f, -1, 140).moving();
    assertTrue(before > 0 && before < 1);
    assertTrue(t.update(true, .79f, -1, 150).moving() > before);
    assertEquals(1, t.update(true, 1, -1, 200).moving(), 0);
  }
}
