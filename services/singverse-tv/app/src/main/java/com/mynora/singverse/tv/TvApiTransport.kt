package com.mynora.singverse.tv

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TvApiResponse(val status: Int, val body: String)

/** Cancellation closes the active socket, including a response stalled after its headers. */
internal object TvApiTransport {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun request(connection: Pair<String, String>, path: String, method: String, body: String?): TvApiResponse {
        val (address, credential) = connection
        TvMediaTransport.requireSameOrigin(address, address + path)
        val content = body?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (method in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        val request = Request.Builder().url(address + path).method(method, content).header("Accept", "application/json")
            .apply { if (credential.isNotBlank()) header("Authorization", "Bearer $credential") }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val bytes = it.body?.byteStream()?.use { stream ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > 2_000_000) throw IOException("服务响应过大")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: ByteArray(0)
                            TvApiResponse(it.code, String(bytes, Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
