package dev.ryuya.galaxybar.live

import dev.ryuya.galaxybar.ContrastDecisionV1
import java.util.Arrays

internal class ContrastDecision : ContrastDecisionV1 {
    override val apiVersion: Int get() = 1

    override fun choose(values: DoubleArray, previous: Int, algorithm: String, hysteresis: Double): Int {
        require(values.size != 0) { "No opaque samples" }
        var white: Double
        var black: Double
        if (algorithm == "median" || algorithm == "mean") {
            var l: Double = 0.0
            if (algorithm == "median") {
                Arrays.sort(values)
                l = values[values.size / 2]
            } else {
                for (v in values) l += v
                l /= values.size.toDouble()
            }
            white = 1.05 / (l + .05)
            black = (l + .05) / .05
        } else if (algorithm == "vote") {
            white = 1.0
            black = 1.0
            for (v in values) {
                if (1.05 / (v + .05) > (v + .05) / .05) white++ else black++
            }
        } else {
            // Lower decile contrast: more robust to mixed backgrounds than a mean.
            // Black contrast increases with luminance; white contrast decreases. One ordered
            // luminance copy gives both original deciles without two contrast arrays/sorts.
            val ordered = values.copyOf()
            Arrays.sort(ordered)
            val decile = values.size / 10
            white = 1.05 / (ordered[values.lastIndex - decile] + .05)
            black = (ordered[decile] + .05) / .05
        }
        val ratio: Double = black / white
        if (previous == -0x1000000 && ratio >= 1 / (1 + hysteresis)) return previous
        if (previous == -0x1 && ratio <= 1 + hysteresis) return previous
        return if (ratio >= 1) -0x1000000 else -0x1
    }
}
