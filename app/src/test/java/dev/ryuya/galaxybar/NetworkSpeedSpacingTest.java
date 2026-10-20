package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

public class NetworkSpeedSpacingTest {
  @Test
  public void standardKeepsNativeNegativeSpacing() {
    assertEquals(-5.85f, NetworkSpeedSpacing.extra(-5.85f, 2.925f, 0), .001f);
  }

  @Test
  public void adjustmentUsesDensityAndRejectsOverlapProneNegativeValues() {
    assertEquals(5.85f, NetworkSpeedSpacing.extra(-5.85f, 2.925f, 4), .001f);
    assertEquals(-5.85f, NetworkSpeedSpacing.extra(-5.85f, 2.925f, -2), .001f);
  }

  @Test
  public void savedValuesCannotExceedSupportedRange() {
    assertEquals(NetworkSpeedSpacing.MIN_DP, NetworkSpeedSpacing.clamp(Integer.MIN_VALUE));
    assertEquals(NetworkSpeedSpacing.MAX_DP, NetworkSpeedSpacing.clamp(Integer.MAX_VALUE));
  }
}
