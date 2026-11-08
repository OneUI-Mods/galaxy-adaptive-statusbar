package dev.ryuya.galaxybar

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Compares the readability of one common color with the colors we would actually use per glyph. */
internal object AutomaticColorGrouping {
    private const val BLACK = -0x1000000
    private const val WHITE = -0x1
    private const val READABLE_CONTRAST = 3.0

    data class Analysis(
        val partColor: Int,
        val characterColors: IntArray,
        val preferCharacters: Boolean,
        val urgent: Boolean,
        val gain: Double,
    ) {
        // During the settle interval, converge the existing glyph fades before merging spans.
        fun characterPhaseColors(): IntArray =
            if (preferCharacters) characterColors else IntArray(characterColors.size) { partColor }
    }

    fun analyze(
        glyphs: List<DoubleArray>,
        previousPart: Int,
        previousCharacters: IntArray,
        algorithm: String,
        hysteresis: Double,
    ): Analysis {
        require(glyphs.isNotEmpty() && glyphs.all { it.isNotEmpty() })
        require(previousCharacters.size == glyphs.size)
        // Equalize glyph weights: wide digits must not drown out a small colon or a narrow digit.
        val pooled =
            DoubleArray(glyphs.size * 32) { i ->
                val glyph = glyphs[i / 32]
                glyph[((i % 32).toLong() * glyph.size / 32).toInt()]
            }
        val colors =
            IntArray(glyphs.size) { i ->
                val samples = if (algorithm == "median") glyphs[i].clone() else glyphs[i]
                Contrast.choose(samples, previousCharacters[i], algorithm, hysteresis)
            }
        var part = Contrast.choose(pooled, previousPart, algorithm, hysteresis)
        val consensus = colors.first()
        if (colors.all { it == consensus } && consensus != part) {
            val oldScore = meanQuality(glyphs, IntArray(glyphs.size) { part })
            val newScore = meanQuality(glyphs, colors)
            if (newScore > oldScore * (1 + hysteresis)) part = consensus
        }
        val commonQuality = DoubleArray(glyphs.size) { quality(glyphs[it], part) }
        val individualQuality =
            DoubleArray(glyphs.size) {
                if (colors[it] == part) commonQuality[it] else quality(glyphs[it], colors[it])
            }
        var improved = 0
        var urgent = false
        var logGain = 0.0
        for (i in glyphs.indices) {
            logGain += ln(individualQuality[i] / commonQuality[i])
            if (
                commonQuality[i] < READABLE_CONTRAST &&
                    individualQuality[i] > commonQuality[i] * 1.3 &&
                    individualQuality[i] - commonQuality[i] >= .7
            ) {
                improved++
                if (commonQuality[i] < 1.8 && individualQuality[i] >= 2.8) urgent = true
            }
        }
        val gain = exp(logGain / glyphs.size)
        val mixed = colors.any { it == BLACK } && colors.any { it == WHITE }
        val split = mixed && improved > 0 && gain >= 1.12
        return Analysis(part, colors, split, split && urgent, gain)
    }

    private fun meanQuality(glyphs: List<DoubleArray>, colors: IntArray): Double =
        exp(glyphs.indices.sumOf { ln(quality(glyphs[it], colors[it])) } / glyphs.size)

    private fun quality(samples: DoubleArray, color: Int): Double {
        val contrasts =
            DoubleArray(samples.size) { i ->
                val luminance = samples[i].coerceIn(0.0, 1.0)
                if (color == WHITE) 1.05 / (luminance + .05) else (luminance + .05) / .05
            }
        contrasts.sort()
        // A lower quantile exposes unreadable patches; the median rejects isolated image noise.
        val low = contrasts[(contrasts.lastIndex * .2).toInt()]
        val median = contrasts[contrasts.size / 2]
        return exp(.65 * ln(max(1.0, low)) + .35 * ln(max(1.0, median)))
    }

    /**
     * React quickly to illegibility, but require a settled background before joining text again.
     */
    class State {
        var characters: Boolean = false
            private set

        private var pendingSince = -1L
        private var changedAt = Long.MIN_VALUE

        fun select(analysis: Analysis, now: Long): Boolean {
            if (analysis.preferCharacters == characters) {
                pendingSince = -1
                return characters
            }
            if (analysis.urgent && analysis.preferCharacters) {
                characters = true
                pendingSince = -1
                changedAt = now
                return true
            }
            if (pendingSince < 0 || now < pendingSince) pendingSince = now
            val dwell = if (analysis.preferCharacters) 100L else 650L
            val heldLongEnough = changedAt == Long.MIN_VALUE || now - changedAt >= 350
            if (heldLongEnough && now - pendingSince >= dwell) {
                characters = analysis.preferCharacters
                pendingSince = -1
                changedAt = now
            }
            return characters
        }
    }
}
