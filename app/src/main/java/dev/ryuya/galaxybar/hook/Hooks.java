package dev.ryuya.galaxybar.hook;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** All hooks are owned by the currently attached modern module instance. */
public final class Hooks {
  private static volatile XposedInterface framework;
  private static final java.util.concurrent.atomic.AtomicLong ids =
      new java.util.concurrent.atomic.AtomicLong();

  public static void attach(XposedInterface api) {
    if (api.getApiVersion() < 102) throw new IllegalStateException("libxposed API 102 required");
    framework = api;
    api.log(4, "GalaxyModule", "libxposed API 102 attached");
  }

  private static XposedInterface api() {
    XposedInterface value = framework;
    if (value == null) throw new IllegalStateException("Modern hook framework not attached");
    return value;
  }

  static HookCallback.Unhook hook(Executable method, HookCallback callback) {
    return new HookCallback.Unhook(
        api()
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .setId("galaxy-" + ids.incrementAndGet())
            .intercept(callback::intercept));
  }

  public static Set<HookCallback.Unhook> hookAllMethods(
      Class<?> type, String name, HookCallback callback) {
    Set<HookCallback.Unhook> result = new LinkedHashSet<>();
    try {
      for (Method method : type.getDeclaredMethods())
        if (method.getName().equals(name)) result.add(hook(method, callback));
      return result;
    } catch (Throwable error) {
      result.forEach(HookCallback.Unhook::unhook);
      throw error;
    }
  }

  public static Set<HookCallback.Unhook> hookAllConstructors(Class<?> type, HookCallback callback) {
    Set<HookCallback.Unhook> result = new LinkedHashSet<>();
    try {
      for (Constructor<?> constructor : type.getDeclaredConstructors())
        result.add(hook(constructor, callback));
      return result;
    } catch (Throwable error) {
      result.forEach(HookCallback.Unhook::unhook);
      throw error;
    }
  }

  public static void deoptimize(Executable method) {
    if (!api().deoptimize(method))
      throw new IllegalStateException("Could not deoptimize " + method);
  }

  public static void log(String message) {
    XposedInterface value = framework;
    if (value != null) value.log(4, "GalaxyModule", message);
  }

  public static void log(Throwable error) {
    XposedInterface value = framework;
    if (value != null) value.log(6, "GalaxyModule", error.toString(), error);
  }
}
