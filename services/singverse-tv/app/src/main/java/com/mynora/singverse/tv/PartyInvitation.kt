package com.mynora.singverse.tv

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A lease for the existing one-hour invitation; only one refresh may be in flight. */
internal data class PartyInvitation(
    val generation: Long = 0,
    val url: String = "",
    val expiresAt: Long = 0,
    val loading: Boolean = false,
    val error: String = "",
    val retryAfter: Long = 0,
) {
    fun begin(now: Long, force: Boolean = false): PartyInvitation? {
        if (loading || (!force && (now < retryAfter || (url.isNotBlank() && now < expiresAt - 60_000)))) return null
        // The POST invalidates the previous invitation. Hide it while its outcome is unknown.
        return PartyInvitation(generation = generation + 1, loading = true)
    }

    fun complete(requestGeneration: Long, value: String, expiry: Long): PartyInvitation =
        if (loading && generation == requestGeneration) copy(url = value, expiresAt = expiry, loading = false) else this

    fun fail(requestGeneration: Long, message: String, now: Long): PartyInvitation =
        if (loading && generation == requestGeneration) copy(loading = false, error = message, retryAfter = now + 30_000) else this

    fun clear(): PartyInvitation = PartyInvitation(generation = generation + 1)
}

/** java.time is unavailable on some supported API 23 televisions. The service emits UTC. */
internal fun invitationExpiry(value: String): Long {
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z"))) { "邀请有效期无效，请重试" }
    val seconds = value.replace(Regex("\\.\\d+Z$"), "Z")
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }.parse(seconds)?.time ?: error("邀请有效期无效，请重试")
}
