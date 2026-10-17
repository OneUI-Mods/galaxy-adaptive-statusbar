package dev.ryuya.galaxybar;

import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import dev.ryuya.galaxybar.hook.*;
import io.github.libxposed.api.XposedModule;

public final class HookEntry extends XposedModule {
  private String processName;

  @Override
  public void onModuleLoaded(ModuleLoadedParam p) {
    Hooks.attach(this);
    processName = p.getProcessName();
  }

  @Override
  public void onPackageReady(PackageReadyParam p) {
    if (p.isFirstPackage())
      loadPackage(p.getPackageName(), p.getClassLoader());
  }

  private static Engine engine;
  private static CrashGuard protection;
  private static boolean initialized;
  private static ClassLoader systemLoader;
  private static LauncherMotion launcherMotion;

  private void loadPackage(String packageName, ClassLoader classLoader) {
    boolean launcher = packageName.equals("com.sec.android.app.launcher");
    if ((!launcher && !packageName.equals("com.android.systemui"))
        || !processName.equals(packageName))
      return;
    // Deliberate allowlist: other firmware requires inspection before enabling hooks.
    if (Build.VERSION.SDK_INT != 36 || !Build.DEVICE.equals("m3q")
        || !Build.DISPLAY.equals("BP4A.251205.006.S948QOPS1AZHL")) {
      Hooks.log("GalaxyBar: unsupported firmware " + Build.DISPLAY);
      return;
    }
    HookCallback.Unhook[] bootstrap = new HookCallback.Unhook[1];
    bootstrap[0] =
        Reflect.findAndHookMethod(Application.class, "attach", Context.class, new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Context context = (Context) p.args[0];
            if (!packageName.equals(context.getPackageName()))
              return;
            if (bootstrap[0] != null)
              bootstrap[0].unhook();
            initialize(context, classLoader);
          }
        });
  }

  private static void initialize(Context context, ClassLoader loader) {
    if (initialized)
      return;
    initialized = true;
    if (context.getPackageName().equals("com.sec.android.app.launcher")) {
      try {
        protection = CrashGuard.open(context, false);
        if (protection.allowsHooks())
          launcherMotion = new LauncherMotion(context, loader, protection);
      } catch (Throwable error) {
        if (protection != null)
          protection.initializationFailed(error);
        else
          CrashGuard.failClosed(context, error);
        Hooks.log("GalaxyBar: launcher motion unavailable: " + error);
      }
      return;
    }
    try {
      protection = CrashGuard.open(context, false);
      if (!protection.allowsHooks())
        return;
      systemLoader = loader;
      installHooks(loader);
    } catch (Throwable error) {
      stopHooks(error);
      if (protection == null)
        CrashGuard.failClosed(context, error);
    }
  }

  static void stopHooks(Throwable error) {
    if (engine != null) {
      try {
        engine.close();
      } catch (Throwable ignored) {
      }
      engine = null;
    }
    disableColors();
    HookRegistry.clear();
    if (protection != null)
      protection.initializationFailed(error);
    Hooks.log(error);
  }

  private static void installHooks(ClassLoader loader) throws Throwable {
    Class<?> clock =
        Reflect.findClass("com.android.systemui.statusbar.policy.QSClockIndicatorView", loader);
    HookRegistry.lifecycle(clock, "onLayout", new HookCallback() {
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
            if (engine == null)
              engine = new Engine(root);
          }
        } catch (Throwable error) {
          stopHooks(error);
        }
      }
    });
    installAttachmentHook(clock);
    Hooks.log("GalaxyBar: QSClockIndicatorView lifecycle hook installed " + Build.DISPLAY);
  }

  static void enableColors() throws Exception {
    if (HookRegistry.colorCount() != 0)
      return;
    if (protection != null) protection.arm();
    ModernTintAdapter.install(systemLoader);
    BatteryAdapter.install(systemLoader);
    HookRegistry.hook(ImageView.class, "setColorFilter", ColorFilter.class, new HookCallback() {
      @Override
      protected void beforeHookedMethod(MethodHookParam p) {
        if (engine != null)
          p.args[0] = engine.standardFilter((ImageView) p.thisObject, (ColorFilter) p.args[0]);
      }
    });
    HookCallback tintChange = new HookCallback() {
      @Override
      protected void afterHookedMethod(MethodHookParam p) {
        if (engine != null)
          engine.standardDrawable((ImageView) p.thisObject);
      }
    };
    HookRegistry.hook(ImageView.class, "setImageTintList", ColorStateList.class, tintChange);
    HookRegistry.hook(ImageView.class, "setImageDrawable", Drawable.class, tintChange);
    Hooks.log("GalaxyBar: color hooks enabled count=" + HookRegistry.colorCount());
  }

  static void disableColors() {
    boolean hadHooks = HookRegistry.colorCount() != 0;
    HookRegistry.clearColors();
    ModernTintAdapter.reset();
    BatteryAdapter.reset();
    if (protection != null) protection.disarm();
    if (hadHooks)
      Hooks.log("GalaxyBar: color hooks disabled count=0");
  }

  private static void installAttachmentHook(Class<?> clock) {
    HookRegistry.lifecycle(clock, "onAttachedToWindow", new HookCallback() {
      @Override
      protected void afterHookedMethod(MethodHookParam p) {
        try {
          View v = (View) p.thisObject;
          ViewTreeObserver.OnGlobalLayoutListener listener =
              new ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                  try {
                    View root = v.getRootView();
                    if (root.getHeight() == 0)
                      return;
                    if (root.getHeight()
                        < root.getResources().getDisplayMetrics().heightPixels / 4) {
                      if (engine != null && !engine.isAttached()) {
                        engine.close();
                        engine = null;
                      }
                      if (engine == null)
                        engine = new Engine(root);
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
          v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
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
  }
}
