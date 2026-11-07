package com.mynora.singverse.mobile

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Old cleanup, heartbeat and metadata I/O must never stand ahead of a new song. */
internal class PlaybackRequests : AutoCloseable {
    private fun executor(capacity: Int) = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity), ThreadPoolExecutor.DiscardOldestPolicy())
    private val opening = executor(1)
    private val heartbeat = executor(1)
    private val cleanup = executor(8)
    private val metadata = executor(8)
    private fun submit(worker: ThreadPoolExecutor, scope: NasRequestScope?, task: () -> Unit) {
        if (worker.isShutdown) return
        try { worker.execute { if (scope?.cancelled != true) task() } } catch (_: RejectedExecutionException) { }
    }
    fun open(scope: NasRequestScope, task: () -> Unit) = submit(opening, scope, task)
    fun poll(scope: NasRequestScope, task: () -> Unit) = submit(heartbeat, scope, task)
    fun metadata(scope: NasRequestScope, task: () -> Unit) = submit(metadata, scope, task)
    fun cancel(task: () -> Unit) = submit(cleanup, null, task)
    override fun close() {
        listOf(opening, heartbeat, metadata).forEach { it.shutdownNow() }
        // The last bounded best-effort lease cancellation still runs after service release.
        cleanup.shutdown()
    }
}
