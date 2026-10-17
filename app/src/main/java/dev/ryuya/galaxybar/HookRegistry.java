package dev.ryuya.galaxybar;

import dev.ryuya.galaxybar.hook.*;
import java.util.*;

/** Own hooks only. Never removes hooks belonging to another module. */
final class HookRegistry {
  private static final List<HookCallback.Unhook> hooks = new ArrayList<>();
  private static final List<HookCallback.Unhook> lifecycle = new ArrayList<>();

  static void hook(Class<?> owner, String name, Object... parameters) {
    hooks.add(Reflect.findAndHookMethod(owner, name, parameters));
  }

  static void lifecycle(Class<?> owner, String name, HookCallback callback) {
    lifecycle.addAll(Hooks.hookAllMethods(owner, name, callback));
  }

  static int colorCount() {
    return hooks.size();
  }

  private static void clear(List<HookCallback.Unhook> handles) {
    for (var hook : handles) {
      try {
        hook.unhook();
      } catch (Throwable error) {
        Hooks.log(error);
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
