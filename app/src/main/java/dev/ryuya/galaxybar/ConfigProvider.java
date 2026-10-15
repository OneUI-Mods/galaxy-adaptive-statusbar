package dev.ryuya.galaxybar;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Only non-sensitive preferences are publicly readable; diagnostic writes are SystemUI-only. */
public final class ConfigProvider extends ContentProvider {
  public static final Uri URI = Uri.parse("content://dev.ryuya.galaxybar.config");

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    if (method.equals("config")) {
      var p = getContext().getSharedPreferences("config", 0);
      Bundle b = new Bundle();
      for (String key :
          new String[] {
            "enabled", "clock", "notifications", "signals", "battery", "debug", "fixed"
          })
        b.putBoolean(
            key,
            p.getBoolean(
                key,
                key.equals("clock")
                    || key.equals("notifications")
                    || key.equals("signals")
                    || key.equals("battery")));
      b.putInt("hz", p.getInt("hz", 10));
      b.putInt(
          "fade_ms", ColorFade.clampDuration(p.getInt("fade_ms", ColorFade.DEFAULT_DURATION_MS)));
      b.putString("algorithm", p.getString("algorithm", "robust"));
      b.putDouble("hysteresis", p.getFloat("hysteresis", .15f));
      b.putString("guard_reset_token", p.getString("guard_reset_token", ""));
      return b;
    }
    if (method.equals("status") || method.equals("guard_status")) {
      boolean system = false;
      String[] names = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
      if (names != null)
        for (String name : names) if (name.equals("com.android.systemui")) system = true;
      if (!system) throw new SecurityException("SystemUI required");
      if (method.equals("guard_status")) {
        if (extras == null) throw new IllegalArgumentException("Guard status required");
        boolean blocked = extras.getBoolean("blocked");
        getContext()
            .getSharedPreferences("guard", 0)
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
