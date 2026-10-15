package dev.ryuya.galaxybar;

import de.robv.android.xposed.*;
import java.util.*;

/** Own hooks only. Never removes hooks belonging to another module. */
final class HookRegistry {
  private static final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
  private static final List<XC_MethodHook.Unhook> lifecycle = new ArrayList<>();

  static void hook(Class<?> owner, String name, Object... parameters) {
    hooks.add(XposedHelpers.findAndHookMethod(owner, name, parameters));
  }

  static void lifecycle(Class<?> owner, String name, XC_MethodHook callback) {
    lifecycle.addAll(XposedBridge.hookAllMethods(owner, name, callback));
  }

  static int colorCount() {
    return hooks.size();
  }

  private static void clear(List<XC_MethodHook.Unhook> handles) {
    for (var hook : handles) {
      try {
        hook.unhook();
      } catch (Throwable error) {
        XposedBridge.log(error);
      }
    }
    handles.clear();
  }

  static void clearColors() {
    clear(hooks);
  }

  static void clear() {
    clearColors();
    clear(lifecycle);
  }
}
