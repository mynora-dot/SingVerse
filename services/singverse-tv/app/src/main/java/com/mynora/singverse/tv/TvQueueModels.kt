package com.mynora.singverse.tv

/** Preserve server ordering and repeated songs, while excluding current and terminal entries. */
internal fun <T> tvWaitingQueue(queue: List<T>, status: (T) -> String): List<T> =
    queue.filter { status(it) == "WAITING" }
