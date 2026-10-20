package dev.ryuya.galaxybar;

/** Screen-space reservation; drawing remains in Samsung's original privacy view. */
final class PrivacySlideGeometry {
  static final long ENTER_DURATION_MS = 400;

  static float unit(float value) {
    return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
  }

  static float shift(
      float iconsRight, float chipLeft, float chipRight, float gap, float reveal, float alpha) {
    if (!Float.isFinite(iconsRight)
        || !Float.isFinite(chipLeft)
        || !Float.isFinite(chipRight)
        || chipRight <= chipLeft
        || !Float.isFinite(gap)
        || gap < 0) return 0;
    float progress = unit(reveal);
    float revealedLeft = chipRight + (chipLeft - chipRight) * progress;
    return Math.min(0, revealedLeft - gap * progress - iconsRight) * unit(alpha);
  }

  static boolean otherHide(Object[] reasons) {
    if (reasons == null || reasons.length != 8) return true;
    boolean hidden = false;
    for (int i = 0; i < reasons.length; i++) {
      if (!(reasons[i] instanceof Boolean value)) return true;
      if (i != 1) hidden |= value;
    }
    return hidden;
  }

  static boolean fits(float iconsLeft, float shift, float occupiedRight) {
    return Float.isFinite(iconsLeft)
        && Float.isFinite(shift)
        && Float.isFinite(occupiedRight)
        && iconsLeft + shift >= occupiedRight;
  }

  static float combinedShift(float chip, float dot) {
    return Math.min(0, Math.min(Float.isFinite(chip) ? chip : 0, Float.isFinite(dot) ? dot : 0));
  }

  static float entranceOffset(float width, float progress) {
    return Float.isFinite(width) && width > 0 ? width * (1 - unit(progress)) : 0;
  }

  static float entranceAlpha(float progress) {
    return unit((unit(progress) - .15f) / .85f);
  }

  static float dotScale(float diameter, float density) {
    if (!Float.isFinite(diameter) || !Float.isFinite(density) || diameter <= 0 || density <= 0)
      return 1;
    return Math.min(1.5f, (diameter + 2 * density) / diameter);
  }

  static float dotShift(float iconsRight, float dotLeft, float gap, float alpha) {
    if (!Float.isFinite(iconsRight) || !Float.isFinite(dotLeft) || !Float.isFinite(gap) || gap < 0)
      return 0;
    return Math.min(-gap, dotLeft - gap - iconsRight) * unit(alpha);
  }
}
