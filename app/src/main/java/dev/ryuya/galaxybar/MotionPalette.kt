package dev.ryuya.galaxybar

import kotlin.jvm.JvmStatic

/* The departing screen keeps its colors until its bar has faded; new surfaces get new palettes. */
object MotionPalette {
    @JvmStatic
    fun choose(
        handedOff: Boolean,
        opening: Boolean,
        opacity: MotionTiming.Opacity,
    ): MotionPalette.Source? {
        if (!handedOff || (opening && opacity.fixed > .05f)) return MotionPalette.Source.FROZEN
        if (opening) return MotionPalette.Source.WINDOW
        return if (opacity.fixed > .01f && opacity.moving < .2f) MotionPalette.Source.SCREEN
        else MotionPalette.Source.WINDOW
    }

    enum class Source {
        FROZEN,
        WINDOW,
        SCREEN,
    }
}
