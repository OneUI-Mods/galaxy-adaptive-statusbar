package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import org.junit.Test;

public class ComposeBatteryColorsTest {
  @Test
  public void matchesSamsungsFullyQualifiedResourceTag() {
    assertTrue(ComposeBatteryColors.isBatteryTag("com.android.systemui:id/battery"));
    assertFalse(ComposeBatteryColors.isBatteryTag("battery"));
    assertFalse(
        ComposeBatteryColors.isBatteryTag("com.android.systemui:id/battery_chip_container"));
    assertFalse(ComposeBatteryColors.isBatteryTag(null));
  }

  @Test
  public void onlyExplicitHomeBatteryReceivesAdaptiveColors() {
    Object home = new Object();
    assertTrue(ComposeBatteryColors.isMainBar(home, 8));
    assertFalse(ComposeBatteryColors.isMainBar(null, 0));
    assertFalse(ComposeBatteryColors.isMainBar(home, 16));
  }

  @Test
  public void whiteBackgroundGetsDarkCapsuleAndReadableLightContents() {
    Object[] original = layout(true);
    Object[] result = ComposeBatteryColors.recolor(original, 0xff000000, 0xffffffff);
    assertEquals(0xff000000, result[6]);
    assertEquals(0xff000000, result[7]);
    assertEquals(0x66000000, result[8]);
    assertEquals(0xffffffff, result[9]);
    assertEquals(0xffffffff, result[10]);
    assertEquals(0xff000000, result[11]);
    assertEquals(0xffeeeeee, original[6]);
    // Charging/warning flags, measured dimensions, text metrics and RTL remain native.
    for (int i = 0; i < original.length; i++)
      if (i < 6 || i > 11) assertEquals(original[i], result[i]);
  }

  @Test
  public void backgroundOffUsesForegroundColorForChargingIcon() {
    Object[] result = ComposeBatteryColors.recolor(layout(false), 0xffffffff, 0xff000000);
    assertEquals(0xffffffff, result[10]);
    assertEquals(false, result[25]);
  }

  @Test
  public void doesNotAddAbsentAttributionOrFixedLevelColors() {
    Object[] original = layout(true);
    original[10] = null;
    original[11] = null;
    Object[] result = ComposeBatteryColors.recolor(original, 0xff777777, 0xff888888);
    assertNull(result[10]);
    assertNull(result[11]);
    assertEquals(0x66777777, result[8]);
  }

  private static Object[] layout(boolean background) {
    Object[] values = new Object[26];
    for (int i = 0; i < values.length; i++) values[i] = i;
    values[6] = 0xffeeeeee;
    values[8] = 0x66ffffff;
    values[25] = background;
    return values;
  }
}
