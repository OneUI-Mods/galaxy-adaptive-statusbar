package dev.ryuya.galaxybar;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import dev.ryuya.galaxybar.hook.HookCallback;
import dev.ryuya.galaxybar.hook.Reflect;
import java.lang.reflect.Method;

/** Reads the native Android 17 battery draw without changing Compose state or arguments. */
final class NativeBatteryView {
  private static final ThreadLocal<View> drawing = new ThreadLocal<>();
  private static String observation = "waiting for native draw";
  private static boolean failed;
  private static Method drawSize;

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
            if (child.getParent() instanceof View parent && supports(parent)) drawing.set(parent);
            else drawing.remove();
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            View previous = (View) p.getObjectExtra("battery-draw-previous");
            if (previous == null) drawing.remove();
            else drawing.set(previous);
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
              // A diagnostic mismatch disables observation only; it never changes native drawing.
              failed = true;
              observation =
                  "native draw observation unavailable: " + error.getClass().getSimpleName();
            }
          }
        });
  }

  static String health() {
    return Build.VERSION.SDK_INT == 37 ? "\nNative battery: " + observation : "";
  }
}
