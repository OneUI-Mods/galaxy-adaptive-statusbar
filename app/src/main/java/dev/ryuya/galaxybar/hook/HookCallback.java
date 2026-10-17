package dev.ryuya.galaxybar.hook;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.HashMap;

/** Local before/after adapter; actual hook registration uses libxposed API 102 chains. */
public abstract class HookCallback {
  protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
  protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

  public static final class MethodHookParam {
    public Executable method;
    public Object thisObject;
    public Object[] args;
    private Object result;
    private Throwable throwable;
    private boolean skip;
    private HashMap<String, Object> extras;
    public Object getResult() {
      return result;
    }
    public Throwable getThrowable() {
      return throwable;
    }
    public boolean hasThrowable() {
      return throwable != null;
    }
    public void setResult(Object value) {
      result = value;
      throwable = null;
      skip = true;
    }
    public void setThrowable(Throwable value) {
      throwable = value;
      result = null;
      skip = true;
    }
    public Object getObjectExtra(String key) {
      return extras == null ? null : extras.get(key);
    }
    public void setObjectExtra(String key, Object value) {
      if (extras == null)
        extras = new HashMap<>();
      extras.put(key, value);
    }
  }

  final Object intercept(XposedInterface.Chain chain) throws Throwable {
    MethodHookParam p = new MethodHookParam();
    p.method = chain.getExecutable();
    p.thisObject = chain.getThisObject();
    p.args = chain.getArgs().toArray();
    try {
      beforeHookedMethod(p);
    } catch (Throwable error) {
      Hooks.log(error);
      p.skip = false;
      p.result = null;
      p.throwable = null;
    }
    if (!p.skip) {
      try {
        p.result = chain.proceed(p.args);
      } catch (Throwable error) {
        p.throwable = error;
      }
    }
    Object result = p.result;
    Throwable throwable = p.throwable;
    try {
      afterHookedMethod(p);
    } catch (Throwable error) {
      Hooks.log(error);
      p.result = result;
      p.throwable = throwable;
    }
    if (p.throwable != null)
      throw p.throwable;
    return p.result;
  }

  public static final class Unhook {
    private final XposedInterface.HookHandle handle;
    Unhook(XposedInterface.HookHandle handle) {
      this.handle = handle;
    }
    public void unhook() {
      handle.unhook();
    }
  }
}
