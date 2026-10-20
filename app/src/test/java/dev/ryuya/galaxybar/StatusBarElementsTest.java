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
}
