package dev.ryuya.galaxybar;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Only non-sensitive preferences are public; each hook host can report only its own guard. */
public final class ConfigProvider extends ContentProvider {
  public static final Uri URI = Uri.parse("content://dev.ryuya.galaxybar.config");
  private volatile IBinder motionEndpoint;
  // Keep a strong reference: SharedPreferences holds listeners weakly.
  private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changes =
      (prefs, key) ->
          getContext()
              .getContentResolver()
              .notifyChange(key == null ? URI : URI.buildUpon().appendPath(key).build(), null);

  @Override
  public boolean onCreate() {
    getContext()
        .getSharedPreferences("config", 0)
        .registerOnSharedPreferenceChangeListener(changes);
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    if (method.equals("config")) {
      var p = getContext().getSharedPreferences("config", 0);
      Bundle b = new Bundle();
      for (String key :
          new String[] {
            "enabled", "clock", "notifications", "signals", "battery", "debug", "fixed", "motion"
          })
        b.putBoolean(
            key,
            p.getBoolean(
                key,
                key.equals("clock")
                    || key.equals("notifications")
                    || key.equals("signals")
                    || key.equals("battery")
                    || key.equals("motion")));
      b.putInt("hz", p.getInt("hz", 10));
      b.putInt(
          "fade_ms", ColorFade.clampDuration(p.getInt("fade_ms", ColorFade.DEFAULT_DURATION_MS)));
      b.putString("algorithm", p.getString("algorithm", "robust"));
      b.putDouble("hysteresis", p.getFloat("hysteresis", .15f));
      b.putString("guard_reset_token", p.getString("guard_reset_token", ""));
      return b;
    }
    if (method.equals("motion_endpoint")) {
      if (!callerIs("com.sec.android.app.launcher"))
        throw new SecurityException("One UI Home required");
      Bundle bundle = new Bundle();
      IBinder endpoint = motionEndpoint;
      if (endpoint != null && endpoint.isBinderAlive())
        bundle.putBinder("motion_endpoint", endpoint);
      return bundle;
    }
    if (method.equals("status") || method.equals("guard_status")) {
      boolean system = callerIs("com.android.systemui");
      boolean launcher = method.equals("guard_status") && callerIs("com.sec.android.app.launcher");
      if (!system && !launcher) throw new SecurityException("Hook host required");
      if (!system && extras != null && extras.containsKey("motion_endpoint"))
        throw new SecurityException("SystemUI required to register motion endpoint");
      if (extras != null && extras.getBinder("motion_endpoint") != null) {
        IBinder endpoint = extras.getBinder("motion_endpoint");
        if (motionEndpoint != endpoint) {
          motionEndpoint = endpoint;
          getContext()
              .getContentResolver()
              .notifyChange(URI.buildUpon().appendPath("motion_endpoint").build(), null);
        }
      }
      if (method.equals("guard_status")) {
        if (extras == null) throw new IllegalArgumentException("Guard status required");
        boolean blocked = extras.getBoolean("blocked");
        getContext()
            .getSharedPreferences(system ? "guard" : "guard_launcher", 0)
            .edit()
            .putBoolean("blocked", blocked)
            .putInt("count", extras.getInt("count"))
            .putString("cause", extras.getString("cause", ""))
            .apply();
        if (blocked)
          getContext()
              .getSharedPreferences("config", 0)
              .edit()
              .putBoolean("enabled", false)
              .apply();
        return Bundle.EMPTY;
      }
      getContext()
          .getSharedPreferences("status", 0)
          .edit()
          .putString("text", arg)
          .putLong("time", System.currentTimeMillis())
          .apply();
      return Bundle.EMPTY;
    }
    throw new IllegalArgumentException(method);
  }

  private boolean callerIs(String expected) {
    String[] names = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
    return names != null && java.util.Arrays.asList(names).contains(expected);
  }

  @Override
  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    return null;
  }

  @Override
  public String getType(Uri u) {
    return null;
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
