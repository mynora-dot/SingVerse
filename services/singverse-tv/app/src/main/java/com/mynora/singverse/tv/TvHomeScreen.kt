package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import kotlinx.coroutines.delay

@Composable internal fun TvHomeScreen(app: TvApp, onPhone: () -> Unit) {
    val appearance = app.homeAppearance
    val banners = appearance.optJSONArray("banners")?.objects().orEmpty().filter { it.optBoolean("enabled", true) }
    // Recent scans remain a truthful fallback for the feature, never for the hot/history sections.
    val recommendations = (app.homePopularSongs.ifEmpty { app.homeSongs }).take(8)
    val signature = app.client.base + banners.joinToString { it.optString("id") + it.optString("version") } + recommendations.joinToString { it.optString("id") }
    var index by remember(signature) { mutableIntStateOf(0) }
    var bannerFocused by remember { mutableStateOf(false) }
    val count = maxOf(banners.size, recommendations.size)
    val song = recommendations.getOrNull(if (recommendations.isEmpty()) 0 else index % recommendations.size)
    val active = banners.getOrNull(if (banners.isEmpty()) 0 else index % banners.size)
    LaunchedEffect(signature, index, bannerFocused, appearance.optBoolean("autoRotate", true), app.online, app.foreground) {
        if (count > 1 && !bannerFocused && appearance.optBoolean("autoRotate", true) && app.online && app.foreground) {
            delay(appearance.optInt("rotationSeconds", 8).coerceIn(5, 60) * 1000L)
            index = (index + 1) % count
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(124.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.weight(1f).fillMaxHeight().onFocusChanged { bannerFocused = it.hasFocus }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active != null) HomeImage(app, active.optString("imagePath"), active.optString("version"), Modifier.size(124.dp).clip(TvShape.card))
                else SongArtwork(app, song, Modifier.size(124.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(appearance.optString("headline").ifBlank { "今晚，先唱这一首" }, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.optString("title") ?: "唱你喜欢的歌", color = Paper, fontSize = 32.sp, lineHeight = 40.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.artists() ?: appearance.optString("subtitle").ifBlank { "在 NAS 导入自备歌曲，开启家庭音乐现场" }, color = Muted,
                        fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HomeButton(if (song == null) "去点歌" else "加入待唱", primary = true, focusKey = "featured:add") {
                            if (song == null) app.navigate("点歌") else app.addSongFromList(song)
                        }
                        if (song != null) HomeButton("歌曲详情", focusKey = "featured:sing") { app.openSong(song) }
                        if (count > 1) {
                            HomeButton("‹", Modifier.width(28.dp), focusKey = "featured:previous") { index = (index - 1 + count) % count }
                            Text("${index + 1}/$count", color = Muted, fontSize = 12.sp)
                            HomeButton("›", Modifier.width(28.dp), focusKey = "featured:next") { index = (index + 1) % count }
                        }
                    }
                }
            }
            HomeTile(Modifier.width(216.dp).fillMaxHeight().tvFocusTarget("phone"), onClick = onPhone) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeGlyph("phone", Modifier.size(18.dp), Muted)
                        Text("手机点歌", color = Paper, fontSize = 18.sp, lineHeight = 24.sp)
                    }
                    Text("亲友扫码，各点一首", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (app.snapshot.optString("status") == "ENDED") "开始新聚会后邀请" else if (!app.online) "网络异常 · 确认以重连" else if (app.participantsKnown) "${app.participantCount} 台设备已加入  ·  打开二维码" else "打开本场邀请二维码",
                        color = if (app.online) Paper else Danger, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeSectionHeading("家庭热歌", "近 30 天常唱", "查看榜单", "hot:all") { app.navigate("热歌") }
            if (app.homePopularSongs.isEmpty()) HomeFeedEmpty(
                if (!app.homeRecommendationsAvailable) "请更新 NAS 服务以读取家庭热歌" else "还没有常唱歌曲，先去点一首", 84.dp)
            else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                app.homePopularSongs.take(4).forEachIndexed { rank, item ->
                    HomeSongTile(app, item, Modifier.weight(1f).height(84.dp), "hot:${item.optString("id")}",
                        "${rank + 1}  ·  ${item.optLong("effectivePlayCount")} 次有效演唱")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeSectionHeading("这台电视唱过", "最近演唱", "全部记录", "history:all") { app.navigate("历史") }
            if (app.homeHistorySongs.isEmpty()) HomeFeedEmpty(
                if (!app.homeRecommendationsAvailable) "请更新 NAS 服务以读取演唱历史" else "这台电视还没有演唱记录", 58.dp)
            else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                app.homeHistorySongs.take(3).forEach { item ->
                    HomeSongTile(app, item, Modifier.weight(1f).height(64.dp), "history:${item.optString("id")}",
                        "${tvHistoryDate(item.optString("lastPlayedAt"))} · ${tvHistoryStatus(item.optString("lastPlaybackStatus"))}", compact = true)
                }
            }
        }
        if (appearance.optString("subtitle").isNotBlank() && song != null) Text(appearance.optString("subtitle"), color = Muted,
            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HomeSectionHeading(title: String, subtitle: String, action: String, focusKey: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        Text("   $subtitle", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        HomeButton(action, focusKey = focusKey, onClick = onClick)
    }
}

@Composable private fun HomeFeedEmpty(message: String, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height).background(Panel, TvShape.card).padding(16.dp), contentAlignment = Alignment.CenterStart) {
        Text(message, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable private fun HomeSongTile(app: TvApp, song: org.json.JSONObject, modifier: Modifier, key: String, caption: String, compact: Boolean = false) {
    HomeTile(modifier.tvFocusTarget(key), onClick = { app.openSong(song) }) {
        Row(Modifier.fillMaxSize().padding(if (compact) 8.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SongArtwork(app, song, Modifier.size(if (compact) 40.dp else 48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(song.optString("title"), color = Paper, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (compact) caption else song.artists(), color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact) Text(caption, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun TvNavigation(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.control
    Button(onClick, modifier.height(36.dp).semantics { this.selected = selected; stateDescription = if (selected) "当前页面" else "" }
        .onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape, focusedScale = 1f), contentPadding = PaddingValues(horizontal = 14.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = if (focused) Elevated else Color.Transparent,
            contentColor = if (selected || focused) Paper else Muted, focusedContainerColor = Elevated, focusedContentColor = Paper)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.width(16.dp).height(1.5.dp).background(if (selected) Paper else Color.Transparent))
        }
    }
}

@Composable private fun HomeImage(app: TvApp, path: String, version: String, modifier: Modifier) {
    val server = app.client.base
    var bitmap by remember(server, path, version) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(server, path, version) {
        if (path.isNotBlank()) bitmap = try { app.client.image(path, version) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    val opacity by animateFloatAsState(if (bitmap == null) 0f else 1f, tween(400), label = "homeImageFade")
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF242426), Color(0xFF111111))))) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = opacity }, contentScale = ContentScale.Crop)
        else HomeGlyph("brand", Modifier.size(68.dp).align(Alignment.CenterEnd).padding(end = 28.dp), Paper.copy(alpha = .1f))
    }
}

@Composable internal fun HomeButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, focusKey: String = label, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick, modifier.height(34.dp).tvFocusTarget(focusKey).onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.control),
        contentPadding = PaddingValues(horizontal = if (label in listOf("‹", "›")) 0.dp else 15.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.control),
        colors = ButtonDefaults.colors(containerColor = if (primary) Paper else Elevated.copy(alpha = .86f), contentColor = if (primary) Ink else Paper,
            focusedContainerColor = if (primary) Paper else Elevated, focusedContentColor = if (primary) Ink else Paper)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HomeTile(
    modifier: Modifier,
    translucent: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick, modifier.onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.card),
        contentPadding = PaddingValues(0.dp), scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.card),
        colors = ButtonDefaults.colors(
            containerColor = if (translucent) Color(0xB818181A) else Panel.copy(alpha = .82f),
            contentColor = Paper,
            focusedContainerColor = Elevated,
            focusedContentColor = Paper,
        ), content = content)
}

@Composable internal fun HomeGlyph(kind: String, modifier: Modifier, color: Color) {
    if (kind == "brand") {
        Image(painterResource(R.drawable.singverse_logo_mark), contentDescription = null, modifier = modifier.alpha(color.alpha))
        return
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height; val s = w * .075f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x, h * y), Offset(w * x2, h * y2), s, StrokeCap.Round)
        when (kind) {
            "search" -> { drawCircle(color, w * .29f, Offset(w * .42f, h * .42f), style = Stroke(s)); line(.64f, .65f, .9f, .91f) }
            "mic" -> { drawRoundRect(color, Offset(w * .34f, 0f), Size(w * .32f, h * .6f), androidx.compose.ui.geometry.CornerRadius(w * .18f), style = Stroke(s)); drawArc(color, 0f, 180f, false, Offset(w * .19f, h * .25f), Size(w * .62f, h * .52f), style = Stroke(s)); line(.5f, .78f, .5f, 1f); line(.32f, 1f, .68f, 1f) }
            "phone" -> drawRoundRect(color, Offset(w * .23f, h * .04f), Size(w * .54f, h * .92f), androidx.compose.ui.geometry.CornerRadius(w * .1f), style = Stroke(s))
            "grid" -> for (x in 0..1) for (y in 0..1) drawRoundRect(color, Offset(w * (.08f + x * .51f), h * (.08f + y * .51f)), Size(w * .32f, h * .32f), androidx.compose.ui.geometry.CornerRadius(w * .07f), style = Stroke(s))
            "playlist" -> { for (i in 0..2) { drawCircle(color, s * .45f, Offset(w * .1f, h * (.2f + i * .28f))); line(.28f, .2f + i * .28f, .8f, .2f + i * .28f) }; line(.8f, .55f, .8f, .9f); drawOval(color, Offset(w * .56f, h * .78f), Size(w * .25f, h * .2f)) }
            "disc" -> { drawCircle(color, w * .43f, center, style = Stroke(s)); drawCircle(color, w * .12f, center, style = Stroke(s)); drawArc(color.copy(alpha = .5f), 210f, 90f, false, Offset(w * .2f, h * .2f), Size(w * .6f, h * .6f), style = Stroke(s)) }
            else -> { line(.5f, .2f, .5f, .8f); line(.2f, .5f, .8f, .5f) }
        }
    }
}
