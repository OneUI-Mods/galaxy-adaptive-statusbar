package dev.ryuya.galaxybar;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContrastTest {
  @Test
  public void batteryFillIsPartOfPercentageBackground() {
    int filled = Contrast.over(0xffffffff, 0xff000000, 255);
    int unfilled = Contrast.over(0xffffffff, 0xff000000, 99);
    assertEquals(0xff000000, filled);
    assertEquals(0xff9c9c9c, unfilled);
    assertEquals(
        0xffffffff, Contrast.choose(new double[] {Contrast.luminance(filled)}, 0, "mean", 0));
    assertEquals(
        0xff000000, Contrast.choose(new double[] {Contrast.luminance(unfilled)}, 0, "mean", 0));
  }

  @Test
  public void linearSrgb() {
    assertEquals(0, Contrast.luminance(0xff000000), 1e-10);
    assertEquals(1, Contrast.luminance(0xffffffff), 1e-10);
    assertEquals(.21586, Contrast.luminance(0xff808080), .00001);
  }

  @Test
  public void extremes() {
    for (String a : new String[] {"robust", "mean", "median", "vote"}) {
      assertEquals(0xffffffff, Contrast.choose(new double[] {0, 0, 0}, 0, a, .15));
      assertEquals(0xff000000, Contrast.choose(new double[] {1, 1, 1}, 0, a, .15));
    }
  }

  @Test
  public void hysteresisBand() {
    double cross = Math.sqrt(.0525) - .05;
    assertEquals(0xffffffff, Contrast.choose(new double[] {cross + .001}, 0xffffffff, "mean", .15));
    assertEquals(0xff000000, Contrast.choose(new double[] {cross - .001}, 0xff000000, "mean", .15));
    assertEquals(0xff000000, Contrast.choose(new double[] {.6}, 0xffffffff, "mean", .15));
  }

  @Test
  public void mixedBackgroundUsesWorstContrast() {
    assertEquals(
        0xff000000,
        Contrast.choose(new double[] {.02, .02, .4, .4, .4, .4, .4, .4, .4, .4}, 0, "mean", 0));
    assertEquals(
        0xffffffff,
        Contrast.choose(new double[] {.02, .02, .4, .4, .4, .4, .4, .4, .4, .4}, 0, "robust", 0));
  }

  @Test(expected = IllegalArgumentException.class)
  public void emptyFailsClosed() {
    Contrast.choose(new double[0], 0, "mean", .15);
  }
}
