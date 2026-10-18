package dev.ryuya.galaxybar;

import android.view.View;
import dev.ryuya.galaxybar.hook.HookCallback;
import dev.ryuya.galaxybar.hook.Reflect;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Uses the verified One UI binding API, including the Compose stacked-mobile icon. */
final class ModernTintAdapter {
  private static final Map<View, int[]> standard = new WeakHashMap<>();
  private static final Map<View, Integer> applied = new WeakHashMap<>();
  private static boolean applying;
  private static Class<?> type;
  private static Class<?> dispatcher, dependency;

  static void install(ClassLoader loader) {
    type =
        Reflect.findClass(
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView", loader);
    dispatcher = Reflect.findClass("com.android.systemui.plugins.DarkIconDispatcher", loader);
    dependency = Reflect.findClass("com.android.systemui.Dependency", loader);
    HookRegistry.hook(
        type,
        "onDarkChangedWithContrast",
        ArrayList.class,
        int.class,
        int.class,
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (applying) return;
            View view = (View) p.thisObject;
            int color =
                (Integer)
                    Reflect.callStaticMethod(dispatcher, "getTint", p.args[0], view, p.args[1]);
            int inverse =
                (Integer)
                    Reflect.callStaticMethod(
                        dispatcher, "getInverseTint", p.args[0], view, p.args[2]);
            standard.put(view, new int[] {color, inverse});
            // The dispatcher updates the binding directly, bypassing the static-color setter.
            // Keep its new baseline, then restore our current paint before the frame is drawn.
            Integer adaptive = applied.get(view);
            if (adaptive != null) paint(view, adaptive);
          }
        });
    HookRegistry.hook(
        type,
        "setStaticDrawableColor",
        int.class,
        int.class,
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!applying) {
              View view = (View) p.thisObject;
              standard.put(view, new int[] {(Integer) p.args[0], (Integer) p.args[1]});
              Integer adaptive = applied.get(view);
              if (adaptive != null) {
                p.args[0] = adaptive;
                p.args[1] = adaptive ^ 0x00ffffff;
              }
            }
          }
        });
  }

  static boolean supports(View view) {
    return type != null && type.isInstance(view);
  }

  static boolean canRestore(View view) {
    if (!standard.containsKey(view)) {
      // Hooks were absent while OFF. Ask the verified native dispatcher for today's baseline,
      // rather than reusing a tint from before the user switched apps or changed the theme.
      Object owner = Reflect.getStaticObjectField(dependency, "sDependency");
      if (owner != null) {
        Object source = Reflect.callMethod(owner, "getDependencyInner", dispatcher);
        var receivers = (Map<?, ?>) Reflect.getObjectField(source, "mReceivers");
        if (receivers.containsKey(view)) Reflect.callMethod(source, "applyDark", view);
      }
    }
    return standard.containsKey(view);
  }

  static void reset() {
    standard.clear();
    applied.clear();
    type = null;
  }

  static void apply(View view, int color) {
    if (!canRestore(view) || java.util.Objects.equals(applied.get(view), color)) return;
    paint(view, color);
    applied.put(view, color);
  }

  private static void paint(View view, int color) {
    applying = true;
    try {
      Reflect.callMethod(view, "setStaticDrawableColor", color, color ^ 0x00ffffff);
      Reflect.callMethod(view, "setDecorColor", color);
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
      Reflect.callMethod(view, "setStaticDrawableColor", colors[0], colors[1]);
      Reflect.callMethod(view, "setDecorColor", colors[0]);
    } finally {
      applying = false;
    }
  }
}
