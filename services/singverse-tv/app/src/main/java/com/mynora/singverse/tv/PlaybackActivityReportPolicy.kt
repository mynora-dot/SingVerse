package com.mynora.singverse.tv

/** Expiration stops telemetry; current-attempt terminal controls still complete the party queue. */
internal fun shouldReportPlaybackActivity(attempt: String, expiredAttempt: String, state: String): Boolean =
    attempt.isNotBlank() && (attempt != expiredAttempt || state == "ENDED" || state == "IDLE")
