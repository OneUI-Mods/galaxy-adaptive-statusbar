package dev.ryuya.galaxybar.hook

import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.util.Arrays
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

open class HookCallbackTest {
    private class Call : Chain {
        @JvmField var args: Array<Any?> = arrayOf<Any?>(3)
        @JvmField var calls: Int = 0
        @JvmField var error: Throwable? = null

        override fun getExecutable(): Executable {
            try {
                return HookCallbackTest::class
                    .java
                    .getDeclaredMethod("target", Int::class.javaPrimitiveType)
            } catch (e: Exception) {
                throw AssertionError(e)
            }
        }

        override fun getThisObject(): Any? {
            return null
        }

        override fun getArgs(): MutableList<Any?> {
            return Arrays.asList<Any?>(*args)
        }

        override fun getArg(n: Int): Any? {
            return args!![n]
        }

        @Throws(Throwable::class)
        override fun proceed(): Any {
            return proceed(args!!)
        }

        @Throws(Throwable::class)
        override fun proceed(a: Array<Any?>): Any {
            calls++
            args = a
            if (error != null) throw error!!
            return a[0] as Int + 1
        }

        @Throws(Throwable::class)
        override fun proceedWith(o: Any): Any {
            return proceed()
        }

        @Throws(Throwable::class)
        override fun proceedWith(o: Any, a: Array<Any?>): Any {
            return proceed(arrayOf(*a))
        }
    }

    @Test
    @Throws(Throwable::class)
    open fun mutatedArgumentsAndAfterResultTravelThroughModernChain() {
        val call = HookCallbackTest.Call()
        val result =
            object : HookCallback() {
                    override fun beforeHookedMethod(p: HookCallback.MethodHookParam) {
                        p.args[0] = 8
                        p.setObjectExtra("before", true)
                    }

                    override fun afterHookedMethod(p: HookCallback.MethodHookParam) {
                        assertEquals(true, p.getObjectExtra("before"))
                        p.setResult(p.getResult() as Int * 2)
                    }
                }
                .intercept(call)
        assertEquals(18, result)
        assertEquals(1, call.calls)
        assertEquals(8, call.args!![0])
    }

    @Test
    @Throws(Throwable::class)
    open fun explicitResultSkipsOriginalExactlyOnce() {
        val call = HookCallbackTest.Call()
        val result =
            object : HookCallback() {
                    override fun beforeHookedMethod(p: HookCallback.MethodHookParam) {
                        p.setResult(42)
                    }
                }
                .intercept(call)
        assertEquals(42, result)
        assertEquals(0, call.calls)
    }

    @Test
    @Throws(Throwable::class)
    open fun deliberatelyRejectedOperationDoesNotFallBackToOriginal() {
        val call = HookCallbackTest.Call()
        val denied = IllegalArgumentException("capture target unavailable")
        try {
            object : HookCallback() {
                    override fun beforeHookedMethod(p: HookCallback.MethodHookParam) {
                        p.setThrowable(denied)
                    }
                }
                .intercept(call)
            fail()
        } catch (actual: Throwable) {
            assertSame(denied, actual)
        }
        assertEquals(0, call.calls)
    }

    @Test
    @Throws(Throwable::class)
    open fun originalFailureRunsCleanupAndPreservesException() {
        val call = HookCallbackTest.Call()
        call.error = IllegalStateException("original")
        val cleanup = booleanArrayOf(false)
        try {
            object : HookCallback() {
                    override fun afterHookedMethod(p: HookCallback.MethodHookParam) {
                        cleanup[0] = true
                        assertTrue(p.hasThrowable())
                    }
                }
                .intercept(call)
            fail()
        } catch (actual: Throwable) {
            assertSame(call.error, actual)
        }
        assertTrue(cleanup[0])
        assertEquals(1, call.calls)
    }

    @Test
    @Throws(Throwable::class)
    open fun afterCallbackCanRecoverFromAnOriginalFailure() {
        val call = HookCallbackTest.Call()
        call.error = IllegalStateException("original")
        val result =
            object : HookCallback() {
                    override fun afterHookedMethod(p: HookCallback.MethodHookParam) {
                        p.setResult(7)
                    }
                }
                .intercept(call)
        assertEquals(7, result)
        assertEquals(1, call.calls)
    }

    @Test
    @Throws(Throwable::class)
    open fun brokenCallbacksKeepOriginalResultAndException() {
        val call = HookCallbackTest.Call()
        val result =
            object : HookCallback() {
                    override fun beforeHookedMethod(p: HookCallback.MethodHookParam) {
                        p.setResult(99)
                        throw IllegalStateException()
                    }

                    override fun afterHookedMethod(p: HookCallback.MethodHookParam) {
                        p.setResult(100)
                        throw IllegalStateException()
                    }
                }
                .intercept(call)
        assertEquals(4, result)
        assertEquals(1, call.calls)
    }

    companion object {
        @JvmStatic
        private fun target(n: Int): Int {
            return n + 1
        }
    }
}
