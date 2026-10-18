package dev.ryuya.galaxybar.hook;

import static org.junit.Assert.*;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;

public class HookCallbackTest {
  private static int target(int n) {
    return n + 1;
  }

  private static final class Call implements XposedInterface.Chain {
    Object[] args = {3};
    int calls;
    Throwable error;

    public Executable getExecutable() {
      try {
        return HookCallbackTest.class.getDeclaredMethod("target", int.class);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    public Object getThisObject() {
      return null;
    }

    public List<Object> getArgs() {
      return Arrays.asList(args);
    }

    public Object getArg(int n) {
      return args[n];
    }

    public Object proceed() throws Throwable {
      return proceed(args);
    }

    public Object proceed(Object[] a) throws Throwable {
      calls++;
      args = a;
      if (error != null) throw error;
      return (int) a[0] + 1;
    }

    public Object proceedWith(Object o) throws Throwable {
      return proceed();
    }

    public Object proceedWith(Object o, Object[] a) throws Throwable {
      return proceed(a);
    }
  }

  @Test
  public void mutatedArgumentsAndAfterResultTravelThroughModernChain() throws Throwable {
    Call call = new Call();
    Object result =
        new HookCallback() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.args[0] = 8;
            p.setObjectExtra("before", true);
          }

          protected void afterHookedMethod(MethodHookParam p) {
            assertEquals(true, p.getObjectExtra("before"));
            p.setResult((int) p.getResult() * 2);
          }
        }.intercept(call);
    assertEquals(18, result);
    assertEquals(1, call.calls);
    assertEquals(8, call.args[0]);
  }

  @Test
  public void explicitResultSkipsOriginalExactlyOnce() throws Throwable {
    Call call = new Call();
    Object result =
        new HookCallback() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setResult(42);
          }
        }.intercept(call);
    assertEquals(42, result);
    assertEquals(0, call.calls);
  }

  @Test
  public void deliberatelyRejectedOperationDoesNotFallBackToOriginal() throws Throwable {
    Call call = new Call();
    Throwable denied = new IllegalArgumentException("capture target unavailable");
    try {
      new HookCallback() {
        protected void beforeHookedMethod(MethodHookParam p) {
          p.setThrowable(denied);
        }
      }.intercept(call);
      fail();
    } catch (Throwable actual) {
      assertSame(denied, actual);
    }
    assertEquals(0, call.calls);
  }

  @Test
  public void originalFailureRunsCleanupAndPreservesException() throws Throwable {
    Call call = new Call();
    call.error = new IllegalStateException("original");
    boolean[] cleanup = {false};
    try {
      new HookCallback() {
        protected void afterHookedMethod(MethodHookParam p) {
          cleanup[0] = true;
          assertTrue(p.hasThrowable());
        }
      }.intercept(call);
      fail();
    } catch (Throwable actual) {
      assertSame(call.error, actual);
    }
    assertTrue(cleanup[0]);
    assertEquals(1, call.calls);
  }

  @Test
  public void afterCallbackCanRecoverFromAnOriginalFailure() throws Throwable {
    Call call = new Call();
    call.error = new IllegalStateException("original");
    Object result =
        new HookCallback() {
          protected void afterHookedMethod(MethodHookParam p) {
            p.setResult(7);
          }
        }.intercept(call);
    assertEquals(7, result);
    assertEquals(1, call.calls);
  }

  @Test
  public void brokenCallbacksKeepOriginalResultAndException() throws Throwable {
    Call call = new Call();
    Object result =
        new HookCallback() {
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setResult(99);
            throw new IllegalStateException();
          }

          protected void afterHookedMethod(MethodHookParam p) {
            p.setResult(100);
            throw new IllegalStateException();
          }
        }.intercept(call);
    assertEquals(4, result);
    assertEquals(1, call.calls);
  }
}
