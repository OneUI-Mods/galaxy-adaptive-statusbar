package dev.ryuya.galaxybar;

import android.animation.*;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import de.robv.android.xposed.*;
import java.util.*;
import java.util.concurrent.*;

/** Observes One UI's final task-window transform after both standard and Home Up calculators. */
final class LauncherMotion {
  private static final String PLAYER = "com.honeyspace.transition.anim.floating.PlayerImpl";
  private final Context context;
  private final ClassLoader loader;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "GalaxyBarMotionConfig");
            t.setDaemon(true);
            return t;
          });
  private final Map<Object, Session> sessions = new WeakHashMap<>();
  private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
  private volatile IBinder endpoint;
  private boolean enabled, failed;
  private int revision;
  private final ContentObserver observer =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
          String key = uri == null ? null : uri.getLastPathSegment();
          if (uri == null
              || uri.equals(ConfigProvider.URI)
              || "enabled".equals(key)
              || "motion".equals(key)) {
            disable();
            failed = false;
          }
          refresh();
        }
      };

  LauncherMotion(Context context, ClassLoader loader) throws Exception {
    this.context = context;
    this.loader = loader;
    if (context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode()
        != 1750305027L) {
      XposedBridge.log("GalaxyBar: unsupported One UI Home version; motion hooks not installed");
      return;
    }
    context.getContentResolver().registerContentObserver(ConfigProvider.URI, true, observer);
    refresh();
  }

  private void refresh() {
    int request = ++revision;
    io.execute(
        () -> {
          try {
            Bundle config =
                context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
            Bundle result =
                context
                    .getContentResolver()
                    .call(ConfigProvider.URI, "motion_endpoint", null, null);
            IBinder binder = result == null ? null : result.getBinder("motion_endpoint");
            main.post(
                () -> {
                  if (request != revision) return;
                  endpoint = binder;
                  boolean wanted =
                      config != null
                          && config.getBoolean("enabled")
                          && config.getBoolean("motion", true);
                  if (!wanted) {
                    disable();
                    failed = false;
                  } else if (!enabled && !failed) {
                    try {
                      install();
                      enabled = true;
                    } catch (Throwable error) {
                      fail(error);
                    }
                  }
                });
          } catch (Throwable error) {
            main.post(
                () -> {
                  if (request == revision) disable();
                });
          }
        });
  }

  private void install() {
    Class<?> player = XposedHelpers.findClass(PLAYER, loader);
    Class<?> input =
        XposedHelpers.findClass(
            "com.honeyspace.transition.anim.floating.entity.FloatingInputData", loader);
    Class<?> desktop =
        XposedHelpers.findClass(
            "com.honeyspace.transition.anim.floating.entity.DesktopInputData", loader);
    Class<?> transaction =
        XposedHelpers.findClass("com.honeyspace.transition.utils.SurfaceTransaction", loader);
    XC_MethodHook start =
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            try {
              if (!enabled) return;
              Session previous = sessions.remove(p.thisObject);
              if (previous != null) end(previous);
              Session s = new Session();
              s.id = SystemClock.elapsedRealtimeNanos();
              s.started = SystemClock.uptimeMillis();
              s.opening = XposedHelpers.getBooleanField(p.args[0], "isForward");
              s.spring = p.method.getName().equals("startSpring");
              sessions.put(p.thisObject, s);
            } catch (Throwable error) {
              fail(error);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            try {
              Session s = sessions.get(p.thisObject);
              if (s == null || !enabled) return;
              AnimatorSet animator =
                  (AnimatorSet) XposedHelpers.callMethod(p.args[0], "getAnimatorSet");
              if (!s.spring) {
                Object params = XposedHelpers.getObjectField(p.thisObject, "appTransitionParams");
                Object opening = XposedHelpers.callMethod(params, "getOpenParams");
                s.duration =
                    ((Number) XposedHelpers.callMethod(opening, "getWindowPositionDuration"))
                            .floatValue()
                        * ValueAnimator.getDurationScale();
              }
              animator.addListener(
                  new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator ignored) {
                      end(s);
                      animator.removeListener(this);
                    }

                    @Override
                    public void onAnimationCancel(Animator ignored) {
                      end(s);
                    }
                  });
            } catch (Throwable error) {
              fail(error);
            }
          }
        };
    hooks.add(XposedHelpers.findAndHookMethod(player, "start", input, boolean.class, start));
    hooks.add(XposedHelpers.findAndHookMethod(player, "startSpring", input, desktop, start));
    hooks.add(
        XposedHelpers.findAndHookMethod(
            player,
            "makeUpdateInfo",
            transaction,
            new XC_MethodHook() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                try {
                  if (!enabled || endpoint == null || p.hasThrowable()) return;
                  Session s = sessions.get(p.thisObject);
                  if (s == null || s.ended) return;
                  if (!s.verified) {
                    if (!fullscreen(p.thisObject)) return;
                    s.verified = true;
                  }
                  long now = SystemClock.uptimeMillis();
                  if (now == s.lastSent) return;
                  s.lastSent = now;
                  Object info = p.getResult();
                  PointF point = (PointF) XposedHelpers.getObjectField(info, "point");
                  Rect crop = (Rect) XposedHelpers.getObjectField(info, "crop");
                  float scale = XposedHelpers.getFloatField(info, "scale");
                  float width = crop.width() * scale;
                  float remaining = -1;
                  if (!s.spring && s.duration > 0)
                    remaining = Math.max(0, s.duration - (now - s.started));
                  else if (s.lastWidth > 0 && now > s.lastTime && width > s.lastWidth) {
                    float full = context.getResources().getDisplayMetrics().widthPixels;
                    remaining =
                        Math.max(0, (full - width) / ((width - s.lastWidth) / (now - s.lastTime)));
                  }
                  s.lastWidth = width;
                  s.lastTime = now;
                  send(
                      new MotionFrame(
                          s.id,
                          now,
                          s.opening,
                          point.x + crop.left * scale,
                          point.y + crop.top * scale,
                          width,
                          crop.height() * scale,
                          XposedHelpers.getFloatField(info, "alpha"),
                          remaining,
                          XposedHelpers.getFloatField(info, "cornerRadius") * scale,
                          false));
                } catch (Throwable error) {
                  fail(error);
                }
              }
            }));
    XC_MethodHook finish =
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Session s = sessions.get(p.thisObject);
            if (s != null) end(s);
          }
        };
    hooks.add(XposedHelpers.findAndHookMethod(player, "end", finish));
    hooks.add(XposedHelpers.findAndHookMethod(player, "finish", finish));
    XposedBridge.log("GalaxyBar: One UI Home window motion hooks installed");
  }

  private boolean fullscreen(Object player) {
    if (XposedHelpers.getBooleanField(player, "isMultiTargets")) return false;
    Object targets = XposedHelpers.getObjectField(player, "finalRemoteTargets");
    if (targets == null) return false;
    Object[] apps = (Object[]) XposedHelpers.callMethod(targets, "getApps");
    if (apps == null) return false;
    for (Object target : apps) {
      Object info = XposedHelpers.getObjectField(target, "taskInfo");
      if (info == null || XposedHelpers.getIntField(info, "displayId") != 0) continue;
      Object configuration = XposedHelpers.getObjectField(target, "windowConfiguration");
      if ((Integer) XposedHelpers.callMethod(configuration, "getWindowingMode") == 1
          && (Integer) XposedHelpers.callMethod(configuration, "getActivityType") == 1) return true;
    }
    return false;
  }

  private void send(MotionFrame frame) {
    IBinder target = endpoint;
    if (target == null) return;
    Parcel data = Parcel.obtain();
    try {
      frame.write(data);
      if (!target.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY))
        endpoint = null;
    } catch (RemoteException error) {
      endpoint = null;
    } finally {
      data.recycle();
    }
  }

  private void end(Session s) {
    if (s.ended) return;
    s.ended = true;
    send(new MotionFrame(s.id, SystemClock.uptimeMillis(), s.opening, 0, 0, 0, 0, 0, -1, 0, true));
  }

  private void disable() {
    enabled = false;
    for (Session s : sessions.values()) end(s);
    sessions.clear();
    for (var hook : hooks) {
      try {
        hook.unhook();
      } catch (Throwable error) {
        XposedBridge.log(error);
      }
    }
    hooks.clear();
  }

  private void fail(Throwable error) {
    disable();
    failed = true;
    XposedBridge.log("GalaxyBar: launcher motion disabled: " + error);
  }

  private static final class Session {
    long id, started, lastSent, lastTime;
    boolean opening, spring, verified, ended;
    float duration, lastWidth;
  }
}
