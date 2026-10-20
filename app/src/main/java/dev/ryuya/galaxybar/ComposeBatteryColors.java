package dev.ryuya.galaxybar;

/** BZI9 BatteryLayoutState colors; all native layout and charging state remain unchanged. */
final class ComposeBatteryColors {
  static boolean isBatteryTag(Object tag) {
    return "com.android.systemui:id/battery".equals(tag);
  }

  static boolean isMainBar(Object homeViewModel, int defaults) {
    return homeViewModel != null && (defaults & 16) == 0;
  }

  static Object[] recolor(Object[] nativeValues, int body, int percentage) {
    if (nativeValues.length != 26) throw new IllegalArgumentException("Battery layout contract");
    Object[] result = nativeValues.clone();
    boolean background = (Boolean) nativeValues[25];
    result[6] = body;
    result[7] = body;
    result[8] = (body & 0x00ffffff) | ((Integer) nativeValues[8] & 0xff000000);
    result[9] = percentage;
    if (result[10] != null) result[10] = background ? percentage : body;
    if (result[11] != null) result[11] = body;
    return result;
  }
}
