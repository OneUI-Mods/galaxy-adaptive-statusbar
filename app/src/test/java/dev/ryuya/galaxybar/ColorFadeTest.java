package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

public class ColorFadeTest {
  private static final int BLACK = 0xff000000, WHITE = 0xffffffff;

  @Test
  public void finishesBeforeNextTenHzCapture() {
    ColorFade fade = new ColorFade(BLACK);
    fade.setTarget(WHITE, 1000);
    assertEquals(BLACK, fade.color(1000));
    int middle = fade.color(1040);
    assertTrue((middle & 255) > 127 && (middle & 255) < 255);
    assertEquals(255, middle >>> 24);
    assertEquals(WHITE, fade.color(1080));
    assertFalse(fade.running(1080));
  }

  @Test
  public void repeatedHighRateDecisionDoesNotRestartFade() {
    ColorFade fade = new ColorFade(WHITE);
    fade.setTarget(BLACK, 1000);
    fade.setTarget(BLACK, 1033);
    fade.setTarget(BLACK, 1066);
    assertEquals(BLACK, fade.color(1080));
    assertFalse(fade.running(1080));
  }

  @Test
  public void reversalStartsFromDisplayedColorWithoutJump() {
    ColorFade fade = new ColorFade(BLACK);
    fade.setTarget(WHITE, 1000);
    int visible = fade.color(1033);
    fade.setTarget(BLACK, 1033);
    assertEquals(visible, fade.color(1033));
    assertTrue((fade.color(1050) & 255) < (visible & 255));
    assertEquals(BLACK, fade.color(1113));
  }

  @Test
  public void firstColorAndDisabledAnimationAreImmediate() {
    ColorFade fade = new ColorFade(WHITE);
    assertEquals(WHITE, fade.color(0));
    assertFalse(fade.running(0));
    fade.setTarget(BLACK, 1000);
    fade.finish();
    assertEquals(BLACK, fade.color(1001));
    assertFalse(fade.running(1001));
  }

  @Test
  public void selectedDurationIsUsedInsteadOfDefault() {
    ColorFade fade = new ColorFade(BLACK);
    fade.setDuration(500, 1000);
    fade.setTarget(WHITE, 1000);
    assertTrue(fade.running(1080));
    assertTrue(fade.running(1499));
    assertEquals(WHITE, fade.color(1500));
    assertFalse(fade.running(1500));
  }

  @Test
  public void zeroDurationImmediatelyCompletesRunningAndFutureTransitions() {
    ColorFade fade = new ColorFade(BLACK);
    fade.setTarget(WHITE, 1000);
    fade.setDuration(0, 1020);
    assertEquals(WHITE, fade.color(1020));
    assertFalse(fade.running(1020));
    fade.setTarget(BLACK, 1030);
    assertEquals(BLACK, fade.color(1030));
    assertFalse(fade.running(1030));
  }

  @Test
  public void changingDurationPreservesVisibleColorAndRepeatedSettingsDoNotRestart() {
    ColorFade fade = new ColorFade(BLACK);
    fade.setTarget(WHITE, 1000);
    int visible = fade.color(1020);
    fade.setDuration(300, 1020);
    assertEquals(visible, fade.color(1020));
    fade.setDuration(300, 1100);
    assertEquals(WHITE, fade.color(1320));
    assertFalse(fade.running(1320));
  }

  @Test
  public void durationIsBoundedAndAlignedWithSliderSteps() {
    assertEquals(0, ColorFade.clampDuration(Integer.MIN_VALUE));
    assertEquals(500, ColorFade.clampDuration(Integer.MAX_VALUE));
    assertEquals(80, ColorFade.clampDuration(80));
    assertEquals(90, ColorFade.clampDuration(85));
  }
}
