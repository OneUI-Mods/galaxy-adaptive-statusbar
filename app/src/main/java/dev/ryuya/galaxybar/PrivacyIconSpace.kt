package dev.ryuya.galaxybar

import android.view.View
import android.view.ViewGroup
import java.util.ArrayList
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.math.abs

/* One owner for icon translation while the large chip hands over to the persistent dot. */
class PrivacyIconSpace(group: View, window: View?) {
    @JvmField val group: View
    private val baseline: Float
    private val clipping: ArrayList<PrivacyIconSpace.Clipping?> =
        ArrayList<PrivacyIconSpace.Clipping?>()
    private var applied: Float
    private var chipShift: Float = 0f
    private var dotShift: Float = 0f

    init {
        this.group = group
        applied = group.getTranslationX()
        baseline = applied
        var parent = group.getParent()
        while (parent is ViewGroup && parent !== window) {
            clipping.add(
                PrivacyIconSpace.Clipping(
                    parent,
                    parent.getClipChildren(),
                    parent.getClipToPadding(),
                )
            )
            parent.setClipChildren(false)
            parent.setClipToPadding(false)
            parent = parent.getParent()
        }
    }

    fun baselineLeft(): Float {
        val at = IntArray(2)
        group.getLocationOnScreen(at)
        return at[0] - group.getTranslationX() + baseline
    }

    fun chip(shift: Float) {
        chipShift = shift
        apply()
    }

    fun dot(shift: Float) {
        dotShift = shift
        apply()
    }

    private fun apply() {
        applied = baseline + PrivacySlideGeometry.combinedShift(chipShift, dotShift)
        group.setTranslationX(applied)
    }

    fun idle(): Boolean {
        return chipShift == 0f && dotShift == 0f
    }

    fun close() {
        if (abs(group.getTranslationX() - applied) < .01f) group.setTranslationX(baseline)
        for (item in clipping) {
            item!!.parent!!.setClipChildren(item!!.children)
            item!!.parent!!.setClipToPadding(item!!.padding)
        }
        clipping.clear()
    }

    @JvmRecord
    private data class Clipping(
        val parent: ViewGroup?,
        val children: Boolean,
        val padding: Boolean,
    ) {
        override fun toString(): String =
            "Clipping[parent=$parent, children=$children, padding=$padding]"
    }
}
