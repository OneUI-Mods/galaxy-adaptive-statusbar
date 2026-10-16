package dev.ryuya.galaxybar;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** SystemUI owns both the real bar and a short-lived, non-interactive rendering of that bar. */
final class StatusBarMotion {
  private final View root;
  private final Handler main;
  private final Consumer<Boolean> motionChanged;
  private final int launcherUid;
  private final AtomicReference<MotionFrame> pending = new AtomicReference<>();
  private final AtomicBoolean posted = new AtomicBoolean();
  private final Runnable drain = this::drain;
  private final Runnable watchdog = () -> finish("timeout");
  private final MotionTrack track = new MotionTrack();
  private final Runnable terminal = this::complete;
  private final Context context;
  private final WindowManager windows;
  private Overlay overlay;

  private record Leaf(View view, Matrix transform, Rect bounds, Rect clip) {}

  private List<Leaf> leaves = List.of();
  private final int[] origin = new int[2];
  private MotionTiming timing;
  private boolean opacityScheduled;
  private volatile MotionPalette.Source colorSource = MotionPalette.Source.SCREEN;
  private final Choreographer.FrameCallback opacityFrame = this::animateOpacity;
  private float originalAlpha;
  private volatile boolean active;
  private boolean enabled, closed, failed;
  private long updates, handoffs, completed;
  private volatile long draws;
  private volatile int colorSpans, textPalette;
  private String reason = "waiting for One UI Home";
  private final IBinder endpoint =
      new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
          if (code != FIRST_CALL_TRANSACTION) return false;
          if (Binder.getCallingUid() != launcherUid)
            throw new SecurityException("One UI Home required");
          MotionFrame frame = MotionFrame.read(data);
          pending.accumulateAndGet(frame, MotionTrack::newer);
          if (posted.compareAndSet(false, true)) main.post(drain);
          return true;
        }
      };

  StatusBarMotion(View root, Handler main, Consumer<Boolean> motionChanged) throws Exception {
    this.root = root;
    this.main = main;
    this.motionChanged = motionChanged;
    int windowType =
        XposedHelpers.getStaticIntField(
            WindowManager.LayoutParams.class, "TYPE_STATUS_BAR_SUB_PANEL");
    context = root.getContext().createWindowContext(root.getDisplay(), windowType, null);
    launcherUid = context.getPackageManager().getPackageUid("com.sec.android.app.launcher", 0);
    windows = context.getSystemService(WindowManager.class);
  }

  Bundle registration() {
    Bundle bundle = new Bundle();
    bundle.putBinder("motion_endpoint", endpoint);
    return bundle;
  }

  boolean active() {
    return active;
  }

  void configure(boolean enabled) {
    if (this.enabled == enabled) return;
    this.enabled = enabled;
    failed = false;
    if (!enabled) cancel("disabled");
    else reason = "waiting for One UI Home";
  }

  private void complete() {
    if (!track.endDue(SystemClock.uptimeMillis())) return;
    if (timing != null && timing.running(SystemClock.uptimeMillis())) {
      main.postDelayed(terminal, 16);
    } else finish("finished");
  }

  private void drain() {
    MotionFrame frame = pending.getAndSet(null);
    posted.set(false);
    if (closed || frame == null) return;
    try {
      update(frame);
    } catch (Throwable error) {
      finish("unavailable");
      failed = true;
      XposedBridge.log("GalaxyBar: motion disabled: " + error);
      HookEntry.stopHooks(error);
    }
  }

  private void update(MotionFrame frame) {
    long now = SystemClock.uptimeMillis();
    int width = root.getResources().getDisplayMetrics().widthPixels;
    int height = root.getResources().getDisplayMetrics().heightPixels;
    MotionTrack.Change change = track.accept(frame, width, height, now);
    if (change == MotionTrack.Change.IGNORE) return;
    if (change == MotionTrack.Change.END) {
      main.removeCallbacks(watchdog);
      main.removeCallbacks(terminal);
      main.postDelayed(terminal, MotionTrack.END_GRACE_MS);
      return;
    }
    if (!enabled
        || failed
        || !ValueAnimator.areAnimatorsEnabled()
        || !context.getSystemService(PowerManager.class).isInteractive()
        || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
        || !root.isAttachedToWindow()
        || (!root.isShown() && leaves.isEmpty())
        || root.getDisplay() == null
        || root.getDisplay().getDisplayId() != 0
        || root.getDisplay().getRotation() != Surface.ROTATION_0) {
      cancel("paused");
      return;
    }
    main.removeCallbacks(terminal);
    if (!active || change == MotionTrack.Change.START) {
      // A replacement for the same task preserves the drawing and fade. A different
      // task must get fresh view placements, even if its animation overlaps in time.
      if (active) releaseDrawing();
      originalAlpha = root.getAlpha();
      timing = new MotionTiming(ValueAnimator.getDurationScale());
      active = true;
      colorSource = MotionPalette.Source.FROZEN;
      motionChanged.accept(true);
      if (leaves.isEmpty()) collectLeaves();
      if (leaves.isEmpty()) {
        cancel("no visible status bar content");
        return;
      }
      XposedBridge.log("GalaxyBar: motion begin " + (frame.opening() ? "open" : "close"));
    }
    updates++;
    MotionTiming.Opacity opacity =
        timing.update(
            frame.opening(),
            frame.width() / width,
            frame.remainingMs(),
            now,
            frame.overview(),
            frame.interactive());
    if (timing.handedOff() && overlay == null) {
      overlay = new Overlay();
      WindowManager.LayoutParams p =
          new WindowManager.LayoutParams(
              WindowManager.LayoutParams.MATCH_PARENT,
              WindowManager.LayoutParams.MATCH_PARENT,
              XposedHelpers.getStaticIntField(
                  WindowManager.LayoutParams.class, "TYPE_STATUS_BAR_SUB_PANEL"),
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                  | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                  | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                  | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
              PixelFormat.TRANSLUCENT);
      p.gravity = Gravity.TOP | Gravity.LEFT;
      p.setFitInsetsTypes(0);
      p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      p.setTitle("GalaxyBar transition");
      p.packageName = "com.android.systemui";
      // This is SystemUI's own visual-only surface, like its charging ripple. Give it
      // no input channel and the same trusted-overlay classification as native effects.
      XposedHelpers.callMethod(p, "setTrustedOverlay");
      XposedHelpers.setIntField(
          p,
          "inputFeatures",
          XposedHelpers.getIntField(p, "inputFeatures")
              | XposedHelpers.getStaticIntField(
                  WindowManager.LayoutParams.class, "INPUT_FEATURE_NO_INPUT_CHANNEL"));
      windows.addView(overlay, p);
      handoffs++;
      XposedBridge.log(
          "GalaxyBar: motion handoff x=" + frame.x() + " y=" + frame.y() + " w=" + frame.width());
    }
    applyOpacity(opacity, frame);
    scheduleOpacity();
    reason = frame.overview() ? "overview" : frame.opening() ? "opening" : "closing";
    main.removeCallbacks(watchdog);
    main.postDelayed(watchdog, 220);
  }

  private void applyOpacity(MotionTiming.Opacity opacity, MotionFrame frame) {
    if (overlay == null) return;
    overlay.opacity = opacity.moving() * frame.alpha();
    overlay.invalidate();
    root.setAlpha(originalAlpha * opacity.fixed());
    MotionPalette.Source next =
        MotionPalette.choose(timing.handedOff(), timing.openingLeg(), opacity);
    if (next != colorSource) {
      colorSource = next;
      motionChanged.accept(true);
    }
  }

  private void scheduleOpacity() {
    if (!opacityScheduled
        && active
        && overlay != null
        && timing.running(SystemClock.uptimeMillis())) {
      opacityScheduled = true;
      Choreographer.getInstance().postFrameCallback(opacityFrame);
    }
  }

  private void animateOpacity(long frameTime) {
    opacityScheduled = false;
    if (!active || overlay == null || timing == null || track.current() == null) return;
    try {
      applyOpacity(timing.opacity(SystemClock.uptimeMillis()), track.current());
      scheduleOpacity();
    } catch (Throwable error) {
      cancel("fade unavailable");
      HookEntry.stopHooks(error);
    }
  }

  void prepare(long now) {
    if (!enabled || active || closed || failed || root.getAlpha() < .99f || !root.isShown()) return;
    collectLeaves();
  }

  private void collectLeaves() {
    root.getLocationOnScreen(origin);
    List<Leaf> next = new ArrayList<>();
    collectLeaf(root, next);
    leaves = List.copyOf(next);
  }

  private void collectLeaf(View view, List<Leaf> result) {
    if (!view.isShown() || view.getAlpha() <= 0 || view.getWidth() <= 0 || view.getHeight() <= 0)
      return;
    String name = view.getClass().getName();
    boolean capsule = name.contains("OngoingChip");
    try {
      capsule |= view.getResources().getResourceEntryName(view.getId()).contains("capsule");
    } catch (Exception ignored) {
    }
    if (capsule
        || ModernTintAdapter.supports(view)
        || view instanceof TextView
        || view instanceof ImageView
        || !(view instanceof ViewGroup)) {
      Rect clip = new Rect();
      if (!view.getGlobalVisibleRect(clip)) return;
      Matrix transform = new Matrix();
      view.transformMatrixToGlobal(transform);
      RectF bounds = new RectF(0, 0, view.getWidth(), view.getHeight());
      transform.mapRect(bounds);
      Rect area = new Rect();
      bounds.roundOut(area);
      transform.postTranslate(-origin[0], -origin[1]);
      clip.offset(-origin[0], -origin[1]);
      result.add(new Leaf(view, transform, area, clip));
    } else {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) collectLeaf(group.getChildAt(i), result);
    }
  }

  List<View> sourceViews() {
    List<View> result = new ArrayList<>(leaves.size());
    for (Leaf leaf : leaves) if (leaf.view().isAttachedToWindow()) result.add(leaf.view());
    return result;
  }

  Rect nativeArea(View view) {
    for (Leaf leaf : leaves) if (leaf.view() == view) return new Rect(leaf.bounds());
    return null;
  }

  Rect mappedArea(Rect area) {
    MotionFrame f = track.current();
    if (!active || f == null) return new Rect(area);
    MotionGeometry.Bounds bounds =
        MotionGeometry.map(
            f, root.getWidth(), origin[0], origin[1], area.left, area.top, area.right, area.bottom);
    return new Rect(
        (int) Math.floor(bounds.left()),
        (int) Math.floor(bounds.top()),
        (int) Math.ceil(bounds.right()),
        (int) Math.ceil(bounds.bottom()));
  }

  MotionPalette.Source colorSource() {
    return colorSource;
  }

  Rect sampleCrop() {
    if (!active || track.current() == null) return null;
    if (colorSource == MotionPalette.Source.SCREEN) {
      return new Rect(
          origin[0], origin[1], origin[0] + root.getWidth(), origin[1] + root.getHeight());
    }
    Rect crop =
        mappedArea(
            new Rect(
                origin[0], origin[1], origin[0] + root.getWidth(), origin[1] + root.getHeight()));
    MotionFrame f = track.current();
    Rect window =
        new Rect(
            (int) Math.floor(f.x()),
            (int) Math.floor(f.y()),
            (int) Math.ceil(f.x() + f.width()),
            (int) Math.ceil(f.y() + f.height()));
    if (!crop.intersect(window)) return null;
    if (!crop.intersect(
        0,
        0,
        root.getResources().getDisplayMetrics().widthPixels,
        root.getResources().getDisplayMetrics().heightPixels)) return null;
    return crop;
  }

  SurfaceControl overlaySurface() {
    if (overlay == null) return null;
    Object viewRoot = XposedHelpers.callMethod(overlay, "getViewRootImpl");
    if (viewRoot == null) return null;
    return (SurfaceControl) XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
  }

  boolean waitingForSurface() {
    if (overlay == null) return false;
    SurfaceControl surface = overlaySurface();
    return surface == null || !surface.isValid();
  }

  void colorsChanged() {
    if (overlay != null) overlay.invalidate();
  }

  void colorState(int spans, int palette) {
    colorSpans = spans;
    textPalette = palette;
  }

  void cancel(String reason) {
    MotionFrame waiting = pending.getAndSet(null);
    if (waiting != null)
      track.accept(
          waiting,
          root.getResources().getDisplayMetrics().widthPixels,
          root.getResources().getDisplayMetrics().heightPixels,
          SystemClock.uptimeMillis());
    finish(reason);
    leaves = List.of();
  }

  private void finish(String reason) {
    if (active)
      XposedBridge.log(
          "GalaxyBar: motion restore "
              + reason
              + " overview="
              + (track.current() != null && track.current().overview()));
    main.removeCallbacks(watchdog);
    main.removeCallbacks(terminal);
    track.clear();
    releaseDrawing();
    this.reason = reason;
  }

  private void releaseDrawing() {
    Choreographer.getInstance().removeFrameCallback(opacityFrame);
    opacityScheduled = false;
    boolean changed = active;
    if (active) {
      root.setAlpha(originalAlpha);
      completed++;
    }
    active = false;
    colorSource = MotionPalette.Source.SCREEN;
    if (overlay != null) {
      try {
        windows.removeViewImmediate(overlay);
      } catch (Throwable error) {
        XposedBridge.log("GalaxyBar: motion cleanup: " + error);
      } finally {
        overlay = null;
      }
    }
    // The final native bar shows the same app/home background. Reject old geometry work,
    // but retain the current color interpolation and contrast history across this handoff.
    if (changed) motionChanged.accept(false);
  }

  String status() {
    return "Motion: "
        + reason
        + "; frames="
        + updates
        + "; handoffs="
        + handoffs
        + "; restored="
        + completed
        + "; live draws="
        + draws
        + "; opacity callbacks="
        + (opacityScheduled ? 1 : 0)
        + "; palette="
        + colorSource
        + "; color spans="
        + colorSpans
        + "; text palette="
        + textPalette;
  }

  void close() {
    closed = true;
    cancel("closed");
    main.removeCallbacks(drain);
  }

  private final class Overlay extends View {
    float opacity;
    private final Path clip = new Path();

    Overlay() {
      super(context);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      try {
        drawBar(canvas);
      } catch (Throwable error) {
        failed = true;
        main.post(
            () -> {
              cancel("drawing unavailable");
              HookEntry.stopHooks(error);
            });
        XposedBridge.log("GalaxyBar: motion draw: " + error);
      }
    }

    private void drawBar(Canvas canvas) {
      if (!canvas.isHardwareAccelerated())
        throw new IllegalStateException("Hardware canvas required");
      if (leaves.isEmpty() || track.current() == null || opacity <= 0) return;
      draws++;
      MotionFrame f = track.current();
      float scale = f.width() / root.getWidth();
      int save = canvas.save();
      clip.reset();
      clip.addRoundRect(
          f.x(),
          f.y(),
          f.x() + f.width(),
          f.y() + f.height(),
          f.radius(),
          f.radius(),
          Path.Direction.CW);
      canvas.clipPath(clip);
      canvas.translate(f.x(), f.y());
      canvas.scale(scale, scale);
      int layer =
          canvas.saveLayerAlpha(0, 0, root.getWidth(), root.getHeight(), Math.round(255 * opacity));
      // Draw the live source content directly. Parent visibility/alpha belongs to the fixed
      // bar; glyph spans, icon tint, battery paint, and their ongoing fades remain live.
      for (Leaf leaf : leaves) {
        if (!leaf.view().isAttachedToWindow()) continue;
        int child = canvas.save();
        canvas.clipRect(leaf.clip());
        canvas.concat(leaf.transform());
        leaf.view().draw(canvas);
        canvas.restoreToCount(child);
      }
      canvas.restoreToCount(layer);
      canvas.restoreToCount(save);
    }
  }
}
