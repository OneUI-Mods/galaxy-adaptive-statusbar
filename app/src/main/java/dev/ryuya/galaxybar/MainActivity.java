package dev.ryuya.galaxybar;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity {
  private SettingsTabs pages;
  private SharedPreferences prefs;
  private MaterialTextView status;
  private MaterialTextView guardSummary;
  private MaterialSwitch enabledControl;
  private MaterialButton guardResetControl;
  private boolean guardResetting;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          SharedPreferences latest = getSharedPreferences("status", MODE_PRIVATE);
          long age = (System.currentTimeMillis() - latest.getLong("time", 0)) / 1000;
          boolean blocked = protectionBlocked();
          enabledControl.setEnabled(!blocked);
          enabledControl.setChecked(!blocked && prefs.getBoolean("enabled", false));
          guardSummary.setText(getString(blocked ? R.string.guard_blocked : R.string.guard_note));
          if (!blocked) guardResetting = false;
          guardResetControl.setEnabled(blocked && !guardResetting);
          status.setText(
              blocked
                  ? getString(R.string.guard_blocked)
                  : age < 15
                      ? latest.getString("text", "")
                          + "\n"
                          + getString(R.string.updated_seconds, age)
                      : getString(R.string.waiting_hook));
          handler.postDelayed(this, 2000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("config", MODE_PRIVATE);
    if (protectionBlocked()) prefs.edit().putBoolean("enabled", false).apply();
    if (getIntent().getBooleanExtra("disable", false)) {
      prefs.edit().putBoolean("enabled", false).apply();
      getIntent().removeExtra("disable");
    }
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    LinearLayout safe = column(0);
    setContentView(safe);
    ViewCompat.setOnApplyWindowInsetsListener(
        safe,
        (view, insets) -> {
          Insets area =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(area.left, area.top, area.right, area.bottom);
          return insets;
        });
    ViewCompat.requestApplyInsets(safe);
    MaterialToolbar toolbar = new MaterialToolbar(this);
    toolbar.setTitle(getString(R.string.app_name));
    safe.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
    pages = new SettingsTabs(this, safe, state);
    LinearLayout body = pages.page(R.string.tab_colors);
    LinearLayout adjustments = pages.page(R.string.tab_adjustments);
    LinearLayout operation = pages.page(R.string.tab_status);
    LinearLayout information = pages.page(R.string.tab_about);
    LinearLayout hero = card(body);
    ((MaterialCardView) hero.getParent())
        .setCardBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(
                hero, com.google.android.material.R.attr.colorPrimaryContainer));
    MaterialSwitch enabled = toggle(hero, getString(R.string.enabled), "enabled", false);
    enabledControl = enabled;
    enabled.setId(R.id.module_enabled);
    MaterialCardView preview = new MaterialCardView(this);
    preview.setRadius(dp(20));
    preview.setStrokeWidth(0);
    preview.setClickable(true);
    preview.setFocusable(true);
    preview.setContentDescription(getString(R.string.open_pattern));
    preview.addView(new ColorPreview(this), new android.view.ViewGroup.LayoutParams(-1, dp(88)));
    preview.setOnClickListener(v -> startActivity(new Intent(this, PatternActivity.class)));
    LinearLayout.LayoutParams previewPosition = new LinearLayout.LayoutParams(-1, dp(88));
    previewPosition.topMargin = dp(12);
    hero.addView(preview, previewPosition);
    LinearLayout motionCard = card(adjustments);
    MaterialSwitch motion = toggle(motionCard, getString(R.string.motion_enabled), "motion", true);
    motion.setId(R.id.motion_enabled);
    text(
        motionCard,
        getString(R.string.motion_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    LinearLayout targets = card(body);
    text(
        targets,
        getString(R.string.color_targets),
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    toggle(targets, getString(R.string.clock), "clock", true);
    toggle(targets, getString(R.string.notifications), "notifications", true);
    toggle(targets, getString(R.string.signals), "signals", true);
    toggle(targets, getString(R.string.battery), "battery", true);
    text(
        targets,
        getString(R.string.battery_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    LinearLayout sampling = card(body);
    int[] rates = {5, 10, 15, 30};
    String[] rateLabels = {"5 Hz", "10 Hz", "15 Hz", getString(R.string.rate_30)};
    int selected = 1;
    for (int i = 0; i < rates.length; i++) if (rates[i] == prefs.getInt("hz", 10)) selected = i;
    dropdown(
        sampling,
        getString(R.string.frequency),
        rateLabels,
        selected,
        index -> prefs.edit().putInt("hz", rates[index]).apply());
    text(
        sampling,
        getString(R.string.power_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    LinearLayout fade = card(adjustments);
    MaterialTextView fadeLabel =
        text(fade, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
    fadeLabel.setId(R.id.fade_duration_value);
    Slider fadeSlider = new Slider(this);
    fadeSlider.setId(R.id.fade_duration_slider);
    fadeSlider.setContentDescription(getString(R.string.fade_duration));
    fadeSlider.setValueFrom(0);
    fadeSlider.setValueTo(ColorFade.MAX_DURATION_MS);
    fadeSlider.setStepSize(ColorFade.STEP_MS);
    fadeSlider.setValue(
        ColorFade.clampDuration(prefs.getInt("fade_ms", ColorFade.DEFAULT_DURATION_MS)));
    fadeSlider.setLabelFormatter(value -> getString(R.string.fade_milliseconds, Math.round(value)));
    fadeLabel.setText(getString(R.string.fade_duration_value, Math.round(fadeSlider.getValue())));
    fadeSlider.addOnChangeListener(
        (control, value, fromUser) -> {
          int duration = Math.round(value);
          fadeLabel.setText(getString(R.string.fade_duration_value, duration));
          if (fromUser) prefs.edit().putInt("fade_ms", duration).apply();
        });
    fade.addView(fadeSlider);
    text(
        fade,
        getString(R.string.fade_duration_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    if (android.os.Build.VERSION.SDK_INT == 37) {
      addNetworkSpeedSettings(adjustments);
      LinearLayout privacy = card(adjustments);
      text(
          privacy,
          getString(R.string.privacy_motion_title),
          com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
      toggle(privacy, getString(R.string.privacy_motion), "privacy_motion", false);
      text(
          privacy,
          getString(R.string.privacy_motion_note),
          com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
      toggle(privacy, getString(R.string.privacy_fallback), "privacy_fallback", true);
      text(
          privacy,
          getString(R.string.privacy_fallback_note),
          com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    }
    LinearLayout analysis = card(adjustments);
    String[] keys = {"robust", "mean", "median", "vote"};
    String[] labels = {
      getString(R.string.robust),
      getString(R.string.mean),
      getString(R.string.median),
      getString(R.string.vote)
    };
    selected = 0;
    for (int i = 0; i < keys.length; i++)
      if (keys[i].equals(prefs.getString("algorithm", "robust"))) selected = i;
    dropdown(
        analysis,
        getString(R.string.algorithm),
        labels,
        selected,
        index -> prefs.edit().putString("algorithm", keys[index]).apply());
    MaterialTextView suppression =
        text(
            analysis,
            getString(R.string.hysteresis),
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
    Slider slider = new Slider(this);
    slider.setValueFrom(0);
    slider.setValueTo(50);
    slider.setStepSize(1);
    slider.setValue(Math.round(prefs.getFloat("hysteresis", .15f) * 100));
    suppression.setText(getString(R.string.hysteresis_value, Math.round(slider.getValue())));
    slider.addOnChangeListener(
        (control, value, fromUser) -> {
          suppression.setText(getString(R.string.hysteresis_value, Math.round(value)));
          if (fromUser) prefs.edit().putFloat("hysteresis", value / 100f).apply();
        });
    analysis.addView(slider);
    button(
        adjustments,
        getString(R.string.open_pattern),
        () -> startActivity(new Intent(this, PatternActivity.class)));
    button(
        adjustments,
        getString(R.string.restore),
        () -> {
          prefs.edit().putBoolean("enabled", false).apply();
          enabled.setChecked(false);
        });
    LinearLayout diagnostic = card(operation);
    text(
        diagnostic,
        getString(R.string.status),
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    status =
        text(
            diagnostic,
            "",
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    status.setTextIsSelectable(true);
    text(
        diagnostic,
        getString(R.string.experimental_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    toggle(diagnostic, getString(R.string.fixed_test), "fixed", false);
    toggle(diagnostic, getString(R.string.debug), "debug", false);
    text(
        diagnostic,
        getString(R.string.guard_title),
        com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
    guardSummary =
        text(
            diagnostic,
            getString(protectionBlocked() ? R.string.guard_blocked : R.string.guard_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    guardResetControl = button(diagnostic, getString(R.string.guard_reset), this::resetCrashGuard);
    guardResetControl.setId(R.id.guard_reset);
    guardResetControl.setEnabled(protectionBlocked());
    text(
        diagnostic,
        getString(R.string.guard_reload_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    button(diagnostic, getString(R.string.root_check), this::checkRoot);
    LinearLayout appearance = card(information);
    text(
        appearance,
        getString(R.string.appearance),
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    String[] languageTags = {"", "ja", "en", "es", "ko", "zh-Hans"};
    String[] languageNames = {
      getString(R.string.system_default), "日本語", "English", "Español", "한국어", "简体中文"
    };
    String currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags();
    int languageIndex = 0;
    for (int i = 1; i < languageTags.length; i++)
      if (currentLocale.equals(languageTags[i])) languageIndex = i;
    dropdown(
        appearance,
        getString(R.string.language),
        languageNames,
        languageIndex,
        index ->
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTags[index])));
    int[] themes = {
      AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
      AppCompatDelegate.MODE_NIGHT_NO,
      AppCompatDelegate.MODE_NIGHT_YES
    };
    int themeIndex = 0;
    for (int i = 0; i < themes.length; i++)
      if (themes[i] == prefs.getInt("ui_theme", -1)) themeIndex = i;
    dropdown(
        appearance,
        getString(R.string.theme),
        new String[] {
          getString(R.string.system_default), getString(R.string.light), getString(R.string.dark)
        },
        themeIndex,
        index -> {
          prefs.edit().putInt("ui_theme", themes[index]).apply();
          AppCompatDelegate.setDefaultNightMode(themes[index]);
        });
    button(
        information,
        getString(R.string.about),
        () -> startActivity(new Intent(this, AboutActivity.class)));
    text(
        information,
        getString(R.string.version, BuildConfig.VERSION_NAME),
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    button(
        information,
        getString(R.string.legal_title),
        () -> startActivity(new Intent(this, LicensesActivity.class)));
    pages.restore();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column(int padding) {
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
    return layout;
  }

  private LinearLayout card(LinearLayout parent) {
    MaterialCardView card = new MaterialCardView(this);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(16));
    card.setRadius(dp(28));
    card.setStrokeWidth(0);
    card.setCardBackgroundColor(
        com.google.android.material.color.MaterialColors.getColor(
            card, com.google.android.material.R.attr.colorSurfaceContainerLow));
    parent.addView(card, params);
    LinearLayout content = column(20);
    card.addView(content);
    return content;
  }

  private MaterialTextView text(LinearLayout parent, String value, int appearance) {
    MaterialTextView text = new MaterialTextView(this);
    text.setTextAppearance(appearance);
    text.setText(value);
    text.setLineSpacing(dp(2), 1f);
    if (appearance == com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
      text.setTextColor(
          com.google.android.material.color.MaterialColors.getColor(
              text, com.google.android.material.R.attr.colorOnSurfaceVariant));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(8), 0, dp(8));
    parent.addView(text, params);
    return text;
  }

  private MaterialSwitch toggle(
      LinearLayout parent, String label, String key, boolean defaultValue) {
    MaterialSwitch control = new MaterialSwitch(this);
    control.setText(label);
    control.setMinHeight(dp(60));
    control.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    boolean protectedMaster = key.equals("enabled") && protectionBlocked();
    control.setChecked(!protectedMaster && prefs.getBoolean(key, defaultValue));
    if (key.equals("enabled")) control.setEnabled(!protectedMaster);
    control.setOnCheckedChangeListener(
        (button, checked) -> {
          if (key.equals("enabled") && checked && protectionBlocked()) {
            control.setChecked(false);
            return;
          }
          prefs.edit().putBoolean(key, checked).apply();
        });
    parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
    return control;
  }

  private interface Selection {
    void choose(int index);
  }

  private void dropdown(
      LinearLayout parent, String hint, String[] labels, int selected, Selection selection) {
    TextInputLayout field =
        new TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputFilledExposedDropdownMenuStyle);
    field.setHint(hint);
    MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(field.getContext());
    input.setInputType(android.text.InputType.TYPE_NULL);
    input.setAdapter(new ArrayAdapter<>(this, R.layout.dropdown_item, labels));
    input.setText(labels[selected], false);
    input.setOnItemClickListener((adapter, view, index, itemId) -> selection.choose(index));
    field.addView(input, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(12), 0, dp(12));
    parent.addView(field, params);
  }

  private MaterialButton button(LinearLayout parent, String label, Runnable action) {
    MaterialButton button =
        new MaterialButton(
            new androidx.appcompat.view.ContextThemeWrapper(this, R.style.TonalActionTheme));
    button.setMinHeight(dp(52));
    button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
    button.setCornerRadius(dp(20));
    button.setText(label);
    button.setOnClickListener(view -> action.run());
    LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
    position.setMargins(0, dp(4), 0, dp(8));
    parent.addView(button, position);
    return button;
  }

  private void addNetworkSpeedSettings(LinearLayout parent) {
    LinearLayout container = card(parent);
    text(
        container,
        getString(R.string.network_speed_style),
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    LinearLayout row = new LinearLayout(this);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    container.addView(row, new LinearLayout.LayoutParams(-1, -2));
    MaterialTextView label =
        text(row, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
    label.setId(R.id.network_speed_spacing_value);
    label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    MaterialCardView sample = new MaterialCardView(this);
    sample.setRadius(dp(16));
    sample.setStrokeWidth(0);
    sample.setCardBackgroundColor(
        com.google.android.material.color.MaterialColors.getColor(
            sample, com.google.android.material.R.attr.colorSurfaceContainerHigh));
    MaterialTextView preview = new MaterialTextView(this);
    preview.setText("12.3\nK/s");
    preview.setTextSize(18);
    preview.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    preview.setGravity(android.view.Gravity.CENTER);
    preview.setIncludeFontPadding(false);
    preview.setPadding(dp(10), dp(12), dp(10), dp(12));
    preview.setContentDescription(getString(R.string.network_speed_sample));
    sample.addView(preview, new android.view.ViewGroup.LayoutParams(-1, -2));
    LinearLayout.LayoutParams samplePosition = new LinearLayout.LayoutParams(dp(84), -2);
    samplePosition.setMargins(dp(12), dp(12), 0, dp(12));
    row.addView(sample, samplePosition);
    Slider slider = new Slider(this);
    slider.setId(R.id.network_speed_spacing_slider);
    slider.setContentDescription(getString(R.string.network_speed_spacing));
    slider.setValueFrom(NetworkSpeedSpacing.MIN_DP);
    slider.setValueTo(NetworkSpeedSpacing.MAX_DP);
    slider.setStepSize(1);
    slider.setValue(NetworkSpeedSpacing.clamp(prefs.getInt(NetworkSpeedSpacing.KEY, 0)));
    slider.setLabelFormatter(value -> spacingLabel(Math.round(value), false));
    java.util.function.IntConsumer update =
        value -> {
          label.setText(spacingLabel(value, true));
          preview.setLineSpacing(dp(-2) + value * getResources().getDisplayMetrics().density, 1);
        };
    update.accept(Math.round(slider.getValue()));
    slider.addOnChangeListener(
        (control, value, fromUser) -> {
          int spacing = Math.round(value);
          update.accept(spacing);
          if (fromUser) prefs.edit().putInt(NetworkSpeedSpacing.KEY, spacing).apply();
        });
    container.addView(slider);
    text(
        container,
        getString(R.string.network_speed_spacing_note),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    button(
        container,
        getString(R.string.network_speed_spacing_reset),
        () -> {
          slider.setValue(0);
          prefs.edit().putInt(NetworkSpeedSpacing.KEY, 0).apply();
        });
  }

  private String spacingLabel(int value, boolean title) {
    return value == 0
        ? getString(R.string.network_speed_spacing_standard)
        : getString(
            title ? R.string.network_speed_spacing_value : R.string.network_speed_spacing_amount,
            value);
  }

  private void resetCrashGuard() {
    guardResetting = true;
    guardResetControl.setEnabled(false);
    new Thread(
            () -> {
              boolean success = false;
              try {
                boolean saved =
                    prefs
                        .edit()
                        .putString("guard_reset_token", java.util.UUID.randomUUID().toString())
                        .putBoolean("enabled", false)
                        .commit();
                if (!saved) throw new IllegalStateException("Reset request could not be saved");
                Process process =
                    new ProcessBuilder(
                            "su",
                            "-c",
                            "kill -TERM $(pidof com.sec.android.app.launcher) $(pidof"
                                + " com.android.systemui)")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroy();
                else success = process.exitValue() == 0;
              } catch (Exception ignored) {
              }
              boolean completed = success;
              runOnUiThread(
                  () -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!completed) guardResetting = false;
                    guardResetControl.setEnabled(!completed);
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.guard_title))
                        .setMessage(
                            getString(
                                completed ? R.string.guard_reloaded : R.string.guard_reload_failed))
                        .setPositiveButton(getString(R.string.close), null)
                        .show();
                  });
            },
            "GalaxyBarGuardReset")
        .start();
  }

  private boolean protectionBlocked() {
    return getSharedPreferences("guard", MODE_PRIVATE).getBoolean("blocked", false)
        || getSharedPreferences("guard_launcher", MODE_PRIVATE).getBoolean("blocked", false);
  }

  private void checkRoot() {
    new Thread(
            () -> {
              String result;
              try {
                Process process =
                    new ProcessBuilder("su", "-c", "id; cat /proc/self/attr/current")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                  process.destroy();
                  result = getString(R.string.root_timeout);
                } else result = new String(process.getInputStream().readAllBytes());
              } catch (Exception error) {
                result = error.toString();
              }
              String message = result;
              runOnUiThread(
                  () -> {
                    if (!isFinishing() && !isDestroyed())
                      new MaterialAlertDialogBuilder(this)
                          .setTitle(getString(R.string.root_status))
                          .setMessage(message)
                          .setPositiveButton(getString(R.string.close), null)
                          .show();
                  });
            },
            "GalaxyBarRootCheck")
        .start();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    boolean disable = intent.getBooleanExtra("disable", false);
    intent.removeExtra("disable");
    setIntent(intent);
    if (disable) {
      prefs.edit().putBoolean("enabled", false).apply();
      recreate();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (pages != null) pages.save(out);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }
}
