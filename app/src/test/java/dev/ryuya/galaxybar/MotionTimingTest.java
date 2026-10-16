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
    assertEquals(0, t.opacity(350).fixed(), 0);
    assertEquals(1, t.opacity(350).moving(), 0);
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
    t.update(true, 1.02f, -1, 250);
    assertEquals(1, t.opacity(350).moving(), 0);
  }

  @Test
  public void closingFollowsTheWindowThenRevealsTheHomeBar() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100);
    assertEquals(1, t.update(false, .9f, -1, 150).moving(), 0);
    t.update(false, .5f, -1, 220);
    t.update(false, .2f, -1, 300);
    assertEquals(1, t.opacity(450).fixed(), 0);
    assertEquals(0, t.opacity(450).moving(), 0);
  }

  @Test
  public void bouncingSpringDoesNotRestartItsFade() {
    MotionTiming t = new MotionTiming();
    t.update(true, .81f, -1, 100);
    float before = t.update(true, .9f, -1, 150).moving();
    assertTrue(before > 0 && before < 1);
    assertTrue(t.update(true, .79f, -1, 160).moving() > before);
    assertEquals(1, t.update(true, 1, -1, 300).moving(), 0);
  }

  @Test
  public void reversalKeepsOpacityAtTheSameInstantAndReturnsTheHomeBar() {
    MotionTiming t = new MotionTiming();
    t.update(true, .9f, 200, 100);
    float before = t.update(true, .9f, 160, 160).moving();
    assertEquals(before, t.update(false, .9f, -1, 160).moving(), 0);
    t.update(false, .5f, -1, 180);
    t.update(false, .2f, -1, 280);
    assertEquals(1, t.opacity(450).fixed(), 0);
    assertEquals(0, t.opacity(450).moving(), 0);
    assertEquals(0, t.update(true, .2f, -1, 460).moving(), 0);
    assertEquals(1, t.update(true, 1, -1, 650).moving(), 0);
  }

  @Test
  public void nonLinearReversalAndOvershootNeverJumpOrExtrapolateOpacity() {
    MotionTiming t = new MotionTiming();
    MotionTiming.Opacity previous = t.update(false, 1, -1, 100);
    float[] widths = {.98f, .9f, .6f, .35f, .7f, .4f, .8f, 1.1f, .95f, .25f};
    for (int i = 0; i < widths.length; i++) {
      MotionTiming.Opacity value = t.update(i % 2 == 0, widths[i], -1, 116 + 16 * i);
      assertTrue(Math.abs(value.moving() - previous.moving()) <= .45f);
      assertTrue(Math.abs(value.fixed() - previous.fixed()) <= .45f);
      assertTrue(value.moving() >= 0 && value.moving() <= 1);
      assertTrue(value.fixed() >= 0 && value.fixed() <= 1);
      previous = value;
    }
  }

  @Test
  public void fastCurveDoesNotWaitUntilTheWindowHasAlreadySettled() {
    MotionTiming t = new MotionTiming();
    t.update(true, .6f, 600, 100);
    assertFalse(t.handedOff());
    t.update(true, .81f, 500, 150);
    assertTrue(t.handedOff());
    assertEquals(1, t.update(true, .95f, 300, 350).moving(), 0);
  }

  @Test
  public void closingStartsOnTheMovingWindowRatherThanFadingIntoItLate() {
    MotionTiming.Opacity value = new MotionTiming().update(false, .99f, -1, 100);
    assertEquals(0, value.fixed(), 0);
    assertEquals(1, value.moving(), 0);
  }

  @Test
  public void overviewDisappearsAndReturnsWithoutFlashingTheHomeBar() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100);
    t.update(false, .55f, -1, 150, true);
    MotionTiming.Opacity card = t.opacity(350);
    assertEquals(0, card.fixed(), 0);
    assertEquals(0, card.moving(), 0);
    assertEquals(card, t.update(true, .6f, -1, 350));
    assertEquals(card, t.opacity(550));
    t.update(true, 1, -1, 550);
    assertEquals(0, t.opacity(750).fixed(), 0);
    assertEquals(1, t.opacity(750).moving(), 0);
  }

  @Test
  public void screenBarDisappearsBeforeTheMovingBarBecomesBright() {
    MotionTiming t = new MotionTiming();
    t.update(true, .85f, 200, 100);
    MotionTiming.Opacity early = t.opacity(130);
    assertTrue(early.fixed() < .35f);
    assertEquals(0, early.moving(), 0);
    MotionTiming.Opacity middle = t.opacity(180);
    assertTrue(middle.fixed() < .01f);
    assertTrue(middle.moving() > 0 && middle.moving() < .6f);
    assertTrue(middle.fixed() + middle.moving() < .6f);
  }

  @Test
  public void stationaryGeometryStillFadesAtDisplayFramesAndThenStops() {
    MotionTiming t = new MotionTiming();
    t.update(true, .85f, 200, 100);
    assertTrue(t.running(140));
    assertTrue(t.opacity(180).moving() > t.opacity(150).moving());
    assertEquals(1, t.opacity(300).moving(), 0);
    assertFalse(t.running(300));
  }

  @Test
  public void repeatedNativeFramesNeverExtendTheHandoff() {
    MotionTiming t = new MotionTiming();
    for (long now = 100; now <= 300; now += 8) t.update(true, .85f, 200, now);
    assertEquals(0, t.opacity(300).fixed(), 0);
    assertEquals(1, t.opacity(300).moving(), 0);
    assertFalse(t.running(300));
  }

  @Test
  public void samplingAtSixtyOrOneHundredTwentyHertzDoesNotChangeTheCurve() {
    MotionTiming a = new MotionTiming(), b = new MotionTiming();
    a.update(true, .85f, 200, 100);
    b.update(true, .85f, 200, 100);
    for (long now = 100; now < 210; now += 16) a.opacity(now);
    for (long now = 100; now < 210; now += 8) b.opacity(now);
    assertEquals(a.opacity(210), b.opacity(210));
    a.update(false, .65f, -1, 210);
    b.update(false, .65f, -1, 210);
    for (long now = 210; now < 270; now += 16) a.opacity(now);
    for (long now = 210; now < 270; now += 8) b.opacity(now);
    assertEquals(a.opacity(270), b.opacity(270));
  }

  @Test
  public void systemDurationScaleAppliesToBothFadesAndStagger() {
    MotionTiming normal = new MotionTiming(), slow = new MotionTiming(2);
    normal.update(true, .85f, 200, 100);
    slow.update(true, .85f, 200, 100);
    assertEquals(normal.opacity(170), slow.opacity(240));
    MotionTiming disabled = new MotionTiming(0);
    assertEquals(new MotionTiming.Opacity(0, 1), disabled.update(true, .85f, 200, 100));
    assertFalse(disabled.running(100));
  }

  @Test
  public void newOverviewCardStartsInvisibleInsteadOfFlashingBright() {
    MotionTiming t = new MotionTiming();
    MotionTiming.Opacity first = t.update(false, .6f, -1, 100, true);
    assertEquals(0, first.fixed(), 0);
    assertEquals(0, first.moving(), 0);
    assertFalse(t.running(100));
  }

  @Test
  public void heldGestureDisappearsCompletelyBeforeTheHomeDestinationIsCommitted() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100, false, true);
    t.update(false, .45f, -1, 200, false, true);
    assertEquals(0, t.opacity(400).fixed(), 0);
    assertEquals(0, t.opacity(400).moving(), 0);
    assertEquals(t.opacity(400), t.update(false, .45f, -1, 400));
    assertEquals(0, t.opacity(600).moving(), 0);
    assertTrue(t.opacity(600).fixed() > .8f);
  }

  @Test
  public void heldGestureReversesFromActualWidthWithoutAnOpacityJump() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100, false, true);
    t.update(false, .6f, -1, 150, false, true);
    MotionTiming.Opacity before = t.opacity(190);
    assertEquals(before, t.update(false, .99f, -1, 190, false, true));
    assertTrue(t.opacity(300).moving() > before.moving());
  }

  @Test
  public void modestShrinkHeldForSecondsCannotLeaveAGhostBar() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100, false, true);
    t.update(false, .89f, -1, 150, false, true);
    assertTrue(t.opacity(190).moving() > 0);
    for (long now = 166; now <= 5000; now += 16) {
      t.update(false, .89f, -1, now, false, true);
      if (now >= 290) {
        assertEquals(0, t.opacity(now).moving(), 0);
        assertEquals(0, t.opacity(now).fixed(), 0);
        assertFalse(t.running(now));
      }
    }
  }

  @Test
  public void shrinkingAndExpandingCanReverseAFadeRepeatedlyWithoutJumping() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100, false, true);
    t.update(false, .86f, -1, 150, false, true);
    MotionTiming.Opacity before = t.opacity(200);
    assertEquals(before, t.update(false, .99f, -1, 200, false, true));
    assertTrue(t.opacity(240).moving() > before.moving());
    before = t.opacity(240);
    assertEquals(before, t.update(false, .86f, -1, 240, false, true));
    assertEquals(0, t.opacity(400).moving(), 0);
    assertEquals(0, t.update(false, 1, -1, 400, false, true).moving(), 0);
    assertEquals(1, t.opacity(550).moving(), 0);
    assertEquals(0, t.opacity(550).fixed(), 0);
  }

  @Test
  public void ordinaryCloseAlsoDisappearsWhenItsWindowShrinks() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100);
    t.update(false, .86f, -1, 150);
    assertEquals(0, t.opacity(300).moving(), 0);
    assertEquals(0, t.opacity(300).fixed(), 0);
    assertFalse(t.running(300));
  }

  @Test
  public void anAlmostZeroTargetStillReachesExactlyZero() {
    MotionTiming t = new MotionTiming();
    t.update(false, 1, -1, 100, false, true);
    t.update(false, .9001f, -1, 150, false, true);
    t.update(false, .89f, -1, 300, false, true);
    assertEquals(0, t.opacity(500).moving(), 0);
    assertFalse(t.running(500));
  }
}
