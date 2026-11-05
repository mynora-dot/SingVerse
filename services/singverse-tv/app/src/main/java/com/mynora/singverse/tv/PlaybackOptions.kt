@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv

import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import kotlin.math.pow

data class PlayerTrack(val group: Int, val index: Int, val type: Int, val label: String, val selected: Boolean, val channels: Int, val sourceId: String = "")
data class OutputRoute(val id: Int, val name: String)
internal const val GENERATED_BACKING_SOURCE = "derived:backing"
class PlaybackOptions(private val context: Context, private val player: ExoPlayer, private val audio: KaraokeAudioProcessor) {
    private val prefs = context.getSharedPreferences("playback-options", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var tracks by mutableStateOf(listOf<PlayerTrack>()); private set
    var routes by mutableStateOf(listOf<OutputRoute>()); private set
    var route by mutableStateOf("系统默认"); private set
    var channel by mutableStateOf("STEREO"); private set
    var semitones by mutableIntStateOf(0); private set
    var volume by mutableIntStateOf(prefs.getInt("volume", 100).coerceIn(0,100)); private set
    var lyricOffset by mutableIntStateOf(prefs.getInt("lyric-offset",0).coerceIn(-5000,5000)); private set
    var sidecars by mutableStateOf(listOf<Pair<String,List<TextLine>>>()); private set
    var plainLyrics by mutableStateOf(listOf<Pair<String,String>>()); private set
    var textLoading by mutableStateOf(false); private set
    var textLoadFailed by mutableStateOf(false); private set
    var textWarnings by mutableStateOf(listOf<String>()); private set
    var textSelection by mutableStateOf("内嵌字幕"); private set
    var activeLine by mutableStateOf<TextLine?>(null); private set
    var nextLine by mutableStateOf<TextLine?>(null); private set
    var lyricPosition by mutableLongStateOf(0); private set
    var textStatus by mutableStateOf(""); private set
    var roleOriginal by mutableStateOf(""); private set
    var roleBacking by mutableStateOf(""); private set
    private var songKey = ""
    private var songId = ""
    var audioDelay by mutableIntStateOf(prefs.getInt("audio-delay",0).coerceIn(0,500)); private set
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { refreshRoutes() }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) { route = "系统默认"; player.setPreferredAudioDevice(null); refreshRoutes() }
    }
    init { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())); player.volume=volume/100f; audio.delayMs=audioDelay; refreshRoutes() }
    private var sourceAudio = listOf<String>()
    private var sourceAudioLabels = listOf<String>()
    private var sourceRepresentation = true
    private var audioOnly = false
    private var textChoiceExplicit = false
    fun setAudioOnly(value: Boolean) { audioOnly = value; selectAudioLyrics() }
    private fun selectAudioLyrics() {
        if (audioOnly && !textChoiceExplicit && (sidecars.isNotEmpty() || plainLyrics.isNotEmpty())) {
            // The endpoint orders original sidecars before the metadata fallback.
            textSelection = sidecars.firstOrNull()?.first ?: plainLyrics.first().first
            val current = player.trackSelectionParameters
            val updated = current.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            // setAudioOnly also runs from onTracksChanged; avoid feeding unchanged parameters
            // back into track selection each time that callback reports the selected tracks.
            if (updated != current) player.trackSelectionParameters = updated
        }
    }
    fun configureSource(streams: org.json.JSONArray?, mapping: org.json.JSONArray?, original: Boolean = true, generatedBacking: Boolean = false) {
        sourceRepresentation = original
        val mapped = mapping?.objects().orEmpty().filter { it.optString("kind")=="audio" }.map { it.optString("sourceId") }
        sourceAudio = if(mapped.isNotEmpty()) mapped else streams?.objects().orEmpty().filter { it.optString("kind")=="audio" }.map { it.optString("id") }
        val source=if(mapped.isNotEmpty()) mapping?.objects().orEmpty() else streams?.objects().orEmpty()
        sourceAudioLabels=source.filter { it.optString("kind")=="audio" }.map { it.optString("title") }
        if (generatedBacking) {
            sourceAudio = sourceAudio + GENERATED_BACKING_SOURCE
            sourceAudioLabels = sourceAudioLabels + ""
        }
        refreshTracks()
    }
    fun refreshTracks() {
        var audioOrdinal = 0
        val count=player.currentTracks.groups.filter { it.type==C.TRACK_TYPE_AUDIO }.sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } }
        tracks = player.currentTracks.groups.flatMapIndexed { groupIndex, group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
            val f=group.getTrackFormat(index)
            val sourceId=if(group.type==C.TRACK_TYPE_AUDIO) {
                val ordinal=audioOrdinal++
                val label=f.label.orEmpty()
                val match=sourceAudioLabels.indexOfFirst { it.isNotBlank() && it==label }
                if(match>=0 && sourceAudioLabels.count { it==label }==1) sourceAudio.getOrElse(match) { "" }
                else if(count==sourceAudio.size) sourceAudio.getOrElse(ordinal) { "" } else ""
            } else ""
            PlayerTrack(groupIndex,index,group.type,listOfNotNull(f.label, f.language?.takeUnless { it=="und" },f.sampleMimeType, f.channelCount.takeIf { it>0 }?.let { "$it 声道" }).joinToString(" · ").ifBlank { "轨道 ${index+1}" },group.isTrackSelected(index),f.channelCount,sourceId)
        } }
    }
    fun selectSource(sourceId: String): Boolean {
        val target=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.sourceId==sourceId } ?: return false
        if(!target.selected)selectTrack(target)
        return true
    }
    fun selectTrack(track: PlayerTrack) {
        val group=player.currentTracks.groups.getOrNull(track.group) ?: return
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(track.type,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,track.index)).build()
        if(track.type==C.TRACK_TYPE_TEXT) { textChoiceExplicit = true; textSelection="内嵌字幕" }
        refreshTracks()
    }
    fun hideText() { textChoiceExplicit = true; textSelection="关闭"; activeLine=null; nextLine=null; player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build() }
    fun chooseText(name: String) { textChoiceExplicit = true; if(name=="关闭") { hideText();return };textSelection=name;activeLine=null;nextLine=null;player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,name!="内嵌字幕").build() }
    fun selectChannel(mode: String) { require(mode in listOf("STEREO","LEFT","RIGHT","MONO"));require(mode=="STEREO" || tracks.any { it.type==C.TRACK_TYPE_AUDIO && it.selected && it.channels==2 }) { "当前音轨不是双声道，无法拆分左右声道" };channel=mode;audio.channelMode=mode }
    fun setPitch(value: Int) { semitones=value.coerceIn(-6,6); player.playbackParameters=PlaybackParameters(1f,2.0.pow(semitones/12.0).toFloat()) }
    fun changeVolume(value: Int) {volume=value.coerceIn(0,100);player.volume=volume/100f;prefs.edit().putInt("volume",volume).apply()}
    fun offset(value: Int) {lyricOffset=value.coerceIn(-5000,5000);prefs.edit().putInt("lyric-offset",lyricOffset).apply()}
    fun delayAudio(value: Int) { audioDelay=value.coerceIn(0,500);audio.delayMs=audioDelay;prefs.edit().putInt("audio-delay",audioDelay).apply() }
    fun refreshRoutes(){routes=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
        val kind=when(it.type){AudioDeviceInfo.TYPE_HDMI,AudioDeviceInfo.TYPE_HDMI_ARC->"HDMI";AudioDeviceInfo.TYPE_BUILTIN_SPEAKER->"扬声器";AudioDeviceInfo.TYPE_BLUETOOTH_A2DP->"蓝牙";AudioDeviceInfo.TYPE_USB_DEVICE->"USB";AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET->"有线耳机";else->"输出 ${it.id}"}
        val count=it.channelCounts.maxOrNull()?.takeIf { count->count>0 }?.let { count->"$count 声道" } ?: "声道能力未报告"
        OutputRoute(it.id,"$kind · ${it.productName} · $count")
    }}
    fun chooseRoute(id: Int?) {val device=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id==id };player.setPreferredAudioDevice(device);route=device?.productName?.toString() ?: "系统默认"}
    fun beginSong(id: String, revision: String) {
        sourceAudio=emptyList();sourceAudioLabels=emptyList();songId=id;songKey="$id:$revision";sidecars=emptyList();plainLyrics=emptyList();activeLine=null;nextLine=null;textChoiceExplicit=false;textLoading=true;textLoadFailed=false;textWarnings=emptyList();textStatus="读取歌词与字幕…";textSelection="内嵌字幕";channel="STEREO";audio.channelMode="STEREO";setPitch(0)
        roleOriginal=prefs.getString("original:$songKey","") ?: "";roleBacking=prefs.getString("backing:$songKey","") ?: ""
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).build()
    }
    fun applyText(id: String,result: JSONObject) {
        if(songId!=id)return
        textLoading=false;textLoadFailed=false
        val timed = mutableListOf<Pair<String,List<TextLine>>>()
        val plain = mutableListOf<Pair<String,String>>()
        val warnings = result.optJSONArray("warnings")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() } }?.toMutableList() ?: mutableListOf()
        result.optJSONArray("items")?.objects().orEmpty().forEach { item ->
            val format = item.optString("format").trim().lowercase(java.util.Locale.ROOT)
            val label = (if (item.optString("id").startsWith("metadata")) "元数据歌词 · " else "") + if (format == "text") "未同步" else format.uppercase(java.util.Locale.ROOT)
            val text = item.optString("text")
            if (format == "text") {
                if (text.isNotBlank() && text.length <= 131072) plain += label to text
                else if (text.length > 131072) warnings += "未同步歌词超过显示限制"
            } else {
                val parsed = runCatching { TextTimeline.parse(format, text) }.getOrNull()
                if (!parsed.isNullOrEmpty()) timed += label to parsed
                else if (text.isNotBlank()) warnings += "${format.uppercase(java.util.Locale.ROOT)} 歌词无法解析"
            }
        }
        sidecars=timed;plainLyrics=plain;textWarnings=warnings
        selectAudioLyrics()
        val count=sidecars.size+plainLyrics.size
        textStatus=if(count==0) "没有可用歌词文件；内嵌轨道以播放器识别为准" else "$count 个歌词/字幕轨可用"
        if(textWarnings.isNotEmpty())textStatus+=" · ${textWarnings.first()}"
    }
    fun beginTextRead(id: String) { if(songId==id){textLoading=true;textLoadFailed=false;textStatus="读取歌词与字幕…"} }
    fun textFailed(id: String){if(songId==id){textLoading=false;textLoadFailed=true;textStatus="歌词读取失败，可在播放设置重试"}}
    fun tick(position: Long){lyricPosition=position-lyricOffset;val preview=tvLyricPreview(sidecars.firstOrNull { it.first==textSelection }?.second.orEmpty(),lyricPosition);activeLine=preview.current;nextLine=preview.next}
    private fun currentSelection(): String {
        val selected=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.selected } ?: return ""
        return if(selected.sourceId.isNotBlank()) "${selected.sourceId}|$channel" else "${selected.group}:${selected.index}:$channel"
    }
    fun isRoleSelected(original: Boolean): Boolean {
        val (track, mode) = roleTrack(original) ?: return false
        return track.selected && channel == mode
    }
    private fun roleTrack(original: Boolean): Pair<PlayerTrack, String>? {
        val role=if(original)roleOriginal else roleBacking
        if(role.isBlank())return null
        val track: PlayerTrack
        val mode: String
        if(role=="generated") {
            mode="STEREO"
            track=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.sourceId==GENERATED_BACKING_SOURCE } ?: return null
        } else if(role.contains('|')) {
            mode=role.substringAfter('|')
            track=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.sourceId==role.substringBefore('|') } ?: return null
        } else {
            if(!sourceRepresentation)return null
            val saved=role.split(':');if(saved.size!=3)return null
            mode=saved[2]
            track=tracks.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.group.toString()==saved[0] && it.index.toString()==saved[1] } ?: return null
        }
        if(mode !in listOf("STEREO","LEFT","RIGHT","MONO") || (mode!="STEREO" && track.channels!=2))return null
        return track to mode
    }
    fun canUseRole(original: Boolean): Boolean = roleTrack(original) != null
    fun applyShared(value: JSONObject) {
        roleOriginal=value.optString("originalId").takeIf { it.isNotBlank() }?.let { "$it|STEREO" } ?: ""
        if(roleOriginal.isBlank() && value.optJSONArray("streams")?.length()==1)roleOriginal="${value.getJSONArray("streams").getJSONObject(0).optString("id")}|STEREO"
        roleBacking=value.optString("backingId").takeIf { it.isNotBlank() }?.let { "$it|STEREO" } ?: ""
        if(value.has("backingResource"))roleBacking="generated"
        if(value.optJSONArray("streams")?.length()==1){
            val localOriginal=prefs.getString("original:$songKey","").orEmpty();val localBacking=prefs.getString("backing:$songKey","").orEmpty()
            if(isChannelRole(localOriginal))roleOriginal=localOriginal
            if(isChannelRole(localBacking))roleBacking=localBacking
        }
    }
    fun isChannelRole(role: String)=listOf("|LEFT","|RIGHT",":LEFT",":RIGHT").any { role.endsWith(it) }
    fun markRole(original: Boolean){val value=currentSelection();if(value.isBlank())return;if(original)roleOriginal=value else roleBacking=value;prefs.edit().putString("${if(original) "original" else "backing"}:$songKey",value).apply()}
    fun useRole(original: Boolean): Boolean {
        val (track, mode)=roleTrack(original) ?: return false
        if(!track.selected)selectTrack(track)
        // roleTrack already validated the target channel count. Media3 may still report the
        // previous selected track until onTracksChanged after the override is applied.
        if(channel!=mode){channel=mode;audio.channelMode=mode}
        val role=if(original)roleOriginal else roleBacking
        if(track.sourceId.isNotBlank() && role!="generated" && !role.contains('|'))markRole(original)
        return true
    }
    fun close(){manager.unregisterAudioDeviceCallback(callback)}
}
