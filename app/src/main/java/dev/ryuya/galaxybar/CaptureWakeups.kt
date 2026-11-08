package dev.ryuya.galaxybar

/** Main-thread scheduling: events wake one turn; inactive states own no periodic timer. */
internal class CaptureWakeups(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val run: () -> Unit,
) {
    private var closed = false
    private var waitingForWorker = false
    private val callback = Runnable { if (!closed) run() }

    fun request() = schedule(0)

    fun schedule(delayMillis: Long?) {
        if (closed) return
        waitingForWorker = false
        remove(callback)
        if (delayMillis != null) post(callback, delayMillis)
    }

    fun afterTurn(
        available: Boolean,
        configured: Boolean,
        enabled: Boolean,
        stopped: Boolean,
        interval: Long,
    ) {
        val repeat = available && (!configured || (enabled && !stopped))
        schedule(if (!repeat) null else if (!configured) 500L else interval)
    }

    fun awaitWorker() {
        if (closed) return
        remove(callback)
        waitingForWorker = true
    }

    fun workerFinished() {
        if (!closed && waitingForWorker) request()
    }

    fun close() {
        closed = true
        waitingForWorker = false
        remove(callback)
    }
}
