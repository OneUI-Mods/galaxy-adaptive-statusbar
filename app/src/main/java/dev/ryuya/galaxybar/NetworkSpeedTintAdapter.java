package dev.ryuya.galaxybar;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.widget.TextView;
import dev.ryuya.galaxybar.hook.HookCallback;
import dev.ryuya.galaxybar.hook.Reflect;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Colors the two-line native speed label without replacing its frequently updated text. */
final class NetworkSpeedTintAdapter {
  private static final String OWNER = "com.android.systemui.statusbar.policy.NetspeedView";
  private static final Map<TextView, ColorStateList> standard = new WeakHashMap<>();
  private static final Map<TextView, Integer> applied = new WeakHashMap<>();

  static void install(ClassLoader loader) throws Exception {
    if (Build.VERSION.SDK_INT != 37) return;
    Class<?> owner = Reflect.findClass(OWNER, loader);
    owner.getDeclaredMethod("onDarkChanged", ArrayList.class, float.class, int.class);
    if (!TextView.class.isAssignableFrom(owner.getDeclaredField("mContentView").getType()))
      throw new NoSuchFieldException("Netspeed content contract");
    HookRegistry.hook(
        owner,
        "onDarkChanged",
        ArrayList.class,
        float.class,
        int.class,
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Object content = Reflect.getObjectField(p.thisObject, "mContentView");
            if (!(content instanceof TextView text) || !supports(text)) return;
            standard.put(text, text.getTextColors());
            Integer adaptive = applied.get(text);
            if (adaptive != null) paint(text, adaptive);
          }
        });
  }

  static boolean supports(View view) {
    if (Build.VERSION.SDK_INT != 37 || !(view.getParent() instanceof View parent)) return false;
    String resource;
    try {
      resource = view.getResources().getResourceEntryName(view.getId());
    } catch (android.content.res.Resources.NotFoundException ignored) {
      return false;
    }
    return StatusBarElements.isNetworkSpeedText(
        Build.VERSION.SDK_INT, view.getClass().getName(), parent.getClass().getName(), resource);
  }

  static void apply(TextView view, int color) {
    standard.computeIfAbsent(view, ignored -> view.getTextColors());
    if (java.util.Objects.equals(applied.get(view), color)) return;
    paint(view, color);
    applied.put(view, color);
  }

  private static void paint(TextView view, int color) {
    ColorStateList baseline = standard.get(view);
    int alpha =
        Color.alpha(baseline.getColorForState(view.getDrawableState(), baseline.getDefaultColor()));
    view.setTextColor((color & 0x00ffffff) | (alpha * Color.alpha(color) / 255 << 24));
  }

  static void restore(TextView view) {
    applied.remove(view);
    ColorStateList baseline = standard.get(view);
    if (baseline != null) view.setTextColor(baseline);
  }

  static void reset() {
    for (TextView view : applied.keySet()) {
      ColorStateList baseline = standard.get(view);
      if (baseline != null) view.setTextColor(baseline);
    }
    applied.clear();
    standard.clear();
  }
}
