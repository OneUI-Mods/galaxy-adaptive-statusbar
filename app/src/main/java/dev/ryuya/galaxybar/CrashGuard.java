package dev.ryuya.galaxybar;

import android.app.ActivityManager;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.util.AtomicFile;
import de.robv.android.xposed.XposedBridge;
import java.io.*;
import java.util.*;

/**
 * Checked before color hooks are installed. No polling, wake locks, or uncaught-handler changes.
 */
final class CrashGuard {
  private final Context context;
  private final AtomicFile file;
  private final java.util.concurrent.ExecutorService reports =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> new Thread(r, "GalaxyBarGuardReport"));
  private CrashLoopState state = new CrashLoopState();

  private CrashGuard(Context context) {
    this.context = context;
    Context device = context.createDeviceProtectedStorageContext();
    file = new AtomicFile(new File(device.getFilesDir(), "galaxybar-crash-guard.properties"));
  }

  static CrashGuard open(Context context) {
    return open(context, true);
  }

  static CrashGuard open(Context context, boolean armOnOpen) {
    CrashGuard guard = new CrashGuard(context);
    try {
      if (guard.file.getBaseFile().exists()
          || new File(guard.file.getBaseFile().getPath() + ".bak").exists()) {
        if (guard.file.getBaseFile().length() > 16_384) throw new IOException("Guard file bounds");
        Properties p = new Properties();
        try (InputStream input = guard.file.openRead()) {
          p.load(input);
        }
        guard.state = CrashLoopState.decode(p);
      }
      String reset = "";
      if (context.getSystemService(UserManager.class).isUserUnlocked()) {
        try {
          Bundle config =
              context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
          if (config != null) reset = config.getString("guard_reset_token", "");
        } catch (Throwable ignored) {
          /* Companion CE data may be unavailable at startup. */
        }
      }
      int boot =
          Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
      List<CrashLoopState.Exit> exits = new ArrayList<>();
      for (var exit :
          context
              .getSystemService(ActivityManager.class)
              .getHistoricalProcessExitReasons(context.getPackageName(), 0, 32)) {
        if (context.getPackageName().equals(exit.getProcessName()))
          exits.add(
              new CrashLoopState.Exit(
                  exit.getPid(), exit.getTimestamp(), exit.getReason(), exit.getStatus()));
      }
      long now = System.currentTimeMillis();
      guard.state.observe(now, boot, reset, exits);
      if (!guard.state.blocked && armOnOpen) guard.state.arm(android.os.Process.myPid(), now);
      guard.save(); // Arm durably before executing any color-hook initialization.
    } catch (Throwable error) {
      guard.state.blocked = true;
      guard.state.cause = "guard_error";
      try {
        guard.save();
      } catch (Throwable ignored) {
      }
      XposedBridge.log("GalaxyBar: guard failed closed: " + error);
    }
    guard.publishWhenUnlocked();
    XposedBridge.log(
        "GalaxyBar: crash guard "
            + (guard.state.blocked ? "BLOCKED " + guard.state.cause : "armed")
            + "; recent crashes="
            + guard.state.crashes.size());
    return guard;
  }

  static void failClosed(Context context, Throwable error) {
    XposedBridge.log("GalaxyBar: guard unavailable; hooks not installed: " + error);
    new Thread(
            () -> {
              try {
                Bundle data = new Bundle();
                data.putBoolean("blocked", true);
                data.putString("cause", "guard_error");
                context.getContentResolver().call(ConfigProvider.URI, "guard_status", null, data);
              } catch (Throwable ignored) {
              }
            },
            "GalaxyBarGuardFailure")
        .start();
  }

  boolean allowsHooks() {
    return !state.blocked;
  }

  void arm() throws IOException {
    int pid = android.os.Process.myPid();
    if (!state.launches.containsKey(pid)) state.arm(pid, System.currentTimeMillis());
    save(); // The launch must be durable before the first launcher hook is installed.
  }

  void initializationFailed(Throwable error) {
    state.blocked = true;
    state.cause = "hook_initialization";
    try {
      save();
    } catch (Throwable ignored) {
    }
    publishWhenUnlocked();
    XposedBridge.log("GalaxyBar: hook initialization stopped: " + error);
  }

  private void save() throws IOException {
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      state.encode().store(output, "Galaxy Adaptive Bar crash protection");
      file.finishWrite(output);
    } catch (Throwable error) {
      if (output != null) file.failWrite(output);
      throw new IOException("Could not persist crash guard", error);
    }
  }

  private void publishWhenUnlocked() {
    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
      publish();
      return;
    }
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent i) {
            context.unregisterReceiver(this);
            publish();
          }
        };
    context.registerReceiver(
        receiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED);
  }

  private void publish() {
    Bundle result = new Bundle();
    result.putBoolean("blocked", state.blocked);
    result.putInt("count", state.crashes.size());
    result.putString("cause", state.cause);
    reports.execute(
        () -> {
          for (int attempt = 0; attempt < 3; attempt++) {
            try {
              context.getContentResolver().call(ConfigProvider.URI, "guard_status", null, result);
              return;
            } catch (Throwable error) {
              if (attempt == 2) XposedBridge.log("GalaxyBar: guard status unavailable: " + error);
              else SystemClock.sleep(1000);
            }
          }
        });
  }
}
