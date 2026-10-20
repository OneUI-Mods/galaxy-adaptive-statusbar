package dev.ryuya.galaxybar;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import dev.ryuya.galaxybar.hook.HookCallback;
import dev.ryuya.galaxybar.hook.Hooks;
import dev.ryuya.galaxybar.hook.Reflect;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Enlarges the original dot without changing its privacy state, color, or visibility. */
final class PrivacyDots implements ViewTreeObserver.OnPreDrawListener {
  private static final PrivacyDots observer = new PrivacyDots();
  private static WeakReference<View> window = new WeakReference<>(null);
  private static WeakReference<Object> controller = new WeakReference<>(null);
  private static final ArrayList<WeakReference<View>> corners = new ArrayList<>();
  private static final ArrayList<Visual> visuals = new ArrayList<>();
  private static boolean installed, enabled;

  static String status() {
    return !installed ? "unavailable" : !enabled ? "off" : "ready; active=" + visuals.size();
  }

  static void install(ClassLoader loader) {
    if (Build.VERSION.SDK_INT != 37 || installed) return;
    try {
      Class<?> owner =
          Reflect.findClass(
              "com.android.systemui.statusbar.events.PrivacyDotViewControllerImpl", loader);
      Class<?> state = Reflect.findClass("com.android.systemui.statusbar.events.ViewState", loader);
      owner.getDeclaredMethod("initialize", View.class, View.class, View.class, View.class);
      owner.getDeclaredMethod("showDotView", View.class);
      owner.getDeclaredField("currentViewState");
      owner.getDeclaredField("displayId");
      state.getDeclaredField("systemPrivacyEventLocationOnlyIsActive");
      state.getDeclaredField("designatedCorner");
      HookRegistry.hook(
          owner,
          "initialize",
          View.class,
          View.class,
          View.class,
          View.class,
          new HookCallback() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
              remember(p.thisObject, p.args);
            }
          });
      HookRegistry.hook(
          owner,
          "showDotView",
          View.class,
          new HookCallback() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
              remember(p.thisObject, p.args);
            }
          });
      installed = true;
    } catch (Throwable unavailable) {
      Hooks.log("GalaxyBar: privacy dot contract unavailable: " + unavailable);
    }
  }

  private static void remember(Object owner, Object[] views) {
    // The cover display has its own controller and geometry.
    if (Reflect.getIntField(owner, "displayId") != 0) return;
    controller = new WeakReference<>(owner);
    for (Object value : views) {
      if (!(value instanceof View view)) continue;
      boolean known = false;
      for (WeakReference<View> corner : corners) if (corner.get() == view) known = true;
      if (!known) corners.add(new WeakReference<>(view));
      listen(view);
    }
    View root = window.get();
    if (root != null) root.invalidate();
  }

  static void attach(View root) {
    View old = window.get();
    if (old != root) {
      unlisten(old);
      restore();
      window = new WeakReference<>(root);
    }
    listen(root);
  }

  static void configure(boolean value) {
    enabled = value && installed;
    if (!enabled) {
      restore();
      unlisten(window.get());
      for (WeakReference<View> corner : corners) unlisten(corner.get());
    } else {
      listen(window.get());
      for (WeakReference<View> corner : corners) listen(corner.get());
    }
  }

  static void reset() {
    configure(false);
    corners.clear();
    controller.clear();
    installed = false;
  }

  private static void listen(View view) {
    if (!enabled || view == null || !view.getViewTreeObserver().isAlive()) return;
    view.getViewTreeObserver().removeOnPreDrawListener(observer);
    view.getViewTreeObserver().addOnPreDrawListener(observer);
  }

  private static void unlisten(View view) {
    if (view != null && view.getViewTreeObserver().isAlive())
      view.getViewTreeObserver().removeOnPreDrawListener(observer);
  }

  @Override
  public boolean onPreDraw() {
    try {
      Object owner = controller.get();
      if (!enabled || owner == null || !PrivacySlide.allowDot()) {
        restore();
        return true;
      }
      Object state = Reflect.getObjectField(owner, "currentViewState");
      if (state == null
          || Reflect.getBooleanField(state, "systemPrivacyEventLocationOnlyIsActive")) {
        restore();
        return true;
      }
      Object target = Reflect.getObjectField(state, "designatedCorner");
      if (!(target instanceof View corner) || !corner.isShown()) {
        restore();
        return true;
      }
      View dot = findDot(corner);
      if (dot == null || dot.getWidth() <= 0 || dot.getHeight() <= 0) {
        restore();
        return true;
      }
      Visual visual = null;
      for (Visual item : visuals) if (item.dot == dot) visual = item;
      if (visual == null) {
        restore();
        visual = new Visual(dot);
        visuals.add(visual);
      }
      visual.apply();
      int[] at = new int[2];
      // getLocationOnScreen includes the view's scale around its existing pivot.
      dot.getLocationOnScreen(at);
      if (!PrivacySlide.reserveDot(at[0], corner.getAlpha())) restore();
    } catch (Throwable error) {
      // A mismatch disables only this optional presentation, leaving the native dot visible.
      configure(false);
      Hooks.log("GalaxyBar: privacy dot presentation stopped: " + error);
    }
    return true;
  }

  private static View findDot(View view) {
    try {
      if (view.getResources().getResourceEntryName(view.getId()).equals("privacy_dot")) return view;
    } catch (android.content.res.Resources.NotFoundException ignored) {
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDot(group.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private static void restore() {
    for (Visual visual : visuals) visual.close();
    visuals.clear();
    PrivacySlide.releaseDot();
  }

  private static final class Visual {
    final View dot;
    final float scaleX, scaleY;
    final ArrayList<Clipping> clipping = new ArrayList<>();
    float appliedX, appliedY;

    Visual(View dot) {
      this.dot = dot;
      scaleX = appliedX = dot.getScaleX();
      scaleY = appliedY = dot.getScaleY();
      for (var parent = dot.getParent();
          parent instanceof ViewGroup group;
          parent = group.getParent()) {
        clipping.add(new Clipping(group, group.getClipChildren(), group.getClipToPadding()));
        group.setClipChildren(false);
        group.setClipToPadding(false);
      }
    }

    void apply() {
      float factor =
          PrivacySlideGeometry.dotScale(
              Math.min(dot.getWidth(), dot.getHeight()),
              dot.getResources().getDisplayMetrics().density);
      appliedX = scaleX * factor;
      appliedY = scaleY * factor;
      dot.setScaleX(appliedX);
      dot.setScaleY(appliedY);
    }

    void close() {
      if (Math.abs(dot.getScaleX() - appliedX) < .001f) dot.setScaleX(scaleX);
      if (Math.abs(dot.getScaleY() - appliedY) < .001f) dot.setScaleY(scaleY);
      for (Clipping item : clipping) {
        item.parent.setClipChildren(item.children);
        item.parent.setClipToPadding(item.padding);
      }
      clipping.clear();
    }
  }

  private record Clipping(ViewGroup parent, boolean children, boolean padding) {}
}
