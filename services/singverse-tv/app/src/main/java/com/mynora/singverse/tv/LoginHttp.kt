package com.mynora.singverse.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI

/** A single origin and an isolated, memory-only cookie jar. Never uses the TV Bearer token. */
internal class LoginHttp(address: String) {
    val base = NasAddress.normalize(address)
    private val cookies = mutableMapOf<String, HttpCookie>()

    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, csrf: String? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/") && !path.contains("..") && !path.contains('#'))
        val server = URI(base)
        if (server.scheme == "http") require(InetAddress.getAllByName(server.host).all { it.isLoopbackAddress || it.isSiteLocalAddress }) {
            "HTTP 仅限本机或受限内网 NAS，请使用 HTTPS 访问其他地址"
        }
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", base)
            val cookie = cookies.values.filter { !it.hasExpired() && (!it.secure || server.scheme == "https") }
                .joinToString("; ") { "${it.name}=${it.value}" }
            if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
            csrf?.let { connection.setRequestProperty("X-CSRF-Token", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            // Preserve wire order: the server clears old cookies before setting the new session.
            var headerIndex = 1
            while (connection.getHeaderFieldKey(headerIndex) != null) {
                if (connection.getHeaderFieldKey(headerIndex).equals("Set-Cookie", true)) {
                    HttpCookie.parse(connection.getHeaderField(headerIndex)).filter { it.name in listOf("singverse_session", "singverse_challenge", "singverse_csrf") }.forEach {
                        if (it.hasExpired() || it.value.isEmpty()) cookies.remove(it.name) else cookies[it.name] = it
                    }
                }
                headerIndex++
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(output.size() + count <= 131072) { "登录响应过大" }
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            val text = bytes?.toString(Charsets.UTF_8).orEmpty()
            val result = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { throw java.io.IOException("NAS 返回了非 JSON 响应，请核对地址") }
            if (status !in 200..299) throw TvHttpException(status, result.optString("detail", "请求失败：$status"))
            result
        } finally { connection.disconnect() }
    }

    suspend fun adminPost(path: String, body: JSONObject? = null): JSONObject {
        val csrf = request("/api/v1/auth/csrf").getString("token")
        return request(path, "POST", body, csrf)
    }

    suspend fun logout() {
        try { adminPost("/api/v1/auth/logout") } finally { cookies.clear() }
    }
}
