package dev.ryuya.galaxybar

import dev.ryuya.galaxybar.live.ContrastDecision

internal interface ContrastDecisionV1 {
    val apiVersion: Int
    fun choose(values: DoubleArray, previous: Int, algorithm: String, hysteresis: Double): Int
}

internal object ContrastDecisionRuntime {
    private val bundled = ContrastDecision()

    fun choose(values: DoubleArray, previous: Int, algorithm: String, hysteresis: Double): Int =
        bundled.choose(values, previous, algorithm, hysteresis)
}
