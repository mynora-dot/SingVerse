package com.mynora.singverse.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

/** A separate device-local index, owned only by the native singing engine. */
class KaraokeWorks(context: Context) : SQLiteOpenHelper(context, "karaoke-works.db", null, 1) {
    val root = File(context.filesDir, "karaoke").apply { mkdirs() }
    val cache = File(context.cacheDir, "karaoke").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE work(id TEXT PRIMARY KEY, metadata TEXT NOT NULL, updated_at INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, next: Int) = Unit
    @Synchronized fun put(meta: JSONObject) {
        val values = ContentValues().apply { put("id", meta.getString("id")); put("metadata", meta.toString()); put("updated_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("work", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun get(id: String): JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "作品标识无效" }
        readableDatabase.query("work", arrayOf("metadata"), "id=?", arrayOf(id), null, null, null).use {
            require(it.moveToFirst()) { "作品不存在" }; return JSONObject(it.getString(0))
        }
    }
    @Synchronized fun all(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        readableDatabase.query("work", arrayOf("metadata"), null, null, null, null, "updated_at DESC").use { while (it.moveToNext()) result.add(JSONObject(it.getString(0))) }
        return result
    }
    fun file(id: String, name: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(name in listOf("voice.wav", "preview.m4a", "mix.m4a", "mix.part", "preview.part"))
        return File(File(root, id).apply { mkdirs() }, name)
    }
    fun backing(id: String) = File(File(cache, id).apply { mkdirs() }, "backing.wav")
    fun playable(id: String): File {
        val meta = get(id)
        val file = file(id, if (meta.optString("status") == "SAVED") "mix.m4a" else "preview.m4a")
        require(file.isFile && file.length() > 0) { "作品尚未生成，请先生成试听" }; return file
    }
    @Synchronized fun delete(id: String) {
        get(id)
        File(root, id).deleteRecursively(); File(cache, id).deleteRecursively()
        writableDatabase.delete("work", "id=?", arrayOf(id))
    }
    fun recover() {
        for (meta in all()) {
            val id = meta.getString("id")
            if (meta.optString("status") in listOf("RECORDING", "FINALIZING", "RENDERING")) {
                KaraokePcm.header(file(id, "voice.wav"), 1); KaraokePcm.header(backing(id), 2)
                meta.put("status", "PREVIEW").put("complete", false).put("interruption", "上次录唱意外结束，已保留可恢复片段")
                meta.put("frames", minOf(KaraokePcm.frames(backing(id), 2), KaraokePcm.frames(file(id, "voice.wav"), 1)))
                val frames = meta.optLong("frames")
                meta.put("durationMs", frames * 1000 / KaraokePcm.RATE)
                    .put("voiceFrames", KaraokePcm.frames(file(id, "voice.wav"), 1))
                    .put("backingFrames", KaraokePcm.frames(backing(id), 2))
                    .put("voiceOffsetFrames", meta.optLong("voiceOffsetFrames"))
                    .put("waveform", org.json.JSONArray(KaraokePcm.waveform(file(id, "voice.wav"), 1)))
                if (frames == 0L) meta.put("status", "FAILED")
                put(meta)
            }
            if (meta.optString("status") == "SAVED" && !file(id, "mix.m4a").isFile) {
                meta.put("status", "MISSING").put("interruption", "本机成品文件丢失"); put(meta)
            }
        }
    }
}
