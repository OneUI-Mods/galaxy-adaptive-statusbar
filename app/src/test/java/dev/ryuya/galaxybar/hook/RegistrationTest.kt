package dev.ryuya.galaxybar.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashSet
import java.util.function.Consumer
import kotlin.jvm.JvmField
import org.junit.Assert.*
import org.junit.Test

open class RegistrationTest {
    open class Sample {
        constructor()

        constructor(n: Int)

        open fun selected() {}

        open fun selected(n: Int) {}

        open fun untouched() {}
    }

    open class Framework {
        @JvmField val registered: MutableList<Executable?> = ArrayList<Executable?>()
        @JvmField var unhooked: Int = 0
        @JvmField var failAt: Int = -1
        @JvmField val ids: MutableList<String?> = ArrayList<String?>()

        open fun api(version: Int): XposedInterface {
            return Proxy.newProxyInstance(
                javaClass.getClassLoader(),
                arrayOf<Class<*>>(XposedInterface::class.java),
                InvocationHandler { o: Any?, m: Method, a: Array<Any?>? ->
                    if (m!!.getName() == "getApiVersion") return@InvocationHandler version
                    if (m!!.getName() == "log") return@InvocationHandler null
                    if (m!!.getName() == "hook") {
                        val target: Executable? = a!![0] as Executable?
                        return@InvocationHandler Proxy.newProxyInstance(
                            javaClass.getClassLoader(),
                            arrayOf<Class<*>>(HookBuilder::class.java),
                            InvocationHandler { builder: Any?, method: Method, args: Array<Any?>? ->
                                if (method!!.getName() == "setExceptionMode") {
                                    assertEquals(ExceptionMode.PASSTHROUGH, args!![0])
                                    return@InvocationHandler builder
                                }
                                if (method!!.getName() == "setId") {
                                    ids.add(args!![0] as String?)
                                    return@InvocationHandler builder
                                }
                                if (method!!.getName() == "intercept") {
                                    check(registered.size != failAt) {
                                        "simulated registration failure"
                                    }
                                    registered.add(target)
                                    return@InvocationHandler Proxy.newProxyInstance(
                                        javaClass.getClassLoader(),
                                        arrayOf<Class<*>>(HookHandle::class.java),
                                        InvocationHandler {
                                            handle: Any?,
                                            operation: Method,
                                            arguments: Array<Any?>? ->
                                            if (operation!!.getName() == "unhook") {
                                                unhooked++
                                                return@InvocationHandler null
                                            }
                                            if (operation!!.getName() == "getExecutable")
                                                return@InvocationHandler target
                                            null
                                        },
                                    )
                                }
                                builder
                            },
                        )
                    }
                    throw UnsupportedOperationException(m!!.getName())
                },
            ) as XposedInterface
        }
    }

    @Test
    open fun registersOnlyNamedOverloadsAndUnhooksOnlyOwnHandles() {
        val f = RegistrationTest.Framework()
        Hooks.attach(f.api(102)!!)
        val handles =
            Hooks.hookAllMethods(
                RegistrationTest.Sample::class.java,
                "selected",
                object : HookCallback() {},
            )
        assertEquals(2, f.registered.size)
        assertEquals(2, handles.size)
        assertTrue(f.registered.stream().allMatch { m: Executable? -> m!!.getName() == "selected" })
        handles.forEach(Consumer { handle -> handle.unhook() })
        assertEquals(2, f.unhooked)
        assertEquals(2, HashSet<String?>(f.ids).size)
    }

    @Test
    open fun partialRegistrationFailureRollsBackInstalledMembers() {
        val f = RegistrationTest.Framework()
        f.failAt = 1
        Hooks.attach(f.api(102)!!)
        try {
            Hooks.hookAllMethods(
                RegistrationTest.Sample::class.java,
                "selected",
                object : HookCallback() {},
            )
            fail()
        } catch (expected: IllegalStateException) {}
        assertEquals(1, f.unhooked)
    }

    @Test
    open fun constructorRegistrationUsesModernHandles() {
        val f = RegistrationTest.Framework()
        Hooks.attach(f.api(102)!!)
        val handles =
            Hooks.hookAllConstructors(
                RegistrationTest.Sample::class.java,
                object : HookCallback() {},
            )
        assertEquals(2, handles.size)
        assertTrue(f.registered.stream().allMatch { m: Executable? -> m is Constructor<*> })
    }

    @Test
    open fun olderModernApiIsRejected() {
        val f = RegistrationTest.Framework()
        try {
            Hooks.attach(f.api(101)!!)
            fail()
        } catch (expected: IllegalStateException) {}
    }
}
