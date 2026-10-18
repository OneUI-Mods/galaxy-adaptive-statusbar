package dev.ryuya.galaxybar.hook;

import static org.junit.Assert.*;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;

public class RegistrationTest {
  static class Sample {
    Sample() {}

    Sample(int n) {}

    void selected() {}

    void selected(int n) {}

    void untouched() {}
  }

  static class Framework {
    final List<Executable> registered = new ArrayList<>();
    int unhooked, failAt = -1;
    final List<String> ids = new ArrayList<>();

    XposedInterface api(int version) {
      return (XposedInterface)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class[] {XposedInterface.class},
              (o, m, a) -> {
                if (m.getName().equals("getApiVersion")) return version;
                if (m.getName().equals("log")) return null;
                if (m.getName().equals("hook")) {
                  Executable target = (Executable) a[0];
                  return Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class[] {XposedInterface.HookBuilder.class},
                      (builder, method, args) -> {
                        if (method.getName().equals("setExceptionMode")) {
                          assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, args[0]);
                          return builder;
                        }
                        if (method.getName().equals("setId")) {
                          ids.add((String) args[0]);
                          return builder;
                        }
                        if (method.getName().equals("intercept")) {
                          if (registered.size() == failAt)
                            throw new IllegalStateException("simulated registration failure");
                          registered.add(target);
                          return Proxy.newProxyInstance(
                              getClass().getClassLoader(),
                              new Class[] {XposedInterface.HookHandle.class},
                              (handle, operation, arguments) -> {
                                if (operation.getName().equals("unhook")) {
                                  unhooked++;
                                  return null;
                                }
                                if (operation.getName().equals("getExecutable")) return target;
                                return null;
                              });
                        }
                        return builder;
                      });
                }
                throw new UnsupportedOperationException(m.getName());
              });
    }
  }

  @Test
  public void registersOnlyNamedOverloadsAndUnhooksOnlyOwnHandles() {
    Framework f = new Framework();
    Hooks.attach(f.api(102));
    var handles = Hooks.hookAllMethods(Sample.class, "selected", new HookCallback() {});
    assertEquals(2, f.registered.size());
    assertEquals(2, handles.size());
    assertTrue(f.registered.stream().allMatch(m -> m.getName().equals("selected")));
    handles.forEach(HookCallback.Unhook::unhook);
    assertEquals(2, f.unhooked);
    assertEquals(2, new HashSet<>(f.ids).size());
  }

  @Test
  public void partialRegistrationFailureRollsBackInstalledMembers() {
    Framework f = new Framework();
    f.failAt = 1;
    Hooks.attach(f.api(102));
    try {
      Hooks.hookAllMethods(Sample.class, "selected", new HookCallback() {});
      fail();
    } catch (IllegalStateException expected) {
    }
    assertEquals(1, f.unhooked);
  }

  @Test
  public void constructorRegistrationUsesModernHandles() {
    Framework f = new Framework();
    Hooks.attach(f.api(102));
    var handles = Hooks.hookAllConstructors(Sample.class, new HookCallback() {});
    assertEquals(2, handles.size());
    assertTrue(f.registered.stream().allMatch(m -> m instanceof Constructor));
  }

  @Test
  public void olderModernApiIsRejected() {
    Framework f = new Framework();
    try {
      Hooks.attach(f.api(101));
      fail();
    } catch (IllegalStateException expected) {
    }
  }
}
