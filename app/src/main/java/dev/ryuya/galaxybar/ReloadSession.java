package dev.ryuya.galaxybar;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.*;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import dev.ryuya.galaxybar.hook.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Resumes only a root-authorized, already-unlocked session during a single SystemUI restart. */
final class ReloadSession {
  private static final ArrayList<HookCallback.Unhook> hooks = new ArrayList<>();
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static Context context;
  private static Path files;
  private static Object mediator;
  private static ReloadTicket ticket;
  private static volatile boolean active;
  private static long deadline;
  private static int boot;

  static void install(Context host, ClassLoader loader) {
    if (!hooks.isEmpty()) return;
    try {
      // Independently checked on AZHL (16) and BZI9 (17); never borrow another SDK's profile.
      String ownerName =
          switch (Build.VERSION.SDK_INT) {
            case 36 -> "com.android.systemui.keyguard.KeyguardViewMediator";
            case 37 -> "com.android.systemui.keyguard.KeyguardViewMediator";
            default -> throw new UnsupportedOperationException("Unreviewed keyguard version");
          };
      Class<?> owner = Reflect.findClass(ownerName, loader);
      if (owner
              .getDeclaredMethod("doKeyguardLocked", Bundle.class, boolean.class, boolean.class)
              .getReturnType()
          != boolean.class) throw new NoSuchMethodException("Keyguard return type");
      owner.getDeclaredMethod("onSystemReady");
      owner.getDeclaredMethod("onStartedGoingToSleep", int.class);
      owner.getDeclaredMethod("onKeyguardExitFinished", String.class);
      owner.getDeclaredMethod("showKeyguard$1", Bundle.class);
      owner.getDeclaredField("mKeyguardViewControllerLazy");
      owner.getDeclaredField("mSystemReady");
      owner.getDeclaredField("mKeyguardInteractor");
      Class<?> interactor =
          Reflect.findClass(
              "com.android.systemui.keyguard.domain.interactor.KeyguardInteractor", loader);
      interactor.getDeclaredMethod("dismissKeyguard");
      interactor.getDeclaredField("keyguardTransitionInteractor");
      Reflect.findClass(
              "com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor",
              loader)
          .getDeclaredField("currentKeyguardState");
      Reflect.findClass("com.android.keyguard.KeyguardViewController", loader)
          .getDeclaredMethod("hide", long.class, long.class);
      for (String field :
          new String[] {"mShowing", "mPM", "mSelectedUserInteractor", "mUpdateMonitor"})
        owner.getDeclaredField(field);
      Class<?> monitor = Reflect.findClass("com.android.keyguard.KeyguardUpdateMonitor", loader);
      monitor.getDeclaredMethod("isForcedLock");
      monitor.getDeclaredMethod("isSimPinSecure");
      monitor.getDeclaredMethod("isUserInLockdown", int.class);
      Reflect.findClass(
              "com.android.systemui.user.domain.interactor.SelectedUserInteractor", loader)
          .getDeclaredMethod("getSelectedUserId");
      context = host;
      files = host.createDeviceProtectedStorageContext().getFilesDir().toPath();
      boot = Settings.Global.getInt(host.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
      hooks.add(
          Reflect.findAndHookMethod(
              owner,
              "onSystemReady",
              new HookCallback() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                  try {
                    consume(p.thisObject);
                  } catch (Throwable error) {
                    stop(error);
                  }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                  if (p.hasThrowable()) {
                    finish(true);
                    return;
                  }
                  if (!active) return;
                  main.post(ReloadSession::resumeWhenReady);
                }
              }));
      hooks.add(
          Reflect.findAndHookMethod(
              owner,
              "doKeyguardLocked",
              Bundle.class,
              boolean.class,
              boolean.class,
              new HookCallback() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                  if (!active || p.thisObject != mediator) return;
                  try {
                    // Explicit lock requests and forced authentication always keep the native path.
                    if (p.args[0] != null || !eligible()) {
                      finish(true);
                      return;
                    }
                    p.setResult(false);
                  } catch (Throwable error) {
                    stop(error);
                  }
                }
              }));
      hooks.add(
          Reflect.findAndHookMethod(
              owner,
              "onStartedGoingToSleep",
              int.class,
              new HookCallback() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                  if (active) finish(true);
                }
              }));
      Properties support = new Properties();
      support.setProperty("protocol", Integer.toString(ReloadTicket.PROTOCOL));
      support.setProperty("pid", Integer.toString(android.os.Process.myPid()));
      support.setProperty("boot", Integer.toString(boot));
      support.setProperty("sdk", Integer.toString(Build.VERSION.SDK_INT));
      write(ReloadTicket.SUPPORT, support);
    } catch (Throwable error) {
      stop(error);
    }
  }

  private static void consume(Object owner) throws Exception {
    Path request = files.resolve(ReloadTicket.REQUEST);
    if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) return;
    Properties properties;
    try {
      var stat = Os.lstat(request.toString());
      if (!OsConstants.S_ISREG(stat.st_mode)
          || stat.st_uid != android.os.Process.myUid()
          || (stat.st_mode & 077) != 0
          || stat.st_size > 2048) return;
      properties = read(request);
    } finally {
      // Consume before using it, including rejected, expired, and interrupted requests.
      Files.deleteIfExists(request);
    }
    ReloadTicket candidate = ReloadTicket.parse(properties);
    int user =
        ((Number)
                Reflect.callMethod(
                    Reflect.getObjectField(owner, "mSelectedUserInteractor"), "getSelectedUserId"))
            .intValue();
    PowerManager power = (PowerManager) Reflect.getObjectField(owner, "mPM");
    if (candidate == null
        || !candidate.accepts(
            boot,
            user,
            SystemClock.uptimeMillis(),
            power.isInteractive(),
            context.getSystemService(UserManager.class).isUserUnlocked())) return;
    // The helper must still be alive and watching the screen through the restart gap.
    // A stale file alone can never authorize a later, unrelated SystemUI start.
    try (LocalSocket socket = new LocalSocket()) {
      socket.connect(
          new LocalSocketAddress(
              "galaxy-reload-" + candidate.nonce(), LocalSocketAddress.Namespace.ABSTRACT));
      socket.setSoTimeout(1000);
      if (socket.getPeerCredentials().getUid() != 0) return;
      socket
          .getOutputStream()
          .write((candidate.nonce() + "\n").getBytes(StandardCharsets.US_ASCII));
      if (!"ALLOW\n"
          .equals(new String(socket.getInputStream().readNBytes(6), StandardCharsets.US_ASCII)))
        return;
    } catch (java.io.IOException noLiveHelper) {
      return;
    }
    mediator = owner;
    ticket = candidate;
    deadline = SystemClock.uptimeMillis() + 5000;
    active = true;
    if (!eligible()) {
      finish(true);
      return;
    }
    Properties result = candidate.properties();
    result.setProperty("phase", "accepted");
    result.setProperty("pid", Integer.toString(android.os.Process.myPid()));
    write(ReloadTicket.RESULT, result);
  }

  private static boolean eligible() throws Exception {
    if (!active || mediator == null || SystemClock.uptimeMillis() > deadline) return false;
    if (!((PowerManager) Reflect.getObjectField(mediator, "mPM")).isInteractive()) return false;
    int user =
        ((Number)
                Reflect.callMethod(
                    Reflect.getObjectField(mediator, "mSelectedUserInteractor"),
                    "getSelectedUserId"))
            .intValue();
    if (user != ticket.user()) return false;
    Object monitor = Reflect.getObjectField(mediator, "mUpdateMonitor");
    if (Boolean.TRUE.equals(Reflect.callMethod(monitor, "isUserInLockdown", user))
        || Boolean.TRUE.equals(Reflect.callMethod(monitor, "isForcedLock"))
        || Boolean.TRUE.equals(Reflect.callMethod(monitor, "isSimPinSecure"))) return false;
    Path cancel = files.resolve(ReloadTicket.CANCEL);
    return !Files.exists(cancel) || !ticket.nonce().equals(read(cancel).getProperty("nonce"));
  }

  private static void observeCompletion() {
    if (!active) return;
    try {
      if (!eligible()) {
        finish(true);
        return;
      }
      if (!Reflect.getBooleanField(mediator, "mShowing") && "GONE".equals(keyguardState())) {
        Properties result = ticket.properties();
        result.setProperty("phase", "resumed");
        result.setProperty("pid", Integer.toString(android.os.Process.myPid()));
        write(ReloadTicket.RESULT, result);
        // No persistent exemption: subsequent lock requests use the unmodified native logic.
        finish(false);
      } else main.postDelayed(ReloadSession::observeCompletion, 100);
    } catch (Throwable error) {
      stop(error);
    }
  }

  private static void resumeWhenReady() {
    if (!active) return;
    try {
      if (!eligible()) {
        finish(true);
        return;
      }
      // onSystemReady only queues work. Wait for the native startup transition before dismissing;
      // an earlier exit can be overwritten by LOCKSCREEN and leave the status bar hidden.
      if (!Reflect.getBooleanField(mediator, "mSystemReady")
          || !"LOCKSCREEN".equals(keyguardState())) {
        main.postDelayed(ReloadSession::resumeWhenReady, 50);
        return;
      }
      Object controller =
          Reflect.callMethod(
              Reflect.getObjectField(mediator, "mKeyguardViewControllerLazy"), "get");
      Reflect.callMethod(controller, "hide", SystemClock.uptimeMillis(), 0L);
      Reflect.callMethod(mediator, "onKeyguardExitFinished", "Galaxy Reload resume");
      main.postDelayed(ReloadSession::observeCompletion, 100);
    } catch (Throwable error) {
      stop(error);
    }
  }

  private static String keyguardState() throws Exception {
    Object interactor = Reflect.getObjectField(mediator, "mKeyguardInteractor");
    Object transition = Reflect.getObjectField(interactor, "keyguardTransitionInteractor");
    Object flow = Reflect.getObjectField(transition, "currentKeyguardState");
    return ((Enum<?>) Reflect.callMethod(flow, "getValue")).name();
  }

  private static Properties read(Path path) throws Exception {
    if (Files.size(path) > 2048) throw new IllegalArgumentException("Oversized reload record");
    Properties result = new Properties();
    try (var input = Files.newInputStream(path)) {
      result.load(input);
    }
    return result;
  }

  private static void write(String name, Properties properties) throws Exception {
    Path temporary = files.resolve(name + ".tmp");
    try (var output = Files.newOutputStream(temporary)) {
      properties.store(output, "Galaxy Reload");
    }
    Os.chmod(temporary.toString(), 0600);
    Files.move(
        temporary,
        files.resolve(name),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  private static void finish(boolean showNativeLock) {
    if (!active) return;
    active = false;
    main.removeCallbacksAndMessages(null);
    Object owner = mediator;
    if (showNativeLock && owner != null)
      main.post(
          () -> {
            try {
              Reflect.callMethod(owner, "showKeyguard$1", (Object) null);
            } catch (Throwable error) {
              Hooks.log(error);
            }
          });
  }

  private static void stop(Throwable error) {
    finish(true);
    for (var hook : hooks)
      try {
        hook.unhook();
      } catch (Throwable ignored) {
      }
    hooks.clear();
    if (files != null)
      try {
        Files.deleteIfExists(files.resolve(ReloadTicket.SUPPORT));
      } catch (Exception ignored) {
      }
    Hooks.log("GalaxyBar: optional reload session unavailable: " + error);
  }

  static void reset() {
    stop(new IllegalStateException("Module hooks stopped"));
  }
}
