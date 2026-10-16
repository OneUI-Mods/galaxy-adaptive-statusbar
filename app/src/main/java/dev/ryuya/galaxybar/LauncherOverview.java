package dev.ryuya.galaxybar;

import android.animation.*;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import de.robv.android.xposed.*;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.Consumer;

/** Observes the actual overview card and Samsung's separate task-launch surface transform. */
final class LauncherOverview {
  interface Sink {
    void frame(
        Object owner,
        int task,
        boolean opening,
        boolean overview,
        RectF rect,
        float alpha,
        float radius);

    void end(Object owner);
  }

  private final Context context;
  private final Handler main;
  private final Sink sink;
  private final Consumer<Throwable> failure;
  private WeakReference<View> card = new WeakReference<>(null);
  private ViewTreeObserver observer;
  private int taskId = -1;
  private boolean overview;
  private Object launch;
  private int launchTask = -1;
  private final Runnable heartbeat = () -> protect(this::refreshCard);
  private final ViewTreeObserver.OnPreDrawListener preDraw =
      () -> {
        protect(this::refreshCard);
        return true;
      };

  LauncherOverview(Context context, Handler main, Sink sink, Consumer<Throwable> failure) {
    this.context = context;
    this.main = main;
    this.sink = sink;
    this.failure = failure;
  }

  void install(ClassLoader loader, List<XC_MethodHook.Unhook> hooks) {
    Class<?> manager =
        XposedHelpers.findClass(
            "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager", loader);
    hooks.add(
        XposedHelpers.findAndHookMethod(
            manager,
            "startRecentEntering",
            new XC_MethodHook() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      stop();
                      taskId =
                          ((Number) XposedHelpers.callMethod(p.thisObject, "getTaskId")).intValue();
                      overview = true;
                      XposedBridge.log("GalaxyBar: overview entering task=" + taskId);
                    });
              }
            }));
    hooks.add(
        XposedHelpers.findAndHookMethod(
            manager,
            "startHomeEntering",
            new XC_MethodHook() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                protect(LauncherOverview.this::stop);
              }
            }));
    Class<?> scene =
        XposedHelpers.findClass("com.honeyspace.ui.common.taskScene.TaskSceneView", loader);
    hooks.add(
        XposedHelpers.findAndHookMethod(
            scene,
            "onDraw",
            Canvas.class,
            new XC_MethodHook() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      if (!overview || taskId < 0 || !((View) p.thisObject).isShown()) return;
                      if (!(Boolean) XposedHelpers.callMethod(p.thisObject, "hasTaskId", taskId))
                        return;
                      if (((List<?>) XposedHelpers.callMethod(p.thisObject, "getTasks")).size()
                          != 1) return;
                      View next = (View) p.thisObject;
                      if (card.get() != next) {
                        detach();
                        card = new WeakReference<>(next);
                        observer = next.getRootView().getViewTreeObserver();
                        observer.addOnPreDrawListener(preDraw);
                        XposedBridge.log("GalaxyBar: overview card connected task=" + taskId);
                      }
                      refreshCard();
                    });
              }
            }));
    Class<?> transition =
        XposedHelpers.findClass("com.honeyspace.transition.utils.TaskLaunchTransition", loader);
    hooks.addAll(
        XposedBridge.hookAllMethods(
            transition,
            "createTaskLeashAnimator",
            new XC_MethodHook() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      stop();
                      if ((Integer) p.args[9] != 0) return;
                      Object targets = p.args[3];
                      Object target = XposedHelpers.callMethod(targets, "getFirstAppTarget");
                      if (target == null) return;
                      Object config = XposedHelpers.getObjectField(target, "windowConfiguration");
                      if ((Integer) XposedHelpers.callMethod(config, "getWindowingMode") != 1
                          || (Integer) XposedHelpers.callMethod(config, "getActivityType") != 1)
                        return;
                      Object info = XposedHelpers.getObjectField(target, "taskInfo");
                      if (info == null || XposedHelpers.getIntField(info, "displayId") != 0) return;
                      launchTask = XposedHelpers.getIntField(target, "taskId");
                      XposedBridge.log("GalaxyBar: overview app launch task=" + launchTask);
                      launch = new Object();
                      p.setObjectExtra("galaxybar.launch", launch);
                      sink.frame(
                          launch,
                          launchTask,
                          true,
                          false,
                          new RectF((RectF) p.args[5]),
                          1,
                          (Float) p.args[2]);
                    });
              }

              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      Object owner = p.getObjectExtra("galaxybar.launch");
                      if (owner == null
                          || p.hasThrowable()
                          || !(p.getResult() instanceof Animator animation)) return;
                      animation.addListener(
                          new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator ignored) {
                              protect(
                                  () -> {
                                    sink.end(owner);
                                    if (launch == owner) {
                                      launch = null;
                                      launchTask = -1;
                                    }
                                    animation.removeListener(this);
                                  });
                            }

                            @Override
                            public void onAnimationCancel(Animator ignored) {
                              onAnimationEnd(ignored);
                            }
                          });
                    });
              }
            }));
    hooks.addAll(
        XposedBridge.hookAllMethods(
            transition,
            "createSurfaceParams",
            new XC_MethodHook() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      if (launch == null || launchTask < 0 || p.hasThrowable()) return;
                      Rect crop = (Rect) XposedHelpers.getStaticObjectField(transition, "cropRect");
                      Matrix matrix =
                          (Matrix) XposedHelpers.getStaticObjectField(transition, "matrix");
                      RectF rect = new RectF(crop);
                      matrix.mapRect(rect);
                      float start =
                          XposedHelpers.getStaticFloatField(transition, "initialWindowRadius");
                      float end =
                          XposedHelpers.getStaticFloatField(transition, "finalWindowRadius");
                      float fraction = (Float) p.args[1];
                      float radius =
                          (start + (end - start) * fraction)
                              * rect.width()
                              / Math.max(1, crop.width());
                      sink.frame(
                          launch,
                          launchTask,
                          true,
                          false,
                          rect,
                          XposedHelpers.getStaticFloatField(transition, "leashAlpha"),
                          radius);
                    });
              }
            }));
  }

  boolean ownsCard() {
    return overview && card.get() != null;
  }

  private void refreshCard() {
    main.removeCallbacks(heartbeat);
    View view = card.get();
    if (!overview || view == null) return;
    if (!view.isAttachedToWindow()
        || !view.isShown()
        || !context.getSystemService(PowerManager.class).isInteractive()
        || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
      stop();
      return;
    }
    Object state = XposedHelpers.callMethod(view, "getSceneStateInfo");
    float ratio = view.getWidth() / (float) Math.max(1, view.getHeight());
    List<?> bounds = (List<?>) XposedHelpers.callMethod(state, "getDestBgCropBounds", ratio, 0f);
    if (bounds.size() != 1) return;
    RectF local = new RectF((RectF) bounds.get(0));
    if (local.isEmpty()) return;
    Matrix matrix = new Matrix();
    view.transformMatrixToGlobal(matrix);
    RectF rect = new RectF(local);
    matrix.mapRect(rect);
    List<?> radii = (List<?>) XposedHelpers.callMethod(view, "getBackgroundCornerRadii");
    if (radii.size() != 1) return;
    float[] corners = (float[]) radii.get(0);
    float alpha = view.getAlpha();
    ViewParent parent = view.getParent();
    while (parent instanceof View ancestor) {
      alpha *= ancestor.getAlpha();
      parent = ancestor.getParent();
    }
    sink.frame(view, taskId, false, true, rect, alpha, corners[0] * rect.width() / local.width());
    // Traversals supply moving frames; a stationary card only needs a lightweight liveness pulse.
    main.postDelayed(heartbeat, 75);
  }

  private void detach() {
    if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
    observer = null;
    main.removeCallbacks(heartbeat);
  }

  void stop() {
    overview = false;
    View previous = card.get();
    if (previous != null) sink.end(previous);
    card.clear();
    detach();
    if (launch != null) sink.end(launch);
    launch = null;
    launchTask = -1;
    taskId = -1;
  }

  private void protect(Runnable task) {
    try {
      task.run();
    } catch (Throwable error) {
      failure.accept(error);
    }
  }
}
