@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.util.TypedValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/** Uses the existing parsed, offset-adjusted Media3 timeline rather than a lyric timer. */
@Composable internal fun AudioPlayerLyrics(app: TvApp, modifier: Modifier = Modifier) {
    val options = app.playbackOptions
    val current = options.activeLine
    val next = options.nextLine
    options.plainLyrics.firstOrNull { it.first == options.textSelection }?.let { (_, text) ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("未同步歌词", color = Muted, fontSize = 12.sp)
            Text(text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(2).joinToString("\n"),
                color = Paper, fontSize = 22.sp, lineHeight = 29.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    if (options.textSelection == "内嵌字幕" && app.embeddedCues.isNotEmpty()) {
        val textSize = with(LocalDensity.current) { 24.sp.toPx() }
        AndroidView(factory = { SubtitleView(it) }, update = {
            it.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            it.setCues(app.embeddedCues)
        }, modifier = modifier.height(90.dp).semantics {
            contentDescription = app.embeddedCues.mapNotNull { it.text?.toString() }.joinToString("\n")
        })
        return
    }
    var bright by remember(current?.start) { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (bright) 1f else .65f, tween(180), label = "lyricLine")
    LaunchedEffect(current?.start) { bright = true }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (current != null) {
            val text = buildAnnotatedString {
                if (!current.hasCompleteWords) append(current.text)
                else current.words.forEach { word ->
                    withStyle(SpanStyle(color = if (word.at <= options.lyricPosition) Primary else Paper)) { append(word.text) }
                }
            }
            Text(text, color = Primary, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(opacity))
        } else Text(when {
            options.textSelection == "关闭" -> "歌词已关闭"
            options.textLoading -> "正在读取歌词…"
            options.textLoadFailed -> "歌词读取失败，可在歌词设置重试"
            options.textSelection == "内嵌字幕" && options.tracks.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT } -> "等待内嵌歌词 / 字幕"
            next != null -> "等待下一句"
            options.sidecars.isNotEmpty() -> "间奏"
            options.textWarnings.isNotEmpty() -> "歌词暂不可用 · ${options.textWarnings.first()}"
            else -> "暂无歌词"
        }, color = Muted, fontSize = 16.sp, lineHeight = 23.sp)
        next?.let { Text(it.text, color = Paper.copy(alpha = .65f), fontSize = 20.sp, lineHeight = 27.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A low-cost playback status animation. It does not read or imply an audio spectrum. */
@Composable internal fun PlaybackAmbience(playing: Boolean, modifier: Modifier = Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (playing) { delay(100); phase += .19f }
    }
    Canvas(modifier.semantics { contentDescription = "播放状态动画，非实时频谱" }) {
        val count = 48
        val stride = size.width / count
        repeat(count) { index ->
            val envelope = .18f + .82f * abs(sin(index * .16f))
            val pulse = if (playing) .3f + .7f * abs(sin(index * .61f + phase)) else .12f
            val height = size.height * envelope * pulse
            drawRect(Primary.copy(alpha = if (playing) .55f else .2f), Offset(index * stride, size.height - height), Size(stride * .5f, height))
        }
    }
}
