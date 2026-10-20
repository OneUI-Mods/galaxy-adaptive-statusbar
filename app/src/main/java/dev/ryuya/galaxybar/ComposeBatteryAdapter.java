package dev.ryuya.galaxybar;

import android.graphics.*;
import android.os.Build;
import dev.ryuya.galaxybar.hook.*;
import java.lang.reflect.*;
import java.text.NumberFormat;
import java.util.*;

/** Android 17's native Compose battery. Android 16 continues to use BatteryAdapter. */
final class ComposeBatteryAdapter {
  private static final String BASE = "com.android.systemui.battery.";
  private static final String[] FIELDS = {
    "displayLevel",
    "shouldShowIcon",
    "shouldShowText",
    "shouldShowWarningText",
    "shouldShowPercentageIcon",
    "shouldDrawTintIconWithoutBackground",
    "frameColor",
    "gaugeColor",
    "backgroundColor",
    "textColor",
    "iconColor",
    "fixedLevelColor",
    "totalWidthPx",
    "totalHeightPx",
    "iconWidthPx",
    "iconHeightPx",
    "textFontSizePx",
    "iconOffsetX",
    "iconOffsetY",
    "textOffsetX",
    "textBaselineY",
    "isRtl",
    "isFull",
    "isCharging",
    "isFixedPercent",
    "shouldShowBackground"
  };
  private static final Map<Object, Entry> entries = new WeakHashMap<>();
  private static final ThreadLocal<Entry> composing = new ThreadLocal<>();
  private static Class<?> stateFactory, composeView;
  private static Constructor<?> layoutConstructor;
  private static Field[] fields;
  private static volatile Entry mainBattery;
  private static int revision;
  private static boolean applied;

  private record Palette(int body, int percentage, boolean enabled, int revision) {}

  private static final class Entry {
    final Object colors;
    Object lastRead;
    Rect area = new Rect();
    Object[] layout;

    Entry() {
      colors = Reflect.callStaticMethod(stateFactory, "mutableStateOf$default", (Object) null);
    }
  }

  record Geometry(
      Entry entry,
      Rect area,
      int width,
      int height,
      RectF text,
      float fill,
      int backgroundAlpha,
      boolean backgroundOff,
      boolean rtl) {}

  static void installDiscovery(ClassLoader loader) throws Exception {
    if (Build.VERSION.SDK_INT != 37) return;
    Class<?> layout = Reflect.findClass(BASE + "BatteryLayoutState", loader);
    fields = new Field[FIELDS.length];
    Class<?>[] types = new Class<?>[FIELDS.length];
    for (int i = 0; i < fields.length; i++) {
      fields[i] = layout.getDeclaredField(FIELDS[i]);
      fields[i].setAccessible(true);
      types[i] = fields[i].getType();
    }
    layoutConstructor = layout.getDeclaredConstructor(types);
    layoutConstructor.setAccessible(true);
    stateFactory = Reflect.findClass("androidx.compose.runtime.SnapshotStateKt", loader);
    // Resolve every external contract before registering discovery or changing a color.
    stateFactory.getMethod("mutableStateOf$default", Object.class);
    composeView = Reflect.findClass(BASE + "view.SamsungBatteryComposeViewKt", loader);
    Class<?> model = Reflect.findClass(BASE + "SamsungBatteryViewModel", loader);
    Class<?> composer = Reflect.findClass("androidx.compose.runtime.Composer", loader);
    composeView.getDeclaredMethod(
        "SamsungBatteryComposeView",
        layout,
        model,
        Reflect.findClass("androidx.compose.ui.Modifier", loader),
        composer,
        int.class);
    Class<?> battery = Reflect.findClass(BASE + "SamsungBatteryKt", loader);
    Class<?> areaDark =
        Reflect.findClass(
            "com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl$areaDark$2",
            loader);
    areaDark.getDeclaredMethod("isDarkTheme", Rect.class);
    Hooks.deoptimize(
        battery.getDeclaredMethod(
            "SamsungBattery",
            model,
            Reflect.findClass("kotlin.jvm.functions.Function0", loader),
            Reflect.findClass("androidx.compose.ui.Modifier", loader),
            Reflect.findClass(BASE + "BatteryTheme", loader),
            Reflect.findClass(
                "com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel",
                loader),
            composer,
            int.class,
            int.class));
    HookRegistry.lifecycle(
        battery,
        "SamsungBattery",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("previousBattery", composing.get());
            if (!ComposeBatteryColors.isMainBar(p.args[4], (Integer) p.args[7])) {
              composing.remove();
              return;
            }
            Entry entry = entries.computeIfAbsent(p.args[0], ignored -> new Entry());
            mainBattery = entry;
            composing.set(entry);
            // Read inside the parent composition so palette updates invalidate this battery only.
            Object value = Reflect.callMethod(entry.colors, "getValue");
            if (!Objects.equals(value, entry.lastRead)) {
              p.args[6] = (Integer) p.args[6] | 1;
              entry.lastRead = value;
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Entry previous = (Entry) p.getObjectExtra("previousBattery");
            if (previous == null) composing.remove();
            else composing.set(previous);
          }
        });
    HookRegistry.lifecycle(
        areaDark,
        "isDarkTheme",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Entry entry = composing.get();
            if (entry != null
                && p.args.length == 1
                && p.args[0] instanceof Rect area
                && !area.isEmpty()) entry.area = new Rect(area);
          }
        });
  }

  static void installColors() {
    if (composeView == null) return;
    HookRegistry.hook(
        composeView,
        "SamsungBatteryComposeView",
        fields[0].getDeclaringClass(),
        Reflect.findClass(BASE + "SamsungBatteryViewModel", composeView.getClassLoader()),
        Reflect.findClass("androidx.compose.ui.Modifier", composeView.getClassLoader()),
        Reflect.findClass("androidx.compose.runtime.Composer", composeView.getClassLoader()),
        int.class,
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) throws Exception {
            Entry entry = entries.get(p.args[1]);
            if (entry == null) return; // Quick settings, lock screen and AOD retain native colors.
            Object[] nativeValues = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) nativeValues[i] = fields[i].get(p.args[0]);
            entry.layout = nativeValues;
            Palette palette = (Palette) Reflect.callMethod(entry.colors, "getValue");
            if (palette == null || !palette.enabled) return;
            p.args[0] =
                layoutConstructor.newInstance(
                    ComposeBatteryColors.recolor(nativeValues, palette.body, palette.percentage));
            p.args[4] = ((Integer) p.args[4] & ~14) | 4;
          }
        });
    // Request a native layout after enabling again, even if the level has not changed.
    for (Entry entry : entries.values())
      Reflect.callMethod(entry.colors, "setValue", new Palette(0, 0, false, ++revision));
  }

  static Geometry geometry() {
    Entry entry = mainBattery;
    if (entry == null || entry.layout == null || entry.area.isEmpty()) return null;
    Object[] v = entry.layout;
    int width = (Integer) v[12], height = (Integer) v[13];
    if (width <= 0 || height <= 0) return null;
    boolean off = !(Boolean) v[25];
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(Typeface.create("sec", Typeface.NORMAL));
    paint.setTextSize((Float) v[16]);
    String value = NumberFormat.getInstance().format(v[0]);
    Rect glyph = new Rect();
    paint.getTextBounds(value, 0, value.length(), glyph);
    float left = (Integer) v[19] - (off ? 0 : paint.measureText(value) / 2);
    float baseline = (Float) v[20];
    RectF text =
        new RectF(
            left + glyph.left, baseline + glyph.top, left + glyph.right, baseline + glyph.bottom);
    int level = (Integer) v[0];
    float fill = (Boolean) v[24] ? .15f : level >= 96 ? 1 : level <= 4 ? 0 : level / 100f;
    return new Geometry(
        entry,
        new Rect(entry.area),
        width,
        height,
        text,
        fill,
        (Integer) v[8] >>> 24,
        off,
        (Boolean) v[21]);
  }

  static int percentageColor(
      Bitmap bitmap,
      Rect crop,
      Rect area,
      Geometry g,
      int body,
      int previous,
      String algorithm,
      double hysteresis) {
    double[] values = new double[192];
    int count = 0;
    for (int row = 0; row < 12; row++)
      for (int column = 0; column < 16; column++) {
        float x = g.text.left + (column + .5f) * g.text.width() / 16;
        float y = g.text.top + (row + .5f) * g.text.height() / 12;
        int sx =
            Math.max(
                0,
                Math.min(
                    bitmap.getWidth() - 1,
                    (int) ((area.left + x / g.width * area.width() - crop.left) / 2)));
        int sy =
            Math.max(
                0,
                Math.min(
                    bitmap.getHeight() - 1,
                    (int) ((area.top + y / g.height * area.height() - crop.top) / 2)));
        int pixel = bitmap.getPixel(sx, sy);
        if (!g.backgroundOff) {
          boolean filled = g.rtl ? x >= g.width * (1 - g.fill) : x <= g.width * g.fill;
          pixel = Contrast.over(pixel, body, filled ? 255 : g.backgroundAlpha);
        }
        values[count++] = Contrast.luminance(pixel);
      }
    return Contrast.choose(values, previous, algorithm, hysteresis);
  }

  static void apply(Geometry geometry, int body, int percentage) {
    applied = true;
    Palette value = new Palette(body, percentage, true, revision);
    Object state = geometry.entry.colors;
    if (!value.equals(Reflect.callMethod(state, "getValue")))
      Reflect.callMethod(state, "setValue", value);
  }

  static void restore() {
    applied = false;
    for (Entry entry : entries.values())
      Reflect.callMethod(entry.colors, "setValue", (Object) null);
  }

  static Rect nativeArea() {
    Entry entry = mainBattery;
    return entry == null ? new Rect() : new Rect(entry.area);
  }

  static boolean applied() {
    return applied;
  }

  static String health() {
    return composeView == null
        ? ""
        : mainBattery != null && mainBattery.layout != null
            ? "; Compose ready"
            : "; waiting for Compose battery";
  }
}
