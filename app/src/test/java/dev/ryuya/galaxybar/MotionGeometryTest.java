package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

/** Checks the coordinates actually used to choose each moving character's contrast. */
public class MotionGeometryTest {
  private MotionFrame frame(float x, float y, float width) {
    return new MotionFrame(1, 100, true, 42, true, true, x, y, width, 2000, 1, -1, 20, false);
  }

  @Test
  public void eachGlyphUsesItsPositionInsideTheScaledWindow() {
    MotionGeometry.Bounds a = MotionGeometry.map(frame(100, 200, 720), 1440, 0, 0, 50, 20, 80, 60);
    assertEquals(new MotionGeometry.Bounds(125, 210, 140, 230), a);
    MotionGeometry.Bounds b = MotionGeometry.map(frame(100, 200, 720), 1440, 0, 0, 90, 20, 120, 60);
    assertEquals(new MotionGeometry.Bounds(145, 210, 160, 230), b);
  }

  @Test
  public void rootOffsetsAreRemovedBeforeTheWindowTransform() {
    MotionGeometry.Bounds a =
        MotionGeometry.map(frame(100, 200, 720), 1440, 10, 30, 60, 50, 90, 90);
    assertEquals(new MotionGeometry.Bounds(125, 210, 140, 230), a);
  }

  @Test
  public void reverseAndOvershootUseOnlyTheCurrentNativeGeometry() {
    float[] widths = {1440, 1200, 900, 1100, 1500, 1000, 1440};
    for (float width : widths) {
      MotionGeometry.Bounds a =
          MotionGeometry.map(frame(0, 0, width), 1440, 0, 0, 720, 0, 800, 100);
      assertEquals(width / 2, a.left(), .0001f);
    }
  }

  @Test
  public void contrastCanDifferPerCharacterAndChangeDuringMotion() {
    MotionGeometry.Bounds first =
        MotionGeometry.map(frame(100, 200, 720), 1440, 0, 0, 50, 20, 80, 60);
    MotionGeometry.Bounds second =
        MotionGeometry.map(frame(100, 200, 720), 1440, 0, 0, 90, 20, 120, 60);
    assertNotEquals(contrast(first), contrast(second));
    MotionGeometry.Bounds moved =
        MotionGeometry.map(frame(140, 200, 720), 1440, 0, 0, 50, 20, 80, 60);
    assertNotEquals(contrast(first), contrast(moved));
  }

  private int contrast(MotionGeometry.Bounds glyph) {
    double background = (glyph.left() + glyph.right()) / 2 < 142 ? 0 : 1;
    return Contrast.choose(new double[] {background}, 0, "robust", .15);
  }
}
