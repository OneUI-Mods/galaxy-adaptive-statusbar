package dev.ryuya.galaxybar

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap.CompressFormat
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff.Mode
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.ryuya.galaxybar.hook.Hooks
import dev.ryuya.galaxybar.hook.Reflect
import java.io.File
import java.io.FileOutputStream
import java.text.BreakIterator
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min

/* UI thread snapshots geometry; one worker captures and analyzes; UI thread applies colors. */
class Engine(root: View) {
    private val root: View
    private val context: Context
    private val motion: StatusBarMotion
    private val main: Handler = Handler(Looper.getMainLooper())
    private val ticker: Runnable = Runnable { this.tick() }
    private val settingsObserver: ContentObserver =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (closed) return
                try {
                    configRevision++
                    if (uri != null && "motion" == uri.getLastPathSegment()) motion.configure(false)
                    if (uri != null && "charging_motion" == uri.getLastPathSegment())
                        ChargingMotion.configure(false)
                    if (uri != null && "privacy_motion" == uri.getLastPathSegment())
                        PrivacySlide.configure(false)
                    // A toggle invalidates the old session before the worker can read the new
                    // setting.
                    // This also handles OFF→ON faster than a capture or a provider round trip.
                    val key: String? = if (uri == null) null else uri.getLastPathSegment()
                    if (uri == null || uri == ConfigProvider.URI || "enabled" == key)
                        suspendColors()
                    else if (
                        java.util.Set.of<String?>(
                                "clock",
                                "notifications",
                                "signals",
                                "battery",
                                "fixed",
                            )
                            .contains(key)
                    )
                        pendingTargetReset = true
                    val revision: Int = configRevision
                    worker!!.execute(Runnable { refreshConfig(revision) })
                } catch (error: Throwable) {
                    HookEntry.stopHooks(error)
                }
            }
        }
    private val screenState: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent) {
                if (Intent.ACTION_SCREEN_OFF == intent.getAction()) {
                    motion.cancel("screen off")
                    PrivacySlide.cancel()
                    ChargingMotion.cancel()
                    epoch++
                    restore()
                    worker!!.execute(Runnable { releaseCapture() })
                }
                main.removeCallbacks(ticker)
                main.post(ticker)
            }
        }
    private val worker: ExecutorService? =
        Executors.newSingleThreadExecutor(
            ThreadFactory { r: Runnable? ->
                val t = Thread(r, "GalaxyBarCapture")
                t.setPriority(Thread.MIN_PRIORITY)
                t
            }
        )
    private val originals: IdentityHashMap<ImageView?, ColorFilter?> =
        IdentityHashMap<ImageView?, ColorFilter?>()
    private val appliedColors: IdentityHashMap<ImageView?, Int?> =
        IdentityHashMap<ImageView?, Int?>()
    private val networkSpeeds: MutableSet<TextView?>? =
        Collections.newSetFromMap<TextView?>(IdentityHashMap<TextView?, Boolean?>())
    private val modern: MutableSet<View?>? =
        Collections.newSetFromMap<View?>(IdentityHashMap<View?, Boolean?>())
    private val nativeBatteries: MutableSet<View?>? =
        Collections.newSetFromMap<View?>(IdentityHashMap<View?, Boolean?>())
    private val batteries: MutableSet<Drawable?>? =
        Collections.newSetFromMap<Drawable?>(IdentityHashMap<Drawable?, Boolean?>())
    private val texts: MutableSet<TextView?>? =
        Collections.newSetFromMap<TextView?>(IdentityHashMap<TextView?, Boolean?>())
    private val previous: MutableMap<String?, Int?> = ConcurrentHashMap<String?, Int?>()
    private val fades: MutableMap<String?, ColorFade?> = HashMap<String?, ColorFade?>()
    private val choreographer: Choreographer? = Choreographer.getInstance()
    private val fadeFrame: FrameCallback = FrameCallback { ignored: Long ->
        fadeScheduled = false
        renderFade()
    }
    private var fadeScheduled: Boolean = false
    private var fadeTargets: MutableList<Engine.Target?>? =
        java.util.Collections.emptyList<Engine.Target?>()
    private val fadeCrop: Rect = Rect()
    private var fadeEpoch: Int = 0
    private var fadeRotation: Int = 0
    private var fadeColors: IntArray = IntArray(0)
    private var fadePercentages: IntArray = IntArray(0)
    @Volatile private var config: Bundle = Bundle()
    @Volatile private var busy: Boolean = false
    @Volatile private var protectedContent: Boolean = false
    @Volatile private var unchangedFrames: Int = 0
    private var framePixels: IntArray = IntArray(0)
    private var frameHash: Int = 0
    private var capturedRotation: Int = -1
    private var lastConfig: Long = 0
    private var lastStatus: Long = 0
    private var lastDebug: Long = 0
    private var errors: Int = 0
    private var epoch: Int = 0
    private var configRevision: Int = 0
    private var paletteEpoch: Int = 0
    private var analysisEpoch: Int = -1
    private var immediateCapture: Boolean = false
    @Volatile private var renderState: String = "Color hooks: 0; fade callbacks: 0; fade targets: 0"
    private var stopped: Boolean = false
    private var wasEnabled: Boolean = false
    private var closed: Boolean = false
    private var applying: Boolean = false
    private var pendingTargetReset: Boolean = false
    private var capture: LayerCapture? = null
    private var state: String = "Hook active; module disabled"
    private var lastError: String = ""
    private var lastMs: Double = 0.0
    private var frames: Long = 0
    private var started: Long = SystemClock.elapsedRealtime()

    private class BarSpan(c: Int) : ForegroundColorSpan(c)

    @JvmRecord
    private data class Target(
        val view: View?,
        val area: Rect?,
        val start: Int,
        val end: Int,
        val text: String?,
        val key: String?,
        val battery: BatteryAdapter.Geometry?,
        val nativeBattery: NativeBatteryView.Geometry?,
    ) {
        constructor(
            view: View?,
            area: Rect?,
            start: Int,
            end: Int,
            text: String?,
            key: String?,
        ) : this(view, area, start, end, text, key, null, null)

        constructor(
            view: View?,
            area: Rect?,
            start: Int,
            end: Int,
            text: String?,
            key: String?,
            battery: BatteryAdapter.Geometry?,
        ) : this(view, area, start, end, text, key, battery, null)

        fun hasBattery(): Boolean {
            return battery != null || nativeBattery != null
        }

        override fun toString(): String =
            "Target[view=$view, area=$area, start=$start, end=$end, text=$text, key=$key, battery=$battery, nativeBattery=$nativeBattery]"
    }

    init {
        this.root = root
        PrivacySlide.attach(root)
        ChargingMotion.attach(root)
        context = root.getContext()
        motion =
            StatusBarMotion(
                root,
                main,
                Consumer { newBackground: Boolean? ->
                    epoch++
                    // A new visible surface has its own palette. Its opacity fade handles the
                    // handoff; never interpolate its first decision from the previous screen.
                    choreographer!!.removeFrameCallback(fadeFrame)
                    fadeScheduled = false
                    fadeTargets = java.util.Collections.emptyList<Engine.Target?>()
                    if (newBackground!!) {
                        fades.clear()
                        paletteEpoch++
                    }
                    immediateCapture = true
                    if (!closed) {
                        main.removeCallbacks(ticker)
                        main.post(ticker)
                    }
                },
            )
        context
            .getContentResolver()
            .registerContentObserver(ConfigProvider.URI!!, true, settingsObserver)
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        context.registerReceiver(screenState, filter, Context.RECEIVER_NOT_EXPORTED)
        Hooks.log(
            ("GalaxyBar: engine root=" +
                root.javaClass.getName() +
                " " +
                root.getWidth() +
                "x" +
                root.getHeight())
        )
        main.post(ticker)
    }

    private fun tick() {
        if (closed) return
        if (busy && immediateCapture) {
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, 16)
            return
        }
        try {
            if (!busy) {
                immediateCapture = false
                busy = true
                val now = SystemClock.elapsedRealtime()
                val enabled = config.getBoolean("enabled")
                val power: PowerManager? =
                    context.getSystemService(Context.POWER_SERVICE) as PowerManager?
                val crop = Rect()
                val moving = motion.active()
                val source = motion.colorSource()
                val sampleWindow: Boolean = moving && source == MotionPalette.Source.WINDOW
                var visible: Boolean =
                    root.isAttachedToWindow() && root.isShown() && root.getGlobalVisibleRect(crop)
                if (moving) {
                    val movingCrop = motion.sampleCrop()
                    visible =
                        root.isAttachedToWindow() &&
                            movingCrop != null &&
                            !motion.waitingForSurface()
                    if (movingCrop != null) crop.set(movingCrop)
                }
                var allowed: Boolean =
                    enabled &&
                        !stopped &&
                        visible &&
                        power!!.isInteractive() &&
                        !context
                            .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                            .isKeyguardLocked() &&
                        (moving || crop.top == 0) &&
                        crop.height() < root.getResources().getDisplayMetrics().heightPixels / 4
                // Other rotations retain standard colors until the capture mapping is verified.
                allowed =
                    allowed and
                        (root.getDisplay() != null &&
                            root.getDisplay().getDisplayId() == 0 &&
                            root.getDisplay().getRotation() == Surface.ROTATION_0)
                val holdCapture: Boolean =
                    allowed && moving && source == MotionPalette.Source.FROZEN
                allowed = allowed and !holdCapture
                if (allowed) motion.prepare(now)
                if (closed) return
                if (
                    !allowed &&
                        !moving &&
                        (!originals.isEmpty() ||
                            !texts!!.isEmpty() ||
                            !modern!!.isEmpty() ||
                            !batteries!!.isEmpty())
                ) {
                    epoch++
                    restore("capture ineligible")
                }
                var targets = ArrayList<Engine.Target?>()
                var surfaces = arrayOfNulls<SurfaceControl>(0)
                if (allowed) {
                    if (moving) {
                        for (view in motion.sourceViews()) collect(view!!, targets)
                        val mapped = ArrayList<Engine.Target?>()
                        for (target in targets) {
                            val area: Rect? =
                                if (sampleWindow) motion.mappedArea(target!!.area!!)
                                else Rect(target!!.area)
                            if (Rect.intersects(area!!, crop))
                                mapped.add(
                                    Engine.Target(
                                        target!!.view,
                                        area,
                                        target!!.start,
                                        target!!.end,
                                        target!!.text,
                                        target!!.key,
                                        target!!.battery,
                                        target!!.nativeBattery,
                                    )
                                )
                        }
                        targets = mapped
                    } else collect(root, targets)
                    val vr = Reflect.callMethod(root, "getViewRootImpl")
                    val surface: SurfaceControl? =
                        Reflect.callMethod(vr!!, "getSurfaceControl") as SurfaceControl?
                    val movingSurface: SurfaceControl? =
                        if (moving) motion.overlaySurface() else null
                    surfaces =
                        if (movingSurface == null) arrayOf<SurfaceControl?>(surface)
                        else arrayOf<SurfaceControl?>(surface, movingSurface)
                }
                val excluded: Array<SurfaceControl?>? = surfaces
                val selectedTargets: MutableList<Engine.Target?>? = targets
                val generation: Int = epoch
                val paletteGeneration: Int = paletteEpoch
                val run: Boolean = allowed
                val display: Int = if (run) root.getDisplay().getDisplayId() else 0
                val rotation: Int = if (run) root.getDisplay().getRotation() else 0
                val settings = Bundle(config)
                val revision: Int = configRevision
                worker!!.execute(
                    Runnable {
                        process(
                            now,
                            run,
                            moving,
                            holdCapture,
                            crop,
                            excluded,
                            display,
                            rotation,
                            selectedTargets!!,
                            generation,
                            paletteGeneration,
                            settings,
                            revision,
                        )
                    }
                )
            }
        } catch (t: Throwable) {
            busy = false
            fail(t)
        }
        var hz = config.getInt("hz", 10)
        if (hz != 5 && hz != 10 && hz != 15 && hz != 30) hz = 10
        val p: PowerManager? = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
        if (p!!.isPowerSaveMode() || unchangedFrames >= 20 || protectedContent) hz = 5
        val asleep: Boolean =
            !p!!.isInteractive() ||
                context
                    .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                    .isKeyguardLocked()
        main.postDelayed(
            ticker,
            (if (asleep) 5000
                else if (!config.getBoolean("enabled") || stopped) 500 else max(33, 1000 / hz))
                .toLong(),
        )
    }

    private fun refreshConfig(revision: Int) {
        if (!context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked())
            return
        try {
            val fresh =
                context.getContentResolver().call(ConfigProvider.URI!!, "config", null, null)
            lastConfig = SystemClock.elapsedRealtime()
            if (fresh != null) main.post(Runnable { acceptConfig(fresh, revision) })
        } catch (error: Throwable) {
            main.post(
                Runnable {
                    if (!closed && revision == configRevision) {
                        suspendColors()
                        // Provider startup/unlock is not a capture or hook failure. The ticker
                        // retries.
                        state = "Waiting for settings"
                    }
                }
            )
        }
    }

    private fun acceptConfig(fresh: Bundle, revision: Int) {
        if (closed || revision != configRevision) return
        config = fresh
        if (pendingTargetReset) {
            epoch++
            restore()
            pendingTargetReset = false
        }
        val enabled = fresh.getBoolean("enabled")
        if (enabled != wasEnabled) {
            epoch++
            restore()
            stopped = false
            protectedContent = false
            errors = 0
            wasEnabled = enabled
            if (enabled) {
                try {
                    HookEntry.enableColors()
                    worker!!.execute(Runnable { BatteryAdapter.validate(context) })
                } catch (error: Throwable) {
                    HookEntry.stopHooks(error)
                    return
                }
            } else HookEntry.disableColors()
        }
        PrivacySlide.configure(
            enabled && fresh.getBoolean("privacy_motion", false),
            fresh.getBoolean("privacy_fallback", true),
        )
        ChargingMotion.configure(enabled && fresh.getBoolean("charging_motion", true))
        NetworkSpeedTintAdapter.setSpacing(fresh.getInt(NetworkSpeedSpacing.KEY, 0))
        motion.configure(
            enabled && fresh.getBoolean("motion", true) && !stopped && !protectedContent
        )
        updateRenderState()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun suspendColors() {
        epoch++
        val disabled = Bundle(config)
        disabled.putBoolean("enabled", false)
        config = disabled
        wasEnabled = false
        motion.configure(false)
        // Restore through the live adapters before unregistering their hooks and clearing
        // baselines.
        restore()
        HookEntry.disableColors()
        updateRenderState()
        worker!!.execute(Runnable { this.releaseCapture() })
    }

    private fun updateRenderState() {
        renderState =
            ("Color hooks: " +
                HookRegistry.colorCount() +
                "; fade callbacks: " +
                (if (fadeScheduled) 1 else 0) +
                "; fade targets: " +
                fadeTargets!!.size +
                "; applied views: " +
                (originals.size +
                    texts!!.size +
                    modern!!.size +
                    batteries!!.size +
                    nativeBatteries!!.size +
                    networkSpeeds!!.size))
    }

    private fun process(
        now: Long,
        run: Boolean,
        moving: Boolean,
        holdCapture: Boolean,
        crop: Rect,
        excluded: Array<SurfaceControl?>?,
        display: Int,
        rotation: Int,
        targets: MutableList<Engine.Target?>,
        generation: Int,
        paletteGeneration: Int,
        settings: Bundle,
        revision: Int,
    ) {
        var run: Boolean = run
        var holdCapture: Boolean = holdCapture
        try {
            if (now - lastConfig > 750) {
                refreshConfig(revision)
            }
            run =
                run and
                    (config.getBoolean("enabled") &&
                        context
                            .getSystemService<PowerManager?>(PowerManager::class.java)
                            .isInteractive() &&
                        !context
                            .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                            .isKeyguardLocked())
            holdCapture =
                holdCapture and
                    (config.getBoolean("enabled") &&
                        context
                            .getSystemService<PowerManager?>(PowerManager::class.java)
                            .isInteractive() &&
                        !context
                            .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                            .isKeyguardLocked())
            if (run && !targets.isEmpty()) {
                if (analysisEpoch != paletteGeneration) {
                    previous.clear()
                    analysisEpoch = paletteGeneration
                }
                if (capturedRotation != rotation) {
                    if (capture != null) capture!!.close()
                    capture = null
                    capturedRotation = rotation
                    unchangedFrames = 0
                    previous.clear()
                }
                if (capture == null) capture = LayerCapture()
                val start = SystemClock.elapsedRealtimeNanos()
                val bmp = capture!!.capture(display, crop, excluded!!)
                try {
                    val size: Int = bmp!!.getWidth() * bmp!!.getHeight()
                    if (framePixels.size != size) framePixels = IntArray(size)
                    bmp!!.getPixels(
                        framePixels,
                        0,
                        bmp!!.getWidth(),
                        0,
                        0,
                        bmp!!.getWidth(),
                        bmp!!.getHeight(),
                    )
                    val hash = framePixels.contentHashCode()
                    unchangedFrames = if (hash == frameHash) unchangedFrames + 1 else 0
                    frameHash = hash
                    val colors = IntArray(targets.size)
                    val percentageColors = IntArray(targets.size)
                    val details = StringBuilder()
                    val nextPrevious = HashMap<String?, Int?>()
                    for (i in targets.indices) {
                        val t = targets.get(i)
                        val values =
                            Engine.samples(
                                framePixels,
                                bmp!!.getWidth(),
                                bmp!!.getHeight(),
                                t!!.area,
                                crop,
                            )
                        var color =
                            Contrast.choose(
                                values!!,
                                previous.getOrDefault(t!!.key, 0)!!,
                                settings.getString("algorithm", "robust"),
                                settings.getDouble("hysteresis", .15),
                            )
                        if (
                            settings.getBoolean("fixed") &&
                                t!!.start >= 0 &&
                                Engine.isClock(t!!.view!!)
                        )
                            color = if (t!!.start % 2 === 0) Color.WHITE else Color.BLACK
                        nextPrevious.put(t!!.key, color)
                        colors[i] = color
                        if (t!!.hasBattery()) {
                            val percentKey: String = t!!.key + ":percentage"
                            percentageColors[i] =
                                if (t!!.nativeBattery != null)
                                    NativeBatteryView.percentageColor(
                                        bmp!!,
                                        crop,
                                        t!!.area!!,
                                        t!!.nativeBattery,
                                        color,
                                        previous.getOrDefault(percentKey, 0)!!,
                                        settings.getString("algorithm", "robust"),
                                        settings.getDouble("hysteresis", .15),
                                    )
                                else
                                    BatteryAdapter.percentageColor(
                                        bmp!!,
                                        crop,
                                        t!!.area!!,
                                        t!!.battery!!,
                                        color,
                                        previous.getOrDefault(percentKey, 0)!!,
                                        settings.getString("algorithm", "robust"),
                                        settings.getDouble("hysteresis", .15),
                                    )
                            nextPrevious.put(percentKey, percentageColors[i])
                            if (settings.getBoolean("debug"))
                                details
                                    .append(percentKey)
                                    .append(
                                        if (percentageColors[i] == Color.BLACK) " black\n"
                                        else " white\n"
                                    )
                        }
                        if (settings.getBoolean("debug")) {
                            var avg: Double = 0.0
                            for (value in values!!) avg += value
                            details
                                .append(t!!.key)
                                .append(" L=")
                                .append(String.format(Locale.US, "%.3f", avg / values!!.size))
                                .append(if (color == Color.BLACK) " black" else " white")
                                .append('\n')
                        }
                    }
                    previous.clear()
                    previous.putAll(nextPrevious)
                    lastMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    frames++
                    errors = 0
                    state =
                        ("Capture OK: " +
                            bmp!!.getWidth() +
                            "×" +
                            bmp!!.getHeight() +
                            "; targets=" +
                            targets.size)
                    main.post(
                        Runnable {
                            val current = Rect()
                            if (
                                !closed &&
                                    epoch == generation &&
                                    config.getBoolean("enabled") &&
                                    !stopped &&
                                    ((moving && motion.active()) ||
                                        (!moving &&
                                            !motion.active() &&
                                            root.getGlobalVisibleRect(current) &&
                                            current == crop)) &&
                                    root.getDisplay().getRotation() == rotation &&
                                    context
                                        .getSystemService<PowerManager?>(PowerManager::class.java)
                                        .isInteractive() &&
                                    !context
                                        .getSystemService<KeyguardManager?>(
                                            KeyguardManager::class.java
                                        )
                                        .isKeyguardLocked()
                            )
                                try {
                                    if (protectedContent) {
                                        protectedContent = false
                                        motion.configure(config.getBoolean("motion", true))
                                    }
                                    transition(
                                        targets,
                                        colors,
                                        percentageColors,
                                        crop,
                                        generation,
                                        rotation,
                                    )
                                } catch (e: Throwable) {
                                    fail(e)
                                }
                        }
                    )
                    if (settings.getBoolean("debug") && now - lastDebug > 3000) {
                        val sample = File(context.getCacheDir(), "galaxybar-background.png")
                        FileOutputStream(sample).use { out ->
                            bmp!!.compress(CompressFormat.PNG, 100, out)
                        }
                        Hooks.log("GalaxyBar: " + state + " ms=" + lastMs + "\n" + details)
                        lastDebug = now
                    }
                } finally {
                    bmp!!.recycle()
                }
            } else if (!settings.getBoolean("enabled")) state = "Hook active; module disabled"
            else if (stopped) state = "Stopped after consecutive errors; toggle module to retry"
            else
                state =
                    "Paused: screen off/locked, status bar hidden, or unsupported display/rotation"
            if (!run && !holdCapture) releaseCapture()
        } catch (t: Throwable) {
            if (t is LayerCapture.ProtectedContentException) {
                errors = 0
                state = "Paused: protected content; standard status bar"
                main.post(
                    Runnable {
                        if (!closed && epoch == generation && !protectedContent) {
                            protectedContent = true
                            epoch++
                            motion.configure(false)
                            restore("protected content")
                        }
                    }
                )
            } else if (t is BackgroundSamples.Unavailable) {
                errors = 0
                unchangedFrames = 0
                state = "Waiting for an opaque background; standard status bar"
                main.post(
                    Runnable {
                        if (!closed && epoch == generation) restore("background not yet opaque")
                    }
                )
            } else if (t is LayerCapture.UnavailableException) {
                errors = 0
                state = "Capture temporarily unavailable (secure, protected, or surface transition)"
                main.post(
                    Runnable {
                        if (!closed && epoch == generation) restore("capture unavailable")
                    }
                )
            } else main.post(Runnable { fail(t) })
            if (
                capture != null &&
                    (t !is LayerCapture.UnavailableException) &&
                    (t !is BackgroundSamples.Unavailable)
            ) {
                capture!!.close()
                capture = null
            }
        } finally {
            try {
                publishStatus(now)
            } catch (error: Throwable) {
                main.post(Runnable { fail(error) })
            }
            busy = false
        }
    }

    private fun publishStatus(now: Long) {
        if (closed) return
        if (now - lastStatus > 2000) {
            val text: String =
                (state +
                    "\nFrame " +
                    frames +
                    "; capture+analysis " +
                    String.format(Locale.US, "%.1f", lastMs) +
                    " ms\nSession rate " +
                    String.format(Locale.US, "%.1f", frames * 1000.0 / max(1L, now - started)) +
                    " Hz" +
                    (if (Build.VERSION.SDK_INT == 37) NativeBatteryView.health()
                    else "\nBattery: " + BatteryAdapter.health()) +
                    "\nFade: " +
                    config.getInt("fade_ms", ColorFade.DEFAULT_DURATION_MS) +
                    " ms" +
                    "\n" +
                    renderState +
                    "\n" +
                    motion.status() +
                    "\nPrivacy motion: " +
                    PrivacySlide.status() +
                    "\nCharging motion: " +
                    ChargingMotion.status() +
                    "\nLast error: " +
                    lastError)
            context
                .getContentResolver()
                .call(ConfigProvider.URI!!, "status", text, motion.registration())
            lastStatus = now
        }
    }

    private fun collect(view: View, out: MutableList<Engine.Target?>) {
        val virtual: Boolean = motion.active() && motion.nativeArea(view) != null
        if (!virtual && (!view.isShown() || view.getAlpha() <= 0)) return
        val name = view.javaClass.getName()
        val id = Engine.id(view)
        if (StatusBarElements.isCompoundIndicator(name, id!!)) return
        if (NativeBatteryView.supports(view)) {
            ChargingMotion.observeBattery(view)
            val area = Rect()
            val geometry = NativeBatteryView.geometry(view)
            if (
                config.getBoolean("battery", true) &&
                    geometry != null &&
                    area(view, area) &&
                    !area.isEmpty()
            ) {
                val drawing = NativeBatteryView.drawingArea(view, area, geometry.shape!!)
                out.add(
                    Engine.Target(
                        view,
                        drawing,
                        -1,
                        -1,
                        "",
                        Engine.key(view) + ":native-battery",
                        null,
                        geometry,
                    )
                )
            }
            return
        }
        if (view is TextView && NetworkSpeedTintAdapter.supports(view)) {
            ChargingMotion.observeSpeed(view)
            val area = Rect()
            if (config.getBoolean("signals", true) && area(view, area) && !area.isEmpty())
                out.add(Engine.Target(view, area, -1, -1, "", Engine.key(view) + ":network-speed"))
            return
        }
        if (ModernTintAdapter.supports(view)) {
            val area = Rect()
            if (
                config.getBoolean("signals", true) &&
                    ModernTintAdapter.canRestore(view) &&
                    area(view, area) &&
                    !area.isEmpty()
            )
                out.add(Engine.Target(view, area, -1, -1, "", Engine.key(view) + ":modern"))
            return
        }
        if (view is ImageView && BatteryAdapter.supports(view)) {
            val area = Rect()
            if (config.getBoolean("battery", true) && area(view, area) && !area.isEmpty())
                out.add(
                    Engine.Target(
                        view,
                        area,
                        -1,
                        -1,
                        "",
                        Engine.key(view) + ":battery",
                        BatteryAdapter.geometry(view),
                    )
                )
            return
        }
        if (
            name.endsWith(".StatusBarIconView") &&
                (Reflect.callMethod(view, "getVisibleState") as Int?) == 2
        )
            return

        if (view is TextView && view.getLayout() != null && !view.getText().toString().isBlank()) {
            val clock = Engine.isClock(view)
            val battery = Engine.ancestor(view, "battery")
            if (
                if (clock) config.getBoolean("clock", true)
                else if (battery) config.getBoolean("battery", true)
                else config.getBoolean("signals", true)
            )
                addText(view, out, clock)
        }
        if (view is ImageView && view.getDrawable() != null) {
            val battery = Engine.ancestor(view, "battery")
            val notification = Engine.ancestor(view, "notification_icon")
            val selected: Boolean =
                !battery &&
                    (if (notification) config.getBoolean("notifications", true)
                    else config.getBoolean("signals", true))
            val r = Rect()
            if (selected && area(view, r) && r.width() > 0 && r.height() > 0)
                out.add(Engine.Target(view, r, -1, -1, "", Engine.key(view) + ":image"))
        }
        if (view is ViewGroup)
            for (i in 0 until view.getChildCount()) collect(view.getChildAt(i), out)
    }

    private fun addText(text: TextView, out: MutableList<Engine.Target?>, characters: Boolean) {
        val value = text.getText().toString()
        val layout = text.getLayout()
        val xy = IntArray(2)
        val visible = Rect()
        if (!area(text, visible)) return
        if (motion.active()) {
            xy[0] = visible.left
            xy[1] = visible.top
        } else text.getLocationOnScreen(xy)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (!characters) {
                start = 0
                end = value.length
            }
            if (value.substring(start, end).isBlank()) {
                if (!characters) break
                start = end
                end = iterator.next()
                continue
            }
            val line = layout.getLineForOffset(start)
            if (layout.getLineForOffset(end - 1) != line) {
                start = end
                end = iterator.next()
                continue
            }
            val left = layout.getPrimaryHorizontal(start)
            val right = layout.getPrimaryHorizontal(end)
            val glyph = Rect()
            text.getPaint().getTextBounds(value, start, end, glyph)
            val x: Int = xy[0] + text.getTotalPaddingLeft() - text.getScrollX()
            val y: Int =
                xy[1] + text.getTotalPaddingTop() - text.getScrollY() + layout.getLineBaseline(line)
            val r =
                Rect(
                    x + (floor(min(left, right))).toInt(),
                    y + glyph.top,
                    x + (ceil(max(left, right))).toInt(),
                    y + glyph.bottom,
                )
            if (r.intersect(visible) && !r.isEmpty())
                out.add(
                    Engine.Target(
                        text,
                        r,
                        start,
                        end,
                        value,
                        Engine.key(text) + ":" + start + ":" + value.substring(start, end),
                    )
                )
            if (!characters) break
            start = end
            end = iterator.next()
        }
    }

    private fun area(view: View, result: Rect): Boolean {
        if (motion.active()) {
            val saved = motion.nativeArea(view)
            if (saved == null) return false
            result.set(saved)
            return !result.isEmpty()
        }
        return view.getGlobalVisibleRect(result)
    }

    private fun transition(
        targets: MutableList<Engine.Target?>,
        colors: IntArray,
        percentages: IntArray,
        crop: Rect?,
        generation: Int,
        rotation: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        val live = HashSet<String?>()
        for (i in targets.indices) {
            val target = targets.get(i)
            retarget(target!!.key, colors[i], now, live)
            if (target!!.hasBattery())
                retarget(target!!.key + ":percentage", percentages[i], now, live)
        }
        fades.keys.retainAll(live)
        fadeTargets = targets
        fadeCrop.set(crop!!)
        fadeEpoch = generation
        fadeRotation = rotation
        if (fadeColors.size != targets.size) {
            fadeColors = IntArray(targets.size)
            fadePercentages = IntArray(targets.size)
        }
        choreographer!!.removeFrameCallback(fadeFrame)
        fadeScheduled = false
        renderFade()
    }

    private fun retarget(key: String?, color: Int, now: Long, live: MutableSet<String?>) {
        live.add(key)
        val fade = fades.computeIfAbsent(key) { ignored: String? -> ColorFade(color) }
        fade!!.setDuration(config.getInt("fade_ms", ColorFade.DEFAULT_DURATION_MS), now)
        fade!!.setTarget(color, now)
    }

    private fun renderFade() {
        if (fadeTargets!!.isEmpty()) return
        val current = Rect()
        if (
            closed ||
                stopped ||
                epoch != fadeEpoch ||
                !config.getBoolean("enabled") ||
                (if (motion.active()) motion.sampleCrop() == null
                else
                    (!root.isShown() ||
                        !root.getGlobalVisibleRect(current) ||
                        (current != fadeCrop))) ||
                root.getDisplay() == null ||
                root.getDisplay().getRotation() != fadeRotation ||
                !context
                    .getSystemService<PowerManager?>(PowerManager::class.java)
                    .isInteractive() ||
                context
                    .getSystemService<KeyguardManager?>(KeyguardManager::class.java)
                    .isKeyguardLocked()
        ) {
            restore("fade state invalid")
            return
        }
        try {
            val now = SystemClock.uptimeMillis()
            val animate = ValueAnimator.areAnimatorsEnabled()
            var running: Boolean = false
            for (i in fadeTargets!!.indices) {
                val target = fadeTargets!!.get(i)
                val body = fades.get(target!!.key)
                if (!animate) body!!.finish()
                fadeColors[i] = body!!.color(now)
                running = running or body!!.running(now)
                if (target!!.hasBattery()) {
                    val percentage = fades.get(target!!.key + ":percentage")
                    if (!animate) percentage!!.finish()
                    fadePercentages[i] = percentage!!.color(now)
                    running = running or percentage!!.running(now)
                }
            }
            apply(fadeTargets!!, fadeColors, fadePercentages)
            if (running && !fadeScheduled) {
                fadeScheduled = true
                choreographer!!.postFrameCallback(fadeFrame)
            }
            updateRenderState()
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun apply(
        targets: MutableList<Engine.Target?>,
        colors: IntArray,
        percentageColors: IntArray,
    ) {
        var colorSpans: Int = 0
        val textPalette = HashSet<Int?>()
        applying = true
        try {
            val spans = IdentityHashMap<TextView?, SpannableString?>()
            val live =
                Collections.newSetFromMap<ImageView?>(IdentityHashMap<ImageView?, Boolean?>())
            val liveNetworkSpeed =
                Collections.newSetFromMap<TextView?>(IdentityHashMap<TextView?, Boolean?>())
            val liveModern = Collections.newSetFromMap<View?>(IdentityHashMap<View?, Boolean?>())
            val liveNativeBattery =
                Collections.newSetFromMap<View?>(IdentityHashMap<View?, Boolean?>())
            val liveBattery =
                Collections.newSetFromMap<Drawable?>(IdentityHashMap<Drawable?, Boolean?>())
            for (i in targets.indices) {
                val t = targets.get(i)
                if (!t!!.view!!.isAttachedToWindow()) continue
                if (t!!.nativeBattery != null) {
                    liveNativeBattery.add(t!!.view)
                    nativeBatteries!!.add(t!!.view)
                    NativeBatteryView.apply(t!!.view, colors[i], percentageColors[i])
                } else if (t!!.battery != null) {
                    if ((t!!.view as ImageView).getDrawable() === t!!.battery.drawable) {
                        liveBattery.add(t!!.battery.drawable)
                        batteries!!.add(t!!.battery.drawable)
                        BatteryAdapter.apply(t!!.battery, colors[i], percentageColors[i])
                    }
                } else if (ModernTintAdapter.supports(t!!.view)) {
                    liveModern.add(t!!.view)
                    modern!!.add(t!!.view)
                    ModernTintAdapter.apply(t!!.view, colors[i])
                } else if (
                    t!!.view is TextView && NetworkSpeedTintAdapter.supports(t!!.view as TextView)
                ) {
                    val speed = t!!.view as TextView
                    liveNetworkSpeed.add(speed)
                    networkSpeeds!!.add(speed)
                    NetworkSpeedTintAdapter.apply(speed, colors[i])
                } else if (t!!.view is TextView) {
                    val text = t!!.view as TextView
                    if (text.getText().toString() != t!!.text) continue
                    colorSpans++
                    textPalette.add(colors[i])
                    val s =
                        spans.computeIfAbsent(text) { v: TextView? ->
                            val a = SpannableString(v!!.getText())
                            for (span in
                                a.getSpans<Engine.BarSpan?>(
                                    0,
                                    a.length,
                                    Engine.BarSpan::class.java,
                                )) a.removeSpan(span)
                            a
                        }
                    s!!.setSpan(
                        Engine.BarSpan(colors[i]),
                        t!!.start,
                        t!!.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                } else
                    run {
                        val image = t!!.view as? ImageView
                        if (image != null) {
                            live.add(image)
                            if (!originals.containsKey(image))
                                originals.put(image, image.getColorFilter())
                            if (appliedColors.get(image) != colors[i]) {
                                image.setColorFilter(colors[i], Mode.SRC_IN)
                                appliedColors.put(image, colors[i])
                            }
                        }
                    }
            }
            run {
                val it = networkSpeeds!!.iterator()
                while (it.hasNext()) {
                    val view = it.next()
                    if (!liveNetworkSpeed.contains(view)) {
                        NetworkSpeedTintAdapter.restore(view!!)
                        it.remove()
                    }
                }
            }
            run {
                val it = modern!!.iterator()
                while (it.hasNext()) {
                    val view = it.next()
                    if (!liveModern.contains(view)) {
                        ModernTintAdapter.restore(view)
                        it.remove()
                    }
                }
            }
            run {
                val it = nativeBatteries!!.iterator()
                while (it.hasNext()) {
                    val view = it.next()
                    if (!liveNativeBattery.contains(view)) {
                        NativeBatteryView.restore(view)
                        it.remove()
                    }
                }
            }
            val it = batteries!!.iterator()
            while (it.hasNext()) {
                val drawable = it.next()
                if (!liveBattery.contains(drawable)) {
                    BatteryAdapter.restore(drawable!!)
                    it.remove()
                }
            }
            for (e in spans.entries) {
                val view = e.key
                val s = e.value
                if (!Engine.sameSpans(view!!.getText(), s!!)) view!!.setText(s)
                texts!!.add(view)
            }
            val images = originals.keys.iterator()
            while (images.hasNext()) {
                val image = images.next()
                if (!live.contains(image)) {
                    image!!.setColorFilter(originals.get(image))
                    appliedColors.remove(image)
                    images.remove()
                }
            }
            val textIterator = texts!!.iterator()
            while (textIterator.hasNext()) {
                val text = textIterator.next()
                if (!spans.containsKey(text)) {
                    Engine.clear(text!!)
                    textIterator.remove()
                }
            }
        } finally {
            applying = false
        }
        updateRenderState()
        motion.colorState(colorSpans, textPalette.size)
        motion.colorsChanged()
    }

    private fun restore(reason: String? = "configuration or lifecycle") {
        if (
            !originals.isEmpty() ||
                !texts!!.isEmpty() ||
                !modern!!.isEmpty() ||
                !batteries!!.isEmpty()
        )
            Hooks.log("GalaxyBar: colors restored: " + reason + "; moving=" + motion.active())
        motion.colorState(0, 0)
        choreographer!!.removeFrameCallback(fadeFrame)
        fadeScheduled = false
        fadeTargets = java.util.Collections.emptyList<Engine.Target?>()
        fades.clear()
        fadePercentages = IntArray(0)
        fadeColors = fadePercentages
        applying = true
        try {
            for (e in originals.entries) e.key!!.setColorFilter(e.value)
            originals.clear()
            appliedColors.clear()
            for (view in networkSpeeds!!) NetworkSpeedTintAdapter.restore(view!!)
            networkSpeeds!!.clear()
            for (view in modern!!) ModernTintAdapter.restore(view)
            modern!!.clear()
            for (view in nativeBatteries!!) NativeBatteryView.restore(view)
            nativeBatteries!!.clear()
            for (drawable in batteries!!) BatteryAdapter.restore(drawable!!)
            batteries!!.clear()
            for (t in texts!!) Engine.clear(t!!)
            texts!!.clear()
            previous.clear()
        } finally {
            applying = false
        }
        updateRenderState()
    }

    fun standardFilter(view: ImageView?, filter: ColorFilter?): ColorFilter? {
        if (
            !applying && Looper.myLooper() == Looper.getMainLooper() && originals.containsKey(view)
        ) {
            originals.put(view, filter)
            val color = appliedColors.get(view)
            if (color != null) return PorterDuffColorFilter(color, Mode.SRC_IN)
        }
        return filter
    }

    fun standardDrawable(view: ImageView) {
        if (
            !applying && Looper.myLooper() == Looper.getMainLooper() && originals.containsKey(view)
        ) {
            val color = appliedColors.get(view)
            if (color == null) return
            applying = true
            try {
                view.setColorFilter(color, Mode.SRC_IN)
            } finally {
                applying = false
            }
        }
    }

    private fun fail(t: Throwable) {
        epoch++
        restore("error: " + t.javaClass.getSimpleName())
        errors++
        lastError = t.toString()
        state = "Capture unavailable; standard colors restored"
        if (errors >= 5) {
            stopped = true
            motion.configure(false)
        }
        Hooks.log("GalaxyBar: " + lastError)
    }

    fun isAttached(): Boolean {
        return root.isAttachedToWindow()
    }

    private fun releaseCapture() {
        if (capture != null) capture!!.close()
        capture = null
        framePixels = IntArray(0)
        unchangedFrames = 0
    }

    fun close() {
        closed = true
        motion.close()
        main.removeCallbacks(ticker)
        context.unregisterReceiver(screenState)
        context.getContentResolver().unregisterContentObserver(settingsObserver)
        epoch++
        suspendColors()
        worker!!.shutdown()
    }

    companion object {
        @JvmStatic
        private fun samples(
            pixels: IntArray?,
            width: Int,
            height: Int,
            area: Rect?,
            crop: Rect,
        ): DoubleArray? {
            val r = Rect(area)
            check(r.intersect(crop)) { "Target outside crop" }
            val x0 = max(0, (r.left - crop.left) / 2)
            val y0 = max(0, (r.top - crop.top) / 2)
            val x1 = min(width, (r.right - crop.left + 1) / 2)
            val y1 = min(height, (r.bottom - crop.top + 1) / 2)
            return BackgroundSamples.read(pixels!!, width, height, x0, y0, x1, y1)
        }

        @JvmStatic
        private fun id(v: View): String? {
            try {
                return v.getResources().getResourceEntryName(v.getId())
            } catch (e: Exception) {
                return v.javaClass.getSimpleName()
            }
        }

        @JvmStatic
        private fun isClock(view: View): Boolean {
            val name = view.javaClass.getName()
            return name.endsWith(".Clock") ||
                name.contains("QSClock") ||
                Engine.id(view)!!.contains("clock")
        }

        @JvmStatic
        private fun ancestor(v: View?, needle: String): Boolean {
            var v: View? = v
            var needle: String = needle
            needle = needle.replace("_", "")
            var i: Int = 0
            while (v != null && i < 12) {
                if (Engine.id(v)!!.lowercase().replace("_", "").contains(needle)) return true
                v = run {
                    val p = v.getParent() as? View
                    if (p != null) p else null
                }
                i++
            }
            return false
        }

        @JvmStatic
        private fun key(v: View): String? {
            return Engine.id(v) + "@" + System.identityHashCode(v)
        }

        @JvmStatic
        private fun sameSpans(a: CharSequence, b: SpannableString): Boolean {
            if (a !is Spanned) return false
            val x = a.getSpans<Engine.BarSpan?>(0, a.length, Engine.BarSpan::class.java)
            val y = b.getSpans<Engine.BarSpan?>(0, b.length, Engine.BarSpan::class.java)
            if (x.size != y.size) return false
            for (i in x.indices) if (
                x[i].getForegroundColor() != y[i].getForegroundColor() ||
                    a.getSpanStart(x[i]) != b.getSpanStart(y[i]) ||
                    a.getSpanEnd(x[i]) != b.getSpanEnd(y[i])
            )
                return false
            return true
        }

        @JvmStatic
        private fun clear(text: TextView) {
            val s = SpannableString(text.getText())
            for (span in s.getSpans<Engine.BarSpan?>(0, s.length, Engine.BarSpan::class.java)) s
                .removeSpan(span)
            text.setText(s)
        }
    }
}
