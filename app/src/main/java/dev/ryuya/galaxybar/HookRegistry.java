package dev.ryuya.galaxybar;

import de.robv.android.xposed.*;
import java.util.*;

/** Own hooks only. Never removes hooks belonging to another module. */
final class HookRegistry {
  private static final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();

  static void hook(Class<?> owner, String name, Object... parameters) {
    hooks.add(XposedHelpers.findAndHookMethod(owner, name, parameters));
  }

  static void all(Class<?> owner, String name, XC_MethodHook callback) {
    hooks.addAll(XposedBridge.hookAllMethods(owner, name, callback));
  }

  static void clear() {
    for (var hook : hooks) {
      try {
        hook.unhook();
      } catch (Throwable error) {
        XposedBridge.log(error);
      }
    }
    hooks.clear();
  }
}
