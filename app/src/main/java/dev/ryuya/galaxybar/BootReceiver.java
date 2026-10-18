package dev.ryuya.galaxybar;

import android.content.*;

/** No root, UI, foreground service, or crash-guard reset during automatic startup. */
public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) return;
    String action = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) Startup.restore(context);
  }
}
