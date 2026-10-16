package dev.ryuya.galaxybar;

/** The departing screen keeps its colors until its bar has faded; new surfaces get new palettes. */
final class MotionPalette {
  enum Source {
    FROZEN,
    WINDOW,
    SCREEN
  }

  static Source choose(boolean handedOff, boolean opening, MotionTiming.Opacity opacity) {
    if (!handedOff || (opening && opacity.fixed() > .05f)) return Source.FROZEN;
    if (opening) return Source.WINDOW;
    return opacity.fixed() > .01f && opacity.moving() < .2f ? Source.SCREEN : Source.WINDOW;
  }
}
