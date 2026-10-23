package dev.ryuya.galaxybar;

import static org.junit.Assert.*;
import org.junit.Test;

public class BackgroundSamplesTest {
  @Test
  public void aMissingPixelRejectsTheWholeSampleRatherThanTreatingItAsBlack() {
    try {
      BackgroundSamples.read(new int[] {0xffffffff, 0x00000000, 0xff000000}, 3, 1, 0, 0, 3, 1);
      fail("Unresolved frame must not produce a contrast decision");
    } catch (BackgroundSamples.Unavailable expected) {
      assertArrayEquals(
          new double[] {1, 0},
          BackgroundSamples.read(new int[] {0xffffffff, 0xff000000}, 2, 1, 0, 0, 2, 1),
          0);
    }
  }

  @Test
  public void onlyTheRequestedRegionHasToBeOpaque() {
    assertArrayEquals(
        new double[] {1},
        BackgroundSamples.read(new int[] {0x00000000, 0xffffffff}, 2, 1, 1, 0, 2, 1),
        0);
  }

  @Test(expected = BackgroundSamples.Unavailable.class)
  public void emptyIntersectionIsTemporary() {
    BackgroundSamples.read(new int[] {0xffffffff}, 1, 1, 0, 0, 0, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void aMalformedBufferRemainsARealError() {
    BackgroundSamples.read(new int[] {0xffffffff}, 2, 1, 0, 0, 1, 1);
  }

  @Test(expected = BackgroundSamples.Unavailable.class)
  public void partiallyTransparentPixelsAreNotLuminanceSamples() {
    BackgroundSamples.read(new int[] {0xf9ffffff}, 1, 1, 0, 0, 1, 1);
  }

  @Test
  public void theExistingOpacityThresholdIsPreserved() {
    assertArrayEquals(
        new double[] {1}, BackgroundSamples.read(new int[] {0xfaffffff}, 1, 1, 0, 0, 1, 1), 0);
  }
}
