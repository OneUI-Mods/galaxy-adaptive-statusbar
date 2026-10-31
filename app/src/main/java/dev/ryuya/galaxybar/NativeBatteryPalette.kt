package dev.ryuya.galaxybar

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/* GPU color mapping of native battery pixels; colored indicators and alpha remain native. */
class NativeBatteryPalette {
    private val shader: RuntimeShader = RuntimeShader(NativeBatteryPalette.PROGRAM)

    private var cachedEffect: RenderEffect? = null
    private var lastBody: Int = 0
    private var lastText: Int = 0
    private var lastSourceBody: Int = 0
    private var lastSourceText: Int = 0

    fun effect(body: Int, text: Int, sourceBody: Int, sourceText: Int): RenderEffect? {
        if (
            cachedEffect != null &&
                body == lastBody &&
                text == lastText &&
                sourceBody == lastSourceBody &&
                sourceText == lastSourceText
        )
            return cachedEffect
        shader.setFloatUniform(
            "body",
            Color.red(body) / 255f,
            Color.green(body) / 255f,
            Color.blue(body) / 255f,
        )
        shader.setFloatUniform(
            "text",
            Color.red(text) / 255f,
            Color.green(text) / 255f,
            Color.blue(text) / 255f,
        )
        shader.setFloatUniform("sourceBody", NativeBatteryPalette.luma(sourceBody))
        shader.setFloatUniform("sourceText", NativeBatteryPalette.luma(sourceText))
        lastBody = body
        lastText = text
        lastSourceBody = sourceBody
        lastSourceText = sourceText
        cachedEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        return cachedEffect
    }

    companion object {
        @JvmField
        val PROGRAM: String =
            """
            uniform shader content;
            uniform float3 body;
            uniform float3 text;
            uniform float sourceText;
            uniform float sourceBody;
            half4 main(float2 p) {
              half4 sample = content.eval(p);
              if (sample.a < 0.001) return sample;
              float3 rgb = sample.rgb / sample.a;
              float spread = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);
              if (spread > 0.07) return sample;
              float luminance = dot(rgb, float3(0.2126, 0.7152, 0.0722));
              float difference = sourceBody - sourceText;
              float fraction = abs(difference) < 0.05 ? 1.0
                  : clamp((luminance - sourceText) / difference, 0.0, 1.0);
              return half4(mix(text, body, fraction) * sample.a, sample.a);
            }

            """
                .trimIndent()

        @JvmStatic
        private fun luma(color: Int): Float {
            return ((Color.red(color) * .2126f +
                Color.green(color) * .7152f +
                Color.blue(color) * .0722f) / 255f)
        }
    }
}
