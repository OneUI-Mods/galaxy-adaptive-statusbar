package dev.ryuya.galaxybar;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;

public final class GalaxyBarApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    AppCompatDelegate.setDefaultNightMode(
        getSharedPreferences("config", MODE_PRIVATE)
            .getInt("ui_theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
    DynamicColors.applyToActivitiesIfAvailable(this);
  }
}
