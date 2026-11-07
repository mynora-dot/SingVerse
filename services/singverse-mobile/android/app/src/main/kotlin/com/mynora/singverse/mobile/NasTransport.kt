package com.mynora.singverse.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class NasFailure(val status: Int, message: String, val retryAfter: String = "") : Exception(message)
internal data class NasConnection(val origin: String, val generation: Long)

/** Native-only cookie store, never shared with android.webkit.CookieManager. */
class NasTransport internal constructor(context: Context) {
    private val preferences = context.getSharedPreferences("nas-session", Context.MODE_PRIVATE)
    private val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    @Volatile var origin = ""; private set
    private var generation = 0L
    private fun session() = synchronized(this) { NasConnection(origin, generation) }
    internal fun connectionIdentity(): NasConnection = session()
    private fun checkSession(session: NasConnection) {
        if (session.origin != origin || session.generation != generation) throw NasFailure(409, "NAS 连接已切换")
    }

    init {
        val saved = preferences.getString("sealed", null)
        if (saved != null) runCatching {
            val pieces = saved.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            val state = JSONObject(String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8))
            origin = normalize(state.getString("origin"))
            val cookies = state.getJSONArray("cookies")
            for (i in 0 until cookies.length()) {
                val c = cookies.getJSONObject(i)
                NasCookies.restore(jar, origin, c.getString("name"), c.getString("value"), c.optBoolean("secure"))
            }
        }.onFailure { origin = ""; jar.cookieStore.removeAll(); preferences.edit().remove("sealed").apply() }
    }

    @Synchronized fun configure(value: String): String {
        val next = normalize(value)
        if (next != origin) { generation++; jar.cookieStore.removeAll(); origin = next; persist() }
        return origin
    }

    @Synchronized fun forget() { generation++; jar.cookieStore.removeAll(); persist() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("singverse-nas-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("singverse-nas-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized private fun persist() {
        if (origin.isBlank()) return
        val cookies = JSONArray()
        jar.cookieStore.cookies.filter { !it.hasExpired() }.forEach {
            cookies.put(JSONObject().put("name", it.name).put("value", it.value).put("secure", it.secure))
        }
        val clear = JSONObject().put("origin", origin).put("cookies", cookies).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(clear), Base64.NO_WRAP)
        preferences.edit().putString("sealed", sealed).apply()
    }

    @Synchronized fun cookieHeader(url: String): String {
        requireSameOrigin(url)
        return jar.get(URI(url), emptyMap())["Cookie"]?.joinToString("; ") ?: ""
    }

    fun requireSameOrigin(url: String) {
        checkResource(origin, url)
    }

    @Synchronized private fun accept(session: NasConnection, url: String, response: Response) {
        checkSession(session)
        val headers = response.headers.values("Set-Cookie")
        if (headers.isEmpty()) return
        val before = jar.cookieStore.cookies.map { listOf(it.name, it.value, it.domain, it.path, it.secure) }
        jar.put(URI(url), mapOf("Set-Cookie" to headers))
        val after = jar.cookieStore.cookies.map { listOf(it.name, it.value, it.domain, it.path, it.secure) }
        if (before != after) persist()
    }

    fun request(path: String, method: String = "GET", body: String? = null): String = request(session(), path, method, body, null)

    private fun request(session: NasConnection, path: String, method: String, body: String?, scope: NasRequestScope?): String {
        synchronized(this) { checkSession(session) }
        val csrf = if (method !in listOf("GET", "HEAD"))
            JSONObject(request(session, "/api/v1/auth/csrf", "GET", null, scope)).getString("token") else null
        val url = session.origin + path
        val cookie = synchronized(this) {
            checkSession(session)
            checkResource(session.origin, url)
            jar.get(URI(url), emptyMap())["Cookie"]?.joinToString("; ") ?: ""
        }
        val builder = Request.Builder().url(url).header("Accept", "application/json").header("Cookie", cookie)
        if (csrf != null) builder.header("X-CSRF-Token", csrf)
        builder.method(method, if (method in listOf("GET", "HEAD")) null else (body ?: "").toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        scope?.attach(call)
        try { return call.execute().use { response ->
            accept(session, url, response)
            val bytes = try { read(response, 4 * 1024 * 1024) }
                finally { synchronized(this) { checkSession(session) } }
            bytes.toString(Charsets.UTF_8).ifBlank { "null" }
        } } finally { scope?.detach(call) }
    }

    fun scopedRequest(expectedOrigin: String, path: String, method: String = "GET", body: String? = null, scope: NasRequestScope? = null): String {
        val session = session()
        if (session.origin != expectedOrigin) throw NasFailure(409, "NAS 连接已切换")
        return request(session, path, method, body, scope)
    }

    internal fun scopedRequest(connection: NasConnection, path: String, method: String, body: String?, scope: NasRequestScope): String =
        request(connection, path, method, body, scope)

    fun bytes(path: String): ByteArray {
        val session = session()
        val url = session.origin + path
        val cookie = synchronized(this) { checkSession(session); checkResource(session.origin, url); cookieHeader(url) }
        return client.newCall(Request.Builder().url(url).header("Cookie", cookie).build()).execute().use {
            try { read(it, 8 * 1024 * 1024) }
            finally { synchronized(this) { checkSession(session) } }
        }
    }

    fun resolve(path: String): String {
        if (!path.startsWith("/api/") || path.startsWith("//") || path.contains('\\')) throw NasFailure(400, "资源路径无效")
        return (origin + path).also { requireSameOrigin(it) }
    }

    /** Every HLS playlist, segment and subtitle is checked before cookies are attached. */
    fun mediaClient(): OkHttpClient = client.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        try {
            requireSameOrigin(url)
            chain.proceed(request.newBuilder().header("Cookie", cookieHeader(url)).build())
        } catch (e: Exception) { throw java.io.IOException("NAS 媒体请求失败", e) }
    }.build()

    fun inspect(value: String, invitation: String? = null): String {
        val base = normalize(value)
        val path = if (invitation == null) "/api/v1/server-info" else "/api/v1/party/invitations/preview"
        val b = Request.Builder().url(base + path)
        if (invitation != null) b.post(JSONObject().put("invitation", invitation).toString().toRequestBody("application/json".toMediaType()))
        return client.newCall(b.build()).execute().use { read(it, 1024 * 1024).toString(Charsets.UTF_8) }
    }

    private fun read(response: Response, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        response.body?.byteStream()?.let { input ->
            val buffer = ByteArray(8192)
            while (output.size() <= limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        if (bytes.size > limit) throw NasFailure(502, "NAS 响应过大")
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("detail") }.getOrDefault("")
            throw NasFailure(response.code, detail.ifBlank { "请求失败（${response.code}）" }, response.header("Retry-After").orEmpty())
        }
        return bytes
    }

    companion object {
        @Volatile private var instance: NasTransport? = null
        fun get(context: Context): NasTransport = instance ?: synchronized(this) {
            instance ?: NasTransport(context.applicationContext).also { instance = it }
        }
        fun normalize(value: String): String {
            val uri = URI(value.trim())
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/"))
                throw NasFailure(400, "请输入 NAS 根地址，例如 http://nas.local:18080")
            return authority(uri)
        }
        fun checkResource(origin: String, url: String) {
            val uri = try { URI(url) } catch (e: java.net.URISyntaxException) { throw NasFailure(400, "媒体地址格式无效") }
            if (origin.isBlank() || uri.scheme !in listOf("http", "https") || uri.host == null ||
                authority(uri) != origin || !uri.path.startsWith("/api/") || uri.rawUserInfo != null ||
                uri.rawFragment != null || uri.path.contains('\\') || uri.path.split('/').any { it == "." || it == ".." })
                throw NasFailure(400, "媒体地址不属于当前 NAS")
        }
        private fun authority(uri: URI): String {
            val port = if (uri.port == -1 || uri.scheme == "http" && uri.port == 80 || uri.scheme == "https" && uri.port == 443) "" else ":${uri.port}"
            return "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
        }
    }
}
