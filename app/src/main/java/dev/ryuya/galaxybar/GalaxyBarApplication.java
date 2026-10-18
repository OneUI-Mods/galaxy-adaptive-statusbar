package dev.ryuya.galaxybar;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;

public final class GalaxyBarApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    applyTheme(this);
    DynamicColors.applyToActivitiesIfAvailable(this);
  }

  static void applyTheme(android.content.Context context) {
    if (!context.getSystemService(android.os.UserManager.class).isUserUnlocked()) return;
    AppCompatDelegate.setDefaultNightMode(
        context
            .getSharedPreferences("config", MODE_PRIVATE)
            .getInt("ui_theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
  }
}
