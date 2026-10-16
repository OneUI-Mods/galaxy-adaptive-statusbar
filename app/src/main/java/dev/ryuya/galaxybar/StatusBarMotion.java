package dev.ryuya.galaxybar;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** SystemUI owns both the real bar and a short-lived, non-interactive rendering of that bar. */
final class StatusBarMotion {
  private final View root;
  private final Handler main;
  private final Runnable pauseCapture;
  private final int launcherUid;
  private final AtomicReference<MotionFrame> pending = new AtomicReference<>();
  private final AtomicBoolean posted = new AtomicBoolean();
  private final Runnable drain = this::drain;
  private final Runnable watchdog = () -> finish("timeout", false);
  private final Context context;
  private final WindowManager windows;
  private Overlay overlay;
  private RenderNode snapshot;
  private MotionTiming timing;
  private MotionFrame current;
  private long session, endedSession;
  private float originalAlpha;
  private volatile boolean active;
  private boolean enabled, closed, failed;
  private long updates, handoffs, completed;
  private String reason = "waiting for One UI Home";
  private final IBinder endpoint =
      new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
          if (code != FIRST_CALL_TRANSACTION) return false;
          if (Binder.getCallingUid() != launcherUid)
            throw new SecurityException("One UI Home required");
          MotionFrame frame = MotionFrame.read(data);
          pending.set(frame);
          if (posted.compareAndSet(false, true)) main.post(drain);
          return true;
        }
      };

  StatusBarMotion(View root, Handler main, Runnable pauseCapture) throws Exception {
    this.root = root;
    this.main = main;
    this.pauseCapture = pauseCapture;
    context = root.getContext();
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
  }

  private void drain() {
    MotionFrame frame = pending.getAndSet(null);
    posted.set(false);
    if (closed || frame == null) return;
    try {
      update(frame);
    } catch (Throwable error) {
      finish("unavailable", true);
      failed = true;
      XposedBridge.log("GalaxyBar: motion disabled: " + error);
    }
  }

  private void update(MotionFrame frame) {
    long now = SystemClock.uptimeMillis();
    int width = root.getResources().getDisplayMetrics().widthPixels;
    int height = root.getResources().getDisplayMetrics().heightPixels;
    if (!frame.valid(width, height, now) || frame.session() <= endedSession) return;
    if (frame.end()) {
      if (frame.session() >= session) {
        endedSession = frame.session();
        finish("finished", false);
      }
      return;
    }
    if (!enabled
        || failed
        || !ValueAnimator.areAnimatorsEnabled()
        || !context.getSystemService(PowerManager.class).isInteractive()
        || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
        || !root.isAttachedToWindow()
        || !root.isShown()
        || root.getDisplay() == null
        || root.getDisplay().getDisplayId() != 0
        || root.getDisplay().getRotation() != Surface.ROTATION_0) {
      cancel("paused");
      return;
    }
    if (frame.session() < session) return;
    if (!active || session != frame.session()) {
      finish("interrupted", false);
      session = frame.session();
      originalAlpha = root.getAlpha();
      timing = new MotionTiming();
      active = true;
      pauseCapture.run();
      XposedBridge.log("GalaxyBar: motion begin " + (frame.opening() ? "open" : "close"));
    }
    updates++;
    current = frame;
    MotionTiming.Opacity opacity =
        timing.update(frame.opening(), frame.width() / width, frame.remainingMs(), now);
    if (timing.handedOff() && overlay == null) {
      captureBar();
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
      windows.addView(overlay, p);
      handoffs++;
      XposedBridge.log(
          "GalaxyBar: motion handoff x=" + frame.x() + " y=" + frame.y() + " w=" + frame.width());
    }
    if (overlay != null) {
      overlay.opacity = opacity.moving() * frame.alpha();
      overlay.invalidate();
      root.setAlpha(originalAlpha * opacity.fixed());
    }
    reason = frame.opening() ? "opening" : "closing";
    main.removeCallbacks(watchdog);
    main.postDelayed(watchdog, 220);
  }

  private void captureBar() {
    snapshot = new RenderNode("GalaxyBar snapshot");
    snapshot.setPosition(0, 0, root.getWidth(), root.getHeight());
    snapshot.setClipToBounds(true);
    Canvas canvas = snapshot.beginRecording(root.getWidth(), root.getHeight());
    try {
      root.draw(canvas);
    } finally {
      snapshot.endRecording();
    }
  }

  void cancel(String reason) {
    MotionFrame waiting = pending.getAndSet(null);
    if (waiting != null) endedSession = Math.max(endedSession, waiting.session());
    finish(reason, true);
  }

  private void finish(String reason, boolean suppressSession) {
    main.removeCallbacks(watchdog);
    if (suppressSession) endedSession = Math.max(endedSession, session);
    if (active) {
      root.setAlpha(originalAlpha);
      completed++;
    }
    active = false;
    if (overlay != null) {
      try {
        windows.removeViewImmediate(overlay);
      } catch (Throwable error) {
        XposedBridge.log("GalaxyBar: motion cleanup: " + error);
      } finally {
        overlay = null;
      }
    }
    if (snapshot != null) snapshot.discardDisplayList();
    snapshot = null;
    current = null;
    this.reason = reason;
  }

  String status() {
    return "Motion: "
        + reason
        + "; frames="
        + updates
        + "; handoffs="
        + handoffs
        + "; restored="
        + completed;
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
        main.post(() -> cancel("drawing unavailable"));
        XposedBridge.log("GalaxyBar: motion draw: " + error);
      }
    }

    private void drawBar(Canvas canvas) {
      if (snapshot == null || current == null || opacity <= 0 || !canvas.isHardwareAccelerated())
        return;
      MotionFrame f = current;
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
      canvas.drawRenderNode(snapshot);
      canvas.restoreToCount(layer);
      canvas.restoreToCount(save);
    }
  }
}
