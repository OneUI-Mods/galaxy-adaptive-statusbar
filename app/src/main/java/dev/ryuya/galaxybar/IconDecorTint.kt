package dev.ryuya.galaxybar

/** Keeps native opacity and the latest native color while an adaptive palette owns the dot. */
internal class IconDecorTint(private var nativeColor: Int) {
    private var adaptiveColor: Int? = null

    fun nativeChanged(color: Int): Int {
        nativeColor = color
        return current()
    }

    fun apply(color: Int): Int {
        adaptiveColor = color
        return current()
    }

    fun restore(): Int {
        adaptiveColor = null
        return nativeColor
    }

    fun current(): Int {
        val adaptive = adaptiveColor ?: return nativeColor
        val alpha = (nativeColor ushr 24) * (adaptive ushr 24) / 255
        return (adaptive and 0x00ffffff) or (alpha shl 24)
    }
}
