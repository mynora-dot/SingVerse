package com.mynora.singverse.tv

import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Conservative runtime decoder report; HDR/display routing needs separate validation. */
object PlaybackCapabilities {
    internal data class DecoderLimit(val width: Int, val height: Int, val bitrate: Int, val profiles: Map<String, Int> = emptyMap(), val fps: Int = 30)
    private val pictures = listOf(1920 to 1080, 1280 to 720, 960 to 540, 848 to 480, 720 to 480, 640 to 360, 320 to 240, 176 to 144)
    private val avcBlocks = mapOf(9 to (99 to 1485), 10 to (99 to 1485), 11 to (396 to 3000), 12 to (396 to 6000),
        13 to (396 to 11880), 20 to (396 to 11880), 21 to (792 to 19800), 22 to (1620 to 20250),
        30 to (1620 to 40500), 31 to (3600 to 108000), 32 to (5120 to 216000), 40 to (8192 to 245760))
    private fun levelRank(level: Int) = if (level == 9) 10.5 else level.toDouble()
    // Codec enumeration may load vendor services. Cache off the UI thread, returning a fresh JSON value.
    private val snapshot: String by lazy { detect().toString() }
    suspend fun report(): JSONObject = withContext(Dispatchers.Default) { JSONObject(snapshot) }

    private fun detect(): JSONObject {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun supports(mime: String) = codecs.any { codec -> codec.supportedTypes.any { it.equals(mime, true) } }
        val avcLevels = mutableMapOf<String, Int>()
        val videoLimits = compatibleVideoLimits(listOf("h264" to MediaFormat.MIMETYPE_VIDEO_AVC, "hevc" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "vp9" to MediaFormat.MIMETYPE_VIDEO_VP9, "av1" to "video/av01").mapNotNull { (name, mime) ->
            val candidates = codecs.filter { codec -> codec.supportedTypes.any { it.equals(mime, true) } }.mapNotNull { codec ->
                runCatching {
                    val capabilities = codec.getCapabilitiesForType(mime)
                    val video = capabilities.videoCapabilities ?: return@runCatching null
                    val profiles = mutableMapOf<String, Int>()
                    if (name == "h264") capabilities.profileLevels.forEach { value ->
                        val profile = avcProfile(value.profile)
                        val level = avcLevel(value.level)
                        if (profile != null && level > 0 && levelRank(level) > levelRank(profiles[profile] ?: 0)) profiles[profile] = level
                    }
                    val bitrate = video.bitrateRange.upper.coerceAtMost(8_000_000)
                    if (name == "h264") avcCandidate(profiles, bitrate) { width, height, fps ->
                        width % video.widthAlignment == 0 && height % video.heightAlignment == 0 && video.areSizeAndRateSupported(width, height, fps.toDouble())
                    } else pictures.firstOrNull { (width, height) -> video.areSizeAndRateSupported(width, height, 30.0) }
                        ?.let { DecoderLimit(it.first, it.second, bitrate) }
                }.getOrNull()
            }
            candidates.maxWithOrNull(compareBy<DecoderLimit> { it.width * it.height }.thenBy { it.fps })?.let {
                if (name == "h264") avcLevels.putAll(it.profiles)
                name to it
            }
        }.filter { it.first != "h264" || avcLevels.isNotEmpty() })
        // Keep AVC's usable envelope; omit weaker optional decoders instead of lowering all video quality.
        val width = videoLimits.minOfOrNull { it.second.width } ?: 0
        val height = videoLimits.minOfOrNull { it.second.height } ?: 0
        val bitrate = videoLimits.minOfOrNull { it.second.bitrate } ?: 0
        val fps = videoLimits.minOfOrNull { it.second.fps } ?: 0
        val audio = listOf("aac" to MediaFormat.MIMETYPE_AUDIO_AAC, "mp3" to MediaFormat.MIMETYPE_AUDIO_MPEG,
            "flac" to MediaFormat.MIMETYPE_AUDIO_FLAC, "opus" to MediaFormat.MIMETYPE_AUDIO_OPUS,
            "vorbis" to MediaFormat.MIMETYPE_AUDIO_VORBIS, "ac3" to MediaFormat.MIMETYPE_AUDIO_AC3)
            .filter { supports(it.second) }.map { it.first } + "pcm_s16le"
        return JSONObject().put("family","android-tv").put("video",JSONArray(videoLimits.map { it.first })).put("audio",JSONArray(audio))
            .put("containers",JSONArray(listOf("mp4","mkv","webm","ts","mp3","flac","ogg","wav")))
            .put("hls",true).put("fmp4",true).put("max_width",width).put("max_height",height)
            .put("max_fps",fps).put("max_bitrate",bitrate).put("max_channels",2).put("max_bit_depth",8).put("hdr",false)
            .put("profiles",JSONObject().put("h264",JSONArray(avcLevels.keys.toList())))
            .put("levels",JSONObject().put("h264",avcLevels.values.minOrNull() ?: 0))
    }

    internal fun compatibleVideoLimits(limits: List<Pair<String, DecoderLimit>>): List<Pair<String, DecoderLimit>> {
        val primary = limits.firstOrNull { it.first == "h264" }?.second
            ?: limits.maxByOrNull { it.second.width * it.second.height }?.second ?: return emptyList()
        return limits.filter { (_, value) -> value.width >= primary.width && value.height >= primary.height && value.bitrate >= primary.bitrate && value.fps >= primary.fps }
    }

    internal fun avcCandidate(profiles: Map<String, Int>, bitrate: Int, accepts: (Int, Int, Int) -> Boolean): DecoderLimit? {
        val level = profiles.values.maxByOrNull(::levelRank) ?: return null
        val blocks = avcBlocks[if (level >= 40) 40 else level] ?: return null
        // One global level cannot describe High31 + Baseline41 as High41.
        val reported = profiles.filterValues { it == level }
        for ((width, height) in pictures) {
            val picture = ((width + 15) / 16) * ((height + 15) / 16)
            if (picture > blocks.first) continue
            val maximumFps = minOf(30, blocks.second / picture)
            for (fps in listOf(maximumFps, 25, 24, 20, 15).filter { it in 1..maximumFps }.distinct()) {
                if (runCatching { accepts(width, height, fps) }.getOrDefault(false)) return DecoderLimit(width, height, bitrate, reported, fps)
            }
        }
        return null
    }

    internal fun avcProfile(profile: Int): String? = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        else -> null // High10/422/444 do not establish an 8-bit decoder profile.
    }

    internal fun avcLevel(level: Int): Int = when (level) {
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> 10
        MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> 9
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
        // The product envelope is 1080p30/8Mbps; higher levels only add encoder constraints.
        MediaCodecInfo.CodecProfileLevel.AVCLevel41,
        MediaCodecInfo.CodecProfileLevel.AVCLevel42,
        MediaCodecInfo.CodecProfileLevel.AVCLevel5,
        MediaCodecInfo.CodecProfileLevel.AVCLevel51,
        MediaCodecInfo.CodecProfileLevel.AVCLevel52,
        MediaCodecInfo.CodecProfileLevel.AVCLevel6,
        MediaCodecInfo.CodecProfileLevel.AVCLevel61,
        MediaCodecInfo.CodecProfileLevel.AVCLevel62 -> 41
        else -> 0
    }
}
