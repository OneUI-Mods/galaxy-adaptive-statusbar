package dev.ryuya.galaxybar;

/** Compound status indicators own their background, foreground colors and internal clipping. */
final class StatusBarElements {
  static boolean isDedicatedComposeBattery(
      int sdk, String className, String parentResource, boolean nativeBatterySibling) {
    return sdk == 37
        && className.equals("androidx.compose.ui.platform.ComposeView")
        && parentResource.equals("system_icons")
        && nativeBatterySibling;
  }

  static boolean isNetworkSpeedText(
      int sdk, String className, String parentClass, String resourceName) {
    return sdk == 37
        && className.equals("com.android.systemui.statusbar.phone.SwitchableDoubleShadowTextView")
        && parentClass.equals("com.android.systemui.statusbar.policy.NetspeedView")
        && resourceName.equals("network_speed_contentview");
  }

  static boolean isCompoundIndicator(String className, String resourceName) {
    return className.contains("OngoingChip")
        || resourceName.contains("capsule")
        || resourceName.equals("samsung_ongoing_cast_chip");
  }
}
