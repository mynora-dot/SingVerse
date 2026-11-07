package com.mynora.singverse.mobile

/** Main-thread counter: media positions and wall-clock changes never contribute listening time. */
internal class ActualPlaybackClock(private val now: () -> Long) {
    data class Sample(val actualPlayedMs: Long, val elapsedMs: Long)
    var attempt: String = ""; private set
    var startedAtEpochMs: Long = 0; private set
    var occurredAtEpochMs: Long = 0; private set
    var lastActivityAtEpochMs: Long? = null; private set
    private var startedAt = 0L
    private var sampledAt = 0L
    private var actualPlayedMs = 0L
    private var wasPlaying = false

    fun start(key: String, startedAtEpochMs: Long = System.currentTimeMillis()): Boolean {
        require(key.isNotBlank())
        if (attempt == key) return false
        attempt = key
        this.startedAtEpochMs = startedAtEpochMs; occurredAtEpochMs = startedAtEpochMs; lastActivityAtEpochMs = null
        startedAt = now(); sampledAt = startedAt; actualPlayedMs = 0; wasPlaying = false
        return true
    }

    fun sample(isPlaying: Boolean): Sample {
        if (attempt.isEmpty()) return Sample(0, 0)
        val current = maxOf(sampledAt, now())
        occurredAtEpochMs = startedAtEpochMs + (current - startedAt)
        if (wasPlaying || isPlaying) lastActivityAtEpochMs = occurredAtEpochMs
        if (wasPlaying) actualPlayedMs += current - sampledAt
        sampledAt = current; wasPlaying = isPlaying
        return Sample(actualPlayedMs, current - startedAt)
    }

    fun clear() { attempt = ""; wasPlaying = false; actualPlayedMs = 0; startedAtEpochMs = 0; occurredAtEpochMs = 0; lastActivityAtEpochMs = null }
}
