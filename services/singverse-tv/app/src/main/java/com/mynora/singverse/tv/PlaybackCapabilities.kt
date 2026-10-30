package com.mynora.singverse.tv

import android.media.MediaCodecList
import android.media.MediaFormat
import org.json.JSONArray
import org.json.JSONObject

/** Conservative runtime decoder report; HDR/display routing needs separate validation. */
object PlaybackCapabilities {
    fun report(): JSONObject {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        fun supports(mime: String) = codecs.any { codec -> codec.supportedTypes.any { it.equals(mime, true) } }
        val video = listOf("h264" to MediaFormat.MIMETYPE_VIDEO_AVC, "hevc" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "vp9" to MediaFormat.MIMETYPE_VIDEO_VP9, "av1" to "video/av01").filter { supports(it.second) }.map { it.first }
        val audio = listOf("aac" to MediaFormat.MIMETYPE_AUDIO_AAC, "mp3" to MediaFormat.MIMETYPE_AUDIO_MPEG,
            "flac" to MediaFormat.MIMETYPE_AUDIO_FLAC, "opus" to MediaFormat.MIMETYPE_AUDIO_OPUS,
            "ac3" to MediaFormat.MIMETYPE_AUDIO_AC3).filter { supports(it.second) }.map { it.first }
        return JSONObject().put("family","android-tv").put("video",JSONArray(video)).put("audio",JSONArray(audio))
            .put("containers",JSONArray(listOf("mp4","mkv","webm","ts","mp3","flac","ogg","wav")))
            .put("hls",true).put("fmp4",true).put("max_width",1920).put("max_height",1080)
            .put("max_fps",30).put("max_bitrate",8000000).put("max_channels",2).put("max_bit_depth",8).put("hdr",false)
            .put("profiles",JSONObject().put("h264",JSONArray(listOf("Constrained Baseline","Baseline","Main","High"))))
            .put("levels",JSONObject().put("h264",41))
    }
}
