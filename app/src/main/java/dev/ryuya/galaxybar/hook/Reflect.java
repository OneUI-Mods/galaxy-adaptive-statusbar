package dev.ryuya.galaxybar.hook;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Cached reflection for the inspected firmware; never calls legacy Xposed APIs. */
public final class Reflect {
  private record FieldKey(Class<?> owner, String name) {}
  private record MethodKey(Class<?> owner, String name, List<Class<?>> args, boolean statik) {}
  private static final Map<FieldKey, Field> fields = new ConcurrentHashMap<>();
  private static final Map<MethodKey, Executable> methods = new ConcurrentHashMap<>();
  private static final Map<Object, Map<String, Object>> extras =
      Collections.synchronizedMap(new WeakHashMap<>());

  public static Class<?> findClass(String name, ClassLoader loader) {
    try {
      return Class.forName(name, false, loader);
    } catch (ClassNotFoundException error) {
      throw new IllegalStateException("Class not found: " + name, error);
    }
  }
  public static HookCallback.Unhook findAndHookMethod(
      Class<?> owner, String name, Object... parameters) {
    if (parameters.length == 0
        || !(parameters[parameters.length - 1] instanceof HookCallback callback))
      throw new IllegalArgumentException("Hook callback required");
    Class<?>[] types = new Class<?>[parameters.length - 1];
    for (int i = 0; i < types.length; i++)
      types[i] = parameters[i] instanceof Class<?> c
          ? c
          : findClass((String) parameters[i], owner.getClassLoader());
    Method method = null;
    for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
      try {
        method = type.getDeclaredMethod(name, types);
        break;
      } catch (NoSuchMethodException ignored) {
      }
    }
    if (method == null)
      throw new IllegalStateException(
          "Method not found: " + owner.getName() + "." + name + Arrays.toString(types));
    method.setAccessible(true);
    return Hooks.hook(method, callback);
  }
  private static Field field(Object object, String name) {
    Class<?> owner = object instanceof Class<?> c ? c : object.getClass();
    return fields.computeIfAbsent(new FieldKey(owner, name), key -> {
      for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
        try {
          Field result = type.getDeclaredField(name);
          result.setAccessible(true);
          return result;
        } catch (NoSuchFieldException ignored) {
        }
      }
      throw new IllegalStateException("Field not found: " + owner.getName() + "." + name);
    });
  }
  public static Object getObjectField(Object object, String name) {
    try {
      return field(object, name).get(object);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
  public static Object getStaticObjectField(Class<?> type, String name) {
    try {
      return field(type, name).get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
  public static int getIntField(Object o, String n) {
    return ((Number) getObjectField(o, n)).intValue();
  }
  public static float getFloatField(Object o, String n) {
    return ((Number) getObjectField(o, n)).floatValue();
  }
  public static boolean getBooleanField(Object o, String n) {
    return (Boolean) getObjectField(o, n);
  }
  public static int getStaticIntField(Class<?> o, String n) {
    return ((Number) getStaticObjectField(o, n)).intValue();
  }
  public static float getStaticFloatField(Class<?> o, String n) {
    return ((Number) getStaticObjectField(o, n)).floatValue();
  }
  public static void setObjectField(Object object, String name, Object value) {
    try {
      field(object, name).set(object, value);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
  public static void setBooleanField(Object o, String n, boolean v) {
    setObjectField(o, n, v);
  }
  public static void setIntField(Object o, String n, int v) {
    setObjectField(o, n, v);
  }

  public static Object callMethod(Object receiver, String name, Object... args) {
    return invoke(best(receiver.getClass(), name, args, false), receiver, args);
  }
  public static Object callStaticMethod(Class<?> owner, String name, Object... args) {
    return invoke(best(owner, name, args, true), null, args);
  }
  public static Object newInstance(Class<?> owner, Object... args) {
    return invoke(best(owner, "<init>", args, false), null, args);
  }
  private static Object invoke(Executable executable, Object receiver, Object[] args) {
    try {
      if (executable instanceof Method method)
        return method.invoke(receiver, args);
      return ((Constructor<?>) executable).newInstance(args);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("Invocation failed: " + executable, e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
  private static Executable best(Class<?> owner, String name, Object[] args, boolean statik) {
    List<Class<?>> signature = new ArrayList<>(args.length);
    for (Object arg : args) signature.add(arg == null ? Void.class : arg.getClass());
    return methods.computeIfAbsent(
        new MethodKey(owner, name, List.copyOf(signature), statik), key -> {
          Executable selected = null;
          int lowest = Integer.MAX_VALUE;
          if (name.equals("<init>")) {
            for (Constructor<?> c : owner.getDeclaredConstructors()) {
              int score = score(c.getParameterTypes(), args);
              if (score < lowest) {
                selected = c;
                lowest = score;
              }
            }
          } else {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
              for (Method m : type.getDeclaredMethods()) {
                if (!m.getName().equals(name) || Modifier.isStatic(m.getModifiers()) != statik)
                  continue;
                int score = score(m.getParameterTypes(), args);
                if (score < lowest) {
                  selected = m;
                  lowest = score;
                }
              }
            }
          }
          if (selected == null && !name.equals("<init>")) {
            for (Method m : owner.getMethods()) {
              if (!m.getName().equals(name) || Modifier.isStatic(m.getModifiers()) != statik)
                continue;
              int score = score(m.getParameterTypes(), args);
              if (score < lowest) {
                selected = m;
                lowest = score;
              }
            }
          }
          if (selected == null)
            throw new IllegalStateException(
                "No matching method: " + owner.getName() + "." + name + signature);
          selected.setAccessible(true);
          return selected;
        });
  }
  private static Class<?> boxed(Class<?> type) {
    if (!type.isPrimitive())
      return type;
    if (type == boolean.class)
      return Boolean.class;
    if (type == byte.class)
      return Byte.class;
    if (type == short.class)
      return Short.class;
    if (type == char.class)
      return Character.class;
    if (type == int.class)
      return Integer.class;
    if (type == long.class)
      return Long.class;
    if (type == float.class)
      return Float.class;
    if (type == double.class)
      return Double.class;
    return Void.class;
  }
  private static int score(Class<?>[] parameters, Object[] args) {
    if (parameters.length != args.length)
      return Integer.MAX_VALUE;
    int score = 0;
    for (int i = 0; i < args.length; i++) {
      Class<?> expected = parameters[i];
      Object actual = args[i];
      if (actual == null) {
        if (expected.isPrimitive())
          return Integer.MAX_VALUE;
        score += 8;
        continue;
      }
      if (boxed(expected) == actual.getClass())
        continue;
      if (expected.isAssignableFrom(actual.getClass())) {
        score += expected == Object.class ? 6 : 2;
        continue;
      }
      // Java reflection permits unboxing and widening, but never narrowing.
      int from = number(actual.getClass()), to = number(boxed(expected));
      if (expected.isPrimitive() && from >= 0 && to >= from) {
        score += to - from + 1;
        continue;
      }
      return Integer.MAX_VALUE;
    }
    return score;
  }
  private static int number(Class<?> type) {
    if (type == Byte.class)
      return 0;
    if (type == Short.class)
      return 1;
    if (type == Integer.class)
      return 2;
    if (type == Long.class)
      return 3;
    if (type == Float.class)
      return 4;
    if (type == Double.class)
      return 5;
    return -1;
  }
  public static Object getAdditionalInstanceField(Object owner, String key) {
    synchronized (extras) {
      Map<String, Object> values = extras.get(owner);
      return values == null ? null : values.get(key);
    }
  }
  public static Object setAdditionalInstanceField(Object owner, String key, Object value) {
    synchronized (extras) {
      return extras.computeIfAbsent(owner, ignored -> new HashMap<>()).put(key, value);
    }
  }
}
