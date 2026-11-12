package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val Ink = Color(0xFF050505)
internal val Panel = Color(0xFF1C1C1E)
internal val Elevated = Color(0xFF2C2C2E)
internal val Paper = Color(0xFFF5F5F7)
internal val Muted = Color(0xFFA1A1A6)
internal val Primary = Color(0xFFF5F5F7)
internal val Focus = Color(0xFFF5F5F7)
internal val Success = Color(0xFF6FCF97)
internal val Danger = Color(0xFFFF6961)
internal val Hairline = Color(0xFF3A3A3C)

internal object TvShape {
    val control = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(18.dp)
    val panel = RoundedCornerShape(28.dp)
    val dialog = RoundedCornerShape(32.dp)
}

/** A memory of Compose focus targets, without intercepting directional keys. */
internal class TvFocusMemory {
    var restoreVersion by mutableIntStateOf(0)
    val dialogs = mutableStateListOf<String>()
    val activeDialogs get() = dialogs.size
    var view: String? = null
    var restoring = false
    var directionalInput = false
    val last = mutableMapOf<String, String>()
    val focusedTargets = mutableSetOf<String>()
    val targets = mutableMapOf<String, FocusRequester>()

    fun restore(page: String): Boolean {
        val key = last[page] ?: return false
        return targets["$page:$key"]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
    }
}
internal val LocalTvFocusMemory = staticCompositionLocalOf { TvFocusMemory() }
internal val LocalTvFocusPage = staticCompositionLocalOf { "default" }

@Composable internal fun Modifier.tvFocusTarget(key: String): Modifier {
    val memory = LocalTvFocusMemory.current
    val page = LocalTvFocusPage.current
    val topDialog = memory.dialogs.lastOrNull()
    val canReceiveFocus = topDialog == null || page == topDialog
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(memory, page, key) {
        val address = "$page:$key"
        memory.targets[address] = requester
        if (focused) { if (!memory.restoring) memory.last[page] = key; memory.focusedTargets.add(address) }
        onDispose {
            if (memory.targets[address] === requester) memory.targets.remove(address)
            memory.focusedTargets.remove(address)
        }
    }
    return this.focusProperties { canFocus = canReceiveFocus }.focusRequester(requester).onFocusChanged {
        focused = it.isFocused
        val address = "$page:$key"
        if (it.isFocused) { if (!memory.restoring) memory.last[page] = key; memory.focusedTargets.add(address) }
        else memory.focusedTargets.remove(address)
    }
}

/** Presentation only: Compose/TV Material retain ownership of remote focus and key handling. */
@Composable internal fun Modifier.tvFocus(focused: Boolean, shape: Shape = TvShape.control, focusedScale: Float = 1.04f): Modifier {
    val amount by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "tvFocus")
    return this.graphicsLayer {
        scaleX = 1f + (focusedScale - 1f) * amount
        scaleY = 1f + (focusedScale - 1f) * amount
        clip = false
    }.drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        if (focused && amount > 0f) {
            drawOutline(outline, Focus.copy(alpha = .08f * amount), style = Stroke(12.dp.toPx()))
        }
        drawContent()
        if (focused && amount > 0f) drawOutline(outline, Focus.copy(alpha = .95f * amount), style = Stroke(1.5.dp.toPx()))
    }
}

@Composable internal fun SingVerseTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Primary,
            onPrimary = Ink,
            background = Ink,
            onBackground = Paper,
            surface = Panel,
            onSurface = Paper,
            surfaceVariant = Elevated,
            onSurfaceVariant = Muted,
            error = Danger,
            onError = Ink,
        ),
        content = content,
    )
}

@Composable internal fun Action(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusKey: String = label,
    textSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.control
    val content = when {
        !enabled -> Muted.copy(alpha = .55f)
        selected -> Ink
        danger -> Danger
        else -> Paper
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 36.dp).tvFocusTarget("action:$focusKey")
            .onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Primary else if (danger) Danger.copy(alpha = .09f) else Panel,
            contentColor = content,
            focusedContainerColor = if (selected) Primary else if (danger) Danger.copy(alpha = .16f) else Elevated,
            focusedContentColor = content,
            disabledContainerColor = Panel.copy(alpha = .45f),
            disabledContentColor = content,
        ),
    ) {
        Text(label, fontSize = textSize, lineHeight = textSize * 1.35f, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun Heading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Paper, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun SectionTitle(title: String, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        if (detail.isNotEmpty()) Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable internal fun StatusPill(label: String, tone: Color = Muted, modifier: Modifier = Modifier) {
    Row(modifier.background(tone.copy(alpha = .1f), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(tone, RoundedCornerShape(3.dp)))
        Text(label, color = tone, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(54.dp).background(Panel, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            MusicMark(Modifier.size(28.dp))
        }
        Text(title, color = Paper, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
        Text(message, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun MusicMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = Muted
        val w = size.width; val h = size.height
        drawLine(color, Offset(w * .42f, h * .72f), Offset(w * .42f, h * .2f), w * .065f)
        drawLine(color, Offset(w * .42f, h * .2f), Offset(w * .8f, h * .12f), w * .065f)
        drawLine(color, Offset(w * .8f, h * .12f), Offset(w * .8f, h * .62f), w * .065f)
        drawOval(color, Offset(w * .19f, h * .64f), Size(w * .26f, h * .2f))
        drawOval(color, Offset(w * .57f, h * .54f), Size(w * .26f, h * .2f))
    }
}

@Composable internal fun Input(value: String, hint: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var editorFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val buttonFocus = remember { FocusRequester() }
    val editorFocus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocus()
            keyboard?.show()
        } else if (restoreFocus) {
            buttonFocus.requestFocus()
            restoreFocus = false
        }
    }
    fun finishEditing() { restoreFocus = true; editing = false; keyboard?.hide() }
    BackHandler(editing) { finishEditing() }
    if (!editing) Action(value.ifBlank { hint }, Modifier.fillMaxWidth().focusRequester(buttonFocus)) { editing = true }
    else BasicTextField(value, onChange, modifier = Modifier.fillMaxWidth().focusRequester(editorFocus).tvFocusTarget("input:$hint")
        .onFocusChanged { editorFocused = it.isFocused }.tvFocus(editorFocused, TvShape.control)
        .background(Elevated, TvShape.control).padding(14.dp), singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { finishEditing() }),
        textStyle = TextStyle(color = Paper, fontSize = 18.sp, lineHeight = 24.sp), cursorBrush = SolidColor(Focus))
}

@Composable internal fun SongArtwork(app: TvApp, song: JSONObject?, modifier: Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop) {
    val server = app.client.base
    val path = song?.optString("coverPath").orEmpty()
    val revision = song?.optString("revision").orEmpty()
    var bitmap by remember(server, path, revision) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(server, path, revision) {
        if (path.isNotEmpty()) bitmap = try {
            app.client.image(path, revision)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }
    Box(modifier.clip(TvShape.card).background(Brush.linearGradient(listOf(Color(0xFF343434), Color(0xFF171717))))) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "歌曲封面", Modifier.fillMaxSize(), contentScale = contentScale)
        else MusicMark(Modifier.size(32.dp).align(Alignment.Center))
    }
}

@Composable internal fun SongCard(app: TvApp, song: JSONObject, modifier: Modifier = Modifier, imageHeight: Dp = Dp.Unspecified,
    metadataVerticalPadding: Dp = 8.dp) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.card
    Button(onClick = { app.openSong(song) },
        modifier = modifier.tvFocusTarget("song:${song.optString("id")}").onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f), contentPadding = PaddingValues(5.dp),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Paper,
            focusedContainerColor = Panel, focusedContentColor = Paper)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                val artworkModifier = if (imageHeight == Dp.Unspecified) Modifier.fillMaxWidth().aspectRatio(1f)
                    else Modifier.widthIn(max = imageHeight).fillMaxWidth().aspectRatio(1f)
                SongArtwork(app, song, artworkModifier)
                val playingId = app.snapshot.optJSONArray("queue")?.objects()?.firstOrNull { it.optString("status") == "PLAYING" }?.optJSONObject("song")?.optString("id").orEmpty()
                if (playingId.isNotBlank() && playingId == song.optString("id")) {
                    Text(stateLabel(app.playerState), color = Ink, fontSize = 10.sp, lineHeight = 14.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(7.dp).background(Primary, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            Column(Modifier.padding(horizontal = 4.dp, vertical = metadataVerticalPadding), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song.optString("title"), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists(), color = Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun JSONObject.artists(): String = optJSONArray("artists")?.let { array -> (0 until array.length()).joinToString(" / ") { array.optString(it) } }.orEmpty().ifBlank { "歌手未知" }

@Composable internal fun SongTable(app: TvApp, state: LazyListState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SongTableText("序号", Modifier.width(32.dp), header = true)
            SongTableText("歌曲", Modifier.weight(1.6f), header = true)
            SongTableText("歌手", Modifier.weight(1f), header = true)
            SongTableText("版本", Modifier.width(76.dp), header = true)
            SongTableText("时长", Modifier.width(48.dp), header = true)
            SongTableText("点歌状态", Modifier.width(96.dp), header = true)
        }
        LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(app.songs, key = { _, song -> song.getString("id") }) { index, song ->
                SongTableRow(app, song, app.number * 20 + index + 1)
            }
        }
    }
}

@Composable private fun SongTableText(label: String, modifier: Modifier, header: Boolean = false, color: Color = Muted) {
    Text(label, modifier, color = color, fontSize = if (header) 11.sp else 12.sp,
        lineHeight = if (header) 16.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable private fun SongTableRow(app: TvApp, song: JSONObject, number: Int) {
    val id = song.getString("id")
    var focused by remember { mutableStateOf(false) }
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty().filter {
        (it.optString("songId").ifBlank { it.optJSONObject("song")?.optString("id").orEmpty() }) == id &&
            it.optString("status") in listOf("WAITING", "HANDOFF", "PLAYING")
    }
    val status = when {
        app.queueAddSongId == id -> "加入中…"
        app.pendingOperation?.let { it.optString("action") == "ADD" && it.optString("songId") == id } == true -> "待核对"
        queue.isNotEmpty() -> "已点 ×${queue.size}"
        else -> "＋ 点歌"
    }
    Button(onClick = { app.addSongFromList(song) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).tvFocusTarget("song:$id")
            .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.control, focusedScale = 1f)
            .onPreviewKeyEvent {
                if (it.key == Key.DirectionRight) {
                    if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) app.openSong(song)
                    true
                } else false
            },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.control),
        colors = ButtonDefaults.colors(containerColor = Panel.copy(alpha = .65f), contentColor = Paper,
            focusedContainerColor = Elevated, focusedContentColor = Paper)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SongTableText(number.toString(), Modifier.width(32.dp))
            Row(Modifier.weight(1.6f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SongArtwork(app, song, Modifier.size(40.dp))
                Text(song.optString("title"), Modifier.weight(1f), color = Paper, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SongTableText(song.artists(), Modifier.weight(1f), color = Paper.copy(alpha = .85f))
            SongTableText(versionLabel(song.optString("version")), Modifier.width(76.dp))
            SongTableText(song.optLong("durationMs").takeIf { it > 0 }?.let(::time) ?: "—", Modifier.width(48.dp))
            Column(Modifier.width(96.dp)) {
                SongTableText(status, Modifier.fillMaxWidth(), color = if (queue.isNotEmpty()) Success else Paper)
                if (focused) SongTableText("右键查看详情", Modifier.fillMaxWidth(), header = true)
            }
        }
    }
}

@Composable internal fun CategoryCard(app: TvApp, tag: JSONObject, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var bitmap by remember(tag.optString("id")) { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(tag.optString("id"), tag.optString("coverVersion"), app.client.base) {
        bitmap = null; failed = false
        val path = tag.optString("coverPath")
        if (path.isNotBlank()) {
            bitmap = try { app.client.image(path, tag.optString("coverVersion")) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
            failed = bitmap == null
        }
    }
    val tones = listOf(Color(0xFF465B75), Color(0xFF5D4E70), Color(0xFF395F62), Color(0xFF66544C), Color(0xFF555C73), Color(0xFF4C5C50))
    val tone = tones[(tag.optString("id").hashCode() ushr 1) % tones.size]
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().tvFocusTarget("category:${tag.optString("id")}")
        .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.card),
        scale = ButtonDefaults.scale(focusedScale = 1f), contentPadding = PaddingValues(0.dp), shape = ButtonDefaults.shape(TvShape.card),
        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Panel)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(TvShape.card).background(Brush.linearGradient(listOf(tone, Panel))), contentAlignment = Alignment.CenterStart) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), "${tag.optString("name")}分类封面", Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Text(tag.optString("name").take(2), color = Paper.copy(alpha = .08f), fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd).padding(14.dp))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B0E14)))))
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tag.optString("name"), modifier = Modifier.weight(1f), color = Paper, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected) Text("已选", color = Primary, fontSize = 10.sp, lineHeight = 14.sp,
                        modifier = Modifier.background(Primary.copy(alpha = .1f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
                }
                Text("${tag.optInt("count")} 首歌曲${if (failed) " · 封面暂不可用" else ""}", color = Paper.copy(alpha = .72f), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun ArtistArtwork(app: TvApp, artist: JSONObject, kind: String, modifier: Modifier) {
    var bitmap by remember(artist.optString("id"), kind) { mutableStateOf<Bitmap?>(null) }
    var derived by remember(artist.optString("id"), kind) { mutableStateOf(false) }
    LaunchedEffect(artist.optString("id"), artist.optString(kind + "Version"), artist.optString("portraitVersion"), app.client.base) {
        bitmap = null; derived = false
        val path = artist.optString(kind + "Path")
        if (path.isNotBlank()) bitmap = try { app.client.image(path, artist.optString(kind + "Version")) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (kind == "background" && bitmap == null) {
            val portraitPath = artist.optString("portraitPath")
            if (portraitPath.isNotBlank()) {
                val portrait = try { app.client.image(portraitPath, artist.optString("portraitVersion")) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
                if (portrait != null) { bitmap = withContext(Dispatchers.Default) { softenedArtistBackground(portrait) }; derived = true }
            }
        }
    }
    val shape = if (kind == "portrait") androidx.compose.foundation.shape.CircleShape else TvShape.panel
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF354352), Panel))), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "${artist.optString("name")}${if (kind == "portrait") "头像" else if (derived) "由头像生成的背景" else "背景图"}", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else if (kind == "portrait") Text(artist.optString("name").take(1), color = Muted, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
        if (derived) Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .28f)))
        if (derived) Text("头像生成背景", color = Paper.copy(alpha = .84f), fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(Ink.copy(alpha = .72f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

internal fun softenedArtistBackground(source: Bitmap): Bitmap {
    val width = minOf(96, source.width).coerceAtLeast(1)
    val height = minOf(96, source.height).coerceAtLeast(1)
    val sample = Bitmap.createScaledBitmap(source, width, height, true)
    var pixels = IntArray(width * height)
    sample.getPixels(pixels, 0, width, 0, 0, width, height)
    if (sample !== source) sample.recycle()
    repeat(2) {
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (dx in -6..6) { val color = pixels[y * width + (x + dx).coerceIn(0, width - 1)]; red += android.graphics.Color.red(color); green += android.graphics.Color.green(color); blue += android.graphics.Color.blue(color); count++ }
            horizontal[y * width + x] = android.graphics.Color.rgb(red / count, green / count, blue / count)
        }
        val vertical = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (dy in -6..6) { val color = horizontal[(y + dy).coerceIn(0, height - 1) * width + x]; red += android.graphics.Color.red(color); green += android.graphics.Color.green(color); blue += android.graphics.Color.blue(color); count++ }
            vertical[y * width + x] = android.graphics.Color.rgb(red / count, green / count, blue / count)
        }
        pixels = vertical
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

@Composable internal fun ArtistCard(app: TvApp, artist: JSONObject) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = { app.openArtist(artist) }, modifier = Modifier.fillMaxWidth().tvFocusTarget("artist:${artist.optString("id")}")
        .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.card),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.card),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Panel), contentPadding = PaddingValues(12.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtistArtwork(app, artist, "portrait", Modifier.size(112.dp))
            Text(artist.optString("name"), color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${artist.optInt("count")} 首", color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun PlaylistCard(app: TvApp, playlist: JSONObject, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = { app.openPlaylist(playlist) }, modifier = modifier.tvFocusTarget("playlist:${playlist.optString("id")}")
        .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.card),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.card),
        colors = ButtonDefaults.colors(containerColor = Panel, contentColor = Paper, focusedContainerColor = Elevated, focusedContentColor = Paper), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SongArtwork(app, JSONObject(playlist.toString()).put("revision", playlist.optString("coverVersion")), Modifier.fillMaxWidth().aspectRatio(1f))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(playlist.optString("name"), fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.optInt("count")} 首歌曲", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
