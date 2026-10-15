package dev.ryuya.galaxybar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Recolors only the verified Samsung renderer, preserving its native geometry and animations. */
final class BatteryAdapter {
  private static final Map<Drawable, State> active =
      java.util.Collections.synchronizedMap(new WeakHashMap<>());
  private static final ThreadLocal<State> drawing = new ThreadLocal<>();
  private static volatile Class<?> type;
  private static volatile boolean validated;
  private static volatile String health = "not validated";

  record Geometry(
      Drawable drawable,
      int width,
      int height,
      RectF percent,
      float fill,
      int backgroundAlpha,
      boolean backgroundOff,
      boolean rtl) {}

  private static final class State {
    final Drawable drawable;
    final Paint text, warning, fill, background, outline, mask;
    final Map<Paint, Paint> copies = new IdentityHashMap<>();
    int bodyColor, percentColor;
    boolean backgroundOff;

    State(Drawable drawable) {
      this.drawable = drawable;
      text = paint(drawable, "textPaint");
      warning = paint(drawable, "warningTextPaint");
      fill = paint(drawable, "batteryLevelPaint");
      background = paint(drawable, "batteryLevelBackgroundPaint");
      outline = paint(drawable, "batteryOuterPaint");
      mask = paint(drawable, "roundedRectPaint");
    }

    Paint replacement(Paint source) {
      if (source == null) return null;
      boolean label = source == text || source == warning;
      if (!label && source != fill && source != background && source != outline && source != mask)
        return source;
      Paint result = copies.computeIfAbsent(source, ignored -> new Paint());
      result.set(source);
      int color = label ? percentColor : bodyColor;
      if (source == background || source == outline)
        color = (color & 0xffffff) | (source.getAlpha() << 24);
      result.setColor(color);
      return result;
    }
  }

  static void install(ClassLoader loader) throws Exception {
    Class<?> candidate =
        XposedHelpers.findClass("com.android.systemui.battery.SamsungBatteryMeterDrawable", loader);
    // Boot-framework Canvas calls may have been inlined into Samsung's precompiled renderer.
    XposedBridge.class
        .getMethod("deoptimizeMethod", java.lang.reflect.Member.class)
        .invoke(null, candidate.getDeclaredMethod("draw", Canvas.class));
    XposedHelpers.findAndHookMethod(
        candidate,
        "draw",
        Canvas.class,
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("galaxy.previousBattery", drawing.get());
            State state = active.get((Drawable) p.thisObject);
            if (state == null) drawing.remove();
            else drawing.set(state);
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            State previous = (State) p.getObjectExtra("galaxy.previousBattery");
            if (previous == null) drawing.remove();
            else drawing.set(previous);
          }
        });
    XC_MethodHook replacePaint =
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            State state = drawing.get();
            if (state != null) {
              int index = p.args.length - 1;
              p.args[index] = state.replacement((Paint) p.args[index]);
            }
          }
        };
    XposedHelpers.findAndHookMethod(
        Canvas.class,
        "drawRect",
        float.class,
        float.class,
        float.class,
        float.class,
        Paint.class,
        replacePaint);
    XposedHelpers.findAndHookMethod(
        Canvas.class,
        "drawRoundRect",
        float.class,
        float.class,
        float.class,
        float.class,
        float.class,
        float.class,
        Paint.class,
        replacePaint);
    XposedHelpers.findAndHookMethod(
        Canvas.class,
        "drawText",
        String.class,
        float.class,
        float.class,
        Paint.class,
        replacePaint);
    XC_MethodHook specialIcon =
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            State state = drawing.get();
            if (state == null) return;
            Drawable icon = (Drawable) p.thisObject;
            p.setObjectExtra("galaxy.oldFilter", icon.getColorFilter());
            p.setObjectExtra("galaxy.batteryIcon", true);
            icon.setColorFilter(
                state.backgroundOff ? state.bodyColor : state.percentColor, PorterDuff.Mode.SRC_IN);
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (Boolean.TRUE.equals(p.getObjectExtra("galaxy.batteryIcon")))
              ((Drawable) p.thisObject)
                  .setColorFilter(
                      (android.graphics.ColorFilter) p.getObjectExtra("galaxy.oldFilter"));
          }
        };
    XposedHelpers.findAndHookMethod(BitmapDrawable.class, "draw", Canvas.class, specialIcon);
    XposedHelpers.findAndHookMethod(VectorDrawable.class, "draw", Canvas.class, specialIcon);
    type = candidate;
  }

  /** A synthetic, off-screen draw checks actual hooked Canvas calls without a screen capture. */
  static synchronized void validate(android.content.Context context) {
    if (validated || type == null) return;
    Drawable drawable = null;
    Bitmap bitmap = null;
    try {
      drawable = (Drawable) XposedHelpers.newInstance(type, context);
      Object battery = XposedHelpers.getObjectField(drawable, "batteryState");
      XposedHelpers.setIntField(battery, "level", 100);
      XposedHelpers.setBooleanField(drawable, "notShowBatteryBackgroundSetting", false);
      XposedHelpers.callMethod(drawable, "resizeDrawable");
      int w = drawable.getIntrinsicWidth(), h = drawable.getIntrinsicHeight();
      if (w <= 0 || h <= 0 || w > 512 || h > 512) throw new IllegalStateException("battery size");
      bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      State state = new State(drawable);
      state.bodyColor = 0xffff0000;
      state.percentColor = 0xff00ff00;
      active.put(drawable, state);
      drawable.setBounds(0, 0, w, h);
      drawable.draw(new Canvas(bitmap));
      int red = 0, green = 0;
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          int pixel = bitmap.getPixel(x, y);
          if (pixel == 0xffff0000) red++;
          if (pixel == 0xff00ff00) green++;
        }
      if (red < 20 || green < 5)
        throw new IllegalStateException(
            "synthetic colors missing: body=" + red + " label=" + green);
      health = "synthetic draw OK";
    } catch (Throwable error) {
      health = "disabled: " + error;
      type = null;
    } finally {
      if (drawable != null) active.remove(drawable);
      if (bitmap != null) bitmap.recycle();
    }
    validated = true;
    XposedBridge.log("GalaxyBar: battery adapter " + health);
  }

  static String health() {
    return health;
  }

  static boolean supports(ImageView view) {
    return validated && type != null && type.isInstance(view.getDrawable());
  }

  private static Paint paint(Drawable drawable, String field) {
    return (Paint) XposedHelpers.getObjectField(drawable, field);
  }

  /** Main-thread geometry snapshot; no pixels are captured or analyzed here. */
  static Geometry geometry(ImageView view) {
    Drawable drawable = view.getDrawable();
    int width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
    int level = (Integer) XposedHelpers.callMethod(drawable, "getBatteryLevel");
    boolean off = XposedHelpers.getBooleanField(drawable, "notShowBatteryBackgroundSetting");
    boolean adjusted = XposedHelpers.getBooleanField(drawable, "enableBaseLineAdjustment");
    Paint textPaint = new Paint(paint(drawable, "textPaint"));
    textPaint.setTextSize(
        XposedHelpers.getFloatField(
            drawable,
            off ? "fontSizeWithoutBackground" : adjusted ? "fontSizeRounded" : "fontSize"));
    var context = (android.content.Context) XposedHelpers.getObjectField(drawable, "context");
    String value =
        NumberFormat.getInstance(context.getResources().getConfiguration().getLocales().get(0))
            .format(level);
    Rect bounds = new Rect();
    textPaint.getTextBounds(value, 0, value.length(), bounds);
    float origin = (Float) XposedHelpers.callMethod(drawable, "getTextOriginX", bounds);
    float baseline =
        adjusted
            ? bounds.height() / 2f + height / 2f - 1
            : bounds.height() / 2f + height / 2f - bounds.bottom - (bounds.bottom == 0 ? 1 : 0);
    float left = origin - (off ? 0 : textPaint.measureText(value) / 2f);
    RectF percent =
        new RectF(
            left + bounds.left,
            baseline + bounds.top,
            left + bounds.right,
            baseline + bounds.bottom);
    float fraction = level >= 96 ? 1 : level <= 4 ? 0 : level < 16 ? .15f : level / 100f;
    int alpha = paint(drawable, "batteryLevelBackgroundPaint").getAlpha();
    return new Geometry(
        drawable,
        width,
        height,
        percent,
        fraction,
        alpha,
        off,
        (Boolean) XposedHelpers.callMethod(drawable, "isRtl"));
  }

  /** Worker-only: percentage contrast includes the battery fill that will be drawn beneath it. */
  static int percentageColor(
      Bitmap bitmap,
      Rect crop,
      Rect area,
      Geometry geometry,
      int body,
      int previous,
      String algorithm,
      double hysteresis) {
    RectF glyph = geometry.percent;
    double[] values = new double[256];
    int count = 0;
    for (int row = 0; row < 12; row++)
      for (int column = 0; column < 16; column++) {
        float x = glyph.left + (column + .5f) * glyph.width() / 16;
        float y = glyph.top + (row + .5f) * glyph.height() / 12;
        int sampleX =
            Math.max(
                0,
                Math.min(
                    bitmap.getWidth() - 1,
                    (int) ((area.left + x / geometry.width * area.width() - crop.left) / 2)));
        int sampleY =
            Math.max(
                0,
                Math.min(
                    bitmap.getHeight() - 1,
                    (int) ((area.top + y / geometry.height * area.height() - crop.top) / 2)));
        int pixel = bitmap.getPixel(sampleX, sampleY);
        if (!geometry.backgroundOff) {
          boolean filled =
              geometry.rtl
                  ? x >= geometry.width * (1 - geometry.fill)
                  : x <= geometry.width * geometry.fill;
          pixel = Contrast.over(pixel, body, filled ? 255 : geometry.backgroundAlpha);
        }
        values[count++] = Contrast.luminance(pixel);
      }
    return Contrast.choose(Arrays.copyOf(values, count), previous, algorithm, hysteresis);
  }

  static void apply(Geometry geometry, int body, int percentage) {
    State state = active.computeIfAbsent(geometry.drawable, State::new);
    boolean changed =
        state.bodyColor != body
            || state.percentColor != percentage
            || state.backgroundOff != geometry.backgroundOff;
    state.bodyColor = body;
    state.percentColor = percentage;
    state.backgroundOff = geometry.backgroundOff;
    if (changed) geometry.drawable.invalidateSelf();
  }

  static void restore(Drawable drawable) {
    if (active.remove(drawable) != null) drawable.invalidateSelf();
  }
}
