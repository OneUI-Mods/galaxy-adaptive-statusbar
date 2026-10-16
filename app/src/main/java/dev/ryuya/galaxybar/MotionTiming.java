package dev.ryuya.galaxybar;

/** Window geometry drives handoff, including springs whose completion time is not predetermined. */
final class MotionTiming {
  static final int LEAD_MS = 250;
  private long handoff = -1;
  private long lastTime = -1;
  private float moving;
  private float dim = 1;

  record Opacity(float fixed, float moving) {}

  Opacity update(boolean opening, float widthRatio, float remainingMs, long now) {
    return update(opening, widthRatio, remainingMs, now, false);
  }

  Opacity update(boolean opening, float widthRatio, float remainingMs, long now, boolean overview) {
    if (handoff < 0
        && (overview
            || !opening
            || widthRatio >= .8f
            || (widthRatio >= .45f && remainingMs >= 0 && remainingMs <= LEAD_MS))) {
      handoff = now;
      if (!opening && !overview && widthRatio >= .8f) moving = 1;
    }
    if (handoff < 0) return new Opacity(1, 0);
    // Geometry is reversible. Never keep fading toward the original target after a reversal.
    // A new native animator may continue this same timing object without restarting its fade.
    float target = overview ? 1 : smooth((widthRatio - .32f) / .34f);
    float step = Math.max(0, now - (lastTime < 0 ? handoff : lastTime)) / 85f;
    moving += Math.max(-step, Math.min(step, target - moving));
    float targetDim = overview ? .35f + .65f * smooth((widthRatio - .65f) / .35f) : 1;
    dim += Math.max(-step, Math.min(step, targetDim - dim));
    lastTime = Math.max(now, lastTime);
    return new Opacity(1 - moving, moving * dim);
  }

  boolean handedOff() {
    return handoff >= 0;
  }

  private static float smooth(float v) {
    float x = Math.max(0, Math.min(1, v));
    return x * x * (3 - 2 * x);
  }
}
