package com.mynora.singverse.mobile

import android.os.Build
import org.json.JSONObject

/** A captured NAS generation prevents A→B→A from using a later login for an old attempt. */
internal class MobilePlaybackHistoryTransport(
    private val nas: NasTransport,
    private val connection: NasConnection,
    private val songId: String
) : PlaybackHistoryReporter.Transport {
    private val scope = NasRequestScope(2000)
    override val identity: Any get() = connection
    override fun current() = nas.connectionIdentity() == connection
    private fun post(path: String, body: JSONObject): JSONObject = try {
        JSONObject(nas.scopedRequest(connection, path, "POST", body.toString(), scope))
    } catch (failure: NasFailure) { throw PlaybackHistoryReporter.Failure(failure.status) }
    override fun create(startedAt: Long): PlaybackHistoryReporter.Grant {
        val result = post("/api/v1/mobile/playback-attempts", JSONObject().put("songId", songId).put("deviceName", Build.MODEL.take(100)).put("startedAt", startedAt))
        return PlaybackHistoryReporter.Grant(result.getString("attemptId"), result.getString("writeToken")).also {
            require(it.attemptId.matches(Regex("[a-f0-9-]{36}")) && it.writeToken.isNotBlank())
        }
    }
    override fun send(grant: PlaybackHistoryReporter.Grant, sequence: Long, frame: PlaybackHistoryReporter.Frame) {
        val event = JSONObject().put("writeToken", grant.writeToken).put("eventSeq", sequence).put("state", frame.state)
            .put("actualPlayedMs", frame.actualPlayedMs).put("elapsedMs", frame.elapsedMs)
            .put("occurredAt", frame.occurredAt)
        frame.lastActivityAt?.let { event.put("lastActivityAt", it) }
        frame.durationMs?.let { event.put("durationMs", it) }
        post("/api/v1/mobile/playback-attempts/${grant.attemptId}/events", event)
    }
    override fun cancel() = scope.cancel()
}

internal fun playbackHistoryDuration(source: Long, player: Long, offset: Long, dynamic: Boolean, live: Boolean): Long? {
    val maximum = 86_400_000L
    if (source in 1..maximum) return source
    if (dynamic || live || player <= 0 || offset !in 0 until maximum || player > maximum - offset) return null
    return player + offset
}
