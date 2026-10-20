package dev.ryuya.galaxybar;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import dev.ryuya.galaxybar.hook.HookCallback;
import dev.ryuya.galaxybar.hook.Hooks;
import dev.ryuya.galaxybar.hook.Reflect;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Optional, View-only privacy reservation. Never changes Compose slots or native flow values. */
final class PrivacySlide {
  private static final String BASE = "com.android.systemui.statusbar.";
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final PathInterpolator easing = new PathInterpolator(.2f, 0, 0, 1);
  private static WeakReference<View> root = new WeakReference<>(null);
  private static Constructor<?> visibility;
  private static Method applyVisibility;
  private static boolean installed, enabled, pending, replaying;
  // Before the first native emission, the pending event must originate from a fully visible group.
  private static boolean otherSamsungHide, homeAllowed = true, infoAllowed = true;
  private static Object nativeVisibility;
  private static View nativeTarget;
  private static Session session;
  private static int pendingGeneration, completed;
  private static String state = "off";

  static void attach(View view) {
    root = new WeakReference<>(view);
  }

  static String status() {
    return state + "; completed=" + completed;
  }

  static void configure(boolean value) {
    boolean next = value && installed;
    if (enabled == next) return;
    enabled = next;
    if (!enabled) clearReservation();
    else state = "ready";
  }

  static void install(ClassLoader loader) {
    if (Build.VERSION.SDK_INT != 37 || installed) return;
    try {
      Class<?> scheduler =
          Reflect.findClass(BASE + "events.SystemStatusAnimationSchedulerImpl", loader);
      Class<?> event = Reflect.findClass(BASE + "events.StatusEvent", loader);
      Class<?> controller =
          Reflect.findClass(BASE + "events.SystemEventChipAnimationControllerImpl", loader);
      Class<?> function = Reflect.findClass("kotlin.jvm.functions.Function1", loader);
      Class<?> visibilityType =
          Reflect.findClass(BASE + "pipeline.shared.ui.model.VisibilityModel", loader);
      Class<?> binder =
          Reflect.findClass(BASE + "pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl", loader);
      Class<?> flags =
          Reflect.findClass(
              BASE
                  + "pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl$isSystemInfoVisible$1",
              loader);
      Class<?> home =
          Reflect.findClass(
              BASE
                  + "pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl$shouldHomeStatusBarBeVisible$1",
              loader);
      Class<?> samsung =
          Reflect.findClass(
              BASE
                  + "pipeline.shared.domain.interactor.SamsungHomeStatusBarInteractor$special$$inlined$combine$1$3",
              loader);
      // Verify every contract before registering any optional hook.
      scheduler.getDeclaredMethod("onStatusEvent", event);
      controller.getDeclaredMethod("prepareChipAnimation", function, boolean.class);
      controller.getDeclaredMethod("onSystemEventAnimationBegin", boolean.class);
      controller.getDeclaredMethod("onSystemEventAnimationFinish", boolean.class, boolean.class);
      controller.getDeclaredField("currentAnimatedView");
      controller.getDeclaredField("animationWindowView");
      controller.getDeclaredField("animRect");
      flags.getDeclaredMethod("invoke", Object.class, Object.class, Object.class);
      home.getDeclaredMethod(
          "invoke",
          Object.class,
          Object.class,
          Object.class,
          Object.class,
          Object.class,
          Object.class);
      samsung.getDeclaredMethod("invoke", Object.class, Object.class, Object.class);
      visibility = visibilityType.getDeclaredConstructor(int.class, boolean.class);
      applyVisibility = binder.getDeclaredMethod("adjustVisibility", View.class, visibilityType);

      HookRegistry.hook(
          samsung,
          "invoke",
          Object.class,
          Object.class,
          Object.class,
          new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
              otherSamsungHide =
                  PrivacySlideGeometry.otherHide(
                      p.args[1] instanceof Object[] reasons ? reasons : null);
            }
          });
      HookRegistry.hook(
          home,
          "invoke",
          Object.class,
          Object.class,
          Object.class,
          Object.class,
          Object.class,
          Object.class,
          new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
              homeAllowed =
                  Boolean.TRUE.equals(p.args[0])
                      && !Boolean.TRUE.equals(p.args[1])
                      && !Boolean.TRUE.equals(p.args[4]);
            }
          });
      HookRegistry.hook(
          flags,
          "invoke",
          Object.class,
          Object.class,
          Object.class,
          new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
              infoAllowed = Reflect.getBooleanField(p.args[1], "isSystemInfoAllowed");
            }
          });
      HookRegistry.hook(
          scheduler,
          "onStatusEvent",
          event,
          new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
              if (!enabled || !privacyEvent(p.args[0]) || session != null) return;
              View group = rightGroup();
              if (!scene(group) || !group.isShown() || group.getAlpha() < .99f) return;
              pending = true;
              state = "reserving";
              int generation = ++pendingGeneration;
              main.postDelayed(
                  () -> {
                    if (pending && session == null && generation == pendingGeneration) {
                      pending = false;
                      restoreNative();
                      state = enabled ? "ready" : "off";
                    }
                  },
                  1500);
            }
          });
      HookRegistry.hook(
          binder,
          "adjustVisibility",
          View.class,
          visibilityType,
          new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) throws Exception {
              if (replaying || p.args[0] != rightGroup()) return;
              nativeTarget = (View) p.args[0];
              nativeVisibility = p.args[1];
              if (canReserve()) p.args[1] = visibility.newInstance(View.VISIBLE, false);
            }
          });
      HookRegistry.hook(
          controller,
          "prepareChipAnimation",
          function,
          boolean.class,
          new HookCallback() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
              if (!pending || Boolean.TRUE.equals(p.args[1]) || !canReserve()) return;
              try {
                Object view = Reflect.getObjectField(p.thisObject, "currentAnimatedView");
                View group = rightGroup();
                if (!(view instanceof View chip)
                    || !chip.getClass()
                        .getName()
                        .equals("com.android.systemui.privacy.ui.view.ComposeOngoingPrivacyChip")
                    || !scene(group)) return;
                Session next = new Session(group, chip, p.thisObject);
                if (!next.fits()) {
                  pending = false;
                  restoreNative();
                  state = "native fallback: insufficient space";
                  return;
                }
                session = next;
                next.attach();
              } catch (Throwable error) {
                fail(error);
              }
            }
          });
      HookRegistry.hook(
          controller,
          "onSystemEventAnimationBegin",
          boolean.class,
          new HookCallback() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
              Session active = session;
              if (active != null
                  && active.controller == p.thisObject
                  && !Boolean.TRUE.equals(p.args[0])) {
                active.started = SystemClock.uptimeMillis();
                active.entering = true;
                active.chip.invalidate();
                state = "entering";
              }
            }
          });
      HookRegistry.hook(
          controller,
          "onSystemEventAnimationFinish",
          boolean.class,
          boolean.class,
          new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
              Session active = session;
              if (active != null
                  && active.controller == p.thisObject
                  && !Boolean.TRUE.equals(p.args[1])) {
                active.entering = false;
                active.exiting = true;
                active.restoreChipClip();
                state = "leaving";
              }
            }
          });
      installed = true;
      state = "off";
    } catch (Throwable unavailable) {
      state = "unavailable";
      Hooks.log("GalaxyBar: privacy slide contract unavailable: " + unavailable);
    }
  }

  private static boolean privacyEvent(Object event) {
    if (!event.getClass().getName().equals(BASE + "events.PrivacyEvent")) return false;
    if (!Reflect.getBooleanField(event, "forceVisible")
        || !Reflect.getBooleanField(event, "showAnimation")) return false;
    if (!(Reflect.getObjectField(event, "privacyItems") instanceof List<?> items)) return false;
    for (Object item : items) {
      Object type = Reflect.getObjectField(item, "privacyType");
      if (type instanceof Enum<?> kind
          && (kind.name().equals("TYPE_CAMERA") || kind.name().equals("TYPE_MICROPHONE")))
        return true;
    }
    return false;
  }

  private static boolean canReserve() {
    return enabled
        && (pending || session != null)
        && !otherSamsungHide
        && homeAllowed
        && infoAllowed
        && scene(rightGroup());
  }

  private static boolean scene(View view) {
    View window = root.get();
    return view != null
        && window != null
        && window.isAttachedToWindow()
        && window.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR
        && window.getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_PORTRAIT
        && window.getDisplay() != null
        && window.getDisplay().getDisplayId() == 0;
  }

  private static View find(View view, String name) {
    if (view == null) return null;
    try {
      if (view.getResources().getResourceEntryName(view.getId()).equals(name)) return view;
    } catch (android.content.res.Resources.NotFoundException ignored) {
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), name);
        if (found != null) return found;
      }
    return null;
  }

  private static View rightGroup() {
    return find(root.get(), "status_bar_end_side_content");
  }

  private static void restoreNative() {
    if (nativeTarget == null || nativeVisibility == null || applyVisibility == null) return;
    replaying = true;
    try {
      applyVisibility.invoke(null, nativeTarget, nativeVisibility);
    } catch (ReflectiveOperationException error) {
      Hooks.log(error);
    } finally {
      replaying = false;
    }
  }

  static void cancel() {
    boolean resume = enabled;
    clearReservation();
    enabled = resume;
    state = enabled ? "ready" : "off";
  }

  static void reset() {
    clearReservation();
    installed = false;
  }

  private static void clearReservation() {
    enabled = false;
    pending = false;
    ++pendingGeneration;
    if (session != null) session.close();
    restoreNative();
    nativeVisibility = null;
    nativeTarget = null;
    state = "off";
  }

  private static void fail(Throwable error) {
    reset();
    HookEntry.stopHooks(error);
  }

  private record Clipping(ViewGroup parent, boolean children, boolean padding) {}

  private static final class Session
      implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    final View group, chip, window;
    final Object controller;
    final float baselineX;
    final Rect baselineClip;
    final ArrayList<Clipping> clipping = new ArrayList<>();
    final float gap;
    long started;
    boolean entering, exiting, closed;
    float appliedX;

    Session(View group, View chip, Object controller) {
      this.group = group;
      this.chip = chip;
      this.controller = controller;
      window = root.get();
      baselineX = appliedX = group.getTranslationX();
      Rect clip = chip.getClipBounds();
      baselineClip = clip == null ? null : new Rect(clip);
      gap = 4 * group.getResources().getDisplayMetrics().density;
    }

    boolean fits() {
      Rect bounds = (Rect) Reflect.getObjectField(controller, "chipBounds");
      int[] origin = new int[2];
      ((View) Reflect.getObjectField(controller, "animationWindowView"))
          .getLocationOnScreen(origin);
      int[] icons = new int[2];
      group.getLocationOnScreen(icons);
      float shift =
          PrivacySlideGeometry.shift(
              icons[0] + group.getWidth(),
              origin[0] + bounds.left,
              origin[0] + bounds.right,
              gap,
              1,
              1);
      float occupied = 0;
      for (String id : new String[] {"clock", "notificationIcons"}) {
        View start = find(window, id);
        if (start != null && start.isShown()) {
          int[] at = new int[2];
          start.getLocationOnScreen(at);
          occupied = Math.max(occupied, at[0] + start.getWidth() + gap);
        }
      }
      return group.getWidth() > 0
          && bounds.width() > 0
          && PrivacySlideGeometry.fits(icons[0], shift, occupied);
    }

    void attach() throws Exception {
      for (var parent = group.getParent();
          parent instanceof ViewGroup container && container != window;
          parent = container.getParent()) {
        clipping.add(
            new Clipping(container, container.getClipChildren(), container.getClipToPadding()));
        container.setClipChildren(false);
        container.setClipToPadding(false);
      }
      group.getViewTreeObserver().addOnPreDrawListener(this);
      chip.addOnAttachStateChangeListener(this);
      window.addOnAttachStateChangeListener(this);
      replaying = true;
      try {
        applyVisibility.invoke(null, group, visibility.newInstance(View.VISIBLE, false));
      } finally {
        replaying = false;
      }
      chip.setClipBounds(new Rect(chip.getWidth(), 0, chip.getWidth(), chip.getHeight()));
      main.postDelayed(
          () -> {
            if (!closed) {
              close();
              pending = false;
              restoreNative();
              state = "native fallback: timeout";
            }
          },
          7000);
    }

    @Override
    public boolean onPreDraw() {
      if (closed) return true;
      try {
        if (!scene(group) || !chip.isAttachedToWindow() || !canReserve()) {
          close();
          restoreNative();
          return true;
        }
        Rect rect = (Rect) Reflect.getObjectField(controller, "animRect");
        int[] origin = new int[2];
        ((View) Reflect.getObjectField(controller, "animationWindowView"))
            .getLocationOnScreen(origin);
        int[] location = new int[2];
        group.getLocationOnScreen(location);
        float baselineRight = location[0] - group.getTranslationX() + baselineX + group.getWidth();
        float fraction =
            started == 0
                ? 0
                : easing.getInterpolation(
                    PrivacySlideGeometry.unit((SystemClock.uptimeMillis() - started) / 320f));
        float reveal = entering ? fraction : started == 0 ? 0 : 1;
        float dx =
            PrivacySlideGeometry.shift(
                baselineRight,
                origin[0] + rect.left + chip.getTranslationX(),
                origin[0] + rect.right + chip.getTranslationX(),
                gap,
                reveal,
                chip.getAlpha());
        appliedX = baselineX + dx;
        group.setTranslationX(appliedX);
        if (entering && fraction < 1) {
          int left = Math.round(chip.getWidth() * (1 - fraction));
          Rect clip = new Rect(left, 0, chip.getWidth(), chip.getHeight());
          if (baselineClip != null) clip.intersect(baselineClip);
          chip.setClipBounds(clip);
          chip.postInvalidateOnAnimation();
        } else {
          restoreChipClip();
          if (!exiting && started != 0) state = "holding";
          if (exiting) chip.postInvalidateOnAnimation();
        }
      } catch (Throwable error) {
        fail(error);
      }
      return true;
    }

    void restoreChipClip() {
      chip.setClipBounds(baselineClip);
    }

    void close() {
      if (closed) return;
      closed = true;
      if (group.getViewTreeObserver().isAlive())
        group.getViewTreeObserver().removeOnPreDrawListener(this);
      chip.removeOnAttachStateChangeListener(this);
      window.removeOnAttachStateChangeListener(this);
      if (Math.abs(group.getTranslationX() - appliedX) < .01f) group.setTranslationX(baselineX);
      restoreChipClip();
      for (Clipping item : clipping) {
        item.parent.setClipChildren(item.children);
        item.parent.setClipToPadding(item.padding);
      }
      clipping.clear();
      if (session == this) session = null;
      pending = false;
      completed++;
      state = enabled ? "ready" : "off";
    }

    @Override
    public void onViewAttachedToWindow(View view) {}

    @Override
    public void onViewDetachedFromWindow(View view) {
      close();
    }
  }
}
