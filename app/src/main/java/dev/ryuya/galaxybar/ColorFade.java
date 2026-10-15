package dev.ryuya.galaxybar;

/** Short color-only transitions; repeated decisions never postpone completion. */
final class ColorFade {
  static final int DEFAULT_DURATION_MS = 80, MAX_DURATION_MS = 500, STEP_MS = 10;
  private int from, target;
  private long start;
  private int duration = DEFAULT_DURATION_MS;

  ColorFade(int initial) {
    from = target = initial;
  }

  static int clampDuration(int milliseconds) {
    int clamped = Math.max(0, Math.min(MAX_DURATION_MS, milliseconds));
    return Math.round(clamped / (float) STEP_MS) * STEP_MS;
  }

  void setDuration(int milliseconds, long now) {
    int next = clampDuration(milliseconds);
    if (duration == next) return;
    from = color(now);
    duration = next;
    start = now;
  }

  void setTarget(int next, long now) {
    if (next == target) return;
    from = color(now);
    target = next;
    start = now;
  }

  void finish() {
    from = target;
  }

  boolean running(long now) {
    return from != target && now - start < duration;
  }

  int color(long now) {
    if (!running(now)) return target;
    float fraction = Math.max(0, (now - start) / (float) duration);
    // Ease out: establish the new contrast quickly, then settle gently.
    fraction = 1 - (1 - fraction) * (1 - fraction);
    int result = 0;
    for (int shift = 0; shift <= 24; shift += 8) {
      int a = (from >>> shift) & 255, b = (target >>> shift) & 255;
      result |= Math.round(a + (b - a) * fraction) << shift;
    }
    return result;
  }
}
