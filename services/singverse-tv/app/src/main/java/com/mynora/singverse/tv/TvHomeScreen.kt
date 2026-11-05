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
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import kotlinx.coroutines.delay

/** Leaves room for the blue focus ring at the ends of horizontal lists. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TvHomeScreen(app: TvApp, libraryState: LazyListState, onPhone: () -> Unit) {
    val appearance = app.homeAppearance
    val banners = appearance.optJSONArray("banners")?.objects().orEmpty().filter { it.optBoolean("enabled", true) }
    val recommendations = app.homeSongs.take(5)
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
    val focusMargin = with(LocalDensity.current) { 12.dp.toPx() }
    val minimalScroll = remember(focusMargin) { object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
            offset < focusMargin -> offset - focusMargin
            offset + size > containerSize - focusMargin -> offset + size - containerSize + focusMargin
            else -> 0f
        }
    } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides minimalScroll) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth().height(122.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { bannerFocused = it.hasFocus }.focusGroup()
                    .clip(RoundedCornerShape(16.dp)).background(Panel)) {
                    HomeImage(app, active?.optString("imagePath") ?: song?.optString("coverPath").orEmpty(), active?.optString("version") ?: song?.optString("revision").orEmpty(), Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20B0E16), Color(0xAF0B0E16), Color(0x220B0E16)))))
                    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("今晚推荐", color = Gold, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(appearance.optString("headline").ifBlank { "把生活，唱成热爱。" }, color = Paper, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (song == null) appearance.optString("subtitle").ifBlank { "你的私人音乐现场" } else "${song.optString("title")}  /  ${song.artists()}", color = Paper, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            HomeButton(if (song == null) "浏览曲库" else "▶ 点唱这首", primary = true, focusKey = "featured:sing") {
                                if (song == null) app.navigate("找歌") else app.openSong(song)
                            }
                            if (song != null) HomeButton("＋ 加入已点", focusKey = "featured:add") { app.command("ADD", songId = song.getString("id")) }
                            Spacer(Modifier.weight(1f))
                            if (count > 1) {
                                HomeButton("‹", Modifier.width(28.dp), focusKey = "featured:previous") { index = (index - 1 + count) % count }
                                Text("${index + 1}/$count", color = Muted, fontSize = 10.sp)
                                HomeButton("›", Modifier.width(28.dp), focusKey = "featured:next") { index = (index + 1) % count }
                            }
                        }
                    }
                }
                PhoneConnectionCard(app, Modifier.width(240.dp).fillMaxHeight(), onPhone)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("快捷点歌")
                QuickSongEntries(app)
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的曲库", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("  ${app.homeTotal} 首歌曲", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    HomeButton("查看全部 ›", focusKey = "library:all") { app.navigate("找歌") }
                }
                if (app.homeSongs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (app.busy) "正在读取曲库…" else "曲库还没有歌曲，请在 NAS 管理端扫描自备歌曲。", color = Muted, fontSize = 14.sp)
                } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val coverSize = (maxHeight - 70.dp).coerceIn(64.dp, 113.dp)
                    LazyRow(state = libraryState, modifier = Modifier.fillMaxSize().focusGroup(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(app.homeSongs, key = { it.getString("id") }) { SongCard(app, it, Modifier.width(coverSize)) }
                    }
                }
            }
        }
    }
}

@Composable private fun SectionLabel(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(3.dp, 13.dp).background(Gold, CircleShape))
        Text(label, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable internal fun QuickSongEntries(app: TvApp, large: Boolean = false) {
    val entries = listOf(Triple("search", "搜索歌曲", "歌名 / 拼音 / 编号"), Triple("mic", "歌手点歌", "按歌手查找歌曲"), Triple("grid", "分类点歌", "语种 / 风格 / 年代"), Triple("playlist", "我的歌单", "NAS 共享 / 自建歌单"))
    val tones = listOf(Color(0xFF282E69), Color(0xFF163A60), Color(0xFF164A42), Color(0xFF492452))
    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEachIndexed { i, entry ->
            HomeTile(Modifier.weight(1f).height(if (large) 108.dp else 50.dp).tvFocusTarget("quick:$i"), onClick = { app.navigate(listOf("找歌", "歌手", "分类", "歌单")[i]) }) {
                Row(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tones[i], tones[i].copy(alpha = .5f)))).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(if (large) 40.dp else 32.dp).background(Paper.copy(alpha = .08f), CircleShape), contentAlignment = Alignment.Center) { HomeGlyph(entry.first, Modifier.size(21.dp), Paper) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.second, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(entry.third, color = Paper.copy(alpha = .65f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", color = Paper, fontSize = 23.sp)
                }
            }
        }
    }
}

@Composable internal fun PhoneConnectionCard(app: TvApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    HomeTile(modifier.tvFocusTarget("phone"), onClick = onClick) {
        Row(Modifier.fillMaxSize().background(Color(0xFF111522)).padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (app.invitation.isNotEmpty() && app.online) Qr(app.invitation, Modifier.size(94.dp))
            else Box(Modifier.size(94.dp).background(Paper, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) { Text(if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成中…" else "确认以重试", color = Ink, fontSize = 11.sp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { HomeGlyph("phone", Modifier.size(13.dp), Paper); Text("手机点歌", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Text("同一网络即可连接", color = Muted, fontSize = 10.sp)
                val status = when { app.snapshot.optString("status") == "ENDED" -> "聚会已结束"; !app.online -> "网络异常 · 确认重连"; app.invitationError.isNotEmpty() -> "邀请异常 · 确认重试"; app.invitationLoading -> "正在生成邀请"; !app.participantsKnown -> "正在同步设备"; app.participantCount > 0 -> "已加入 ${app.participantCount} 台"; else -> "已加入 0 台 · 等待连接" }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(4.dp).background(if (!app.online || app.invitationError.isNotEmpty()) Color(0xFFE5A68B) else Color(0xFF53C99A), CircleShape))
                    Text(status, color = Muted, fontSize = 10.sp, maxLines = 2)
                }
            }
        }
    }
}

@Composable internal fun TvNavigation(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val isCurrentPage = selected
    Button(onClick, modifier.height(30.dp).semantics { this.selected = isCurrentPage; stateDescription = if (isCurrentPage) "当前页面" else "" }.onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape), contentPadding = PaddingValues(horizontal = 18.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = if (selected) Gold else Color.Transparent, contentColor = if (selected) Ink else Muted,
            focusedContainerColor = if (selected) Gold else Elevated, focusedContentColor = if (selected) Ink else Paper)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun HomeImage(app: TvApp, path: String, version: String, modifier: Modifier) {
    val server = app.client.base
    var bitmap by remember(server, path, version) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(server, path, version) {
        if (path.isNotBlank()) bitmap = try { app.client.image(path, version) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    val opacity by animateFloatAsState(if (bitmap == null) 0f else 1f, tween(350), label = "homeImageFade")
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF303044), Color(0xFF151B29))))) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = opacity }, contentScale = ContentScale.Crop)
        else HomeGlyph("brand", Modifier.size(54.dp).align(Alignment.CenterEnd).padding(end = 14.dp), Gold.copy(alpha = .22f))
    }
}

@Composable internal fun HomeButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, focusKey: String = label, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Button(onClick, modifier.height(30.dp).tvFocusTarget(focusKey).onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape), contentPadding = PaddingValues(horizontal = if (label in listOf("‹", "›")) 0.dp else 13.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = if (primary) Gold else Elevated, contentColor = if (primary) Ink else Paper, focusedContainerColor = if (primary) Gold else Color(0xFF2C3B50), focusedContentColor = if (primary) Ink else Paper)) {
        Text(label, fontSize = 13.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HomeTile(modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)
    Button(onClick, modifier.onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape),
        contentPadding = PaddingValues(0.dp), scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = Panel, contentColor = Paper, focusedContainerColor = Elevated, focusedContentColor = Paper), content = content)
}

@Composable internal fun HomeGlyph(kind: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val s = w * .075f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x, h * y), Offset(w * x2, h * y2), s, StrokeCap.Round)
        when (kind) {
            "brand" -> { listOf(.33f, .8f, .56f, 1f, .4f).forEachIndexed { i, v -> line(.1f + i * .2f, .5f - v * .4f, .1f + i * .2f, .5f + v * .4f) } }
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
