package com.mynora.singverse.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TvHttpException(val status: Int, message: String) : java.io.IOException(message)

class TvClient(context: Context) {
    private val covers = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    private val preferences = context.getSharedPreferences("nas", Context.MODE_PRIVATE)
    @Volatile var base: String = preferences.getString("base", "") ?: ""
    @Volatile var token: String = ""
    @Volatile internal var connectionGeneration = 0L; private set
    init {
        token = runCatching {
            val value = preferences.getString("token", null) ?: return@runCatching ""
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("singverse-device", null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("singverse-device", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun credentials(): Pair<String, String> = base to token
    @Synchronized fun save(address: String, credential: String) {
        val uri = URI(address.trim().trimEnd('/'))
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "请输入 NAS 根地址，例如 http://nas:18080" }
        if (base != uri.toString()) covers.evictAll()
        connectionGeneration++
        base = uri.toString(); token = credential
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("base", base).putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    @Synchronized fun clear() { connectionGeneration++; covers.evictAll(); token = ""; preferences.edit().remove("token").apply() }
    suspend fun image(path: String, revision: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val (address, credential) = credentials()
        val key=address+path+"@"+revision
        covers.get(key)?.let { return@withContext it }
        if (!path.startsWith("/api/v1/tv/home/banners/") && !path.startsWith("/api/v1/tv/library/songs/") && !path.startsWith("/api/v1/tv/library/classification-tags/") && !path.startsWith("/api/v1/tv/library/artists/")) return@withContext null
        val connection = URI(address + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $credential")
            if (connection.responseCode != 200) return@withContext null
            val bytes = connection.inputStream.use { it.readBytesBounded(2_000_000) }
            val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            if(options.outWidth<=0 || options.outHeight<=0)return@withContext null
            options.inSampleSize=1
            val limit=if(path.startsWith("/api/v1/tv/home/banners/") || path.startsWith("/api/v1/tv/library/classification-tags/") || path.startsWith("/api/v1/tv/library/artists/"))1280 else 512
            while(maxOf(options.outWidth/options.inSampleSize,options.outHeight/options.inSampleSize)>limit)options.inSampleSize*=2
            options.inJustDecodeBounds=false
            if (base != address || token != credential) return@withContext null
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.also { covers.put(key,it) }
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) { val count = read(chunk); if (count < 0) break; require(out.size() + count <= limit) { "服务响应过大" }; out.write(chunk, 0, count) }
        return out.toByteArray()
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, connection: Pair<String, String> = credentials()): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/"))
        val (address, _) = connection
        val server = URI(address)
        if (server.scheme == "http") require(java.net.InetAddress.getAllByName(server.host).all { it.isLoopbackAddress || it.isSiteLocalAddress }) { "HTTP 仅限本机或受限内网 NAS，请使用 HTTPS 访问其他地址" }
        val response = TvApiTransport.request(connection, path, method, body?.toString())
        if (response.status !in 200..299) throw TvHttpException(response.status, runCatching { JSONObject(response.body).optString("detail", "请求失败：${response.status}") }.getOrDefault("请求失败：${response.status}"))
        response.body
    }
}
