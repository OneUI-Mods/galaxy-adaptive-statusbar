package dev.ryuya.galaxybar;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import de.robv.android.xposed.*;
import java.io.*;
import java.text.BreakIterator;
import java.util.*;
import java.util.concurrent.*;

/** UI thread snapshots geometry; one worker captures and analyzes; UI thread applies colors. */
final class Engine {
  private final View root;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable ticker = this::tick;
  private final BroadcastReceiver screenState =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
          if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            epoch++;
            restore();
            worker.execute(() -> releaseCapture());
          }
          main.removeCallbacks(ticker);
          main.post(ticker);
        }
      };
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "GalaxyBarCapture");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });
  private final IdentityHashMap<ImageView, ColorFilter> originals = new IdentityHashMap<>();
  private final IdentityHashMap<ImageView, Integer> appliedColors = new IdentityHashMap<>();
  private final Set<View> modern = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<Drawable> batteries = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<TextView> texts = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Map<String, Integer> previous = new ConcurrentHashMap<>();
  private final Map<String, ColorFade> fades = new HashMap<>();
  private final Choreographer choreographer = Choreographer.getInstance();
  private final Choreographer.FrameCallback fadeFrame =
      ignored -> {
        fadeScheduled = false;
        renderFade();
      };
  private boolean fadeScheduled;
  private List<Target> fadeTargets = Collections.emptyList();
  private final Rect fadeCrop = new Rect();
  private int fadeEpoch, fadeRotation;
  private int[] fadeColors = new int[0], fadePercentages = new int[0];
  private volatile Bundle config = new Bundle();
  private volatile boolean busy;
  private volatile int unchangedFrames;
  private int[] framePixels = new int[0];
  private int frameHash, capturedRotation = -1;
  private long lastConfig, lastStatus, lastDebug;
  private int errors, epoch;
  private boolean stopped, wasEnabled, closed, applying;
  private LayerCapture capture;
  private String state = "Hook active; module disabled", lastError = "";
  private double lastMs;
  private long frames, started = SystemClock.elapsedRealtime();

  private static final class BarSpan extends ForegroundColorSpan {
    BarSpan(int c) {
      super(c);
    }
  }

  private record Target(
      View view,
      Rect area,
      int start,
      int end,
      String text,
      String key,
      BatteryAdapter.Geometry battery) {
    Target(View view, Rect area, int start, int end, String text, String key) {
      this(view, area, start, end, text, key, null);
    }
  }

  Engine(View root) {
    this.root = root;
    context = root.getContext();
    worker.execute(() -> BatteryAdapter.validate(context));
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_SCREEN_ON);
    filter.addAction(Intent.ACTION_USER_PRESENT);
    context.registerReceiver(screenState, filter, Context.RECEIVER_NOT_EXPORTED);
    XposedBridge.log(
        "GalaxyBar: engine root="
            + root.getClass().getName()
            + " "
            + root.getWidth()
            + "x"
            + root.getHeight());
    main.post(ticker);
  }

  private void tick() {
    if (closed) return;
    try {
      if (!busy) {
        busy = true;
        long now = SystemClock.elapsedRealtime();
        boolean enabled = config.getBoolean("enabled");
        if (enabled != wasEnabled) {
          epoch++;
          stopped = false;
          errors = 0;
          wasEnabled = enabled;
          if (!enabled) restore();
        }
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        Rect crop = new Rect();
        boolean visible =
            root.isAttachedToWindow() && root.isShown() && root.getGlobalVisibleRect(crop);
        boolean allowed =
            enabled
                && !stopped
                && visible
                && power.isInteractive()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && crop.top == 0
                && crop.height() < root.getResources().getDisplayMetrics().heightPixels / 4;
        // Other rotations retain standard colors until the capture mapping is verified.
        allowed &=
            root.getDisplay() != null
                && root.getDisplay().getDisplayId() == 0
                && root.getDisplay().getRotation() == Surface.ROTATION_0;
        if (!allowed
            && (!originals.isEmpty()
                || !texts.isEmpty()
                || !modern.isEmpty()
                || !batteries.isEmpty())) {
          epoch++;
          restore();
        }
        List<Target> targets = new ArrayList<>();
        SurfaceControl surface = null;
        if (allowed) {
          collect(root, targets);
          Object vr = XposedHelpers.callMethod(root, "getViewRootImpl");
          surface = (SurfaceControl) XposedHelpers.callMethod(vr, "getSurfaceControl");
        }
        final SurfaceControl excluded = surface;
        final int generation = epoch;
        final boolean run = allowed;
        final int display = run ? root.getDisplay().getDisplayId() : 0;
        final int rotation = run ? root.getDisplay().getRotation() : 0;
        final Bundle settings = new Bundle(config);
        worker.execute(
            () ->
                process(
                    now, run, crop, excluded, display, rotation, targets, generation, settings));
      }
    } catch (Throwable t) {
      busy = false;
      fail(t);
    }
    int hz = config.getInt("hz", 10);
    if (hz != 5 && hz != 10 && hz != 15 && hz != 30) hz = 10;
    PowerManager p = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    if (p.isPowerSaveMode() || unchangedFrames >= 20) hz = 5;
    boolean asleep =
        !p.isInteractive() || context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    main.postDelayed(
        ticker,
        asleep ? 5000 : !config.getBoolean("enabled") || stopped ? 500 : Math.max(33, 1000 / hz));
  }

  private void process(
      long now,
      boolean run,
      Rect crop,
      SurfaceControl excluded,
      int display,
      int rotation,
      List<Target> targets,
      int generation,
      Bundle settings) {
    try {
      if (now - lastConfig > 750) {
        Bundle fresh = context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
        if (fresh != null) config = fresh;
        lastConfig = now;
      }
      run &=
          context.getSystemService(PowerManager.class).isInteractive()
              && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
      if (run && !targets.isEmpty()) {
        if (capturedRotation != rotation) {
          if (capture != null) capture.close();
          capture = null;
          capturedRotation = rotation;
          unchangedFrames = 0;
          previous.clear();
        }
        if (capture == null) capture = new LayerCapture();
        long start = SystemClock.elapsedRealtimeNanos();
        Bitmap bmp = capture.capture(display, crop, excluded);
        try {
          int size = bmp.getWidth() * bmp.getHeight();
          if (framePixels.length != size) framePixels = new int[size];
          bmp.getPixels(framePixels, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
          int hash = Arrays.hashCode(framePixels);
          unchangedFrames = hash == frameHash ? unchangedFrames + 1 : 0;
          frameHash = hash;
          int[] colors = new int[targets.size()];
          int[] percentageColors = new int[targets.size()];
          StringBuilder details = new StringBuilder();
          Set<String> liveKeys = new HashSet<>();
          for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            liveKeys.add(t.key);
            double[] values = samples(bmp, t.area, crop);
            int color =
                Contrast.choose(
                    values,
                    previous.getOrDefault(t.key, 0),
                    settings.getString("algorithm", "robust"),
                    settings.getDouble("hysteresis", .15));
            if (settings.getBoolean("fixed") && t.start >= 0 && isClock(t.view))
              color = t.start % 2 == 0 ? Color.WHITE : Color.BLACK;
            previous.put(t.key, color);
            colors[i] = color;
            if (t.battery != null) {
              String percentKey = t.key + ":percentage";
              liveKeys.add(percentKey);
              percentageColors[i] =
                  BatteryAdapter.percentageColor(
                      bmp,
                      crop,
                      t.area,
                      t.battery,
                      color,
                      previous.getOrDefault(percentKey, 0),
                      settings.getString("algorithm", "robust"),
                      settings.getDouble("hysteresis", .15));
              previous.put(percentKey, percentageColors[i]);
              if (settings.getBoolean("debug"))
                details
                    .append(percentKey)
                    .append(percentageColors[i] == Color.BLACK ? " black\n" : " white\n");
            }
            if (settings.getBoolean("debug")) {
              double avg = 0;
              for (double value : values) avg += value;
              details
                  .append(t.key)
                  .append(" L=")
                  .append(String.format(Locale.US, "%.3f", avg / values.length))
                  .append(color == Color.BLACK ? " black" : " white")
                  .append('\n');
            }
          }
          previous.keySet().retainAll(liveKeys);
          lastMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6;
          frames++;
          errors = 0;
          state =
              "Capture OK: "
                  + bmp.getWidth()
                  + "×"
                  + bmp.getHeight()
                  + "; targets="
                  + targets.size();
          main.post(
              () -> {
                Rect current = new Rect();
                if (epoch == generation
                    && config.getBoolean("enabled")
                    && !stopped
                    && root.getGlobalVisibleRect(current)
                    && current.equals(crop)
                    && root.getDisplay().getRotation() == rotation
                    && context.getSystemService(PowerManager.class).isInteractive()
                    && !context.getSystemService(KeyguardManager.class).isKeyguardLocked())
                  try {
                    transition(targets, colors, percentageColors, crop, generation, rotation);
                  } catch (Throwable e) {
                    fail(e);
                  }
              });
          if (settings.getBoolean("debug") && now - lastDebug > 3000) {
            File sample = new File(context.getCacheDir(), "galaxybar-background.png");
            try (FileOutputStream out = new FileOutputStream(sample)) {
              bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            XposedBridge.log("GalaxyBar: " + state + " ms=" + lastMs + "\n" + details);
            lastDebug = now;
          }
        } finally {
          bmp.recycle();
        }
      } else if (!settings.getBoolean("enabled")) state = "Hook active; module disabled";
      else if (stopped) state = "Stopped after consecutive errors; toggle module to retry";
      else state = "Paused: screen off/locked, status bar hidden, or unsupported display/rotation";
      if (!run) releaseCapture();
      if (now - lastStatus > 2000) {
        String text =
            state
                + "\nFrame "
                + frames
                + "; capture+analysis "
                + String.format(Locale.US, "%.1f", lastMs)
                + " ms\nSession rate "
                + String.format(Locale.US, "%.1f", frames * 1000.0 / Math.max(1, now - started))
                + " Hz\nBattery: "
                + BatteryAdapter.health()
                + "\nLast error: "
                + lastError;
        context.getContentResolver().call(ConfigProvider.URI, "status", text, null);
        lastStatus = now;
      }
    } catch (Throwable t) {
      main.post(() -> fail(t));
      if (capture != null && !(t instanceof LayerCapture.UnavailableException)) {
        capture.close();
        capture = null;
      }
    } finally {
      busy = false;
    }
  }

  private static double[] samples(Bitmap b, Rect area, Rect crop) {
    Rect r = new Rect(area);
    if (!r.intersect(crop)) throw new IllegalStateException("Target outside crop");
    int x0 = Math.max(0, (r.left - crop.left) / 2), y0 = Math.max(0, (r.top - crop.top) / 2);
    int x1 = Math.min(b.getWidth(), (r.right - crop.left + 1) / 2),
        y1 = Math.min(b.getHeight(), (r.bottom - crop.top + 1) / 2);
    double[] a = new double[Math.max(1, (x1 - x0) * (y1 - y0))];
    int n = 0;
    int step = Math.max(1, (int) Math.sqrt(a.length / 256.0));
    for (int y = y0; y < y1; y += step)
      for (int x = x0; x < x1; x += step) {
        int p = b.getPixel(x, y);
        if (Color.alpha(p) < 250)
          throw new IllegalStateException("Undefined/transparent background");
        a[n++] = Contrast.luminance(p);
      }
    if (n == 0) throw new IllegalStateException("Empty background");
    return Arrays.copyOf(a, n);
  }

  private void collect(View view, List<Target> out) {
    if (!view.isShown() || view.getAlpha() <= 0) return;
    String name = view.getClass().getName(), id = id(view);
    if (name.contains("OngoingChip") || id.contains("capsule")) return;
    if (ModernTintAdapter.supports(view)) {
      Rect area = new Rect();
      if (config.getBoolean("signals", true)
          && ModernTintAdapter.canRestore(view)
          && view.getGlobalVisibleRect(area)
          && !area.isEmpty()) out.add(new Target(view, area, -1, -1, "", key(view) + ":modern"));
      return;
    }
    if (view instanceof ImageView battery && BatteryAdapter.supports(battery)) {
      Rect area = new Rect();
      if (config.getBoolean("battery", true) && view.getGlobalVisibleRect(area) && !area.isEmpty())
        out.add(
            new Target(
                view, area, -1, -1, "", key(view) + ":battery", BatteryAdapter.geometry(battery)));
      return;
    }
    if (name.endsWith(".StatusBarIconView")
        && ((Integer) XposedHelpers.callMethod(view, "getVisibleState")) == 2) return;

    if (view instanceof TextView text
        && text.getLayout() != null
        && !text.getText().toString().isBlank()) {
      boolean clock = isClock(view);
      boolean battery = ancestor(view, "battery");
      if (clock
          ? config.getBoolean("clock", true)
          : battery ? config.getBoolean("battery", true) : config.getBoolean("signals", true))
        addText(text, out, clock);
    }
    if (view instanceof ImageView image && image.getDrawable() != null) {
      boolean battery = ancestor(view, "battery"),
          notification = ancestor(view, "notification_icon");
      boolean selected =
          !battery
              && (notification
                  ? config.getBoolean("notifications", true)
                  : config.getBoolean("signals", true));
      Rect r = new Rect();
      if (selected && view.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0)
        out.add(new Target(image, r, -1, -1, "", key(view) + ":image"));
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
  }

  private void addText(TextView text, List<Target> out, boolean characters) {
    String value = text.getText().toString();
    Layout layout = text.getLayout();
    int[] xy = new int[2];
    text.getLocationOnScreen(xy);
    Rect visible = new Rect();
    if (!text.getGlobalVisibleRect(visible)) return;
    BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
    iterator.setText(value);
    for (int start = iterator.first(), end = iterator.next();
        end != BreakIterator.DONE;
        start = end, end = iterator.next()) {
      if (!characters) {
        start = 0;
        end = value.length();
      }
      if (value.substring(start, end).isBlank()) {
        if (!characters) break;
        continue;
      }
      int line = layout.getLineForOffset(start);
      if (layout.getLineForOffset(end - 1) != line) continue;
      float left = layout.getPrimaryHorizontal(start), right = layout.getPrimaryHorizontal(end);
      Rect glyph = new Rect();
      text.getPaint().getTextBounds(value, start, end, glyph);
      int x = xy[0] + text.getTotalPaddingLeft() - text.getScrollX(),
          y = xy[1] + text.getTotalPaddingTop() - text.getScrollY() + layout.getLineBaseline(line);
      Rect r =
          new Rect(
              x + (int) Math.floor(Math.min(left, right)),
              y + glyph.top,
              x + (int) Math.ceil(Math.max(left, right)),
              y + glyph.bottom);
      if (r.intersect(visible) && !r.isEmpty())
        out.add(
            new Target(
                text,
                r,
                start,
                end,
                value,
                key(text) + ":" + start + ":" + value.substring(start, end)));
      if (!characters) break;
    }
  }

  private static String id(View v) {
    try {
      return v.getResources().getResourceEntryName(v.getId());
    } catch (Exception e) {
      return v.getClass().getSimpleName();
    }
  }

  private static boolean isClock(View view) {
    String name = view.getClass().getName();
    return name.endsWith(".Clock") || name.contains("QSClock") || id(view).contains("clock");
  }

  private static boolean ancestor(View v, String needle) {
    needle = needle.replace("_", "");
    for (int i = 0; v != null && i < 12; i++) {
      if (id(v).toLowerCase(Locale.ROOT).replace("_", "").contains(needle)) return true;
      v = v.getParent() instanceof View p ? p : null;
    }
    return false;
  }

  private static String key(View v) {
    return id(v) + "@" + System.identityHashCode(v);
  }

  private void transition(
      List<Target> targets,
      int[] colors,
      int[] percentages,
      Rect crop,
      int generation,
      int rotation) {
    long now = SystemClock.uptimeMillis();
    Set<String> live = new HashSet<>();
    for (int i = 0; i < targets.size(); i++) {
      Target target = targets.get(i);
      retarget(target.key, colors[i], now, live);
      if (target.battery != null) retarget(target.key + ":percentage", percentages[i], now, live);
    }
    fades.keySet().retainAll(live);
    fadeTargets = targets;
    fadeCrop.set(crop);
    fadeEpoch = generation;
    fadeRotation = rotation;
    if (fadeColors.length != targets.size()) {
      fadeColors = new int[targets.size()];
      fadePercentages = new int[targets.size()];
    }
    choreographer.removeFrameCallback(fadeFrame);
    fadeScheduled = false;
    renderFade();
  }

  private void retarget(String key, int color, long now, Set<String> live) {
    live.add(key);
    fades.computeIfAbsent(key, ignored -> new ColorFade(color)).setTarget(color, now);
  }

  private void renderFade() {
    if (fadeTargets.isEmpty()) return;
    Rect current = new Rect();
    if (closed
        || stopped
        || epoch != fadeEpoch
        || !config.getBoolean("enabled")
        || !root.isShown()
        || !root.getGlobalVisibleRect(current)
        || !current.equals(fadeCrop)
        || root.getDisplay() == null
        || root.getDisplay().getRotation() != fadeRotation
        || !context.getSystemService(PowerManager.class).isInteractive()
        || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
      restore();
      return;
    }
    try {
      long now = SystemClock.uptimeMillis();
      boolean animate = ValueAnimator.areAnimatorsEnabled(), running = false;
      for (int i = 0; i < fadeTargets.size(); i++) {
        Target target = fadeTargets.get(i);
        ColorFade body = fades.get(target.key);
        if (!animate) body.finish();
        fadeColors[i] = body.color(now);
        running |= body.running(now);
        if (target.battery != null) {
          ColorFade percentage = fades.get(target.key + ":percentage");
          if (!animate) percentage.finish();
          fadePercentages[i] = percentage.color(now);
          running |= percentage.running(now);
        }
      }
      apply(fadeTargets, fadeColors, fadePercentages);
      if (running && !fadeScheduled) {
        fadeScheduled = true;
        choreographer.postFrameCallback(fadeFrame);
      }
    } catch (Throwable error) {
      fail(error);
    }
  }

  private void apply(List<Target> targets, int[] colors, int[] percentageColors) {
    applying = true;
    try {
      Map<TextView, SpannableString> spans = new IdentityHashMap<>();
      Set<ImageView> live = Collections.newSetFromMap(new IdentityHashMap<>());
      Set<View> liveModern = Collections.newSetFromMap(new IdentityHashMap<>());
      Set<Drawable> liveBattery = Collections.newSetFromMap(new IdentityHashMap<>());
      for (int i = 0; i < targets.size(); i++) {
        Target t = targets.get(i);
        if (!t.view.isAttachedToWindow()) continue;
        if (t.battery != null) {
          if (((ImageView) t.view).getDrawable() == t.battery.drawable()) {
            liveBattery.add(t.battery.drawable());
            batteries.add(t.battery.drawable());
            BatteryAdapter.apply(t.battery, colors[i], percentageColors[i]);
          }
        } else if (ModernTintAdapter.supports(t.view)) {
          liveModern.add(t.view);
          modern.add(t.view);
          ModernTintAdapter.apply(t.view, colors[i]);
        } else if (t.view instanceof TextView text) {
          if (!text.getText().toString().equals(t.text)) continue;
          SpannableString s =
              spans.computeIfAbsent(
                  text,
                  v -> {
                    SpannableString a = new SpannableString(v.getText());
                    for (BarSpan span : a.getSpans(0, a.length(), BarSpan.class))
                      a.removeSpan(span);
                    return a;
                  });
          s.setSpan(new BarSpan(colors[i]), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (t.view instanceof ImageView image) {
          live.add(image);
          if (!originals.containsKey(image)) originals.put(image, image.getColorFilter());
          if (!Objects.equals(appliedColors.get(image), colors[i])) {
            image.setColorFilter(colors[i], PorterDuff.Mode.SRC_IN);
            appliedColors.put(image, colors[i]);
          }
        }
      }
      for (Iterator<View> it = modern.iterator(); it.hasNext(); ) {
        View view = it.next();
        if (!liveModern.contains(view)) {
          ModernTintAdapter.restore(view);
          it.remove();
        }
      }
      for (Iterator<Drawable> it = batteries.iterator(); it.hasNext(); ) {
        Drawable drawable = it.next();
        if (!liveBattery.contains(drawable)) {
          BatteryAdapter.restore(drawable);
          it.remove();
        }
      }
      for (var e : spans.entrySet()) {
        TextView view = e.getKey();
        SpannableString s = e.getValue();
        if (!sameSpans(view.getText(), s)) view.setText(s);
        texts.add(view);
      }
      Iterator<ImageView> images = originals.keySet().iterator();
      while (images.hasNext()) {
        ImageView image = images.next();
        if (!live.contains(image)) {
          image.setColorFilter(originals.get(image));
          appliedColors.remove(image);
          images.remove();
        }
      }
      Iterator<TextView> textIterator = texts.iterator();
      while (textIterator.hasNext()) {
        TextView text = textIterator.next();
        if (!spans.containsKey(text)) {
          clear(text);
          textIterator.remove();
        }
      }
    } finally {
      applying = false;
    }
  }

  private static boolean sameSpans(CharSequence a, SpannableString b) {
    if (!(a instanceof Spanned old)) return false;
    BarSpan[] x = old.getSpans(0, a.length(), BarSpan.class),
        y = b.getSpans(0, b.length(), BarSpan.class);
    if (x.length != y.length) return false;
    for (int i = 0; i < x.length; i++)
      if (x[i].getForegroundColor() != y[i].getForegroundColor()
          || old.getSpanStart(x[i]) != b.getSpanStart(y[i])
          || old.getSpanEnd(x[i]) != b.getSpanEnd(y[i])) return false;
    return true;
  }

  private static void clear(TextView text) {
    SpannableString s = new SpannableString(text.getText());
    for (BarSpan span : s.getSpans(0, s.length(), BarSpan.class)) s.removeSpan(span);
    text.setText(s);
  }

  private void restore() {
    choreographer.removeFrameCallback(fadeFrame);
    fadeScheduled = false;
    fadeTargets = Collections.emptyList();
    fades.clear();
    fadeColors = fadePercentages = new int[0];
    applying = true;
    try {
      for (var e : originals.entrySet()) e.getKey().setColorFilter(e.getValue());
      originals.clear();
      appliedColors.clear();
      for (View view : modern) ModernTintAdapter.restore(view);
      modern.clear();
      for (Drawable drawable : batteries) BatteryAdapter.restore(drawable);
      batteries.clear();
      for (TextView t : texts) clear(t);
      texts.clear();
      previous.clear();
    } finally {
      applying = false;
    }
  }

  void standardFilter(ImageView view, ColorFilter filter) {
    if (!applying && Looper.myLooper() == Looper.getMainLooper() && originals.containsKey(view)) {
      originals.put(view, filter);
      appliedColors.remove(view);
    }
  }

  void standardDrawable(ImageView view) {
    if (!applying && Looper.myLooper() == Looper.getMainLooper() && originals.containsKey(view)) {
      ColorFilter original = originals.remove(view);
      appliedColors.remove(view);
      applying = true;
      try {
        view.setColorFilter(original);
      } finally {
        applying = false;
      }
    }
  }

  private void fail(Throwable t) {
    epoch++;
    restore();
    errors++;
    lastError = t.toString();
    state = "Capture unavailable; standard colors restored";
    if (errors >= 5) stopped = true;
    XposedBridge.log("GalaxyBar: " + lastError);
  }

  boolean isAttached() {
    return root.isAttachedToWindow();
  }

  private void releaseCapture() {
    if (capture != null) capture.close();
    capture = null;
    framePixels = new int[0];
    unchangedFrames = 0;
  }

  void close() {
    closed = true;
    main.removeCallbacks(ticker);
    context.unregisterReceiver(screenState);
    epoch++;
    restore();
    worker.execute(this::releaseCapture);
    worker.shutdown();
  }
}
