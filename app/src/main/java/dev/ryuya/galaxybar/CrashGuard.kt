package dev.ryuya.galaxybar

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings.Global
import android.util.AtomicFile
import dev.ryuya.galaxybar.hook.Hooks
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayList
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.log

/*
 * Checked before color hooks are installed. No polling, wake locks, or uncaught-handler changes.
 */
class CrashGuard private constructor(context: Context) {
    private val context: Context
    private val file: AtomicFile
    private val reports: ExecutorService? =
        Executors.newSingleThreadExecutor(
            ThreadFactory { r: Runnable? -> Thread(r, "GalaxyBarGuardReport") }
        )
    private var state: CrashLoopState = CrashLoopState()
    private var armed: Boolean = false

    init {
        this.context = context
        val device = context.createDeviceProtectedStorageContext()
        file = AtomicFile(File(device.getFilesDir(), "galaxybar-crash-guard.properties"))
    }

    fun allowsHooks(): Boolean {
        return !state.blocked
    }

    @Throws(IOException::class)
    fun arm() {
        val pid = Process.myPid()
        if (armed) return
        state.arm(pid, System.currentTimeMillis())
        save() // Durable before enabling color or launcher hooks.
        armed = true
    }

    fun disarm() {
        if (!armed) return
        armed = false
        state.disarm(Process.myPid())
        try {
            save()
        } catch (error: Throwable) {
            state.stop("guard_error", System.currentTimeMillis())
            try {
                save()
            } catch (ignored: Throwable) {}
            publishWhenUnlocked()
            Hooks.log("GalaxyBar: guard disarm failed: " + error)
        }
    }

    fun initializationFailed(error: Throwable?) {
        state.stop("hook_initialization", System.currentTimeMillis())
        try {
            save()
        } catch (ignored: Throwable) {}
        publishWhenUnlocked()
        Hooks.log("GalaxyBar: hook initialization stopped: " + error)
    }

    @Throws(IOException::class)
    private fun save() {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            state.encode().store(output, "Galaxy Adaptive Bar crash protection")
            file.finishWrite(output)
        } catch (error: Throwable) {
            if (output != null) file.failWrite(output)
            throw IOException("Could not persist crash guard", error)
        }
    }

    private fun publishWhenUnlocked() {
        if (context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked()) {
            publish()
            return
        }
        val receiver: BroadcastReceiver =
            object : BroadcastReceiver() {
                private var handled: Boolean = false

                override fun onReceive(c: Context?, i: Intent?) {
                    if (handled) return
                    handled = true
                    // SystemUI/launcher can dispatch the unlock through a different Context
                    // wrapper.
                    // A duplicate/delayed delivery must never crash the host during restoration.
                    try {
                        context.unregisterReceiver(this)
                    } catch (alreadyRemoved: IllegalArgumentException) {
                        /* Receiver is already detached. */
                    }
                    publish()
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun publish() {
        val result = Bundle()
        result.putBoolean("blocked", state.blocked)
        result.putInt("count", state.crashes.size)
        result.putString("cause", state.cause)
        reports!!.execute(
            Runnable {
                for (attempt in 0..2) {
                    try {
                        context
                            .getContentResolver()
                            .call(ConfigProvider.URI!!, "guard_status", null, result)
                        return@Runnable
                    } catch (error: Throwable) {
                        if (attempt == 2) Hooks.log("GalaxyBar: guard status unavailable: " + error)
                        else SystemClock.sleep(1000)
                    }
                }
            }
        )
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun open(context: Context, armOnOpen: Boolean = true): CrashGuard {
            val guard = CrashGuard(context)
            try {
                if (
                    guard.file.getBaseFile().exists() ||
                        File(guard.file.getBaseFile().getPath() + ".bak").exists()
                ) {
                    if (guard.file.getBaseFile().length() > 16384)
                        throw IOException("Guard file bounds")
                    val p = Properties()
                    guard.file.openRead().use { input ->
                        p.load(input)
                    }
                    guard.state = CrashLoopState.decode(p)
                }
                var reset: String = ""
                if (
                    context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked()
                ) {
                    try {
                        val config =
                            context
                                .getContentResolver()
                                .call(ConfigProvider.URI!!, "config", null, null)
                        if (config != null) reset = config.getString("guard_reset_token", "")
                    } catch (ignored: Throwable) {
                        /* Companion CE data may be unavailable at startup. */
                    }
                }
                val boot = Global.getInt(context.getContentResolver(), Global.BOOT_COUNT, -1)
                val exits = ArrayList<CrashLoopState.Exit?>()
                for (exit in
                    context
                        .getSystemService<ActivityManager?>(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(context.getPackageName(), 0, 32)) {
                    if (context.getPackageName() == exit.getProcessName())
                        exits.add(
                            CrashLoopState.Exit(
                                exit.getPid(),
                                exit.getTimestamp(),
                                exit.getReason(),
                                exit.getStatus(),
                            )
                        )
                }
                val now = System.currentTimeMillis()
                guard.state.observe(now, boot, reset, exits)
                if (!guard.state.blocked && armOnOpen) {
                    guard.state.arm(Process.myPid(), now)
                    guard.armed = true
                }
                guard.save() // Arm durably before executing any color-hook initialization.
            } catch (error: Throwable) {
                guard.state.stop("guard_error", System.currentTimeMillis())
                try {
                    guard.save()
                } catch (ignored: Throwable) {}
                Hooks.log("GalaxyBar: guard failed closed: " + error)
            }
            guard.publishWhenUnlocked()
            Hooks.log(
                ("GalaxyBar: crash guard " +
                    (if (guard.state.blocked) "BLOCKED " + guard.state.cause
                    else if (guard.armed) "armed" else "idle") +
                    "; recent crashes=" +
                    guard.state.crashes.size)
            )
            return guard
        }

        @JvmStatic
        fun failClosed(context: Context, error: Throwable?) {
            Hooks.log("GalaxyBar: guard unavailable; hooks not installed: " + error)
            Thread(
                    Runnable {
                        try {
                            val data = Bundle()
                            data.putBoolean("blocked", true)
                            data.putString("cause", "guard_error")
                            context
                                .getContentResolver()
                                .call(ConfigProvider.URI!!, "guard_status", null, data)
                        } catch (ignored: Throwable) {}
                    },
                    "GalaxyBarGuardFailure",
                )
                .start()
        }
    }
}
