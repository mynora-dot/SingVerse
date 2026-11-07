package com.mynora.singverse.mobile

import okhttp3.Call
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cancels the CSRF and API calls belonging to one playback attempt together. */
class NasRequestScope(val timeoutMillis: Long = 30_000) {
    private val calls = mutableSetOf<Call>()
    @Volatile var cancelled = false; private set

    @Synchronized internal fun attach(call: Call) {
        if (cancelled) { call.cancel(); throw IOException("播放请求已取消") }
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        calls.add(call)
    }
    @Synchronized internal fun detach(call: Call) { calls.remove(call) }
    fun cancel() {
        val pending = synchronized(this) { cancelled = true; calls.toList().also { calls.clear() } }
        pending.forEach { it.cancel() }
    }
}
