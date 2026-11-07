package com.mynora.singverse.tv

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Every media request, including HLS children and subtitles, stays on the authorized NAS. */
internal object TvMediaTransport {
    fun requireSameOrigin(origin: String, resource: String) {
        val base = URI(origin)
        val uri = URI(resource)
        fun port(value: URI) = if (value.port != -1) value.port else if (value.scheme == "https") 443 else 80
        require(base.scheme in listOf("http", "https") && !base.host.isNullOrBlank())
        require(uri.scheme == base.scheme && uri.host.equals(base.host, true) && port(uri) == port(base) &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.path.startsWith("/api/v1/") &&
            !uri.path.contains('\\') && uri.path.split('/').none { it == "." || it == ".." }) {
            "媒体地址不属于当前 NAS"
        }
    }

    fun client(connection: () -> Pair<String, String>): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val (address, token) = connection()
            try { requireSameOrigin(address, request.url.toString()) }
            catch (failure: Exception) { throw IOException("媒体地址不属于当前 NAS", failure) }
            if (connection() != (address to token)) throw IOException("NAS 连接已切换")
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        }.build()
}
