package dev.ryuya.galaxybar;

/** Independent screen/card fades; only native geometry decides when ownership changes. */
final class MotionTiming {
  static final int LEAD_MS = 250;
  private final float durationScale;
  private final Fade fixed = new Fade(1), moving = new Fade(0), visibility = new Fade(1);
  private boolean handedOff;
  private boolean openingLeg, returningFromOverview;
  private float previousWidth = Float.NaN;

  record Opacity(float fixed, float moving) {}

  MotionTiming() {
    this(1);
  }

  MotionTiming(float durationScale) {
    this.durationScale = Math.max(0, durationScale);
  }

  Opacity update(boolean opening, float widthRatio, float remainingMs, long now) {
    return update(opening, widthRatio, remainingMs, now, false, false);
  }

  Opacity update(boolean opening, float widthRatio, float remainingMs, long now, boolean overview) {
    return update(opening, widthRatio, remainingMs, now, overview, false);
  }

  Opacity update(
      boolean opening,
      float widthRatio,
      float remainingMs,
      long now,
      boolean overview,
      boolean interactive) {
    boolean wasHandedOff = handedOff;
    boolean cardMode = overview || interactive;
    if (cardMode) returningFromOverview = true;
    if (!handedOff
        && (overview
            || !opening
            || interactive
            || widthRatio >= .8f
            || (widthRatio >= .45f && remainingMs >= 0 && remainingMs <= LEAD_MS))) {
      handedOff = true;
      openingLeg = opening;
      if (!opening || overview || interactive) {
        // A closing window begins at the existing bar's position: transfer without a dip.
        fixed.snap(0);
        moving.snap(1);
        if (cardMode) visibility.snap(cardVisibility(widthRatio));
      } else {
        fixed.to(0, now, ms(90), 0, true);
        moving.to(1, now, ms(140), ms(35), false);
      }
    }
    if (handedOff) {
      boolean direction = opening;
      if (interactive && !Float.isNaN(previousWidth)) {
        float delta = widthRatio - previousWidth;
        direction = Math.abs(delta) >= .003f ? delta > 0 : openingLeg;
      }
      boolean changedDirection = direction != openingLeg;
      openingLeg = direction;
      if (cardMode) {
        fixed.to(0, now, ms(90), 0, true);
        moving.to(1, now, ms(120), 0, false);
      } else if (openingLeg) {
        fixed.to(0, now, ms(90), 0, true);
        // Do not restart or remove the initial stagger on every native frame.
        if (wasHandedOff && changedDirection) moving.to(1, now, ms(140), 0, false);
      } else {
        // The card bar disappears before it becomes tiny. The home bar arrives later;
        // these independent envelopes deliberately avoid a pair of equally bright clocks.
        moving.to(smooth((widthRatio - .58f) / .2f), now, ms(80), 0, false);
        fixed.to(smooth((.60f - widthRatio) / .2f), now, ms(110), 0, false);
      }
      // A reduced window must become completely clear, including while the finger
      // is held still. Geometry selects the target; time completes the fade without
      // requiring more movement. Expansion/reversal starts at the displayed alpha.
      float targetVisibility =
          cardMode || returningFromOverview || !openingLeg ? cardVisibility(widthRatio) : 1;
      visibility.to(targetVisibility, now, ms(140), 0, false);
    }
    previousWidth = widthRatio;
    return opacity(now);
  }

  Opacity opacity(long now) {
    return new Opacity(fixed.value(now), moving.value(now) * visibility.value(now));
  }

  boolean running(long now) {
    return fixed.running(now) || moving.running(now) || visibility.running(now);
  }

  boolean openingLeg() {
    return openingLeg;
  }

  boolean handedOff() {
    return handedOff;
  }

  private int ms(int duration) {
    return Math.round(duration * durationScale);
  }

  private static float cardVisibility(float widthRatio) {
    return smooth((widthRatio - .90f) / .09f);
  }

  private static float smooth(float v) {
    float x = Math.max(0, Math.min(1, v));
    return x * x * (3 - 2 * x);
  }

  private static final class Fade {
    private float from, target;
    private long start;
    private int duration;
    private boolean easeOut;

    Fade(float value) {
      snap(value);
    }

    void snap(float value) {
      from = target = value;
      duration = 0;
    }

    void to(float value, long now, int duration, int delay, boolean easeOut) {
      if (value == target) return;
      from = value(now);
      target = value;
      start = now + delay;
      this.duration = duration;
      this.easeOut = easeOut;
    }

    boolean running(long now) {
      return from != target && now < start + duration;
    }

    float value(long now) {
      if (!running(now)) return target;
      if (now <= start) return from;
      float x = Math.min(1, (now - start) / (float) duration);
      float eased = easeOut ? 1 - (1 - x) * (1 - x) * (1 - x) : smooth(x);
      return from + (target - from) * eased;
    }
  }
}
