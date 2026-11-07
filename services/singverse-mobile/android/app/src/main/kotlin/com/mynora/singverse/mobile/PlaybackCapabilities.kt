package com.mynora.singverse.mobile

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal data class AvcSupport(val profiles: List<String>, val level: Int, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

/** One decoder must support the advertised profile, level and picture size together. */
internal object PlaybackCapabilities {
    private var cached: String? = null
    private val pictures = listOf(1920 to 1080, 1280 to 720, 960 to 540, 848 to 480, 640 to 360, 320 to 240, 176 to 144)
    private val levels = mapOf(
        9 to (99 to 1485), 10 to (99 to 1485), 11 to (396 to 3000), 12 to (396 to 6000),
        13 to (396 to 11880), 20 to (396 to 11880), 21 to (792 to 19800), 22 to (1620 to 20250),
        30 to (1620 to 40500), 31 to (3600 to 108000), 32 to (5120 to 216000),
        40 to (8192 to 245760), 41 to (8192 to 245760))
    private fun levelRank(value: Int) = if (value == 9) 10.5 else value.toDouble()
    internal fun profile(value: Int): String? = when (value) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        else -> null
    }
    internal fun level(value: Int): Int? = when (value) {
        MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> 9
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> 10
        MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> 11
        MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> 12
        MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> 13
        MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> 20
        MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> 21
        MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> 22
        MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> 30
        MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> 31
        MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> 32
        MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> 40
        MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> 41
        MediaCodecInfo.CodecProfileLevel.AVCLevel42, MediaCodecInfo.CodecProfileLevel.AVCLevel5,
        MediaCodecInfo.CodecProfileLevel.AVCLevel51, MediaCodecInfo.CodecProfileLevel.AVCLevel52,
        MediaCodecInfo.CodecProfileLevel.AVCLevel6, MediaCodecInfo.CodecProfileLevel.AVCLevel61,
        MediaCodecInfo.CodecProfileLevel.AVCLevel62 -> 41
        else -> null
    }
    internal fun candidate(profileLevels: List<Pair<String, Int>>, bitrate: Int, accepts: (Int, Int, Int) -> Boolean): AvcSupport? {
        val maximum = profileLevels.maxByOrNull { levelRank(it.second) }?.second ?: return null
        val limit = levels[maximum] ?: return null
        val profiles = profileLevels.filter { it.second == maximum }.map { it.first }.distinct()
        for ((width, height) in pictures) {
            val blocks = ceil(width / 16.0).toInt() * ceil(height / 16.0).toInt()
            if (blocks > limit.first) continue
            val fps = minOf(30, limit.second / blocks)
            for (rate in listOf(fps, 25, 24, 20, 15).filter { it in 1..fps }.distinct()) {
                if (accepts(width, height, rate)) return AvcSupport(profiles, maximum, width, height, rate, bitrate.coerceAtMost(8_000_000))
            }
        }
        return null
    }
    @Synchronized fun snapshot(): JSONObject {
        cached?.let { return JSONObject(it) }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun video(mime: String) = codecs.mapNotNull { codec -> runCatching {
            if (codec.supportedTypes.none { it.equals(mime, true) }) null else codec.getCapabilitiesForType(mime)
        }.getOrNull() }
        val avc = video("video/avc").mapNotNull { cap ->
            val support = cap.videoCapabilities ?: return@mapNotNull null
            candidate(cap.profileLevels.mapNotNull { pair ->
                val profile = profile(pair.profile); val level = level(pair.level)
                if (profile == null || level == null) null else profile to level
            }, support.bitrateRange.upper) { width, height, fps ->
                width % support.widthAlignment == 0 && height % support.heightAlignment == 0 &&
                    runCatching { support.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)
            }
        }.maxWithOrNull(compareBy<AvcSupport> { it.width * it.height }.thenBy { it.fps }.thenBy { levelRank(it.level) })
            ?: error("手机未报告可用的标准 H.264 解码能力")
        val videos = mutableListOf("h264")
        for ((name, mime) in listOf("hevc" to "video/hevc", "vp9" to "video/x-vnd.on2.vp9", "av1" to "video/av01")) {
            if (video(mime).any { runCatching { it.videoCapabilities?.areSizeAndRateSupported(avc.width, avc.height, avc.fps.toDouble()) == true }.getOrDefault(false) }) videos.add(name)
        }
        val audios = listOf("aac" to "audio/mp4a-latm", "mp3" to "audio/mpeg", "flac" to "audio/flac", "opus" to "audio/opus", "ac3" to "audio/ac3")
            .filter { (_, mime) -> codecs.any { codec -> codec.supportedTypes.any { it.equals(mime, true) } } }.map { it.first }
        val result = JSONObject().put("family", PlaybackProtocol.CLIENT_FAMILY)
            .put("containers", JSONArray(listOf("mp4", "mkv", "webm", "ts", "mp3", "flac", "ogg", "wav")))
            .put("video", JSONArray(videos)).put("audio", JSONArray(audios)).put("hls", true).put("fmp4", true)
            .put("max_width", avc.width).put("max_height", avc.height).put("max_fps", avc.fps)
            .put("max_bitrate", avc.bitrate).put("max_bit_depth", 8).put("max_channels", 2).put("hdr", false)
            .put("profiles", JSONObject().put("h264", JSONArray(avc.profiles))).put("levels", JSONObject().put("h264", avc.level))
        cached = result.toString()
        return result
    }
}
