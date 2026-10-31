package dev.ryuya.galaxybar

import android.app.Application
import android.content.Context
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import kotlin.jvm.JvmStatic

class GalaxyBarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FrameworkServiceConnection.register(this)
        GalaxyBarApplication.applyTheme(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    companion object {
        @JvmStatic
        fun applyTheme(context: Context) {
            if (!context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked())
                return
            AppCompatDelegate.setDefaultNightMode(
                context
                    .getSharedPreferences("config", Context.MODE_PRIVATE)
                    .getInt("ui_theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            )
        }
    }
}
