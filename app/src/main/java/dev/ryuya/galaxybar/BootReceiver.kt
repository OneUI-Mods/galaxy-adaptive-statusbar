package dev.ryuya.galaxybar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/* No root, UI, foreground service, or crash-guard reset during automatic startup. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val action = intent.getAction()
        if (
            Intent.ACTION_BOOT_COMPLETED == action ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED == action ||
                Intent.ACTION_MY_PACKAGE_REPLACED == action
        )
            Startup.restore(context!!)
    }
}
