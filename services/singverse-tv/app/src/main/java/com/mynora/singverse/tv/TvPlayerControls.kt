package com.mynora.singverse.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

/** Shared controls only; the parent owns the different video/audio presentation. */
@Composable internal fun PlayerControls(
    app: TvApp,
    audioOnly: Boolean,
    onQueue: () -> Unit,
    onLyrics: () -> Unit,
    onOutput: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    primaryFocus: FocusRequester,
) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" }
    val waiting = queue.firstOrNull { it.optString("status") in listOf("WAITING", "HANDOFF") }
    val playbackId = app.snapshot.optJSONObject("playback")?.optString("songId").orEmpty()
    val songId = app.currentSong?.optString("id")?.takeIf { it.isNotBlank() && it == playbackId }.orEmpty()
    val hasSong = current != null || (playbackId.isNotBlank() && app.playerState in listOf("PLAYING", "PAUSED", "PREPARING", "BUFFERING", "ERROR"))
    val confirming = current == null && waiting != null && app.snapshot.optJSONObject("playback")?.optBoolean("confirmed") != true
    val canRestart = !confirming && playbackId.isNotBlank() && (current != null || songId.isNotBlank())
    val retrying = !confirming && app.playerState == "ERROR"
    val pause = app.playing
    val label = when {
        confirming -> "确认开唱"
        retrying -> "重试"
        pause -> "暂停"
        hasSong -> "继续"
        else -> "播放"
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PlayerProgress(app)
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerControlButton(label, if (pause) "pause" else if (retrying) "restart" else "play", "player:pause",
                Modifier.focusRequester(primaryFocus), enabled = hasSong || confirming, emphasized = true) {
                when {
                    confirming -> app.command("START")
                    retrying -> app.retryPlayback()
                    pause -> app.pauseLocal()
                    else -> app.command("RESUME")
                }
            }
            PlayerControlButton("重唱", "restart", "player:restart", enabled = canRestart) { app.command("RESTART") }
            PlayerControlButton("切歌", "next", "player:next", enabled = hasSong || waiting != null) { app.command("NEXT") }
            PlayerControlButton("已点", "queue", "player:queue", onClick = onQueue)
            val favorite = songId.isNotBlank() && app.isFavorite(songId)
            PlayerControlButton(if (favorite) "已收藏" else "收藏", "favorite", "player:favorite", selected = favorite, enabled = songId.isNotBlank()) { app.toggleFavorite() }
            Spacer(Modifier.weight(1f))
            Row(Modifier.background(Ink.copy(alpha = .48f), TvShape.control).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerControlButton("原唱", "original", "player:role:original", selected = app.activeAudioRole == "original", enabled = app.canUseAudioRole(true)) { app.useAudioRole(true) }
                PlayerControlButton("伴奏", "backing", "player:role:backing", selected = app.activeAudioRole == "backing", enabled = app.canUseAudioRole(false)) { app.useAudioRole(false) }
            }
            Spacer(Modifier.weight(1f))
            PlayerControlButton(if (audioOnly) "歌词" else "歌词字幕", "lyrics", "player:lyrics", onClick = onLyrics)
            PlayerControlButton(if (audioOnly) "音效" else "声音输出", "output", "player:output", onClick = onOutput)
            PlayerControlButton(if (audioOnly) "更多" else "更多设置", "more", "player:more", onClick = onMore)
        }
    }
}

/** Left/right previews only. Actual seeking requires confirmation, and Back/focus loss cancels. */
@Composable private fun PlayerProgress(app: TvApp) {
    val songId = app.snapshot.optJSONObject("playback")?.optString("songId").orEmpty()
    var preview by remember(songId) { mutableStateOf<Long?>(null) }
    var focused by remember { mutableStateOf(false) }
    val duration = app.duration.coerceAtLeast(0)
    val position = (preview ?: app.position).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    BackHandler(enabled = focused && preview != null) { preview = null }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(30.dp).tvFocusTarget("player:progress")
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) preview = null }
            .tvFocus(focused, RoundedCornerShape(8.dp))
            .background(if (focused) Elevated.copy(alpha = .8f) else Color.Transparent, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown && duration > 0) {
                            val offset = if (event.key == Key.DirectionLeft) -10_000L else 10_000L
                            preview = ((preview ?: app.position) + offset).coerceIn(0, duration)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            preview?.let { app.seekAbsolute(it.coerceIn(0, duration)); preview = null }
                        }
                        true
                    }
                    else -> false
                }
            }
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                stateDescription = if (preview != null) "预览 ${controlTime(position)}，确认跳转，返回取消" else "${controlTime(position)} / ${controlTime(duration)}，左右预览"
            }.focusable().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(controlTime(position), color = if (preview != null) Primary else Paper, fontSize = 11.sp, lineHeight = 15.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Paper.copy(alpha = .16f), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (preview != null) Focus else Primary, RoundedCornerShape(2.dp)))
            }
            if (preview != null) Text("确认跳转 · 返回取消", color = Muted, fontSize = 9.sp, lineHeight = 11.sp)
        }
        Text(controlTime(duration), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable private fun PlayerControlButton(
    label: String,
    icon: String,
    focusKey: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.control
    val highlighted = selected || emphasized
    val ink = if (highlighted && enabled) Ink else if (enabled) Paper else Muted.copy(alpha = .6f)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp).tvFocusTarget("action:$focusKey").onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape).semantics {
                this.selected = selected
                if (!enabled) stateDescription = "不可用"
            },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = if (highlighted) Primary else Panel.copy(alpha = .8f), contentColor = ink,
            focusedContainerColor = if (highlighted) Primary else Elevated, focusedContentColor = ink,
            disabledContainerColor = if (selected) Primary.copy(alpha = .65f) else Panel.copy(alpha = .35f), disabledContentColor = ink,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PlayerControlGlyph(icon, ink)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label, color = ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!enabled) Text("不可用", color = ink, fontSize = 8.sp, lineHeight = 10.sp)
            }
        }
    }
}

@Composable private fun PlayerControlGlyph(kind: String, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke.width, StrokeCap.Round)
        when (kind) {
            "pause" -> { line(.32f, .18f, .32f, .82f); line(.68f, .18f, .68f, .82f) }
            "play" -> drawPath(Path().apply { moveTo(w * .3f, h * .15f); lineTo(w * .82f, h * .5f); lineTo(w * .3f, h * .85f); close() }, color, style = stroke)
            "restart" -> { drawArc(color, -45f, 285f, false, Offset(w * .16f, h * .16f), Size(w * .68f, h * .68f), style = stroke); line(.74f, .08f, .74f, .32f); line(.74f, .32f, .5f, .32f) }
            "next" -> { line(.18f, .2f, .68f, .5f); line(.68f, .5f, .18f, .8f); line(.8f, .18f, .8f, .82f) }
            "queue" -> { repeat(3) { i -> val y = .24f + i * .26f; drawCircle(color, w * .035f, Offset(w * .14f, h * y)); line(.32f, y, .88f, y) } }
            "favorite" -> drawPath(Path().apply { moveTo(w * .5f, h * .88f); cubicTo(w * -.2f, h * .36f, w * .12f, h * -.08f, w * .5f, h * .27f); cubicTo(w * .88f, h * -.08f, w * 1.2f, h * .36f, w * .5f, h * .88f); close() }, color, style = stroke)
            "original" -> { drawRoundRect(color, Offset(w * .36f, h * .08f), Size(w * .28f, h * .5f), androidx.compose.ui.geometry.CornerRadius(w * .14f), style = stroke); drawArc(color, 0f, 180f, false, Offset(w * .2f, h * .32f), Size(w * .6f, h * .4f), style = stroke); line(.5f, .72f, .5f, .91f); line(.34f, .92f, .66f, .92f) }
            "backing" -> { line(.39f, .16f, .39f, .77f); line(.39f, .16f, .82f, .08f); line(.82f, .08f, .82f, .68f); drawOval(color, Offset(w * .12f, h * .68f), Size(w * .27f, h * .2f), style = stroke); drawOval(color, Offset(w * .55f, h * .6f), Size(w * .27f, h * .2f), style = stroke) }
            "lyrics" -> { drawRoundRect(color, Offset(w * .1f, h * .17f), Size(w * .8f, h * .66f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = stroke); line(.27f, .4f, .73f, .4f); line(.27f, .6f, .6f, .6f) }
            "output" -> { drawPath(Path().apply { moveTo(w * .1f, h * .35f); lineTo(w * .32f, h * .35f); lineTo(w * .52f, h * .17f); lineTo(w * .52f, h * .83f); lineTo(w * .32f, h * .65f); lineTo(w * .1f, h * .65f); close() }, color, style = stroke); drawArc(color, -55f, 110f, false, Offset(w * .5f, h * .2f), Size(w * .4f, h * .6f), style = stroke) }
            else -> repeat(3) { i -> drawCircle(color, w * .065f, Offset(w * (.2f + i * .3f), h * .5f), style = stroke) }
        }
    }
}

private fun controlTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60_000}:${(ms.coerceAtLeast(0) / 1_000 % 60).toString().padStart(2, '0')}"
