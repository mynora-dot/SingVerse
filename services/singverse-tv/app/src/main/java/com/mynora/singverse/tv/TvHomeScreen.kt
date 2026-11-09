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

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TvHomeScreen(app: TvApp, libraryState: LazyListState, onPhone: () -> Unit) {
    val viewAllFocus = remember { FocusRequester() }
    val focusMemory = LocalTvFocusMemory.current
    val focusPage = LocalTvFocusPage.current
    var lastLibrarySong by remember { mutableStateOf("") }
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
    val focusMargin = with(LocalDensity.current) { 14.dp.toPx() }
    val minimalScroll = remember(focusMargin) { object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
            offset < focusMargin -> offset - focusMargin
            offset + size > containerSize - focusMargin -> offset + size - containerSize + focusMargin
            else -> 0f
        }
    } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides minimalScroll) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(150.dp).onFocusChanged { bannerFocused = it.hasFocus }.focusGroup()
                .clip(TvShape.panel).background(Panel)) {
                HomeImage(app, active?.optString("imagePath") ?: song?.optString("coverPath").orEmpty(),
                    active?.optString("version") ?: song?.optString("revision").orEmpty(), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFA050505), Color(0xB8050505), Color(0x10050505)))))
                Column(Modifier.fillMaxHeight().widthIn(max = 610.dp).padding(start = 26.dp, top = 16.dp, bottom = 14.dp, end = 22.dp)) {
                    Text("今晚推荐", color = Paper.copy(alpha = .66f), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
                    Text(appearance.optString("headline").ifBlank { "把生活，唱成热爱。" }, color = Paper, fontSize = 30.sp, lineHeight = 37.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (song == null) appearance.optString("subtitle").ifBlank { "你的私人音乐现场" } else "${song.optString("title")}  ·  ${song.artists()}",
                        color = Paper.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        HomeButton(if (song == null) "浏览曲库" else "播放", focusKey = "featured:sing") {
                            if (song == null) app.navigate("找歌") else app.openSong(song)
                        }
                        if (song != null) HomeButton("加入已点", focusKey = "featured:add") { app.command("ADD", songId = song.getString("id")) }
                        Spacer(Modifier.weight(1f))
                        if (count > 1) {
                            HomeButton("‹", Modifier.width(32.dp), focusKey = "featured:previous") { index = (index - 1 + count) % count }
                            Text("${index + 1} / $count", color = Paper.copy(alpha = .55f), fontSize = 10.sp)
                            HomeButton("›", Modifier.width(32.dp), focusKey = "featured:next") { index = (index + 1) % count }
                        }
                    }
                }
                PhoneConnectionCard(app, Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).width(218.dp).height(108.dp), onPhone)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("快捷点歌", color = Paper.copy(alpha = .56f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 5.dp))
                QuickSongEntries(app)
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的曲库", color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                    Text("  ${app.homeTotal} 首", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.weight(1f))
                    HomeButton("查看全部", Modifier.focusRequester(viewAllFocus).focusProperties {
                        val songId = lastLibrarySong.ifBlank { app.homeSongs.getOrNull(libraryState.firstVisibleItemIndex)?.optString("id").orEmpty() }
                        down = focusMemory.targets["$focusPage:song:$songId"] ?: FocusRequester.Default
                    }, focusKey = "library:all") { app.navigate("找歌") }
                }
                if (app.homeSongs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (app.busy) "正在读取曲库…" else "曲库还没有歌曲，请在 NAS 管理端扫描自备歌曲。", color = Muted, fontSize = 14.sp)
                } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Reserve both text lines plus button (10), metadata (8), gap (3) and row (18) dp.
                    val textHeight = with(LocalDensity.current) { 19.sp.toDp() + 16.sp.toDp() }
                    val coverSize = (maxHeight - textHeight - 39.dp).coerceIn(1.dp, 106.dp)
                    // Shrink the artwork independently so the text retains its readable width.
                    val cardWidth = (coverSize + 10.dp).coerceAtLeast(68.dp)
                    LazyRow(state = libraryState, modifier = Modifier.fillMaxSize().focusGroup(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(app.homeSongs, key = { it.getString("id") }) { song ->
                            SongCard(app, song, Modifier.width(cardWidth).focusProperties { up = viewAllFocus }
                                .onFocusChanged { if (it.isFocused) lastLibrarySong = song.getString("id") },
                                imageHeight = coverSize, metadataVerticalPadding = 4.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun QuickSongEntries(app: TvApp, large: Boolean = false) {
    val entries = listOf(
        Triple("search", "搜索歌曲", "歌名 / 拼音 / 编号"),
        Triple("mic", "歌手点歌", "按歌手查找歌曲"),
        Triple("grid", "分类点歌", "语种 / 风格 / 年代"),
        Triple("playlist", "我的歌单", "NAS 共享歌单"),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEachIndexed { i, entry ->
            HomeTile(
                Modifier.weight(if (large && i == 0) 1.25f else 1f).height(if (large) 118.dp else 52.dp).tvFocusTarget("quick:$i"),
                onClick = { app.navigate(listOf("找歌", "歌手", "分类", "歌单")[i]) },
            ) {
                val foreground = Paper
                Row(Modifier.fillMaxSize().padding(horizontal = if (large) 20.dp else 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeGlyph(entry.first, Modifier.size(if (large) 25.dp else 19.dp), foreground)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.second, color = foreground, fontSize = if (large) 18.sp else 14.sp, lineHeight = if (large) 25.sp else 19.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (large) Text(entry.third, color = foreground.copy(alpha = .58f), fontSize = 11.sp, lineHeight = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", color = foreground.copy(alpha = .7f), fontSize = 22.sp, fontWeight = FontWeight.Light)
                }
            }
        }
    }
}

@Composable internal fun PhoneConnectionCard(app: TvApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    HomeTile(modifier.tvFocusTarget("phone"), translucent = true, onClick = onClick) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (app.invitation.isNotEmpty() && app.online) Qr(app.invitation, Modifier.size(80.dp))
            else Box(Modifier.size(80.dp).background(Paper, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成中…" else "确认以重试", color = Ink, fontSize = 10.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("手机点歌", color = Paper, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Text("扫码加入", color = Paper.copy(alpha = .55f), fontSize = 10.sp, lineHeight = 14.sp)
                val status = when {
                    app.snapshot.optString("status") == "ENDED" -> "聚会已结束"
                    !app.online -> "网络异常"
                    app.invitationError.isNotEmpty() -> "邀请异常"
                    app.invitationLoading -> "正在生成"
                    !app.participantsKnown -> "正在同步"
                    app.participantCount > 0 -> "${app.participantCount} 台已加入"
                    else -> "等待加入"
                }
                Text(status, color = if (!app.online || app.invitationError.isNotEmpty()) Danger else Paper.copy(alpha = .66f),
                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun TvNavigation(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Button(onClick, modifier.height(34.dp).semantics { this.selected = selected; stateDescription = if (selected) "当前页面" else "" }
        .onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape), contentPadding = PaddingValues(horizontal = 18.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = when { focused -> Elevated; selected -> Panel; else -> Color.Transparent },
            contentColor = if (selected || focused) Paper else Muted, focusedContainerColor = Elevated, focusedContentColor = Paper)) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
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
