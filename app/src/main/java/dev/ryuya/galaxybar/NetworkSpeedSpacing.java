package dev.ryuya.galaxybar;

/** A density-independent adjustment relative to Samsung's native (possibly negative) spacing. */
final class NetworkSpeedSpacing {
  static final String KEY = "network_speed_spacing_dp";
  static final int MIN_DP = -2, MAX_DP = 10;

  static int clamp(int value) {
    return Math.max(MIN_DP, Math.min(MAX_DP, value));
  }

  static float extra(float nativeExtra, float density, int adjustmentDp) {
    return nativeExtra + density * clamp(adjustmentDp);
  }
}
