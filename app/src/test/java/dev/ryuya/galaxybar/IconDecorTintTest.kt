package dev.ryuya.galaxybar

import org.junit.Assert.assertEquals
import org.junit.Test

class IconDecorTintTest {
    @Test
    fun themeUpdatesCannotFlashThroughAndOffRestoresLatestNativeColor() {
        val tint = IconDecorTint(0xff000000.toInt())
        assertEquals(0xffffffff.toInt(), tint.apply(0xffffffff.toInt()))
        assertEquals(0xffffffff.toInt(), tint.nativeChanged(0xff112233.toInt()))
        assertEquals(0xff112233.toInt(), tint.restore())
        assertEquals(0xff445566.toInt(), tint.nativeChanged(0xff445566.toInt()))
    }

    @Test
    fun fadesKeepNativeOpacityWithoutCompoundingItAcrossFrames() {
        val tint = IconDecorTint(0x80000000.toInt())
        assertEquals(0x80ffffff.toInt(), tint.apply(0xffffffff.toInt()))
        assertEquals(0x80808080.toInt(), tint.apply(0xff808080.toInt()))
        assertEquals(0x80808080.toInt(), tint.apply(0xff808080.toInt()))
        assertEquals(0x40808080, tint.nativeChanged(0x40000000))
        assertEquals(0x40000000, tint.restore())
    }

    @Test
    fun transparentNativeDotStaysTransparentAndAdaptiveAlphaIsRespected() {
        val tint = IconDecorTint(0x00112233)
        assertEquals(0x00ffffff, tint.apply(0xffffffff.toInt()))
        assertEquals(0xffffffff.toInt(), tint.nativeChanged(0xff000000.toInt()))
        assertEquals(0x80abcdef.toInt(), tint.apply(0x80abcdef.toInt()))
    }
}
