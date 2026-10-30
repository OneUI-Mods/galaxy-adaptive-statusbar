package dev.ryuya.galaxybar.hook

import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

open class ReflectTest {
    open class Parent {
        private var count: Int = 4

        private fun inherited(b: Boolean): String? {
            return if (b) "yes" else "no"
        }
    }

    interface Defaults {
        fun increment(n: Int): Int {
            return n + 4
        }
    }

    open class Sample constructor(value: String) : ReflectTest.Parent(), ReflectTest.Defaults {
        private val value: String

        init {
            this.value = value
        }

        private fun choose(n: Number?): String? {
            return "number"
        }

        private fun choose(n: Int?): String? {
            return "integer"
        }

        private fun nullable(o: Any?): String? {
            return if (o == null) "null" else "object"
        }

        private fun widen(n: Long): Long {
            return n + 2
        }

        private fun category(n: Number?): String? {
            return "instance"
        }

        companion object {
            private var STATIC: Int = 12

            @JvmStatic
            private fun category(n: Int?): String? {
                return "static"
            }
        }
    }

    @Test
    open fun privateInheritedFieldsAndPrimitiveUpdates() {
        val s = ReflectTest.Sample("x")
        assertEquals(4, Reflect.getIntField(s, "count"))
        Reflect.setIntField(s, "count", 8)
        assertEquals(8, Reflect.getIntField(s, "count"))
        assertEquals(12, Reflect.getStaticIntField(ReflectTest.Sample::class.java, "STATIC"))
    }

    @Test
    open fun resolvesExactOverloadsAndPrivateParentMethods() {
        val s = ReflectTest.Sample("x")
        assertEquals("integer", Reflect.callMethod(s, "choose", 1))
        assertEquals("number", Reflect.callMethod(s, "choose", 1.5))
        assertEquals("yes", Reflect.callMethod(s, "inherited", true))
        assertEquals(5L, Reflect.callMethod(s, "widen", 3))
        assertEquals("null", Reflect.callMethod(s, "nullable", null as Any?))
    }

    @Test
    open fun staticAndInstanceResolutionHaveDifferentCacheKeys() {
        val s = ReflectTest.Sample("x")
        assertEquals("instance", Reflect.callMethod(s, "category", 1))
        assertEquals(
            "static",
            Reflect.callStaticMethod(ReflectTest.Sample::class.java, "category", 1),
        )
        assertEquals("instance", Reflect.callMethod(s, "category", 1))
    }

    @Test
    open fun defaultInterfaceMethodIsCallable() {
        assertEquals(7, Reflect.callMethod(ReflectTest.Sample("x"), "increment", 3))
    }

    @Test
    open fun constructorAndPerInstanceAttachments() {
        val a: ReflectTest.Sample? =
            Reflect.newInstance(ReflectTest.Sample::class.java, "a") as ReflectTest.Sample?
        val b: ReflectTest.Sample? =
            Reflect.newInstance(ReflectTest.Sample::class.java, "b") as ReflectTest.Sample?
        assertEquals("a", Reflect.getObjectField(a!!, "value"))
        Reflect.setAdditionalInstanceField(a, "mix", true)
        assertEquals(true, Reflect.getAdditionalInstanceField(a, "mix"))
        assertNull(Reflect.getAdditionalInstanceField(b, "mix"))
    }
}
