package dev.ryuya.galaxybar.hook;
import static org.junit.Assert.*;

import org.junit.Test;
public class ReflectTest {
  static class Parent {
    private int count = 4;
    private String inherited(boolean b) {
      return b ? "yes" : "no";
    }
  }
  interface Defaults { default int increment(int n) { return n + 4; } }
  static class Sample extends Parent implements Defaults {
    private static int STATIC = 12;
    private final String value;
    private Sample(String value) {
      this.value = value;
    }
    private String choose(Number n) {
      return "number";
    }
    private String choose(Integer n) {
      return "integer";
    }
    private String nullable(Object o) {
      return o == null ? "null" : "object";
    }
    private long widen(long n) {
      return n + 2;
    }
    private static String category(Integer n) {
      return "static";
    }
    private String category(Number n) {
      return "instance";
    }
  }
  @Test
  public void privateInheritedFieldsAndPrimitiveUpdates() {
    Sample s = new Sample("x");
    assertEquals(4, Reflect.getIntField(s, "count"));
    Reflect.setIntField(s, "count", 8);
    assertEquals(8, Reflect.getIntField(s, "count"));
    assertEquals(12, Reflect.getStaticIntField(Sample.class, "STATIC"));
  }
  @Test
  public void resolvesExactOverloadsAndPrivateParentMethods() {
    Sample s = new Sample("x");
    assertEquals("integer", Reflect.callMethod(s, "choose", 1));
    assertEquals("number", Reflect.callMethod(s, "choose", 1.5));
    assertEquals("yes", Reflect.callMethod(s, "inherited", true));
    assertEquals(5L, Reflect.callMethod(s, "widen", 3));
    assertEquals("null", Reflect.callMethod(s, "nullable", (Object) null));
  }
  @Test
  public void staticAndInstanceResolutionHaveDifferentCacheKeys() {
    Sample s = new Sample("x");
    assertEquals("instance", Reflect.callMethod(s, "category", 1));
    assertEquals("static", Reflect.callStaticMethod(Sample.class, "category", 1));
    assertEquals("instance", Reflect.callMethod(s, "category", 1));
  }
  @Test
  public void defaultInterfaceMethodIsCallable() {
    assertEquals(7, Reflect.callMethod(new Sample("x"), "increment", 3));
  }
  @Test
  public void constructorAndPerInstanceAttachments() {
    Sample a = (Sample) Reflect.newInstance(Sample.class, "a"),
           b = (Sample) Reflect.newInstance(Sample.class, "b");
    assertEquals("a", Reflect.getObjectField(a, "value"));
    Reflect.setAdditionalInstanceField(a, "mix", true);
    assertEquals(true, Reflect.getAdditionalInstanceField(a, "mix"));
    assertNull(Reflect.getAdditionalInstanceField(b, "mix"));
  }
}
