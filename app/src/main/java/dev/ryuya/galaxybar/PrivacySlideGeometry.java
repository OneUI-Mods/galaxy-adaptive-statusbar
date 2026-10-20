package dev.ryuya.galaxybar;

/** Screen-space reservation; drawing remains in Samsung's original privacy view. */
final class PrivacySlideGeometry {
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
}
