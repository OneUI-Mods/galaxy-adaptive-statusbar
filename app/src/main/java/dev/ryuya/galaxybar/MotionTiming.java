package dev.ryuya.galaxybar;

/** Window geometry drives handoff, including springs whose completion time is not predetermined. */
final class MotionTiming {
  static final int LEAD_MS = 250;
  private long handoff = -1;

  record Opacity(float fixed, float moving) {}

  Opacity update(boolean opening, float widthRatio, float remainingMs, long now) {
    if (handoff < 0
        && (!opening
            || widthRatio >= .8f
            || (widthRatio >= .45f && remainingMs >= 0 && remainingMs <= LEAD_MS))) {
      handoff = now;
    }
    if (handoff < 0) return new Opacity(1, 0);
    float fade = smooth((now - handoff) / 85f);
    if (opening) return new Opacity(1 - fade, fade);
    float home = 1 - smooth((widthRatio - .32f) / .34f);
    return new Opacity(Math.max(1 - fade, home), fade * (1 - home));
  }

  boolean handedOff() {
    return handoff >= 0;
  }

  private static float smooth(float v) {
    float x = Math.max(0, Math.min(1, v));
    return x * x * (3 - 2 * x);
  }
}
