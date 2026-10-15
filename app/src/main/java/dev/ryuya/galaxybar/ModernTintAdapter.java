package dev.ryuya.galaxybar;

import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Uses the verified One UI binding API, including the Compose stacked-mobile icon. */
final class ModernTintAdapter {
  private static final Map<View, int[]> standard = new WeakHashMap<>();
  private static final Map<View, Integer> applied = new WeakHashMap<>();
  private static boolean applying;
  private static Class<?> type;

  static void install(ClassLoader loader) {
    type =
        XposedHelpers.findClass(
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView", loader);
    Class<?> dispatcher =
        XposedHelpers.findClass("com.android.systemui.plugins.DarkIconDispatcher", loader);
    XposedHelpers.findAndHookMethod(
        type,
        "onDarkChangedWithContrast",
        ArrayList.class,
        int.class,
        int.class,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (applying) return;
            View view = (View) p.thisObject;
            applied.remove(view);
            int color =
                (Integer)
                    XposedHelpers.callStaticMethod(
                        dispatcher, "getTint", p.args[0], view, p.args[1]);
            int inverse =
                (Integer)
                    XposedHelpers.callStaticMethod(
                        dispatcher, "getInverseTint", p.args[0], view, p.args[2]);
            standard.put(view, new int[] {color, inverse});
          }
        });
    XposedHelpers.findAndHookMethod(
        type,
        "setStaticDrawableColor",
        int.class,
        int.class,
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!applying) {
              applied.remove((View) p.thisObject);
              standard.put(
                  (View) p.thisObject, new int[] {(Integer) p.args[0], (Integer) p.args[1]});
            }
          }
        });
  }

  static boolean supports(View view) {
    return type != null && type.isInstance(view);
  }

  static boolean canRestore(View view) {
    return standard.containsKey(view);
  }

  static void apply(View view, int color) {
    if (!canRestore(view) || java.util.Objects.equals(applied.get(view), color)) return;
    applying = true;
    try {
      XposedHelpers.callMethod(
          view, "setStaticDrawableColor", color, color == 0xff000000 ? 0xffffffff : 0xff000000);
      XposedHelpers.callMethod(view, "setDecorColor", color);
      applied.put(view, color);
    } finally {
      applying = false;
    }
  }

  static void restore(View view) {
    applied.remove(view);
    int[] colors = standard.get(view);
    if (colors == null) return;
    applying = true;
    try {
      XposedHelpers.callMethod(view, "setStaticDrawableColor", colors[0], colors[1]);
      XposedHelpers.callMethod(view, "setDecorColor", colors[0]);
    } finally {
      applying = false;
    }
  }
}
