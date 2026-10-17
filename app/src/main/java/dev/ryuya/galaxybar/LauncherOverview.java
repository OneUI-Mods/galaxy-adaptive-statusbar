package dev.ryuya.galaxybar;

import android.animation.*;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import dev.ryuya.galaxybar.hook.*;
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

  private record CardGeometry(RectF rect, float alpha, float radius) {}

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

  void install(ClassLoader loader, List<HookCallback.Unhook> hooks) {
    Class<?> manager =
        Reflect.findClass(
            "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager", loader);
    hooks.add(
        Reflect.findAndHookMethod(
            manager,
            "startRecentEntering",
            new HookCallback() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      stop();
                      taskId =
                          ((Number) Reflect.callMethod(p.thisObject, "getTaskId")).intValue();
                      overview = true;
                      Hooks.log("GalaxyBar: overview entering task=" + taskId);
                    });
              }
            }));
    hooks.add(
        Reflect.findAndHookMethod(
            manager,
            "startHomeEntering",
            new HookCallback() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                protect(LauncherOverview.this::stop);
              }
            }));
    Class<?> scene =
        Reflect.findClass("com.honeyspace.ui.common.taskScene.TaskSceneView", loader);
    hooks.add(
        Reflect.findAndHookMethod(
            scene,
            "onDraw",
            Canvas.class,
            new HookCallback() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      if (!overview || taskId < 0 || !((View) p.thisObject).isShown()) return;
                      if (!(Boolean) Reflect.callMethod(p.thisObject, "hasTaskId", taskId))
                        return;
                      if (((List<?>) Reflect.callMethod(p.thisObject, "getTasks")).size()
                          != 1) return;
                      View next = (View) p.thisObject;
                      if (card.get() != next) {
                        // A TaskSceneView can draw before its background crop is ready.
                        // Do not suppress the native animation until we can send a card frame.
                        CardGeometry geometry = geometry(next);
                        if (geometry == null || geometry.alpha() <= 0) return;
                        detach();
                        card = new WeakReference<>(next);
                        observer = next.getRootView().getViewTreeObserver();
                        observer.addOnPreDrawListener(preDraw);
                        Hooks.log(
                            "GalaxyBar: overview card connected task="
                                + taskId
                                + " alpha="
                                + geometry.alpha());
                        publish(next, geometry);
                      } else {
                        refreshCard();
                      }
                    });
              }
            }));
    Class<?> transition =
        Reflect.findClass("com.honeyspace.transition.utils.TaskLaunchTransition", loader);
    hooks.addAll(
        Hooks.hookAllMethods(
            transition,
            "createTaskLeashAnimator",
            new HookCallback() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      stop();
                      if ((Integer) p.args[9] != 0) return;
                      Object targets = p.args[3];
                      Object target = Reflect.callMethod(targets, "getFirstAppTarget");
                      if (target == null) return;
                      Object config = Reflect.getObjectField(target, "windowConfiguration");
                      if ((Integer) Reflect.callMethod(config, "getWindowingMode") != 1
                          || (Integer) Reflect.callMethod(config, "getActivityType") != 1)
                        return;
                      Object info = Reflect.getObjectField(target, "taskInfo");
                      if (info == null || Reflect.getIntField(info, "displayId") != 0) return;
                      launchTask = Reflect.getIntField(target, "taskId");
                      Hooks.log("GalaxyBar: overview app launch task=" + launchTask);
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
        Hooks.hookAllMethods(
            transition,
            "createSurfaceParams",
            new HookCallback() {
              @Override
              protected void afterHookedMethod(MethodHookParam p) {
                protect(
                    () -> {
                      if (launch == null || launchTask < 0 || p.hasThrowable()) return;
                      Rect crop = (Rect) Reflect.getStaticObjectField(transition, "cropRect");
                      Matrix matrix =
                          (Matrix) Reflect.getStaticObjectField(transition, "matrix");
                      RectF rect = new RectF(crop);
                      matrix.mapRect(rect);
                      float start =
                          Reflect.getStaticFloatField(transition, "initialWindowRadius");
                      float end =
                          Reflect.getStaticFloatField(transition, "finalWindowRadius");
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
                          Reflect.getStaticFloatField(transition, "leashAlpha"),
                          radius);
                    });
              }
            }));
  }

  boolean followsTask(int task) {
    return overview && taskId == task;
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
    CardGeometry geometry = geometry(view);
    if (geometry == null) return;
    publish(view, geometry);
  }

  private CardGeometry geometry(View view) {
    Object state = Reflect.callMethod(view, "getSceneStateInfo");
    float ratio = view.getWidth() / (float) Math.max(1, view.getHeight());
    List<?> bounds = (List<?>) Reflect.callMethod(state, "getDestBgCropBounds", ratio, 0f);
    if (bounds.size() != 1) return null;
    RectF local = new RectF((RectF) bounds.get(0));
    if (local.isEmpty()) return null;
    Matrix matrix = new Matrix();
    view.transformMatrixToGlobal(matrix);
    RectF rect = new RectF(local);
    matrix.mapRect(rect);
    List<?> radii = (List<?>) Reflect.callMethod(view, "getBackgroundCornerRadii");
    if (radii.size() != 1) return null;
    float[] corners = (float[]) radii.get(0);
    if (corners.length == 0) return null;
    float alpha = view.getAlpha();
    ViewParent parent = view.getParent();
    while (parent instanceof View ancestor) {
      alpha *= ancestor.getAlpha();
      parent = ancestor.getParent();
    }
    return new CardGeometry(rect, alpha, corners[0] * rect.width() / local.width());
  }

  private void publish(View view, CardGeometry geometry) {
    sink.frame(view, taskId, false, true, geometry.rect(), geometry.alpha(), geometry.radius());
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
