package dev.ryuya.galaxybar

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import java.util.Arrays
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField

/* Only non-sensitive preferences are public; each hook host can report only its own guard. */
class ConfigProvider : ContentProvider() {
    @Volatile private var motionEndpoint: IBinder? = null
    // Keep a strong reference: SharedPreferences holds listeners weakly.
    private val changes: OnSharedPreferenceChangeListener =
        OnSharedPreferenceChangeListener { prefs: SharedPreferences?, key: String? ->
            getContext()!!
                .getContentResolver()
                .notifyChange(
                    if (key == null) ConfigProvider.URI!!
                    else ConfigProvider.URI!!.buildUpon().appendPath(key).build()!!,
                    null,
                )
        }

    override fun onCreate(): Boolean {
        getContext()!!
            .getSharedPreferences("config", 0)
            .registerOnSharedPreferenceChangeListener(changes)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "config") {
            val p = getContext()!!.getSharedPreferences("config", 0)
            val b = Bundle()
            for (key in
                arrayOf<String>(
                    "enabled",
                    "clock",
                    "notifications",
                    "signals",
                    "battery",
                    "debug",
                    "fixed",
                    "motion",
                    "privacy_motion",
                )) b.putBoolean(
                key,
                p.getBoolean(
                    key,
                    key == "clock" ||
                        key == "notifications" ||
                        key == "signals" ||
                        key == "battery" ||
                        key == "motion",
                ),
            )
            b.putInt("hz", p.getInt("hz", 10))
            b.putBoolean("privacy_fallback", p.getBoolean("privacy_fallback", true))
            b.putInt(
                "fade_ms",
                ColorFade.clampDuration(p.getInt("fade_ms", ColorFade.DEFAULT_DURATION_MS)),
            )
            b.putInt(
                NetworkSpeedSpacing.KEY,
                NetworkSpeedSpacing.clamp(p.getInt(NetworkSpeedSpacing.KEY, 0)),
            )
            b.putString("algorithm", p.getString("algorithm", "robust"))
            b.putDouble("hysteresis", p.getFloat("hysteresis", .15f).toDouble())
            b.putString("guard_reset_token", p.getString("guard_reset_token", ""))
            return b
        }
        if (method == "motion_endpoint") {
            if (!callerIs("com.sec.android.app.launcher"))
                throw SecurityException("One UI Home required")
            val bundle = Bundle()
            val endpoint: IBinder? = motionEndpoint
            if (endpoint != null && endpoint.isBinderAlive())
                bundle.putBinder("motion_endpoint", endpoint)
            return bundle
        }
        if (method == "status" || method == "guard_status") {
            val system = callerIs("com.android.systemui")
            val launcher: Boolean =
                method == "guard_status" && callerIs("com.sec.android.app.launcher")
            if (!system && !launcher) throw SecurityException("Hook host required")
            if (!system && extras != null && extras.containsKey("motion_endpoint"))
                throw SecurityException("SystemUI required to register motion endpoint")
            if (extras != null && extras.getBinder("motion_endpoint") != null) {
                val endpoint = extras.getBinder("motion_endpoint")
                if (motionEndpoint !== endpoint) {
                    motionEndpoint = endpoint
                    getContext()!!
                        .getContentResolver()
                        .notifyChange(
                            ConfigProvider.URI!!.buildUpon().appendPath("motion_endpoint").build(),
                            null,
                        )
                }
            }
            if (method == "guard_status") {
                requireNotNull(extras) { "Guard status required" }
                val blocked = extras.getBoolean("blocked")
                getContext()!!
                    .getSharedPreferences(if (system) "guard" else "guard_launcher", 0)
                    .edit()
                    .putBoolean("blocked", blocked)
                    .putInt("count", extras.getInt("count"))
                    .putString("cause", extras.getString("cause", ""))
                    .apply()
                if (blocked)
                    getContext()!!
                        .getSharedPreferences("config", 0)
                        .edit()
                        .putBoolean("enabled", false)
                        .apply()
                return Bundle.EMPTY
            }
            getContext()!!
                .getSharedPreferences("status", 0)
                .edit()
                .putString("text", arg)
                .putLong("time", System.currentTimeMillis())
                .apply()
            return Bundle.EMPTY
        }
        throw IllegalArgumentException(method)
    }

    private fun callerIs(expected: String?): Boolean {
        val names = getContext()!!.getPackageManager().getPackagesForUid(Binder.getCallingUid())
        return names != null && Arrays.asList<String?>(*names).contains(expected)
    }

    override fun query(
        u: Uri,
        p: Array<String?>?,
        s: String?,
        a: Array<String?>?,
        o: String?,
    ): Cursor? {
        return null
    }

    override fun getType(u: Uri): String? {
        return null
    }

    override fun insert(u: Uri, v: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(u: Uri, s: String?, a: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    companion object {
        @JvmField val URI: Uri? = Uri.parse("content://dev.ryuya.galaxybar.config")
    }
}
