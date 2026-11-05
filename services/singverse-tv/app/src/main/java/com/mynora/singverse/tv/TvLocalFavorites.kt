package com.mynora.singverse.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Personal bookmarks on this television; NAS song IDs are scoped to their server. */
internal class TvLocalFavorites(context: Context) {
    private val preferences = context.getSharedPreferences("tv-local-favorites", Context.MODE_PRIVATE)
    private fun key(server: String): String = MessageDigest.getInstance("SHA-256")
        .digest(server.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun songs(server: String): List<JSONObject> = runCatching {
        JSONArray(preferences.getString(key(server), "[]")).objects().filter { it.optString("id").isNotBlank() }
    }.getOrDefault(emptyList())

    fun toggle(server: String, song: JSONObject) {
        val id = song.optString("id")
        if (server.isBlank() || id.isBlank()) return
        val existing = songs(server)
        val result = if (existing.any { it.optString("id") == id }) existing.filterNot { it.optString("id") == id }
        else {
            val bookmark = JSONObject()
            listOf("id", "title", "artists", "coverPath", "revision", "album", "releaseDate", "durationMs").forEach {
                if (song.has(it) && !song.isNull(it)) bookmark.put(it, song.get(it))
            }
            listOf(bookmark) + existing
        }
        preferences.edit().putString(key(server), JSONArray(result.take(200)).toString()).apply()
    }
}
