package dev.ryuya.galaxybar;

import android.graphics.*;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import dev.ryuya.galaxybar.hook.HookCallback;
import dev.ryuya.galaxybar.hook.Reflect;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Uses native Android 17 battery drawing without changing Compose state or composable arguments.
 */
final class NativeBatteryView {
  private static final ThreadLocal<View> drawing = new ThreadLocal<>();
  private static volatile String observation = "waiting for native draw";
  private static boolean failed;
  private static volatile String effectState = "native";
  private static Method drawSize;
  private static final Map<View, Entry> entries = new WeakHashMap<>();

  private static final class Entry {
    WeakReference<View> owner = new WeakReference<>(null);
    Snapshot snapshot;
    NativeBatteryPalette palette;
    RenderNode layer;
    boolean active, recording;
    int body, text;
  }

  record Geometry(BatteryAdapter.Geometry shape, int gauge, int background) {}

  private record Snapshot(Geometry geometry, int body, int text) {}

  private record Session(Entry entry, Canvas destination) {}

  static Geometry geometry(View view) {
    Entry entry = entries.get(view);
    return entry == null || entry.snapshot == null ? null : entry.snapshot.geometry;
  }

  static Rect drawingArea(View view, Rect bounds, BatteryAdapter.Geometry geometry) {
    float sx = bounds.width() / (float) view.getWidth();
    float sy = bounds.height() / (float) view.getHeight();
    float width = geometry.width() * sx, height = geometry.height() * sy;
    Rect result = new Rect();
    new RectF(
            bounds.exactCenterX() - width / 2,
            bounds.exactCenterY() - height / 2,
            bounds.exactCenterX() + width / 2,
            bounds.exactCenterY() + height / 2)
        .roundOut(result);
    return result;
  }

  static void apply(View view, int body, int text) {
    Entry entry = entries.get(view);
    if (entry == null || entry.snapshot == null || failed) return;
    if (entry.active && entry.body == body && entry.text == text) return;
    entry.active = true;
    entry.body = body;
    entry.text = text;
    effectState = "adaptive=" + Integer.toHexString(body) + "/" + Integer.toHexString(text);
    View owner = entry.owner.get();
    if (owner != null) owner.invalidate();
  }

  static void restore(View view) {
    Entry entry = entries.get(view);
    if (entry == null || !entry.active) return;
    entry.active = false;
    effectState = "native";
    // The effect belongs only to our nested RenderNode, never to a native View/RenderNode.
    if (entry.layer != null) entry.layer.setRenderEffect(null);
    View owner = entry.owner.get();
    if (owner != null) owner.invalidate();
  }

  static boolean supports(View view) {
    if (Build.VERSION.SDK_INT != 37
        || !view.getClass().getName().equals("androidx.compose.ui.platform.ComposeView")
        || !(view.getParent() instanceof ViewGroup parent)) return false;
    boolean nativeSibling = false;
    for (int i = 0; i < parent.getChildCount(); i++) {
      View sibling = parent.getChildAt(i);
      if (sibling.getClass().getName().equals("com.android.systemui.battery.BatteryMeterView")
          && resource(sibling).equals("battery")) nativeSibling = true;
    }
    return StatusBarElements.isDedicatedComposeBattery(
        Build.VERSION.SDK_INT, view.getClass().getName(), resource(parent), nativeSibling);
  }

  private static String resource(View view) {
    try {
      return view.getResources().getResourceEntryName(view.getId());
    } catch (android.content.res.Resources.NotFoundException ignored) {
      return "";
    }
  }

  static void installObservation(ClassLoader loader) {
    try {
      installValidatedObservation(loader);
    } catch (Exception error) {
      failed = true;
      observation = "native draw observation unavailable: " + error.getClass().getSimpleName();
    }
  }

  private static void installValidatedObservation(ClassLoader loader) throws Exception {
    if (Build.VERSION.SDK_INT != 37) return;
    Class<?> owner = Reflect.findClass("androidx.compose.ui.platform.AndroidComposeView", loader);
    Class<?> draw =
        Reflect.findClass(
            "com.android.systemui.battery.view.SamsungBatteryComposeViewKt$$ExternalSyntheticLambda0",
            loader);
    // Validate the read-only contracts before installing either callback.
    owner.getDeclaredMethod("dispatchDraw", Canvas.class);
    draw.getDeclaredMethod("invoke", Object.class);
    draw.getDeclaredField("f$1");
    draw.getDeclaredField("f$4");
    Class<?> scope = Reflect.findClass("androidx.compose.ui.graphics.drawscope.DrawScope", loader);
    for (Method method : scope.getMethods())
      if (method.getName().startsWith("getSize-")
          && method.getParameterCount() == 0
          && method.getReturnType() == long.class) drawSize = method;
    if (drawSize == null) throw new NoSuchMethodException("DrawScope size");
    HookRegistry.lifecycle(
        owner,
        "dispatchDraw",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("battery-draw-previous", drawing.get());
            View child = (View) p.thisObject;
            if (child.getParent() instanceof View parent && supports(parent)) {
              drawing.set(parent);
              Entry entry = entries.computeIfAbsent(parent, ignored -> new Entry());
              entry.owner = new WeakReference<>(child);
              Canvas destination = (Canvas) p.args[0];
              if (entry.active
                  && !entry.recording
                  && !failed
                  && destination.isHardwareAccelerated()) {
                if (entry.layer == null) entry.layer = new RenderNode("GalaxyBar native battery");
                entry.layer.setPosition(0, 0, child.getWidth(), child.getHeight());
                Canvas recording = entry.layer.beginRecording();
                entry.recording = true;
                p.setObjectExtra("battery-render-session", new Session(entry, destination));
                p.args[0] = recording;
              }
            } else drawing.remove();
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            try {
              Session session = (Session) p.getObjectExtra("battery-render-session");
              if (session == null) return;
              Entry entry = session.entry;
              try {
                entry.layer.endRecording();
                if (!p.hasThrowable()) {
                  try {
                    if (entry.active && !failed) {
                      if (entry.palette == null) entry.palette = new NativeBatteryPalette();
                      entry.layer.setRenderEffect(
                          entry.palette.effect(
                              entry.body, entry.text, entry.snapshot.body, entry.snapshot.text));
                    } else entry.layer.setRenderEffect(null);
                  } catch (RuntimeException error) {
                    disableEffect(
                        "native battery effect unavailable: " + error.getClass().getSimpleName());
                    entry.layer.setRenderEffect(null);
                  }
                  session.destination.drawRenderNode(entry.layer);
                }
              } finally {
                entry.recording = false;
              }
            } finally {
              View previous = (View) p.getObjectExtra("battery-draw-previous");
              if (previous == null) drawing.remove();
              else drawing.set(previous);
            }
          }
        });
    HookRegistry.lifecycle(
        draw,
        "invoke",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            View view = drawing.get();
            if (view == null || failed) return;
            try {
              Object layout = Reflect.getObjectField(p.thisObject, "f$1");
              Paint paint = (Paint) Reflect.getObjectField(p.thisObject, "f$4");
              long size = (Long) drawSize.invoke(p.args[0]);
              Object context = Reflect.callMethod(p.args[0], "getDrawContext");
              Object composeCanvas = Reflect.callMethod(context, "getCanvas");
              Canvas canvas = (Canvas) Reflect.getObjectField(composeCanvas, "internalCanvas");
              Matrix matrix = new Matrix();
              canvas.getMatrix(matrix);
              Entry entry = entries.get(view);
              entry.snapshot =
                  snapshot(
                      layout, paint, String.valueOf(Reflect.getObjectField(p.thisObject, "f$3")));
              int[] position = new int[2];
              view.getLocationOnScreen(position);
              observation =
                  "view="
                      + view.getWidth()
                      + "x"
                      + view.getHeight()
                      + " at "
                      + position[0]
                      + ","
                      + position[1]
                      + "; canvas="
                      + Float.intBitsToFloat((int) (size >> 32))
                      + "x"
                      + Float.intBitsToFloat((int) size)
                      + "; layout="
                      + Reflect.getIntField(layout, "totalWidthPx")
                      + "x"
                      + Reflect.getIntField(layout, "totalHeightPx")
                      + "; frame="
                      + Integer.toHexString(Reflect.getIntField(layout, "frameColor"))
                      + "; text="
                      + Integer.toHexString(paint.getColor())
                      + "; transform="
                      + matrix.toShortString();
            } catch (Throwable error) {
              disableEffect(
                  "native draw observation unavailable: " + error.getClass().getSimpleName());
            }
          }
        });
  }

  private static void disableEffect(String reason) {
    failed = true;
    observation = reason;
    for (View view : entries.keySet()) restore(view);
  }

  private static Snapshot snapshot(Object layout, Paint paint, String value) {
    int width = Reflect.getIntField(layout, "totalWidthPx");
    int height = Reflect.getIntField(layout, "totalHeightPx");
    Rect glyph = new Rect();
    paint.getTextBounds(value, 0, value.length(), glyph);
    float x = Reflect.getIntField(layout, "textOffsetX");
    if (paint.getTextAlign() == Paint.Align.CENTER) x -= paint.measureText(value) / 2;
    else if (paint.getTextAlign() == Paint.Align.RIGHT) x -= paint.measureText(value);
    float y = Reflect.getFloatField(layout, "textBaselineY");
    int level = Reflect.getIntField(layout, "displayLevel");
    float fill =
        Reflect.getBooleanField(layout, "isFixedPercent")
            ? .15f
            : level >= 96 ? 1 : level <= 4 ? 0 : level / 100f;
    BatteryAdapter.Geometry geometry =
        new BatteryAdapter.Geometry(
            null,
            width,
            height,
            new RectF(x + glyph.left, y + glyph.top, x + glyph.right, y + glyph.bottom),
            fill,
            ((Reflect.getIntField(layout, "frameColor") >>> 24)
                        * (Reflect.getIntField(layout, "backgroundColor") >>> 24)
                    + 127)
                / 255,
            !Reflect.getBooleanField(layout, "shouldShowBackground"),
            Reflect.getBooleanField(layout, "isRtl"));
    return new Snapshot(
        new Geometry(
            geometry,
            Reflect.getIntField(layout, "gaugeColor"),
            Reflect.getIntField(layout, "backgroundColor")),
        Reflect.getIntField(layout, "frameColor"),
        paint.getColor());
  }

  static int percentageColor(
      Bitmap bitmap,
      Rect crop,
      Rect area,
      Geometry geometry,
      int body,
      int previous,
      String algorithm,
      double hysteresis) {
    BatteryAdapter.Geometry shape = geometry.shape;
    double[] samples = new double[192];
    int count = 0;
    for (int row = 0; row < 12; row++)
      for (int column = 0; column < 16; column++) {
        float x = shape.percent().left + (column + .5f) * shape.percent().width() / 16;
        float y = shape.percent().top + (row + .5f) * shape.percent().height() / 12;
        int sx =
            Math.max(
                0,
                Math.min(
                    bitmap.getWidth() - 1,
                    (int) ((area.left + x / shape.width() * area.width() - crop.left) / 2)));
        int sy =
            Math.max(
                0,
                Math.min(
                    bitmap.getHeight() - 1,
                    (int) ((area.top + y / shape.height() * area.height() - crop.top) / 2)));
        int pixel = bitmap.getPixel(sx, sy);
        if (!shape.backgroundOff()) {
          boolean filled =
              shape.rtl()
                  ? x >= shape.width() * (1 - shape.fill())
                  : x <= shape.width() * shape.fill();
          int source = filled ? geometry.gauge : geometry.background;
          int overlay = isNeutral(source) ? body : source;
          pixel = Contrast.over(pixel, overlay, filled ? source >>> 24 : shape.backgroundAlpha());
        }
        samples[count++] = Contrast.luminance(pixel);
      }
    return Contrast.choose(samples, previous, algorithm, hysteresis);
  }

  private static boolean isNeutral(int color) {
    int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
    return Math.max(Math.max(r, g), b) - Math.min(Math.min(r, g), b) <= 17;
  }

  static String health() {
    return Build.VERSION.SDK_INT == 37
        ? "\nNative battery: " + observation + "; " + effectState
        : "";
  }
}
