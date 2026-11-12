package com.mynora.singverse.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.tv.material3.Text

private val PlaybackSettingTabs = listOf(
    "音轨" to "音轨",
    "歌词字幕" to "歌词字幕",
    "声音输出" to "声音输出",
    "同步" to "同步",
)

/** Shared by the player dialog and Settings; playback operations stay in PlaybackOptions/TvApp. */
@Composable internal fun PlaybackSettings(
    app: TvApp,
    initialTab: String = "音轨",
    audioOnly: Boolean = app.playerMode == TvPlayerMode.AUDIO,
    modifier: Modifier = Modifier,
) {
    var tab by remember(initialTab) {
        mutableStateOf(PlaybackSettingTabs.firstOrNull { initialTab == it.first || initialTab == it.second }?.first ?: "音轨")
    }
    Column(modifier.fillMaxWidth().heightIn(max = 332.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            items(PlaybackSettingTabs, key = { it.first }) { (value, label) ->
                Action(label, selected = tab == value, focusKey = "playback:tab:$value", textSize = 14.sp) { tab = value }
            }
        }
        key(tab, audioOnly) {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().background(Ink, TvShape.card),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    "音轨" -> audioTrackSettings(app)
                    "歌词字幕" -> textTrackSettings(app)
                    "声音输出" -> outputSettings(app)
                    "同步" -> synchronizationSettings(app, audioOnly)
                }
            }
        }
    }
}

private fun LazyListScope.audioTrackSettings(app: TvApp) {
    val options = app.playbackOptions
    val audio = options.tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
    section("audio", "可用音轨", "选择音轨后试听，仅显示本机可播放的音轨")
    if (audio.isEmpty()) item("audio:empty") { SettingNote("开始播放后显示可用音轨。") }
    items(audio, key = { "audio:${it.group}:${it.index}" }) { track ->
        Action(track.label, Modifier.fillMaxWidth(), selected = track.selected,
            focusKey = "playback:audio:${track.group}:${track.index}") { options.selectTrack(track) }
    }
    if (audio.any { it.selected && it.channels == 2 }) {
        section("channel", "声道", "分别试听左右声道，也可保留立体声或混合为单声道")
        item("channel:choices") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("STEREO" to "立体声", "LEFT" to "左声道", "RIGHT" to "右声道", "MONO" to "混合单声道").forEach { (mode, label) ->
                    Action(label, Modifier.weight(1f), selected = options.channel == mode, textSize = 14.sp,
                        focusKey = "playback:channel:$mode") { options.selectChannel(mode) }
                }
            }
        }
    }
    if (audio.isNotEmpty()) {
        section("roles", "原唱 / 伴奏标记", "先试听再标记，演唱时可在主控制栏切换")
        item("roles:status") {
            SettingNote("原唱${if (options.roleOriginal.isBlank()) "未标记" else "已标记"} · 伴奏${if (options.roleBacking.isBlank()) "未标记" else "已标记"}")
        }
        item("roles:original") {
            Action("当前标记为原唱", Modifier.fillMaxWidth(), focusKey = "playback:role:original") { app.saveAudioRole(true) }
        }
        item("roles:backing") {
            Action("当前标记为伴奏", Modifier.fillMaxWidth(), focusKey = "playback:role:backing") { app.saveAudioRole(false) }
        }
    }
}

private fun LazyListScope.textTrackSettings(app: TvApp) {
    val options = app.playbackOptions
    section("text", "歌词与字幕来源", options.textStatus.ifBlank { "开始播放后读取歌词与字幕。" })
    items(listOf("关闭", "内嵌字幕") + (options.sidecars.map { it.first } + options.plainLyrics.map { it.first }).distinct(), key = { "text:source:$it" }) { name ->
        Action(name, Modifier.fillMaxWidth(), selected = options.textSelection == name,
            focusKey = "playback:text:source:$name") { options.chooseText(name) }
    }
    val embedded = options.tracks.filter { it.type == C.TRACK_TYPE_TEXT }
    if (embedded.isNotEmpty()) {
        section("embedded", "内嵌字幕轨道")
        items(embedded, key = { "text:track:${it.group}:${it.index}" }) { track ->
            Action(track.label, Modifier.fillMaxWidth(), selected = track.selected && options.textSelection == "内嵌字幕",
                focusKey = "playback:text:track:${track.group}:${track.index}") { options.selectTrack(track) }
        }
    }
    item("text:reload") {
        Action("重新读取歌词 / 字幕", Modifier.fillMaxWidth(), focusKey = "playback:text:reload") { app.reloadText() }
    }
}

private fun LazyListScope.outputSettings(app: TvApp) {
    val options = app.playbackOptions
    section("output", "声音输出", "优先输出：${options.route}。实际输出由电视系统协商。")
    item("output:default") {
        Action("系统默认输出", Modifier.fillMaxWidth(), selected = options.route == "系统默认",
            focusKey = "playback:output:default") { options.chooseRoute(null) }
    }
    items(options.routes, key = { "output:route:${it.id}" }) { route ->
        Action(route.name, Modifier.fillMaxWidth(), focusKey = "playback:output:${route.id}") { options.chooseRoute(route.id) }
    }
    section("volume", "应用音量", "只调整应用音量，不等同于电视或功放系统音量。")
    item("volume:stepper") {
        SettingStepper("应用音量", "${options.volume}%", "playback:volume:down", "playback:volume:up",
            decreaseEnabled = options.volume > 0, increaseEnabled = options.volume < 100,
            onDecrease = { options.changeVolume(options.volume - 10) }, onIncrease = { options.changeVolume(options.volume + 10) })
    }
    section("pitch", "音调", "范围 ±6 半音，保持原播放速度。")
    item("pitch:stepper") {
        SettingStepper("音调", "${if (options.semitones > 0) "+" else ""}${options.semitones} 半音", "playback:pitch:down", "playback:pitch:up",
            decreaseEnabled = options.semitones > -6, increaseEnabled = options.semitones < 6,
            onDecrease = { options.setPitch(options.semitones - 1) }, onIncrease = { options.setPitch(options.semitones + 1) })
    }
    item("pitch:reset") {
        Action("恢复原调", Modifier.fillMaxWidth(), selected = options.semitones == 0,
            focusKey = "playback:pitch:reset") { options.setPitch(0) }
    }
}

private fun LazyListScope.synchronizationSettings(app: TvApp, audioOnly: Boolean) {
    val options = app.playbackOptions
    if (!audioOnly) {
        section("quality", "视频画质", "切换后沿用当前歌曲与播放进度。")
        item("quality:choices") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto", "1080p", "720p", "480p").forEach { quality ->
                    Action(if (quality == "auto") "自动画质" else quality, Modifier.weight(1f), selected = app.playbackQuality == quality, textSize = 14.sp,
                        focusKey = "playback:quality:$quality") { app.changeQuality(quality) }
                }
            }
        }
    }
    section("lyric-offset", "外置歌词偏移", "正数延后显示，负数提前显示；内嵌字幕使用媒体自身时间轴。")
    item("lyric-offset:stepper") {
        SettingStepper("歌词偏移", "${options.lyricOffset} ms", "playback:lyric-offset:earlier", "playback:lyric-offset:later",
            decreaseEnabled = options.lyricOffset > -5000, increaseEnabled = options.lyricOffset < 5000,
            onDecrease = { options.offset(options.lyricOffset - 100) }, onIncrease = { options.offset(options.lyricOffset + 100) })
    }
    section("audio-delay", "PCM 音频延迟", "范围 0–500ms；按实际音响效果调节。")
    item("audio-delay:stepper") {
        SettingStepper("音频延迟", "${options.audioDelay} ms", "playback:audio-delay:earlier", "playback:audio-delay:later",
            decreaseEnabled = options.audioDelay > 0, increaseEnabled = options.audioDelay < 500,
            onDecrease = { options.delayAudio(options.audioDelay - 50) }, onIncrease = { options.delayAudio(options.audioDelay + 50) })
    }
    item("sync:reset") {
        Action("恢复同步默认值", Modifier.fillMaxWidth(), focusKey = "playback:sync:reset") { options.offset(0); options.delayAudio(0) }
    }
}

@Composable private fun SettingStepper(
    label: String,
    value: String,
    decreaseKey: String,
    increaseKey: String,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(Panel, TvShape.control).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Paper, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        Text(value, color = Paper, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
        Action("−", Modifier.width(48.dp), focusKey = decreaseKey, enabled = decreaseEnabled, onClick = onDecrease)
        Spacer(Modifier.width(6.dp))
        Action("＋", Modifier.width(48.dp), focusKey = increaseKey, enabled = increaseEnabled, onClick = onIncrease)
    }
}

private fun LazyListScope.section(key: String, title: String, description: String = "") {
    item("section:$key") {
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Paper, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (description.isNotBlank()) SettingNote(description)
        }
    }
}

@Composable private fun SettingNote(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
}
