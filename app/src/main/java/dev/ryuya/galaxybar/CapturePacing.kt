package dev.ryuya.galaxybar

import kotlin.math.ceil
import kotlin.math.max

/** Limits compositor capture work independently of palette changes and animation callbacks. */
internal class CapturePacing {
    private var lastFinishedAtMs: Long? = null
    private var lastCostMs = 0.0
    private var cooldownMs = 0L
    private var retryAtMs = 0L
    private var recoverySamples = 0
    private var recoveryCeilingMs = 0L

    @Synchronized
    fun completed(finishedAtMs: Long, costMs: Double, successful: Boolean) {
        lastFinishedAtMs = finishedAtMs
        lastCostMs = if (costMs.isFinite() && costMs >= 0.0) costMs else 250.0
        val requestedCooldown = cooldownFor(lastCostMs)
        if (requestedCooldown > cooldownMs) {
            cooldownMs = requestedCooldown
            recoverySamples = 0
            recoveryCeilingMs = 0L
        } else if (successful && requestedCooldown < cooldownMs) {
            recoveryCeilingMs = max(recoveryCeilingMs, requestedCooldown)
            if (++recoverySamples >= RECOVERY_SAMPLES) {
                cooldownMs = recoveryCeilingMs
                recoverySamples = 0
                recoveryCeilingMs = 0L
            }
        } else {
            recoverySamples = 0
            recoveryCeilingMs = 0L
        }
        // Failed captures cannot clear an expensive-capture cooldown or spin on unavailable pixels.
        retryAtMs = if (successful) 0L else finishedAtMs + 250L
    }

    @Synchronized
    fun delayMs(nowMs: Long, requestedIntervalMs: Long): Long {
        val finished = lastFinishedAtMs ?: return 0L
        val normalRest = max(0L, requestedIntervalMs - lastCostMs.toLong())
        val next = max(finished + max(normalRest, cooldownMs), retryAtMs)
        return max(0L, next - nowMs)
    }

    @Synchronized fun minimumRestMs(): Long = cooldownMs

    companion object {
        private const val FRAME_BUDGET_MS = 1000.0 / 120.0
        private const val RECOVERY_SAMPLES = 6

        private fun cooldownFor(costMs: Double): Long =
            when {
                costMs >= FRAME_BUDGET_MS * 12 -> 5000L
                costMs >= FRAME_BUDGET_MS * 4 -> 2000L
                costMs >= FRAME_BUDGET_MS * 2 -> 1000L
                costMs > FRAME_BUDGET_MS -> max(250L, ceil(costMs * 20).toLong())
                else -> 0L
            }
    }
}
