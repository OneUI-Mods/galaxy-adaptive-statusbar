package dev.ryuya.galaxybar;

import android.animation.*;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.*;
import dev.ryuya.galaxybar.hook.*;
import java.util.*;
import java.util.concurrent.*;

/** Observes One UI's final task-window transform after both standard and Home Up calculators. */
final class LauncherMotion {
  private static final String PLAYER = "com.honeyspace.transition.anim.floating.PlayerImpl";
  private final Context context;
  private final ClassLoader loader;
  private final CrashGuard protection;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "GalaxyBarMotionConfig");
            t.setDaemon(true);
            return t;
          });
  private final Map<Object, Session> sessions = new WeakHashMap<>();
  private final List<HookCallback.Unhook> hooks = new ArrayList<>();
  private Session foreground;
  private final LauncherOverview overview;
  private volatile IBinder endpoint;
  private boolean enabled, failed;
  private int revision;
  private final Runnable heartbeat =
      () -> {
        try {
          heartbeat();
        } catch (Throwable error) {
          fail(error);
        }
      };
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
            failed = !protection.allowsHooks();
          }
          refresh();
        }
      };

  LauncherMotion(Context context, ClassLoader loader, CrashGuard protection) throws Exception {
    this.context = context;
    this.loader = loader;
    this.protection = protection;
    overview =
        new LauncherOverview(
            context,
            main,
            new LauncherOverview.Sink() {
              public void frame(
                  Object owner,
                  int task,
                  boolean opening,
                  boolean recent,
                  RectF rect,
                  float alpha,
                  float radius) {
                if (!enabled || endpoint == null) return;
                Session s = sessions.get(owner);
                if (s == null || s.ended || s != foreground) s = begin(owner);
                long now = SystemClock.uptimeMillis();
                s.lastSent = now;
                s.last =
                    new MotionFrame(
                        s.id,
                        now,
                        opening,
                        task,
                        recent,
                        recent,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height(),
                        Math.max(0, Math.min(1, alpha)),
                        -1,
                        Math.max(0, radius),
                        false);
                send(s.last);
              }

              public void end(Object owner) {
                Session s = sessions.get(owner);
                if (s != null) LauncherMotion.this.end(s);
              }
            },
            this::fail);
    if (context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode()
        != 1750305027L) {
      Hooks.log("GalaxyBar: unsupported One UI Home version; motion hooks not installed");
      return;
    }
    context.getContentResolver().registerContentObserver(ConfigProvider.URI, true, observer);
    context.registerReceiver(new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent intent) { refresh(); }
    }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED);
    refresh();
  }

  private void refresh() {
    if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
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
                    failed = !protection.allowsHooks();
                  } else if (!enabled && !failed && protection.allowsHooks()) {
                    try {
                      protection.arm();
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
    Class<?> player = Reflect.findClass(PLAYER, loader);
    Class<?> input =
        Reflect.findClass(
            "com.honeyspace.transition.anim.floating.entity.FloatingInputData", loader);
    Class<?> desktop =
        Reflect.findClass(
            "com.honeyspace.transition.anim.floating.entity.DesktopInputData", loader);
    Class<?> transaction =
        Reflect.findClass("com.honeyspace.transition.utils.SurfaceTransaction", loader);
    HookCallback start =
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            try {
              if (!enabled) return;
              overview.stop();
              Session s = begin(p.thisObject);
              s.input = p.args[0];
              s.opening = Reflect.getBooleanField(p.args[0], "isForward");
              s.spring = p.method.getName().equals("startSpring");
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
                  (AnimatorSet) Reflect.callMethod(p.args[0], "getAnimatorSet");
              if (!s.spring && s.duration <= 0) {
                Object params = Reflect.getObjectField(p.thisObject, "appTransitionParams");
                Object opening = Reflect.callMethod(params, "getOpenParams");
                s.duration =
                    ((Number) Reflect.callMethod(opening, "getWindowPositionDuration"))
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
    hooks.add(Reflect.findAndHookMethod(player, "start", input, boolean.class, start));
    hooks.add(Reflect.findAndHookMethod(player, "startSpring", input, desktop, start));
    hooks.add(
        Reflect.findAndHookMethod(
            player,
            "makeUpdateInfo",
            transaction,
            new HookCallback() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                try {
                  if (!enabled || endpoint == null || p.hasThrowable() || overview.ownsCard())
                    return;
                  Session s = sessions.get(p.thisObject);
                  if (s == null || s.ended || s != foreground) return;
                  if (!s.verified) {
                    if (!fullscreen(p.thisObject)) return;
                    s.verified = true;
                  }
                  long now = SystemClock.uptimeMillis();
                  if (now == s.lastSent) return;
                  Object info = p.getResult();
                  PointF point = (PointF) Reflect.getObjectField(info, "point");
                  Rect crop = (Rect) Reflect.getObjectField(info, "crop");
                  float scale = Reflect.getFloatField(info, "scale");
                  float width = crop.width() * scale;
                  float remaining = -1;
                  boolean opening = Reflect.getBooleanField(p.thisObject, "isForward");
                  if (opening != s.opening) {
                    s.opening = opening;
                    s.spring =
                        true; // A changed native target invalidates the old end-time estimate.
                  }
                  if (!s.spring && !s.interactive && s.duration > 0)
                    remaining = Math.max(0, s.duration - (now - s.started));
                  s.lastSent = now;
                  int task =
                      ((Number) Reflect.callMethod(p.thisObject, "getRunningTaskId"))
                          .intValue();
                  s.last =
                      new MotionFrame(
                          s.id,
                          now,
                          s.opening,
                          task,
                          s.interactive,
                          overview.followsTask(task),
                          point.x + crop.left * scale,
                          point.y + crop.top * scale,
                          width,
                          crop.height() * scale,
                          Reflect.getFloatField(info, "alpha"),
                          remaining,
                          Reflect.getFloatField(info, "cornerRadius") * scale,
                          false);
                  send(s.last);
                } catch (Throwable error) {
                  fail(error);
                }
              }
            }));
    HookCallback finish =
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Session s = sessions.get(p.thisObject);
            if (s != null) end(s);
          }
        };
    hooks.add(Reflect.findAndHookMethod(player, "end", finish));
    hooks.add(Reflect.findAndHookMethod(player, "finish", finish));
    // Catch/moving can run with the old AnimatorSet paused, before any start/startSpring call.
    HookCallback gesture =
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            try {
              if (!enabled) return;
              Session s = sessions.get(p.thisObject);
              if (s == null || s.ended || s != foreground) s = begin(p.thisObject);
              foreground = s;
              s.interactive = true;
              s.spring = true;
              main.removeCallbacks(heartbeat);
              main.postDelayed(heartbeat, 75);
            } catch (Throwable error) {
              fail(error);
            }
          }
        };
    hooks.add(
        Reflect.findAndHookMethod(player, "catch", RectF.class, boolean.class, gesture));
    hooks.add(
        Reflect.findAndHookMethod(
            player, "moving", RectF.class, float.class, float.class, float.class, gesture));
    Class<?> valuePlayer =
        Reflect.findClass(
            "com.honeyspace.transition.anim.floating.animator.ValueAnimPlayer", loader);
    Class<?> function = Reflect.findClass("kotlin.jvm.functions.Function1", loader);
    hooks.add(
        Reflect.findAndHookMethod(
            valuePlayer,
            "play",
            function,
            new HookCallback() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                try {
                  if (!enabled || foreground == null) return;
                  Object calculator = Reflect.getObjectField(p.thisObject, "calculator");
                  if (Reflect.callMethod(calculator, "getInput") != foreground.input) return;
                  foreground.duration =
                      ((Number) Reflect.callMethod(calculator, "getPositionDuration"))
                              .floatValue()
                          * ValueAnimator.getDurationScale();
                } catch (Throwable error) {
                  fail(error);
                }
              }
            }));
    overview.install(loader, hooks);
    Hooks.log("GalaxyBar: One UI Home window motion hooks installed");
  }

  private boolean fullscreen(Object player) {
    if (Reflect.getBooleanField(player, "isMultiTargets")) return false;
    Object targets = Reflect.getObjectField(player, "finalRemoteTargets");
    if (targets == null) return false;
    Object[] apps = (Object[]) Reflect.callMethod(targets, "getApps");
    if (apps == null) return false;
    for (Object target : apps) {
      Object info = Reflect.getObjectField(target, "taskInfo");
      if (info == null || Reflect.getIntField(info, "displayId") != 0) continue;
      Object configuration = Reflect.getObjectField(target, "windowConfiguration");
      if ((Integer) Reflect.callMethod(configuration, "getWindowingMode") == 1
          && (Integer) Reflect.callMethod(configuration, "getActivityType") == 1) return true;
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
    } catch (RemoteException | RuntimeException error) {
      endpoint = null;
    } finally {
      data.recycle();
    }
  }

  private void end(Session s) {
    if (s.ended) return;
    s.ended = true;
    send(
        new MotionFrame(
            s.id,
            SystemClock.uptimeMillis(),
            s.opening,
            0,
            false,
            false,
            0,
            0,
            0,
            0,
            0,
            -1,
            0,
            true));
  }

  private Session begin(Object player) {
    Session previous = sessions.remove(player);
    if (previous != null) end(previous);
    Session s = new Session();
    s.id = SystemClock.elapsedRealtimeNanos();
    s.started = SystemClock.uptimeMillis();
    sessions.put(player, s);
    foreground = s;
    return s;
  }

  private void heartbeat() {
    if (!enabled) return;
    if (!context.getSystemService(PowerManager.class).isInteractive()
        || context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) {
      for (Session s : sessions.values()) if (s.interactive) end(s);
      return;
    }
    boolean again = false;
    long now = SystemClock.uptimeMillis();
    for (Session s : sessions.values()) {
      if (!s.interactive || s.ended || s != foreground) continue;
      again = true;
      if (s.last == null || now - s.lastSent < 60) continue;
      MotionFrame f = s.last;
      send(
          new MotionFrame(
              f.session(),
              now,
              f.opening(),
              f.taskId(),
              true,
              f.overview(),
              f.x(),
              f.y(),
              f.width(),
              f.height(),
              f.alpha(),
              -1,
              f.radius(),
              false));
      s.lastSent = now;
    }
    if (again) main.postDelayed(heartbeat, 75);
  }

  private void disable() {
    enabled = false;
    overview.stop();
    main.removeCallbacks(heartbeat);
    for (Session s : sessions.values()) end(s);
    sessions.clear();
    foreground = null;
    for (var hook : hooks) {
      try {
        hook.unhook();
      } catch (Throwable error) {
        Hooks.log(error);
      }
    }
    hooks.clear();
  }

  private void fail(Throwable error) {
    disable();
    failed = true;
    protection.initializationFailed(error);
    Hooks.log("GalaxyBar: launcher motion disabled: " + error);
  }

  private static final class Session {
    long id, started, lastSent;
    boolean opening, spring, verified, ended, interactive;
    float duration;
    MotionFrame last;
    Object input;
  }
}
