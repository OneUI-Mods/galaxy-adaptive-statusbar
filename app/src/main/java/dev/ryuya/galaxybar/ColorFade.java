package dev.ryuya.galaxybar;

/** Short color-only transitions; repeated decisions never postpone completion. */
final class ColorFade {
  static final long DURATION_MS = 80;
  private int from, target;
  private long start;

  ColorFade(int initial) {
    from = target = initial;
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
    return from != target && now - start < DURATION_MS;
  }

  int color(long now) {
    if (!running(now)) return target;
    float fraction = Math.max(0, (now - start) / (float) DURATION_MS);
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
