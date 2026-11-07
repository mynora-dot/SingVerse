package com.mynora.singverse.mobile

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Best-effort telemetry has one worker and a bounded, coalescing in-memory backlog of attempts. */
internal class PlaybackHistoryReporter : AutoCloseable {
    data class Grant(val attemptId: String, val writeToken: String)
    data class Frame(val state: String, val actualPlayedMs: Long, val elapsedMs: Long, val durationMs: Long? = null,
        val startedAt: Long = 0, val occurredAt: Long = 0, val lastActivityAt: Long? = null)
    interface Transport {
        val identity: Any
        fun current(): Boolean
        fun create(startedAt: Long): Grant
        fun send(grant: Grant, sequence: Long, frame: Frame)
        fun cancel()
    }
    class Failure(val status: Int) : Exception()
    class Attempt internal constructor(internal val transport: Transport) {
        internal var grant: Grant? = null
        internal var sequence = -1L
        @Volatile internal var invalid = false
        internal var terminal = false
    }
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "singverse-playback-history") }
    private val pending = LinkedHashMap<Attempt, Frame>()
    private val unsupported = LinkedHashSet<Any>()
    private var running = false
    private var closed = false

    fun begin(transport: Transport) = Attempt(transport)

    @Synchronized fun offer(attempt: Attempt, frame: Frame) {
        if (closed || attempt.invalid || attempt.terminal || unsupported.contains(attempt.transport.identity)) return
        if (frame.state in TERMINAL && frame.state != "ERROR") attempt.terminal = true
        pending[attempt] = frame
        while (pending.size > 8) {
            val oldest = pending.keys.first()
            pending.remove(oldest); oldest.invalid = true; oldest.transport.cancel()
        }
        if (!running) {
            running = true
            try { worker.execute(::drain) } catch (_: RejectedExecutionException) { running = false; pending.clear() }
        }
    }

    fun invalidate(attempt: Attempt?) {
        if (attempt == null) return
        synchronized(this) { attempt.invalid = true; pending.remove(attempt) }
        attempt.transport.cancel()
    }

    private fun drain() {
        while (true) {
            val next = synchronized(this) {
                // A new/current song is reported before older best-effort terminal cleanup.
                val entry = pending.entries.lastOrNull()
                if (entry == null) { running = false; return }
                (entry.key to entry.value).also { pending.remove(entry.key) }
            }
            val (attempt, frame) = next
            if (attempt.invalid || !attempt.transport.current()) { invalidate(attempt); continue }
            try {
                if (attempt.grant == null) {
                    if (frame.state in TERMINAL && frame.actualPlayedMs == 0L) continue
                    attempt.grant = attempt.transport.create(frame.startedAt)
                }
                if (attempt.invalid || !attempt.transport.current()) { invalidate(attempt); continue }
                attempt.transport.send(checkNotNull(attempt.grant), ++attempt.sequence, frame)
            } catch (error: Exception) {
                if (error is Failure && error.status in setOf(401, 403, 410)) { invalidate(attempt); continue }
                if (error is Failure && error.status in setOf(404, 405, 501)) synchronized(this) {
                    unsupported.add(attempt.transport.identity)
                    while (unsupported.size > 16) unsupported.remove(unsupported.first())
                    pending.keys.filter { it.transport.identity == attempt.transport.identity }.forEach { pending.remove(it) }
                }
                // The next heartbeat retries the latest cumulative value, never blocks the player.
                // A terminal event gets one bounded retry while this connection is still valid.
                if (frame.state in TERMINAL && (error !is Failure || error.status == 429 || error.status >= 500) &&
                    !attempt.invalid && attempt.transport.current()) {
                    try {
                        val grant = attempt.grant
                        if (grant != null) attempt.transport.send(grant, attempt.sequence, frame)
                    } catch (_: Exception) { }
                }
            }
        }
    }

    override fun close() {
        synchronized(this) { closed = true }
        // The already queued final event has a short transport timeout and may drain after service destruction.
        worker.shutdown()
    }

    companion object { private val TERMINAL = setOf("COMPLETED", "STOPPED", "SKIPPED", "ERROR") }
}
