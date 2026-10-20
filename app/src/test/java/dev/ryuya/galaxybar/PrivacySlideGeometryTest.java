package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class PrivacySlideGeometryTest {
  @Test
  public void fullChipLeavesMeasuredGap() {
    float shift = PrivacySlideGeometry.shift(1397, 1287, 1407, 12, 1, 1);
    assertEquals(-122, shift, .01f);
    assertEquals(12, 1287 - (1397 + shift), .01f);
  }

  @Test
  public void appearanceStartsWithoutJumpAndMovesMonotonically() {
    float previous = 0;
    for (int i = 0; i <= 100; i++) {
      float current = PrivacySlideGeometry.shift(1397, 1287, 1407, 12, i / 100f, 1);
      assertTrue(current <= previous + .001f);
      previous = current;
    }
    assertEquals(0, PrivacySlideGeometry.shift(1397, 1287, 1407, 12, 0, 1), 0);
  }

  @Test
  public void alphaAndNativeBoundsReturnIconsAsChipDisappears() {
    assertEquals(-61, PrivacySlideGeometry.shift(1397, 1287, 1407, 12, 1, .5f), .01f);
    assertEquals(0, PrivacySlideGeometry.shift(1397, 1287, 1407, 12, 1, 0), 0);
    assertEquals(0, PrivacySlideGeometry.shift(1397, 1410, 1430, 12, 1, 1), 0);
  }

  @Test
  public void invalidGeometryCannotWriteNaNTransforms() {
    assertEquals(0, PrivacySlideGeometry.shift(1397, Float.NaN, 1407, 12, 1, 1), 0);
    assertEquals(0, PrivacySlideGeometry.shift(1397, 1407, 1287, 12, 1, 1), 0);
    assertEquals(0, PrivacySlideGeometry.shift(1397, 1287, 1407, Float.NaN, 1, 1), 0);
    assertEquals(0, PrivacySlideGeometry.shift(1397, 1287, 1407, 12, 1, Float.NaN), 0);
  }

  @Test
  public void crowdedStatusBarFallsBackToNativeLayout() {
    assertTrue(PrivacySlideGeometry.fits(999, -122, 700));
    assertFalse(PrivacySlideGeometry.fits(999, -122, 900));
    assertFalse(PrivacySlideGeometry.fits(999, -122, Float.NaN));
  }

  @Test
  public void everyOtherSamsungHideReasonIsPreserved() {
    Object[] reasons = new Object[8];
    Arrays.fill(reasons, false);
    reasons[1] = true;
    assertFalse(PrivacySlideGeometry.otherHide(reasons));
    for (int i = 0; i < 8; i++)
      if (i != 1) {
        reasons[i] = true;
        assertTrue(PrivacySlideGeometry.otherHide(reasons));
        reasons[i] = false;
      }
  }

  @Test
  public void malformedNativeStateDeclinesTheOverride() {
    assertTrue(PrivacySlideGeometry.otherHide(null));
    assertTrue(PrivacySlideGeometry.otherHide(new Object[7]));
    assertTrue(PrivacySlideGeometry.otherHide(new Object[8]));
  }
}
