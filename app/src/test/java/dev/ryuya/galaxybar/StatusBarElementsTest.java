package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

public class StatusBarElementsTest {
  @Test
  public void samsungRecordingChipKeepsItsBackgroundAndNativeColorsAsOneElement() {
    assertTrue(
        StatusBarElements.isCompoundIndicator(
            "com.android.systemui.statusbar.phone.TouchInterceptFrameLayout",
            "samsung_ongoing_cast_chip"));
    // Do not classify every icon as a chip: these remain ordinary recolorable leaves.
    assertFalse(StatusBarElements.isCompoundIndicator("android.widget.ImageView", "status_icon"));
    assertFalse(StatusBarElements.isCompoundIndicator("android.widget.TextView", "clock"));
  }

  @Test
  public void existingAndroid16CapsulesStillKeepTheirCompleteRendering() {
    assertTrue(
        StatusBarElements.isCompoundIndicator("android.widget.FrameLayout", "ongoing_capsule"));
    assertTrue(StatusBarElements.isCompoundIndicator("com.android.systemui.OngoingChip", ""));
    assertFalse(StatusBarElements.isCompoundIndicator("android.widget.FrameLayout", "status_bar"));
  }

  @Test
  public void onlyTheDedicatedAndroid17BatteryViewIsAnAtomicLeaf() {
    String compose = "androidx.compose.ui.platform.ComposeView";
    assertTrue(StatusBarElements.isDedicatedComposeBattery(37, compose, "system_icons", true));
    assertFalse(StatusBarElements.isDedicatedComposeBattery(36, compose, "system_icons", true));
    assertFalse(StatusBarElements.isDedicatedComposeBattery(38, compose, "system_icons", true));
    assertFalse(StatusBarElements.isDedicatedComposeBattery(37, compose, "status_bar", true));
    assertFalse(StatusBarElements.isDedicatedComposeBattery(37, compose, "system_icons", false));
    assertFalse(
        StatusBarElements.isDedicatedComposeBattery(
            37, "android.widget.ImageView", "system_icons", true));
  }

  @Test
  public void nativeSpeedLabelIsSeparateFromOtherShadowTextAndAndroid16() {
    String label = "com.android.systemui.statusbar.phone.SwitchableDoubleShadowTextView";
    String owner = "com.android.systemui.statusbar.policy.NetspeedView";
    assertTrue(StatusBarElements.isNetworkSpeedText(37, label, owner, "network_speed_contentview"));
    assertFalse(
        StatusBarElements.isNetworkSpeedText(36, label, owner, "network_speed_contentview"));
    assertFalse(
        StatusBarElements.isNetworkSpeedText(38, label, owner, "network_speed_contentview"));
    assertFalse(StatusBarElements.isNetworkSpeedText(37, label, owner, "clock"));
    assertFalse(
        StatusBarElements.isNetworkSpeedText(
            37, label, "android.widget.LinearLayout", "network_speed_contentview"));
    assertFalse(
        StatusBarElements.isNetworkSpeedText(
            37, "android.widget.TextView", owner, "network_speed_contentview"));
  }
}
