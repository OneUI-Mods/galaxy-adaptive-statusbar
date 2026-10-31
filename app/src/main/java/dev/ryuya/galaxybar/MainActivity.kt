package dev.ryuya.galaxybar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.View.OnClickListener
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.os.LocaleListCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.IntConsumer
import kotlin.math.round

class MainActivity : AppCompatActivity() {
    private var pages: SettingsTabs? = null
    private var prefs: SharedPreferences? = null
    private var status: MaterialTextView? = null
    private var guardSummary: MaterialTextView? = null
    private var enabledControl: MaterialSwitch? = null
    private var guardResetControl: MaterialButton? = null
    private var guardResetting: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val refresh: Runnable =
        object : Runnable {
            override fun run() {
                val latest = getSharedPreferences("status", Context.MODE_PRIVATE)
                val age: Long = (System.currentTimeMillis() - latest.getLong("time", 0)) / 1000
                val blocked = protectionBlocked()
                enabledControl!!.setEnabled(!blocked)
                enabledControl!!.setChecked(!blocked && prefs!!.getBoolean("enabled", false))
                guardSummary!!.setText(
                    getString(if (blocked) R.string.guard_blocked else R.string.guard_note)
                )
                if (!blocked) guardResetting = false
                guardResetControl!!.setEnabled(blocked && !guardResetting)
                status!!.setText(
                    if (blocked) getString(R.string.guard_blocked)
                    else if (age < 15)
                        (latest.getString("text", "") +
                            "\n" +
                            getString(R.string.updated_seconds, age))
                    else getString(R.string.waiting_hook)
                )
                handler.postDelayed(this, 2000)
            }
        }

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        if (protectionBlocked()) prefs!!.edit().putBoolean("enabled", false).apply()
        if (getIntent().getBooleanExtra("disable", false)) {
            prefs!!.edit().putBoolean("enabled", false).apply()
            getIntent().removeExtra("disable")
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val safe = column(0)
        setContentView(safe)
        ViewCompat.setOnApplyWindowInsetsListener(
            safe,
            OnApplyWindowInsetsListener { view: View?, insets: WindowInsetsCompat? ->
                val area = insets!!.getInsets(Type.systemBars() or Type.displayCutout())
                view!!.setPadding(area.left, area.top, area.right, area.bottom)
                insets!!
            },
        )
        ViewCompat.requestApplyInsets(safe)
        val toolbar = MaterialToolbar(this)
        toolbar.setTitle(getString(R.string.app_name))
        safe.addView(toolbar, android.widget.LinearLayout.LayoutParams(-1, dp(64)))
        pages = SettingsTabs(this, safe, state)
        val body = pages!!.page(R.string.tab_colors)
        val adjustments = pages!!.page(R.string.tab_adjustments)
        val operation = pages!!.page(R.string.tab_status)
        val information = pages!!.page(R.string.tab_about)
        val hero = card(body)
        (hero.getParent() as MaterialCardView).setCardBackgroundColor(
            MaterialColors.getColor(
                hero,
                com.google.android.material.R.attr.colorPrimaryContainer,
            )
        )
        val enabled = toggle(hero, getString(R.string.enabled), "enabled", false)
        enabledControl = enabled
        enabled.setId(R.id.module_enabled)
        val preview = MaterialCardView(this)
        preview.setRadius(dp(20).toFloat())
        preview.setStrokeWidth(0)
        preview.setClickable(true)
        preview.setFocusable(true)
        preview.setContentDescription(getString(R.string.open_pattern))
        preview.addView(ColorPreview(this), android.view.ViewGroup.LayoutParams(-1, dp(88)))
        preview.setOnClickListener(
            OnClickListener { v: View? -> startActivity(Intent(this, PatternActivity::class.java)) }
        )
        val previewPosition = android.widget.LinearLayout.LayoutParams(-1, dp(88))
        previewPosition.topMargin = dp(12)
        hero.addView(preview, previewPosition)
        val motionCard = card(adjustments)
        val motion = toggle(motionCard, getString(R.string.motion_enabled), "motion", true)
        motion.setId(R.id.motion_enabled)
        text(
            motionCard,
            getString(R.string.motion_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        val targets = card(body)
        text(
            targets,
            getString(R.string.color_targets),
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        toggle(targets, getString(R.string.clock), "clock", true)
        toggle(targets, getString(R.string.notifications), "notifications", true)
        toggle(targets, getString(R.string.signals), "signals", true)
        toggle(targets, getString(R.string.battery), "battery", true)
        text(
            targets,
            getString(R.string.battery_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        val sampling = card(body)
        val rates = intArrayOf(5, 10, 15, 30)
        val rateLabels = arrayOf<String?>("5 Hz", "10 Hz", "15 Hz", getString(R.string.rate_30))
        var selected: Int = 1
        for (i in rates.indices) if (rates[i] == prefs!!.getInt("hz", 10)) selected = i
        dropdown(
            sampling,
            getString(R.string.frequency),
            rateLabels,
            selected,
            MainActivity.Selection { index: Int ->
                prefs!!.edit().putInt("hz", rates[index]).apply()
            },
        )
        text(
            sampling,
            getString(R.string.power_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        val fade = card(adjustments)
        val fadeLabel =
            text(fade, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        fadeLabel.setId(R.id.fade_duration_value)
        val fadeSlider = Slider(this)
        fadeSlider.setId(R.id.fade_duration_slider)
        fadeSlider.setContentDescription(getString(R.string.fade_duration))
        fadeSlider.setValueFrom(0f)
        fadeSlider.setValueTo(ColorFade.MAX_DURATION_MS.toFloat())
        fadeSlider.setStepSize(ColorFade.STEP_MS.toFloat())
        fadeSlider.setValue(
            ColorFade.clampDuration(prefs!!.getInt("fade_ms", ColorFade.DEFAULT_DURATION_MS))
                .toFloat()
        )
        fadeSlider.setLabelFormatter(
            LabelFormatter { value: Float ->
                getString(R.string.fade_milliseconds, Math.round(value))
            }
        )
        fadeLabel.setText(
            getString(R.string.fade_duration_value, Math.round(fadeSlider.getValue()))
        )
        fadeSlider.addOnChangeListener(
            OnChangeListener { control: Slider?, value: Float, fromUser: Boolean ->
                val duration = Math.round(value)
                fadeLabel.setText(getString(R.string.fade_duration_value, duration))
                if (fromUser) prefs!!.edit().putInt("fade_ms", duration).apply()
            }
        )
        fade.addView(fadeSlider)
        text(
            fade,
            getString(R.string.fade_duration_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        if (Build.VERSION.SDK_INT == 37) {
            val charging = card(adjustments)
            toggle(charging, getString(R.string.charging_motion), "charging_motion", true)
            text(
                charging,
                getString(R.string.charging_motion_note),
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
            addNetworkSpeedSettings(adjustments)
            val privacy = card(adjustments)
            text(
                privacy,
                getString(R.string.privacy_motion_title),
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
            )
            toggle(privacy, getString(R.string.privacy_motion), "privacy_motion", false)
            text(
                privacy,
                getString(R.string.privacy_motion_note),
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
            toggle(privacy, getString(R.string.privacy_fallback), "privacy_fallback", true)
            text(
                privacy,
                getString(R.string.privacy_fallback_note),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
            )
        }
        val analysis = card(adjustments)
        val keys = arrayOf<String?>("robust", "mean", "median", "vote")
        val labels =
            arrayOf<String?>(
                getString(R.string.robust),
                getString(R.string.mean),
                getString(R.string.median),
                getString(R.string.vote),
            )
        selected = 0
        for (i in keys.indices) if (keys[i] == prefs!!.getString("algorithm", "robust"))
            selected = i
        dropdown(
            analysis,
            getString(R.string.algorithm),
            labels,
            selected,
            MainActivity.Selection { index: Int ->
                prefs!!.edit().putString("algorithm", keys[index]).apply()
            },
        )
        val suppression =
            text(
                analysis,
                getString(R.string.hysteresis),
                com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
            )
        val slider = Slider(this)
        slider.setValueFrom(0f)
        slider.setValueTo(50f)
        slider.setStepSize(1f)
        slider.setValue(Math.round(prefs!!.getFloat("hysteresis", .15f) * 100).toFloat())
        suppression.setText(getString(R.string.hysteresis_value, Math.round(slider.getValue())))
        slider.addOnChangeListener(
            OnChangeListener { control: Slider?, value: Float, fromUser: Boolean ->
                suppression.setText(getString(R.string.hysteresis_value, Math.round(value)))
                if (fromUser) prefs!!.edit().putFloat("hysteresis", value / 100f).apply()
            }
        )
        analysis.addView(slider)
        button(
            adjustments,
            getString(R.string.open_pattern),
            Runnable { startActivity(Intent(this, PatternActivity::class.java)) },
        )
        button(
            adjustments,
            getString(R.string.restore),
            Runnable {
                prefs!!.edit().putBoolean("enabled", false).apply()
                enabled.setChecked(false)
            },
        )
        val diagnostic = card(operation)
        text(
            diagnostic,
            getString(R.string.status),
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        status =
            text(
                diagnostic,
                "",
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
        status!!.setTextIsSelectable(true)
        text(
            diagnostic,
            getString(R.string.experimental_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        toggle(diagnostic, getString(R.string.fixed_test), "fixed", false)
        toggle(diagnostic, getString(R.string.debug), "debug", false)
        text(
            diagnostic,
            getString(R.string.guard_title),
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
        )
        guardSummary =
            text(
                diagnostic,
                getString(if (protectionBlocked()) R.string.guard_blocked else R.string.guard_note),
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
        guardResetControl =
            button(diagnostic, getString(R.string.guard_reset), Runnable { this.resetCrashGuard() })
        guardResetControl!!.setId(R.id.guard_reset)
        guardResetControl!!.setEnabled(protectionBlocked())
        text(
            diagnostic,
            getString(R.string.guard_reload_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        button(diagnostic, getString(R.string.root_check), Runnable { this.checkRoot() })
        val appearance = card(information)
        text(
            appearance,
            getString(R.string.appearance),
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        val languageTags = arrayOf<String?>("", "ja", "en", "es", "ko", "zh-Hans")
        val languageNames =
            arrayOf<String?>(
                getString(R.string.system_default),
                "日本語",
                "English",
                "Español",
                "한국어",
                "简体中文",
            )
        val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        var languageIndex: Int = 0
        for (i in 1 until languageTags.size) if (currentLocale == languageTags[i]) languageIndex = i
        dropdown(
            appearance,
            getString(R.string.language),
            languageNames,
            languageIndex,
            MainActivity.Selection { index: Int ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languageTags[index])
                )
            },
        )
        val themes =
            intArrayOf(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_YES,
            )
        var themeIndex: Int = 0
        for (i in themes.indices) if (themes[i] == prefs!!.getInt("ui_theme", -1)) themeIndex = i
        dropdown(
            appearance,
            getString(R.string.theme),
            arrayOf<String?>(
                getString(R.string.system_default),
                getString(R.string.light),
                getString(R.string.dark),
            ),
            themeIndex,
            MainActivity.Selection { index: Int ->
                prefs!!.edit().putInt("ui_theme", themes[index]).apply()
                AppCompatDelegate.setDefaultNightMode(themes[index])
            },
        )
        button(
            information,
            getString(R.string.about),
            Runnable { startActivity(Intent(this, AboutActivity::class.java)) },
        )
        text(
            information,
            getString(R.string.version, BuildConfig.VERSION_NAME),
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        button(
            information,
            getString(R.string.legal_title),
            Runnable { startActivity(Intent(this, LicensesActivity::class.java)) },
        )
        pages!!.restore()
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    private fun column(padding: Int): LinearLayout {
        val layout = LinearLayout(this)
        layout.setOrientation(LinearLayout.VERTICAL)
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        return layout
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val card = MaterialCardView(this)
        val params = android.widget.LinearLayout.LayoutParams(-1, -2)
        params.setMargins(0, 0, 0, dp(16))
        card.setRadius(dp(28).toFloat())
        card.setStrokeWidth(0)
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
            )
        )
        parent.addView(card, params)
        val content = column(20)
        card.addView(content)
        return content
    }

    private fun text(parent: LinearLayout, value: String?, appearance: Int): MaterialTextView {
        val text = MaterialTextView(this)
        text.setTextAppearance(appearance)
        text.setText(value)
        text.setLineSpacing(dp(2).toFloat(), 1f)
        if (appearance == com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            text.setTextColor(
                MaterialColors.getColor(
                    text,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )
        val params = android.widget.LinearLayout.LayoutParams(-1, -2)
        params.setMargins(0, dp(8), 0, dp(8))
        parent.addView(text, params)
        return text
    }

    private fun toggle(
        parent: LinearLayout,
        label: String?,
        key: String,
        defaultValue: Boolean,
    ): MaterialSwitch {
        val control = MaterialSwitch(this)
        control.setText(label)
        control.setMinHeight(dp(60))
        control.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
        )
        val protectedMaster: Boolean = key == "enabled" && protectionBlocked()
        control.setChecked(!protectedMaster && prefs!!.getBoolean(key, defaultValue))
        if (key == "enabled") control.setEnabled(!protectedMaster)
        control.setOnCheckedChangeListener(
            OnCheckedChangeListener { button: CompoundButton?, checked: Boolean ->
                if (key == "enabled" && checked && protectionBlocked()) {
                    control.setChecked(false)
                    return@OnCheckedChangeListener
                }
                prefs!!.edit().putBoolean(key, checked).apply()
            }
        )
        parent.addView(control, android.widget.LinearLayout.LayoutParams(-1, -2))
        return control
    }

    private fun interface Selection {
        fun choose(index: Int)
    }

    private fun dropdown(
        parent: LinearLayout,
        hint: String?,
        labels: Array<String?>,
        selected: Int,
        selection: MainActivity.Selection,
    ) {
        val field =
            TextInputLayout(
                this,
                null,
                com.google.android.material.R.attr.textInputFilledExposedDropdownMenuStyle,
            )
        field.setHint(hint)
        val input = MaterialAutoCompleteTextView(field.getContext())
        input.setInputType(InputType.TYPE_NULL)
        input.setAdapter<ArrayAdapter<String?>?>(
            ArrayAdapter<String?>(this, R.layout.dropdown_item, labels)
        )
        input.setText(labels[selected], false)
        input.setOnItemClickListener(
            OnItemClickListener { adapter: AdapterView<*>?, view: View?, index: Int, itemId: Long ->
                selection.choose(index)
            }
        )
        field.addView(input, android.widget.LinearLayout.LayoutParams(-1, -2))
        val params = android.widget.LinearLayout.LayoutParams(-1, -2)
        params.setMargins(0, dp(12), 0, dp(12))
        parent.addView(field, params)
    }

    private fun button(parent: LinearLayout, label: String?, action: Runnable): MaterialButton {
        val button = MaterialButton(ContextThemeWrapper(this, R.style.TonalActionTheme))
        button.setMinHeight(dp(52))
        button.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
        button.setCornerRadius(dp(20))
        button.setText(label)
        button.setOnClickListener(OnClickListener { view: View? -> action.run() })
        val position = android.widget.LinearLayout.LayoutParams(-1, -2)
        position.setMargins(0, dp(4), 0, dp(8))
        parent.addView(button, position)
        return button
    }

    private fun addNetworkSpeedSettings(parent: LinearLayout) {
        val container = card(parent)
        text(
            container,
            getString(R.string.network_speed_style),
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        val row = LinearLayout(this)
        row.setGravity(Gravity.CENTER_VERTICAL)
        container.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
        val label =
            text(row, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        label.setId(R.id.network_speed_spacing_value)
        label.setLayoutParams(android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        val sample = MaterialCardView(this)
        sample.setRadius(dp(16).toFloat())
        sample.setStrokeWidth(0)
        sample.setCardBackgroundColor(
            MaterialColors.getColor(
                sample,
                com.google.android.material.R.attr.colorSurfaceContainerHigh,
            )
        )
        val preview = MaterialTextView(this)
        preview.setText("12.3\nK/s")
        preview.setTextSize(18f)
        preview.setTypeface(Typeface.DEFAULT_BOLD)
        preview.setGravity(Gravity.CENTER)
        preview.setIncludeFontPadding(false)
        preview.setPadding(dp(10), dp(12), dp(10), dp(12))
        preview.setContentDescription(getString(R.string.network_speed_sample))
        sample.addView(preview, android.view.ViewGroup.LayoutParams(-1, -2))
        val samplePosition = android.widget.LinearLayout.LayoutParams(dp(84), -2)
        samplePosition.setMargins(dp(12), dp(12), 0, dp(12))
        row.addView(sample, samplePosition)
        val slider = Slider(this)
        slider.setId(R.id.network_speed_spacing_slider)
        slider.setContentDescription(getString(R.string.network_speed_spacing))
        slider.setValueFrom(NetworkSpeedSpacing.MIN_DP.toFloat())
        slider.setValueTo(NetworkSpeedSpacing.MAX_DP.toFloat())
        slider.setStepSize(1f)
        slider.setValue(
            NetworkSpeedSpacing.clamp(prefs!!.getInt(NetworkSpeedSpacing.KEY, 0)).toFloat()
        )
        slider.setLabelFormatter(
            LabelFormatter { value: Float -> spacingLabel(Math.round(value), false)!! }
        )
        val update = IntConsumer { value: Int ->
            label.setText(spacingLabel(value, true))
            preview.setLineSpacing(dp(-2) + value * getResources().getDisplayMetrics().density, 1f)
        }
        update.accept(Math.round(slider.getValue()))
        slider.addOnChangeListener(
            OnChangeListener { control: Slider?, value: Float, fromUser: Boolean ->
                val spacing = Math.round(value)
                update.accept(spacing)
                if (fromUser) prefs!!.edit().putInt(NetworkSpeedSpacing.KEY, spacing).apply()
            }
        )
        container.addView(slider)
        text(
            container,
            getString(R.string.network_speed_spacing_note),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        button(
            container,
            getString(R.string.network_speed_spacing_reset),
            Runnable {
                slider.setValue(0f)
                prefs!!.edit().putInt(NetworkSpeedSpacing.KEY, 0).apply()
            },
        )
    }

    private fun spacingLabel(value: Int, title: Boolean): String {
        return if (value == 0) getString(R.string.network_speed_spacing_standard)
        else
            getString(
                if (title) R.string.network_speed_spacing_value
                else R.string.network_speed_spacing_amount,
                value,
            )
    }

    private fun resetCrashGuard() {
        guardResetting = true
        guardResetControl!!.setEnabled(false)
        Thread(
                Runnable {
                    var success: Boolean = false
                    try {
                        val saved =
                            prefs!!
                                .edit()
                                .putString("guard_reset_token", UUID.randomUUID().toString())
                                .putBoolean("enabled", false)
                                .commit()
                        check(saved) { "Reset request could not be saved" }
                        val process =
                            ProcessBuilder(
                                    "su",
                                    "-c",
                                    "kill -TERM $(pidof com.sec.android.app.launcher) $(pidof" +
                                        " com.android.systemui)",
                                )
                                .redirectErrorStream(true)
                                .start()
                        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroy()
                        else success = process.exitValue() == 0
                    } catch (ignored: Exception) {}
                    val completed: Boolean = success
                    runOnUiThread(
                        Runnable {
                            if (isFinishing() || isDestroyed()) return@Runnable
                            if (!completed) guardResetting = false
                            guardResetControl!!.setEnabled(!completed)
                            MaterialAlertDialogBuilder(this)
                                .setTitle(getString(R.string.guard_title))
                                .setMessage(
                                    getString(
                                        if (completed) R.string.guard_reloaded
                                        else R.string.guard_reload_failed
                                    )
                                )
                                .setPositiveButton(getString(R.string.close), null)
                                .show()
                        }
                    )
                },
                "GalaxyBarGuardReset",
            )
            .start()
    }

    private fun protectionBlocked(): Boolean {
        return getSharedPreferences("guard", Context.MODE_PRIVATE).getBoolean("blocked", false) ||
            getSharedPreferences("guard_launcher", Context.MODE_PRIVATE)
                .getBoolean("blocked", false)
    }

    private fun checkRoot() {
        Thread(
                Runnable {
                    var result: String?
                    try {
                        val process =
                            ProcessBuilder("su", "-c", "id; cat /proc/self/attr/current")
                                .redirectErrorStream(true)
                                .start()
                        if (!process.waitFor(15, TimeUnit.SECONDS)) {
                            process.destroy()
                            result = getString(R.string.root_timeout)
                        } else result = String(process.getInputStream().readAllBytes())
                    } catch (error: Exception) {
                        result = error.toString()
                    }
                    val message: String? = result
                    runOnUiThread(
                        Runnable {
                            if (!isFinishing() && !isDestroyed())
                                MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.root_status))
                                    .setMessage(message)
                                    .setPositiveButton(getString(R.string.close), null)
                                    .show()
                        }
                    )
                },
                "GalaxyBarRootCheck",
            )
            .start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val disable = intent.getBooleanExtra("disable", false)
        intent.removeExtra("disable")
        setIntent(intent)
        if (disable) {
            prefs!!.edit().putBoolean("enabled", false).apply()
            recreate()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (pages != null) pages!!.save(out!!)
        super.onSaveInstanceState(out!!)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
