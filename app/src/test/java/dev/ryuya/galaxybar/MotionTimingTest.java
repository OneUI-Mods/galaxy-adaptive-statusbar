package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

public class MotionTimingTest {
  @Test
  public void normalOpeningHandoffBeginsInTheLastQuarterSecond() {
    MotionTiming t = new MotionTiming();
    assertEquals(1, t.update(true, .6f, 300, 100).fixed(), 0);
    assertFalse(t.handedOff());
    t.update(true, .75f, 280, 120);
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

  @Test
  public void reversalKeepsOpacityAtTheSameInstantAndReturnsTheHomeBar() {
    MotionTiming t = new MotionTiming();
    t.update(true, .9f, 200, 100);
    float before = t.update(true, .9f, 160, 140).moving();
    assertEquals(before, t.update(false, .9f, -1, 140).moving(), 0);
    t.update(false, .5f, -1, 180);
    MotionTiming.Opacity home = t.update(false, .2f, -1, 280);
    assertEquals(1, home.fixed(), 0);
    assertEquals(0, home.moving(), 0);
    assertEquals(0, t.update(true, .2f, -1, 290).moving(), 0);
    assertEquals(1, t.update(true, 1, -1, 400).moving(), 0);
  }

  @Test
  public void nonLinearReversalAndOvershootNeverJumpOrExtrapolateOpacity() {
    MotionTiming t = new MotionTiming();
    float previous = t.update(false, 1, -1, 100).moving();
    float[] widths = {.98f, .9f, .6f, .35f, .7f, .4f, .8f, 1.1f, .95f, .25f};
    for (int i = 0; i < widths.length; i++) {
      MotionTiming.Opacity value = t.update(i % 2 == 0, widths[i], -1, 116 + 16 * i);
      assertTrue(Math.abs(value.moving() - previous) <= 16f / 85 + .00001f);
      assertEquals(1, value.fixed() + value.moving(), .00001f);
      assertTrue(value.moving() >= 0 && value.moving() <= 1);
      previous = value.moving();
    }
  }

  @Test
  public void fastCurveDoesNotWaitUntilTheWindowHasAlreadySettled() {
    MotionTiming t = new MotionTiming();
    t.update(true, .6f, 600, 100);
    assertFalse(t.handedOff());
    t.update(true, .81f, 500, 150);
    assertTrue(t.handedOff());
    assertEquals(1, t.update(true, .95f, 400, 250).moving(), 0);
  }

  @Test
  public void closingStartsOnTheMovingWindowRatherThanFadingIntoItLate() {
    MotionTiming.Opacity value = new MotionTiming().update(false, .99f, -1, 100);
    assertEquals(0, value.fixed(), 0);
    assertEquals(1, value.moving(), 0);
  }

  @Test
  public void overviewKeepsADimmedBarOnTheCardAndReturnsWithTheApp() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100);
    t.update(false, .7f, -1, 150, true);
    MotionTiming.Opacity card = t.update(false, .55f, -1, 250, true);
    assertEquals(0, card.fixed(), 0);
    assertEquals(.35f, card.moving(), .00001f);
    MotionTiming.Opacity restored = t.update(true, 1, -1, 350, false);
    assertEquals(0, restored.fixed(), 0);
    assertEquals(1, restored.moving(), 0);
  }
}
