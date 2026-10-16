package dev.ryuya.galaxybar;

/** Window geometry drives handoff, including springs whose completion time is not predetermined. */
final class MotionTiming {
  static final int LEAD_MS = 250;
  private long handoff = -1;
  private long lastTime = -1;
  private float moving;

  record Opacity(float fixed, float moving) {}

  Opacity update(boolean opening, float widthRatio, float remainingMs, long now) {
    if (handoff < 0
        && (!opening
            || (remainingMs < 0 && widthRatio >= .8f)
            || (widthRatio >= .45f && remainingMs >= 0 && remainingMs <= LEAD_MS))) {
      handoff = now;
    }
    if (handoff < 0) return new Opacity(1, 0);
    // Geometry is reversible. Never keep fading toward the original target after a reversal.
    // A new native animator may continue this same timing object without restarting its fade.
    float target = smooth((widthRatio - .32f) / .34f);
    float step = Math.max(0, now - (lastTime < 0 ? handoff : lastTime)) / 85f;
    moving += Math.max(-step, Math.min(step, target - moving));
    lastTime = Math.max(now, lastTime);
    return new Opacity(1 - moving, moving);
  }

  boolean handedOff() {
    return handoff >= 0;
  }

  private static float smooth(float v) {
    float x = Math.max(0, Math.min(1, v));
    return x * x * (3 - 2 * x);
  }
}
