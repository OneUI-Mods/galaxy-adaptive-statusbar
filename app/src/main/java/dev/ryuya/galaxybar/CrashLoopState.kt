package dev.ryuya.galaxybar

import android.app.ApplicationExitInfo
import java.util.LinkedHashMap
import java.util.Properties
import java.util.StringJoiner
import java.util.TreeSet
import java.util.function.Consumer
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Persistent policy only: counts exits of processes in which this module armed its hooks. */
class CrashLoopState {
    @JvmRecord
    data class Exit(val pid: Int, val time: Long, val reason: Int, val signal: Int) {
        override fun toString(): String =
            "Exit[pid=$pid, time=$time, reason=$reason, signal=$signal]"
    }

    @JvmField val launches: LinkedHashMap<Int?, Long?> = LinkedHashMap<Int?, Long?>()
    @JvmField val crashes: TreeSet<Long?> = TreeSet<Long?>()
    @JvmField var blocked: Boolean = false
    @JvmField var boot: Int = -1
    @JvmField var lastObserved: Long = 0
    @JvmField var lastStopAt: Long = 0
    @JvmField var resetToken: String = ""
    @JvmField var cause: String = ""
    @JvmField var lastStopCause: String = ""

    fun observe(
        now: Long,
        currentBoot: Int,
        requestedReset: String,
        exits: MutableList<CrashLoopState.Exit?>,
    ) {
        if (!requestedReset.isEmpty() && requestedReset != resetToken) {
            blocked = false
            cause = ""
            launches.clear()
            crashes.clear()
            resetToken = requestedReset
        }
        if (boot != currentBoot || now < lastObserved) {
            launches.clear()
            crashes.clear()
            boot = currentBoot
        }
        lastObserved = now
        crashes.removeIf { t: Long? -> t!! > now || now - t > CrashLoopState.WINDOW_MS }!!
        for (exit in exits) {
            val started = launches.get(exit!!.pid)
            if (started == null || exit!!.time < started || exit!!.time > now) continue
            if (exit!!.reason == ApplicationExitInfo.REASON_UNKNOWN) continue
            launches.remove(exit!!.pid)
            if (
                CrashLoopState.abnormal(exit!!.reason, exit!!.signal) &&
                    now - exit!!.time <= CrashLoopState.WINDOW_MS
            )
                crashes.add(exit!!.time)
        }
        if (!blocked && crashes.size >= CrashLoopState.LIMIT) stop("crash_loop", now)
        // Exit history is asynchronous and bounded. An absent/UNKNOWN record is not
        // evidence of a crash: manual restarts may appear in a later observation.
    }

    fun stop(reason: String, now: Long) {
        blocked = true
        cause = reason
        lastStopCause = reason
        lastStopAt = now
    }

    fun disarm(pid: Int) {
        launches.remove(pid)
    }

    fun arm(pid: Int, now: Long) {
        check(!blocked) { "Crash protection is latched" }
        launches.put(pid, now)
        while (launches.size > 8) launches.remove(launches.keys.iterator().next())
    }

    fun encode(): Properties {
        val p = Properties()
        p.setProperty("format", "1")
        p.setProperty("blocked", blocked.toString())
        p.setProperty("boot", boot.toString())
        p.setProperty("observed", lastObserved.toString())
        p.setProperty("reset", resetToken)
        p.setProperty("cause", cause)
        p.setProperty("last_stop_cause", lastStopCause)
        p.setProperty("last_stop_at", lastStopAt.toString())
        val starts = StringJoiner(";")
        launches.forEach { (pid: Int?, time: Long?) -> starts.add(pid.toString() + ":" + time) }
        p.setProperty("launches", starts.toString())
        val times = StringJoiner(";")
        crashes.forEach(Consumer { time: Long? -> times.add(time.toString()) })
        p.setProperty("crashes", times.toString())
        return p
    }

    companion object {
        const val LIMIT: Int = 3
        const val WINDOW_MS: Long = 120000

        @JvmStatic
        fun abnormal(reason: Int, signal: Int): Boolean {
            return reason == ApplicationExitInfo.REASON_CRASH ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_ANR ||
                reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
                (reason == ApplicationExitInfo.REASON_SIGNALED && (signal == 6 || signal == 11))
        }

        @JvmStatic
        fun decode(p: Properties): CrashLoopState {
            require("1" == p.getProperty("format")) { "Guard state format" }
            val state = CrashLoopState()
            val blocked = p.getProperty("blocked")
            require(!("true" != blocked && "false" != blocked)) { "Guard latch" }
            state.blocked = blocked.toBoolean()
            state.boot = p.getProperty("boot").toInt()
            state.lastObserved = p.getProperty("observed").toLong()
            state.resetToken = p.getProperty("reset", "")
            state.cause = p.getProperty("cause", "")
            state.lastStopCause = p.getProperty("last_stop_cause", "")
            state.lastStopAt = p.getProperty("last_stop_at", "0").toLong()
            for (entry in
                p.getProperty("launches", "")
                    .split(";".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()) {
                if (entry.isEmpty()) continue
                val pair = entry.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                require(pair.size == 2) { "Guard launch" }
                state.launches.put(pair[0].toInt(), pair[1].toLong())
            }
            for (time in
                p.getProperty("crashes", "")
                    .split(";".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()) if (!time.isEmpty()) state.crashes.add(time.toLong())
            require(!(state.launches.size > 8 || state.crashes.size > 32)) { "Guard bounds" }
            return state
        }
    }
}
