package dev.ryuya.galaxybar;

import android.content.*;
import android.os.UserManager;

/** CE settings stay untouched until unlock; reboot never overrides OFF or crash protection. */
final class Startup {
  static void restore(Context context) {
    if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
    context.getSharedPreferences("config", Context.MODE_PRIVATE); // Start the settings process.
    GalaxyBarApplication.applyTheme(context);
    context.getContentResolver().notifyChange(ConfigProvider.URI, null);
  }
}
