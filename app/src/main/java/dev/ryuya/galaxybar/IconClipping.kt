package dev.ryuya.galaxybar

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Charging and privacy reservations can share the same ancestors without restoring too early. */
internal object IconClipping {
    private class Saved(val children: Boolean, val padding: Boolean, var users: Int = 0)

    private val registry = ThreadLocal.withInitial { WeakHashMap<ViewGroup, Saved>() }

    fun acquire(view: View, window: View?): AutoCloseable {
        val owners = registry.get()
        val parents = ArrayList<ViewGroup>()
        var parent = view.parent
        while (parent is ViewGroup && parent !== window) {
            val saved = owners.getOrPut(parent) { Saved(parent.clipChildren, parent.clipToPadding) }
            saved.users++
            parent.clipChildren = false
            parent.clipToPadding = false
            parents.add(parent)
            parent = parent.parent
        }
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                for (owner in parents) {
                    val saved = owners[owner] ?: continue
                    if (--saved.users == 0) {
                        owners.remove(owner)
                        if (!owner.clipChildren) owner.clipChildren = saved.children
                        if (!owner.clipToPadding) owner.clipToPadding = saved.padding
                    }
                }
                parents.clear()
            }
        }
    }
}
