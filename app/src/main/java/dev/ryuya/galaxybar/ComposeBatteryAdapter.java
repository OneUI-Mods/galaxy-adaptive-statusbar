package dev.ryuya.galaxybar;

import android.graphics.*;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import dev.ryuya.galaxybar.hook.*;
import java.lang.ref.WeakReference;
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
  private static Class<?> composeOwnerType;
  private static Object batteryTag;
  private static WeakReference<View> ownerView = new WeakReference<>(null);
  private static Class<?> stateFactory, composeView;
  private static Constructor<?> layoutConstructor;
  private static Field[] fields;
  private static volatile Entry mainBattery;
  private static int revision;
  private static boolean applied;
  private static int visitedViews, visitedOwners, visitedNodes;
  private static String rootType = "";
  private static final Set<String> observedTags = new LinkedHashSet<>();

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
    composeOwnerType = Reflect.findClass("androidx.compose.ui.platform.AndroidComposeView", loader);
    composeOwnerType.getDeclaredField("semanticsOwner");
    Class<?> semanticsOwner =
        Reflect.findClass("androidx.compose.ui.semantics.SemanticsOwner", loader);
    semanticsOwner.getDeclaredMethod("getUnmergedRootSemanticsNode");
    Class<?> semanticsNode =
        Reflect.findClass("androidx.compose.ui.semantics.SemanticsNode", loader);
    semanticsNode.getDeclaredMethod("getConfig");
    semanticsNode.getDeclaredMethod("getChildren$ui", boolean.class, boolean.class);
    semanticsNode.getDeclaredMethod("getBoundsInWindow");
    batteryTag =
        Reflect.getStaticObjectField(
            Reflect.findClass("androidx.compose.ui.semantics.SemanticsProperties", loader),
            "TestTag");
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
        int.class);
    HookRegistry.lifecycle(
        battery,
        "SamsungBattery",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!ComposeBatteryColors.isMainBar(p.args[4], (Integer) p.args[7])) {
              return;
            }
            Entry entry = entries.computeIfAbsent(p.args[0], ignored -> new Entry());
            mainBattery = entry;
            // Read inside the parent composition so palette updates invalidate this battery only.
            Object value = Reflect.callMethod(entry.colors, "getValue");
            if (!Objects.equals(value, entry.lastRead)) {
              p.args[6] = (Integer) p.args[6] | 1;
              entry.lastRead = value;
            }
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

  static Geometry geometry(View root) {
    Entry entry = mainBattery;
    if (entry == null || entry.layout == null) return null;
    visitedViews = visitedOwners = visitedNodes = 0;
    observedTags.clear();
    rootType = root.getClass().getSimpleName();
    View owner = ownerView.get();
    Rect bounds =
        owner != null && owner.isAttachedToWindow() && owner.getRootView() == root
            ? semanticBounds(owner)
            : null;
    if (bounds == null) bounds = findOwner(root, new int[] {256});
    entry.area = bounds == null ? new Rect() : bounds;
    if (entry.area.isEmpty()) return null;
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

  private static Rect findOwner(View view, int[] remaining) {
    visitedViews++;
    if (--remaining[0] < 0 || !view.isShown()) return null;
    if (composeOwnerType.isInstance(view)) {
      Rect bounds = semanticBounds(view);
      if (bounds != null) {
        ownerView = new WeakReference<>(view);
        return bounds;
      }
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        Rect bounds = findOwner(group.getChildAt(i), remaining);
        if (bounds != null) return bounds;
      }
    return null;
  }

  private static Rect semanticBounds(View view) {
    visitedOwners++;
    Object owner = Reflect.getObjectField(view, "semanticsOwner");
    Object root = Reflect.callMethod(owner, "getUnmergedRootSemanticsNode");
    ArrayDeque<Object> nodes = new ArrayDeque<>();
    nodes.add(root);
    int remaining = 256;
    while (!nodes.isEmpty() && --remaining >= 0) {
      Object node = nodes.removeFirst();
      visitedNodes++;
      Object config = Reflect.callMethod(node, "getConfig");
      for (Object item : (Iterable<?>) config) {
        Map.Entry<?, ?> property = (Map.Entry<?, ?>) item;
        if (property.getKey() == batteryTag && observedTags.size() < 8)
          observedTags.add(String.valueOf(property.getValue()));
        if (property.getKey() == batteryTag
            && ComposeBatteryColors.isBatteryTag(property.getValue())) {
          Object rect = Reflect.callMethod(node, "getBoundsInWindow");
          RectF bounds =
              new RectF(
                  Reflect.getFloatField(rect, "left"),
                  Reflect.getFloatField(rect, "top"),
                  Reflect.getFloatField(rect, "right"),
                  Reflect.getFloatField(rect, "bottom"));
          Rect pixels = new Rect();
          bounds.roundOut(pixels);
          if (!pixels.isEmpty()) return pixels;
        }
      }
      nodes.addAll((List<?>) Reflect.callMethod(node, "getChildren$ui", true, false));
    }
    return null;
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
            ? "; Compose "
                + BuildConfig.VERSION_NAME
                + "; area="
                + mainBattery.area
                + "; root="
                + rootType
                + "; views="
                + visitedViews
                + "; owners="
                + visitedOwners
                + "; nodes="
                + visitedNodes
                + "; tags="
                + observedTags
            : "; Compose " + BuildConfig.VERSION_NAME + " waiting for battery";
  }
}
