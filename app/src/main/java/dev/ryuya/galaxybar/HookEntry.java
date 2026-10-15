package dev.ryuya.galaxybar;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
  private static Engine engine;
  private static CrashGuard protection;
  private static boolean initialized;

  @Override
  public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
    if (!lp.packageName.equals("com.android.systemui")
        || !lp.processName.equals("com.android.systemui")) return;
    // Deliberate allowlist: other firmware requires inspection before enabling hooks.
    if (Build.VERSION.SDK_INT != 36
        || !Build.DEVICE.equals("m3q")
        || !Build.DISPLAY.equals("BP4A.251205.006.S948QOPS1AZHL")) {
      XposedBridge.log("GalaxyBar: unsupported firmware " + Build.DISPLAY);
      return;
    }
    Application app = AndroidAppHelper.currentApplication();
    if (app != null && lp.packageName.equals(app.getPackageName())) {
      initialize(app, lp.classLoader);
      return;
    }
    XC_MethodHook.Unhook[] bootstrap = new XC_MethodHook.Unhook[1];
    bootstrap[0] =
        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                Context context = (Context) p.args[0];
                if (!"com.android.systemui".equals(context.getPackageName())) return;
                if (bootstrap[0] != null) bootstrap[0].unhook();
                initialize(context, lp.classLoader);
              }
            });
  }

  private static void initialize(Context context, ClassLoader loader) {
    if (initialized) return;
    initialized = true;
    try {
      protection = CrashGuard.open(context);
      if (!protection.allowsHooks()) return;
      installHooks(loader);
    } catch (Throwable error) {
      stopHooks(error);
      if (protection == null) CrashGuard.failClosed(context, error);
    }
  }

  private static void stopHooks(Throwable error) {
    if (engine != null) {
      try {
        engine.close();
      } catch (Throwable ignored) {
      }
      engine = null;
    }
    HookRegistry.clear();
    if (protection != null) protection.initializationFailed(error);
    XposedBridge.log(error);
  }

  private static void installHooks(ClassLoader loader) throws Throwable {
    Class<?> clock =
        XposedHelpers.findClass(
            "com.android.systemui.statusbar.policy.QSClockIndicatorView", loader);
    ModernTintAdapter.install(loader);
    BatteryAdapter.install(loader);
    HookRegistry.hook(
        ImageView.class,
        "setColorFilter",
        ColorFilter.class,
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (engine != null)
              engine.standardFilter((ImageView) p.thisObject, (ColorFilter) p.args[0]);
          }
        });
    XC_MethodHook tintChange =
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (engine != null) engine.standardDrawable((ImageView) p.thisObject);
          }
        };
    HookRegistry.hook(ImageView.class, "setImageTintList", ColorStateList.class, tintChange);
    HookRegistry.hook(ImageView.class, "setImageDrawable", Drawable.class, tintChange);
    HookRegistry.all(
        clock,
        "onLayout",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            try {
              View view = (View) p.thisObject;
              View root = view.getRootView();
              if (root.getHeight() > 0
                  && root.getHeight() < root.getResources().getDisplayMetrics().heightPixels / 4) {
                if (engine != null && !engine.isAttached()) {
                  engine.close();
                  engine = null;
                }
                if (engine == null) engine = new Engine(root);
              }
            } catch (Throwable error) {
              stopHooks(error);
            }
          }
        });
    HookRegistry.all(
        clock,
        "onAttachedToWindow",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            try {
              View v = (View) p.thisObject;
              ViewTreeObserver.OnGlobalLayoutListener listener =
                  new ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                      try {
                        View root = v.getRootView();
                        if (root.getHeight() == 0) return;
                        if (root.getHeight()
                            < root.getResources().getDisplayMetrics().heightPixels / 4) {
                          if (engine != null && !engine.isAttached()) {
                            engine.close();
                            engine = null;
                          }
                          if (engine == null) engine = new Engine(root);
                          v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                      } catch (Throwable e) {
                        stopHooks(e);
                        v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                      }
                    }
                  };
              v.getViewTreeObserver().addOnGlobalLayoutListener(listener);
              v.post(listener::onGlobalLayout);
              v.addOnAttachStateChangeListener(
                  new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View ignored) {}

                    public void onViewDetachedFromWindow(View ignored) {
                      if (v.getViewTreeObserver().isAlive())
                        v.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                      if (engine != null && !engine.isAttached()) {
                        engine.close();
                        engine = null;
                      }
                      v.removeOnAttachStateChangeListener(this);
                    }
                  });
            } catch (Throwable e) {
              stopHooks(e);
            }
          }
        });
    XposedBridge.log("GalaxyBar: QSClockIndicatorView hook installed " + Build.DISPLAY);
  }
}
