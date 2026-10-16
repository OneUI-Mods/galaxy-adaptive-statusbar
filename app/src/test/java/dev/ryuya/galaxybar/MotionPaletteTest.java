package dev.ryuya.galaxybar;

import static dev.ryuya.galaxybar.MotionPalette.Source.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class MotionPaletteTest {
  @Test
  public void homeColorsDoNotSampleTheTinyOpeningAppBeforeHandoff() {
    assertEquals(FROZEN, MotionPalette.choose(false, true, new MotionTiming.Opacity(1, 0)));
    assertEquals(FROZEN, MotionPalette.choose(true, true, new MotionTiming.Opacity(.5f, .02f)));
    assertEquals(WINDOW, MotionPalette.choose(true, true, new MotionTiming.Opacity(.02f, .15f)));
  }

  @Test
  public void closingSamplesHomeWhenTheMovingBarHasFaded() {
    assertEquals(WINDOW, MotionPalette.choose(true, false, new MotionTiming.Opacity(0, 1)));
    assertEquals(SCREEN, MotionPalette.choose(true, false, new MotionTiming.Opacity(.1f, .1f)));
    assertEquals(SCREEN, MotionPalette.choose(true, false, new MotionTiming.Opacity(1, 0)));
  }

  @Test
  public void dimOverviewAndReturnKeepSamplingTheCard() {
    assertEquals(WINDOW, MotionPalette.choose(true, false, new MotionTiming.Opacity(0, .35f)));
    assertEquals(WINDOW, MotionPalette.choose(true, true, new MotionTiming.Opacity(0, .35f)));
  }

  @Test
  public void openingHasOnePaletteTransferAndClosingEndsAtTheHomePalette() {
    MotionTiming timing = new MotionTiming();
    timing.update(true, .85f, 200, 100);
    MotionPalette.Source source = FROZEN;
    int switches = 0;
    for (int now = 100; now <= 400; now++) {
      MotionPalette.Source next = MotionPalette.choose(true, true, timing.opacity(now));
      if (next != source) switches++;
      source = next;
    }
    assertEquals(1, switches);
    assertEquals(WINDOW, source);
    timing.update(false, .1f, -1, 400);
    assertEquals(SCREEN, MotionPalette.choose(true, false, timing.opacity(600)));
  }
}
