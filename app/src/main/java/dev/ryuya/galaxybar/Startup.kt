package dev.ryuya.galaxybar

import android.content.Context
import android.os.UserManager
import kotlin.jvm.JvmStatic

/* CE settings stay untouched until unlock; reboot never overrides OFF or crash protection. */
object Startup {
    @JvmStatic
    fun restore(context: Context) {
        if (!context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked())
            return
        context.getSharedPreferences("config", Context.MODE_PRIVATE) // Start the settings process.
        GalaxyBarApplication.applyTheme(context)
        context.getContentResolver().notifyChange(ConfigProvider.URI!!, null)
    }
}
