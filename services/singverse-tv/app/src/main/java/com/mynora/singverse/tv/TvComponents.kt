package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
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

internal val Ink = Color(0xFF08090B)
internal val Panel = Color(0xFF191B20)
internal val Elevated = Color(0xFF24262D)
internal val Paper = Color(0xFFF5F5F7)
internal val Muted = Color(0xFFA1A1AA)
internal val Accent = Color(0xFF64A8FF)

@Composable internal fun SingVerseTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Paper, onPrimary = Ink, surface = Panel, onSurface = Paper), content = content)
}

@Composable internal fun Action(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, scale = ButtonDefaults.scale(focusedScale = 1.04f),
        shape = ButtonDefaults.shape(RoundedCornerShape(22.dp)),
        colors = ButtonDefaults.colors(containerColor = if (selected) Elevated else Panel, contentColor = Paper, focusedContainerColor = Paper, focusedContentColor = Ink)) {
        Text(label, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun Heading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Paper, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MusicMark(Modifier.size(36.dp))
        Text(title, color = Paper, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
        Text(message, color = Muted, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun MusicMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = Color(0xFF9EA6BA)
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
    val keyboard = LocalSoftwareKeyboardController.current
    val buttonFocus = remember { FocusRequester() }
    val editorFocus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) { editorFocus.requestFocus(); keyboard?.show() } else buttonFocus.requestFocus() }
    BackHandler(editing) { editing = false; keyboard?.hide() }
    if (!editing) Action(value.ifBlank { hint }, Modifier.fillMaxWidth().focusRequester(buttonFocus)) { editing = true }
    else BasicTextField(value, onChange, modifier = Modifier.fillMaxWidth().focusRequester(editorFocus).border(1.dp, Paper, RoundedCornerShape(16.dp)).padding(14.dp), singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); editing = false }),
        textStyle = TextStyle(color = Paper, fontSize = 18.sp, lineHeight = 23.sp), cursorBrush = SolidColor(Paper))
}

@Composable internal fun SongArtwork(app: TvApp, song: JSONObject?, modifier: Modifier) {
    var bitmap by remember(song?.optString("id")) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song?.optString("id"), song?.optString("revision"), app.client.base) {
        bitmap = null
        val path = song?.optString("coverPath").orEmpty()
        if (path.isNotEmpty()) bitmap = runCatching { app.client.image(path, song?.optString("revision").orEmpty()) }.getOrNull()
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF292E3D), Color(0xFF14161D))))) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "歌曲封面", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else MusicMark(Modifier.size(42.dp).align(Alignment.Center))
    }
}

@Composable internal fun SongCard(app: TvApp, song: JSONObject, modifier: Modifier = Modifier, imageHeight: Dp = 96.dp) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "coverFocus")
    val focus = remember { FocusRequester() }
    LaunchedEffect(app.lastSongId) { if (app.lastSongPage == app.page && app.lastSongId == song.optString("id")) focus.requestFocus() }
    Button(onClick = { app.openSong(song) },
        modifier = modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }.graphicsLayer { scaleX = scale; scaleY = scale },
        scale = ButtonDefaults.scale(focusedScale = 1f), contentPadding = PaddingValues(0.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Paper, focusedContainerColor = Paper, focusedContentColor = Ink)) {
        Column(Modifier.fillMaxWidth()) {
            SongArtwork(app, song, Modifier.fillMaxWidth().height(imageHeight))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song.optString("title"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists(), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun JSONObject.artists(): String = optJSONArray("artists")?.let { array -> (0 until array.length()).joinToString(" / ") { array.optString(it) } }.orEmpty().ifBlank { "歌手未知" }


@Composable internal fun CategoryCard(app: TvApp, tag: JSONObject, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var bitmap by remember(tag.optString("id")) { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused)1.04f else 1f,tween(160),label="categoryFocus")
    LaunchedEffect(tag.optString("id"), tag.optString("coverVersion"), app.client.base) {
        bitmap=null;failed=false
        val path=tag.optString("coverPath")
        if(path.isNotBlank()) {
            bitmap=try { app.client.image(path,tag.optString("coverVersion")) } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch(failure: Exception) { null }
            failed=bitmap==null
        }
    }
    val tones=listOf(Color(0xFF465B75),Color(0xFF5D4E70),Color(0xFF395F62),Color(0xFF66544C),Color(0xFF555C73),Color(0xFF4C5C50))
    val tone=tones[(tag.optString("id").hashCode() ushr 1)%tones.size]
    Button(onClick=onClick,modifier=Modifier.fillMaxWidth().onFocusChanged { focused=it.isFocused }.graphicsLayer { scaleX=scale;scaleY=scale }.border(if(focused)2.dp else 0.dp,if(focused)Paper else Color.Transparent,RoundedCornerShape(18.dp)),scale=ButtonDefaults.scale(focusedScale=1f),contentPadding=PaddingValues(0.dp),shape=ButtonDefaults.shape(RoundedCornerShape(18.dp)),colors=ButtonDefaults.colors(containerColor=Panel,focusedContainerColor=Panel)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(tone,Panel)))) {
            if(bitmap!=null) Image(bitmap!!.asImageBitmap(),"${tag.optString("name")}分类封面",Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
            else Text(tag.optString("name").take(2),color=Paper.copy(alpha=.12f),fontSize=52.sp,lineHeight=64.sp,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.TopEnd).padding(16.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xDD08090B)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(tag.optString("name"),color=Paper,fontSize=24.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${tag.optInt("count")} 首歌曲${if(failed) " · 封面暂不可用" else ""}",color=Paper.copy(alpha=.75f),fontSize=13.sp,lineHeight=17.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(selected)Text("✓ 已选",color=Ink,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium,modifier=Modifier.align(Alignment.TopEnd).padding(12.dp).background(Paper,RoundedCornerShape(12.dp)).padding(horizontal=9.dp,vertical=5.dp))
        }
    }
}


@Composable internal fun ArtistArtwork(app: TvApp, artist: JSONObject, kind: String, modifier: Modifier) {
    var bitmap by remember(artist.optString("id"),kind) {mutableStateOf<Bitmap?>(null)}
    var derived by remember(artist.optString("id"),kind) {mutableStateOf(false)}
    LaunchedEffect(artist.optString("id"),artist.optString(kind+"Version"),artist.optString("portraitVersion"),app.client.base) {
        bitmap=null;derived=false
        val path=artist.optString(kind+"Path")
        if(path.isNotBlank())bitmap=try {app.client.image(path,artist.optString(kind+"Version"))}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(failure:Exception){null}
        if(kind=="background" && bitmap==null) {
            val portraitPath=artist.optString("portraitPath")
            if(portraitPath.isNotBlank()) {
                val portrait=try {app.client.image(portraitPath,artist.optString("portraitVersion"))}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(failure:Exception){null}
                if(portrait!=null) { bitmap=withContext(Dispatchers.Default) { softenedArtistBackground(portrait) };derived=true }
            }
        }
    }
    val shape=if(kind=="portrait") androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(20.dp)
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF354352),Panel))),contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),"${artist.optString("name")}${if(kind=="portrait") "头像" else if(derived) "由头像生成的背景" else "背景图"}",Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        else if(kind=="portrait")Text(artist.optString("name").take(1),color=Muted,fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.Medium)
        if(derived) Box(Modifier.fillMaxSize().background(Ink.copy(alpha=.28f)))
        if(derived)Text("头像生成背景",color=Paper.copy(alpha=.84f),fontSize=11.sp,lineHeight=15.sp,
            modifier=Modifier.align(Alignment.TopEnd).padding(10.dp).background(Ink.copy(alpha=.72f),RoundedCornerShape(6.dp)).padding(horizontal=8.dp,vertical=4.dp))
    }
}

/** A small, softened presentation bitmap; the original portrait and NAS image data remain intact. */
internal fun softenedArtistBackground(source: Bitmap): Bitmap {
    val width=minOf(96,source.width).coerceAtLeast(1)
    val height=minOf(96,source.height).coerceAtLeast(1)
    val sample=Bitmap.createScaledBitmap(source,width,height,true)
    var pixels=IntArray(width*height)
    sample.getPixels(pixels,0,width,0,0,width,height)
    if(sample!==source)sample.recycle()
    repeat(2) {
        val horizontal=IntArray(pixels.size)
        for(y in 0 until height)for(x in 0 until width){
            var red=0;var green=0;var blue=0;var count=0
            for(dx in -6..6){val color=pixels[y*width+(x+dx).coerceIn(0,width-1)];red+=android.graphics.Color.red(color);green+=android.graphics.Color.green(color);blue+=android.graphics.Color.blue(color);count++}
            horizontal[y*width+x]=android.graphics.Color.rgb(red/count,green/count,blue/count)
        }
        val vertical=IntArray(pixels.size)
        for(y in 0 until height)for(x in 0 until width){
            var red=0;var green=0;var blue=0;var count=0
            for(dy in -6..6){val color=horizontal[(y+dy).coerceIn(0,height-1)*width+x];red+=android.graphics.Color.red(color);green+=android.graphics.Color.green(color);blue+=android.graphics.Color.blue(color);count++}
            vertical[y*width+x]=android.graphics.Color.rgb(red/count,green/count,blue/count)
        }
        pixels=vertical
    }
    return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
}
@Composable internal fun ArtistCard(app: TvApp, artist: JSONObject) {
    var focused by remember {mutableStateOf(false)}
    val focus=remember {FocusRequester()}
    LaunchedEffect(app.lastArtistId) {
        if(app.lastArtistId==artist.optString("id")) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            focus.requestFocus()
        }
    }
    Button(onClick={app.openArtist(artist)},modifier=Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {focused=it.isFocused},scale=ButtonDefaults.scale(focusedScale=1.04f),shape=ButtonDefaults.shape(RoundedCornerShape(18.dp)),colors=ButtonDefaults.colors(containerColor=Color.Transparent,focusedContainerColor=Panel),contentPadding=PaddingValues(12.dp)) {
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
            ArtistArtwork(app,artist,"portrait",Modifier.size(112.dp).border(if(focused)2.dp else 0.dp,if(focused)Paper else Color.Transparent,androidx.compose.foundation.shape.CircleShape))
            Text(artist.optString("name"),color=Paper,fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${artist.optInt("count")} 首${if(!artist.optBoolean("managed")) "" else ""}",color=Muted,fontSize=12.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun PlaylistCard(app:TvApp,playlist:JSONObject,modifier:Modifier){
    Button(onClick={app.openPlaylist(playlist)},modifier=modifier,scale=ButtonDefaults.scale(focusedScale=1.04f),shape=ButtonDefaults.shape(RoundedCornerShape(16.dp)),colors=ButtonDefaults.colors(containerColor=Panel,contentColor=Paper,focusedContainerColor=Paper,focusedContentColor=Ink),contentPadding=PaddingValues(0.dp)){
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp)){
            SongArtwork(app,JSONObject(playlist.toString()).put("revision",playlist.optString("coverVersion")),Modifier.fillMaxWidth().height(92.dp))
            Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text(playlist.optString("name"),fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${playlist.optInt("count")} 首歌曲",fontSize=13.sp,lineHeight=18.sp)
            }
        }
    }
}
