package dev.ryuya.galaxybar;

/** The renderer and contrast sampler share the same native-window mapping. */
final class MotionGeometry {
  record Bounds(float left, float top, float right, float bottom) {}

  static Bounds map(
      MotionFrame frame,
      float sourceWidth,
      float originX,
      float originY,
      float left,
      float top,
      float right,
      float bottom) {
    float scale = frame.width() / sourceWidth;
    return new Bounds(
        frame.x() + (left - originX) * scale,
        frame.y() + (top - originY) * scale,
        frame.x() + (right - originX) * scale,
        frame.y() + (bottom - originY) * scale);
  }
}
