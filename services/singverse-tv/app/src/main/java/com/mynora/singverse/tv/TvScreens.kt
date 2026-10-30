@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.json.*

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun stateLabel(value: String) = mapOf("IDLE" to "已停止", "PREPARING" to "准备中", "BUFFERING" to "缓冲中", "PLAYING" to "正在演唱", "PAUSED" to "已暂停", "ENDED" to "演唱结束", "ERROR" to "播放失败", "WAITING" to "待唱", "HANDOFF" to "等待确认", "COMPLETED" to "已唱", "SKIPPED" to "已跳过", "FAILED" to "播放失败", "CANCELLED" to "已撤回")[value] ?: "等待同步"
private fun versionLabel(value: String) = mapOf("ORIGINAL" to "原唱", "ACCOMPANIMENT" to "伴奏", "INSTRUMENTAL" to "纯音乐", "DUET" to "合唱", "KARAOKE" to "卡拉 OK")[value] ?: "版本待确认"
private fun time(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable private fun Modal(title: String, subtitle: String = "", dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.widthIn(max = 500.dp).fillMaxWidth().background(Elevated, RoundedCornerShape(26.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Heading(title, subtitle)
            content()
        }
    }
}

@Composable internal fun TvScreen(app: TvApp) {
    var invitationOpen by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val playlistsGrid=rememberLazyGridState()
    val playlistSongsGrid=rememberLazyGridState()
    val artistsGrid=rememberLazyGridState()
    val artistSongsGrid=rememberLazyGridState()
    val homeFocus = remember { FocusRequester() }
    LaunchedEffect(app.connected, app.page) { if (app.connected && app.page == "首页") homeFocus.requestFocus() }
    BackHandler {
        when {
            app.error.isNotEmpty() -> app.error = ""
            app.selected != null -> app.selected = null
            app.playlistDetail != null -> app.closePlaylist()
            app.artistDetail != null -> app.closeArtist()
            app.page == "演唱" && app.controlVisible -> app.controlVisible = false
            app.page != "首页" -> app.navigate("首页")
            else -> app.pendingConfirm = "退出演唱"
        }
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        when {
            !app.connected -> Connection(app)
            app.page == "演唱" -> PlayerScreen(app)
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("SingVerse", color = Paper, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.background(Panel, RoundedCornerShape(28.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("首页", "找歌", "歌手", "分类", "歌单", "已点", "设置").forEach { page ->
                            Action(page, if (page == "首页") Modifier.focusRequester(homeFocus) else Modifier, selected = app.page == page) {
                                app.navigate(page)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Action("手机加入") { invitationOpen = true; if (app.invitation.isEmpty()) app.invite() }
                }
                Box(Modifier.weight(1f)) {
                    if (app.selected != null) Detail(app)
                    else if(app.playlistDetail!=null) PlaylistDetail(app,playlistSongsGrid)
                    else if(app.artistDetail!=null) ArtistDetail(app,artistSongsGrid)
                    else when (app.page) {
                        "首页" -> Home(app)
                        "找歌" -> Library(app, grid)
                        "歌单" -> Playlists(app,playlistsGrid)
                        "歌手" -> Artists(app,artistsGrid)
                        "分类" -> Categories(app)
                        "已点" -> Queue(app)
                        "设置" -> Settings(app)
                    }
                }
                if (!app.online) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("NAS 离线 · 本机仍可暂停与停止", color=Muted); Action("重连并核对") { app.reconnect() } }
                if (app.snapshot.has("playback")) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(5.dp).background(Accent, RoundedCornerShape(3.dp)))
                    Text(stateLabel(app.playerState), color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Action("返回演唱") { app.page = "演唱" }
                }
            }
        }
        if (app.error.isNotEmpty()) Column(Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 680.dp).background(Elevated, RoundedCornerShape(18.dp)).border(1.dp, Color(0xFF72565B), RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(app.error, color = Paper, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("关闭提示") { app.error = "" }
                if (app.pendingOperation != null) { Action("核对后重试") { app.retryPending() }; Action("放弃本次重试") { app.discardPending(); app.error="" } }
            }
        }
    }
    if (invitationOpen) Modal("一起点歌", "用微信扫描二维码，加入这场聚会。", { invitationOpen = false }) {
        if (app.invitation.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Qr(app.invitation) }
        else Text(if (app.busy) "正在生成邀请…" else "邀请尚未生成，请重试。", color = Muted)
        Text("手机需要能够访问此 NAS。刷新邀请后，旧二维码失效。", color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
        val focus = remember { FocusRequester() }; LaunchedEffect(Unit) { focus.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("完成", Modifier.focusRequester(focus)) { invitationOpen = false }
            Action("刷新邀请") { app.invite() }
        }
    }
    app.pendingConfirm?.let { action -> Modal("${action}？", "此操作会影响当前演唱。", { app.pendingConfirm = null }) {
        val cancel = remember { FocusRequester() }; LaunchedEffect(Unit) { cancel.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("取消", Modifier.focusRequester(cancel)) { app.pendingConfirm = null }
            Action("确认") {
                app.pendingConfirm = null
                when (action) {
                    "散场" -> app.command("END")
                    "清空待唱" -> app.command("CLEAR")
                    "切歌" -> app.command("NEXT")
                    "退出演唱" -> { app.stopLocal(); app.navigate("首页") }
                    "更换 NAS" -> app.disconnect()
                }
            }
        }
    } }
}

@Composable private fun Connection(app: TvApp) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1B2030), Ink))), contentAlignment = Alignment.Center) {
        Column(Modifier.width(500.dp).heightIn(max=460.dp).verticalScroll(rememberScrollState()).background(Panel.copy(alpha = .95f), RoundedCornerShape(28.dp)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("SingVerse", color = Paper, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
            Heading(if (app.authorization == null) "连接你的音乐" else "在 NAS 上确认连接", if (app.authorization == null) "输入电视与手机均可访问的 NAS 地址。" else "打开管理页的「电视设备」，核对设备与授权码。")
            if (app.authorization == null) {
                Action("查找局域网 NAS") { app.discovery.start() }
                if(app.discovery.status.isNotBlank()) Text(app.discovery.status,color=Muted,fontSize=14.sp)
                app.discovery.services.forEach { (name,address) -> Action("$name · $address") { app.address=address;app.discovery.stop() } }
                Input(app.address, "输入 NAS 地址") { app.address = it }
                Action(if (app.busy) "正在连接…" else "连接 NAS") { if (!app.busy) app.beginAuthorization() }
                if (app.client.token.isNotEmpty()) Action("恢复连接") { app.connect() }
            } else {
                Text(app.authorization!!.optString("code"), color = Paper, fontSize = 52.sp, lineHeight = 65.sp, letterSpacing = 8.sp, fontWeight = FontWeight.Medium)
                Text("授权码有效期 10 分钟。批准后将自动连接。", color = Muted, fontSize = 15.sp, lineHeight = 19.sp)
                Action("取消连接") { app.authorization=null }
            }
        }
    }
}

@Composable private fun Home(app: TvApp) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" }
    val next = queue.firstOrNull { it.optString("status") in listOf("WAITING", "HANDOFF") }
    val song = (current ?: next)?.optJSONObject("song")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().height(180.dp).background(Brush.horizontalGradient(listOf(Color(0xFF202432), Panel)), RoundedCornerShape(22.dp)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (current != null) stateLabel(app.playerState) else if (next != null) "下一首" else "你的音乐，随时开唱", color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
                Text(song?.optString("title") ?: "选一首，开始唱", color = Paper, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song != null) Text(song.artists(), color = Muted, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        app.snapshot.optString("status") == "ENDED" -> Action("开始新聚会") { app.newSession() }
                        current != null -> Action("返回演唱") { app.page = "演唱" }
                        next != null -> Action("确认开唱") { app.command("START") }
                        else -> Action("浏览曲库") { app.navigate("找歌") }
                    }
                    Action("查看已点") { app.navigate("已点") }
                }
            }
            SongArtwork(app, song, Modifier.width(260.dp).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("你的曲库 · ${app.homeTotal}", color = Paper, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f)); Action("刷新曲库") { app.run { app.loadHome() } }; Spacer(Modifier.width(12.dp)); Text(if (app.snapshot.optBoolean("acceptingRequests")) "开放点歌" else "暂停接单", color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
        }
        if (app.homeSongs.isEmpty()) EmptyState("曲库还没有歌曲", "请在 NAS 管理页添加媒体来源并扫描。")
        else LazyRow(Modifier.fillMaxWidth().height(168.dp), contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(app.homeSongs, key = { it.getString("id") }) { songItem -> SongCard(app, songItem, Modifier.width(166.dp), imageHeight = 82.dp) }
        }
    }
}

@Composable private fun Qr(value: String) {
    val bitmap = remember(value) { runCatching {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320)
        Bitmap.createBitmap(320, 320, Bitmap.Config.RGB_565).apply { for (x in 0 until 320) for (y in 0 until 320) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
    }.getOrNull() }
    if (bitmap != null) Image(bitmap.asImageBitmap(), "本场邀请二维码", Modifier.size(190.dp))
}

@Composable private fun Library(app: TvApp, grid: LazyGridState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("找歌")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Input(app.query, "歌名 / 歌手 / 拼音 / 编号") { app.query = it } }
            Action("搜索") { app.artistId = ""; app.search() }
            Action("分类") { app.page = "分类" }
            Action("清除筛选") { app.selectedTags = emptySet(); app.unclassified = false; app.artistId = ""; app.search() }
        }
        Text("${app.total} 首歌曲${if (app.selectedTags.isNotEmpty()) " · ${app.selectedTags.size} 项分类" else ""}", color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
        if (app.songs.isEmpty()) EmptyState(if (app.busy) "正在找歌" else "没有找到歌曲", "试试其他关键词，或清除筛选条件。", Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(4), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(app.songs, key = { it.getString("id") }) { SongCard(app, it, Modifier.fillMaxWidth()) }
        }
        Pagination(app.number, app.total, { if (app.number > 0) { app.number--; app.search(false) } }, { if ((app.number + 1) * 20 < app.total) { app.number++; app.search(false) } })
    }
}

@Composable private fun Pagination(page: Int, total: Long, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Action("上一页", onClick = previous)
        Text("${page + 1} / ${maxOf(1, (total + 19) / 20)}", color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
        Action("下一页", onClick = next)
    }
}

@Composable private fun Categories(app: TvApp) {
    val dimensions = linkedMapOf("LANGUAGE" to "语种", "DECADE" to "年代", "GENRE" to "风格", "MOOD" to "情绪", "SCENE" to "场景", "CUSTOM" to "自定义")
    var dimension by remember { mutableStateOf("LANGUAGE") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("分类", "同类任选，不同维度同时满足。数量为全库歌曲数。")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) { items(dimensions.keys.toList()) { key -> Action(dimensions.getValue(key), selected = dimension == key) { dimension = key } } }
        val tags = app.tags.filter { it.optString("dimension") == dimension }
        if (tags.isEmpty()) EmptyState("暂无${dimensions[dimension]}分类", "可以在 NAS 管理页整理歌曲分类。", Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(tags, key = { it.getString("id") }) { tag ->
                val id = tag.getString("id")
                CategoryCard(app, tag, selected = id in app.selectedTags) { app.unclassified = false; app.selectedTags = if (id in app.selectedTags) app.selectedTags - id else app.selectedTags + id }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("查看歌曲${if (app.selectedTags.isNotEmpty()) " · ${app.selectedTags.size} 项" else ""}") { app.artistId = ""; app.page = "找歌"; app.search() }
            Action("未分类") { app.unclassified = true; app.artistId = ""; app.selectedTags = emptySet(); app.page = "找歌"; app.search() }
        }
    }
}

@Composable private fun Artists(app: TvApp, grid: LazyGridState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("歌手", "从你的曲库发现歌手")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)) { Input(app.artistQuery, "查找歌手") { app.artistQuery = it } }; Action("查找") { app.artistPage = 0; app.loadArtists() } }
        if (app.artists.isEmpty()) EmptyState("还没有歌手", "请检查已保存的歌曲元数据。", Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(4), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(app.artists, key = { it.getString("id") }) { artist -> ArtistCard(app,artist) }
        }
        Pagination(app.artistPage, app.artistTotal, { if (app.artistPage > 0) { app.artistPage--; app.loadArtists() } }, { if ((app.artistPage + 1) * 20 < app.artistTotal) { app.artistPage++; app.loadArtists() } })
    }
}

@Composable private fun Playlists(app:TvApp,grid:LazyGridState){
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Heading("歌单","为每一次欢聚准备的音乐")
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.weight(1f)){Input(app.playlistQuery,"查找歌单"){app.playlistQuery=it}};Action("查找"){app.playlistPage=0;app.loadPlaylists()}}
        if(app.playlists.isEmpty())EmptyState(if(app.busy)"正在读取歌单" else "还没有歌单","可在 NAS 管理端的歌单管理中创建，支持 AI 辅助选歌。",Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(4),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            items(app.playlists,key={it.getString("id")}){playlist->
                val focus=remember {FocusRequester()}
                LaunchedEffect(app.lastPlaylistId){if(app.lastPlaylistId==playlist.optString("id")){withFrameNanos{};withFrameNanos{};focus.requestFocus()}}
                PlaylistCard(app,playlist,Modifier.fillMaxWidth().focusRequester(focus))
            }
        }
        Pagination(app.playlistPage,app.playlistTotal,{if(app.playlistPage>0){app.playlistPage--;app.loadPlaylists()}},{if((app.playlistPage+1)*20<app.playlistTotal){app.playlistPage++;app.loadPlaylists()}})
    }
}
@Composable private fun PlaylistDetail(app:TvApp,grid:LazyGridState){
    val playlist=app.playlistDetail?:return
    val focus=remember {FocusRequester()}
    LaunchedEffect(playlist.optString("id")){if(app.lastSongPage!=app.page||app.songs.none{it.optString("id")==app.lastSongId}){withFrameNanos{};withFrameNanos{};focus.requestFocus()}}
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(20.dp)).padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)){
            SongArtwork(app,JSONObject(playlist.toString()).put("revision",playlist.optString("coverVersion")),Modifier.width(150.dp).height(85.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(playlist.optString("name"),color=Paper,fontSize=30.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${playlist.optInt("count")} 首歌曲",color=Muted,fontSize=14.sp,lineHeight=18.sp)
                Text(playlist.optString("description").ifBlank{"选择一首想唱的歌"},color=Muted,fontSize=14.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Action("返回歌单",Modifier.focusRequester(focus)){app.closePlaylist()}
        }
        if(app.songs.isEmpty())EmptyState(if(app.busy)"正在读取歌曲" else "歌单暂无可用歌曲","请在 NAS 管理端选择曲库中的歌曲。",Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(4),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){items(app.songs,key={it.getString("id")}){SongCard(app,it,Modifier.fillMaxWidth(),imageHeight=78.dp)}}
        Pagination(app.number,app.total,{if(app.number>0){app.number--;app.search(false)}},{if((app.number+1)*20<app.total){app.number++;app.search(false)}})
    }
}

@Composable private fun ArtistDetail(app: TvApp, grid: LazyGridState) {
    val artist=app.artistDetail ?: return
    val returnFocus=remember {FocusRequester()}
    LaunchedEffect(artist.optString("id")) {
        if(app.lastSongPage!=app.page || app.songs.none {it.optString("id")==app.lastSongId}) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            returnFocus.requestFocus()
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(142.dp).background(Panel,RoundedCornerShape(20.dp))) {
            ArtistArtwork(app,artist,"background",Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink.copy(alpha=.96f),Ink.copy(alpha=.55f)))))
            Row(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                ArtistArtwork(app,artist,"portrait",Modifier.size(94.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(artist.optString("name"),color=Paper,fontSize=30.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text("${artist.optInt("count")} 首歌曲 · ${if(artist.optBoolean("managed")) "曲库歌手" else "曲库原署名"}",color=Muted,fontSize=14.sp,lineHeight=18.sp)
                    Text(artist.optString("biography").ifBlank { if(artist.optBoolean("managed")) "歌手信息随歌曲自动同步" else "歌手信息随歌曲自动同步" },color=Muted,fontSize=14.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Action("返回歌手",Modifier.focusRequester(returnFocus)) {app.closeArtist()}
            }
        }
        if(app.songs.isEmpty())EmptyState(if(app.busy)"正在读取歌曲" else "暂无关联歌曲","可以返回歌手页刷新资料。",Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(4),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {items(app.songs,key={it.getString("id")}) {SongCard(app,it,Modifier.fillMaxWidth(),imageHeight=78.dp)}}
        Pagination(app.number,app.total,{if(app.number>0){app.number--;app.search(false)}},{if((app.number+1)*20<app.total){app.number++;app.search(false)}})
    }
}

@Composable private fun Detail(app: TvApp) {
    val song = app.selected ?: return
    val focus = remember { FocusRequester() }; LaunchedEffect(song.optString("id")) { focus.requestFocus() }
    Row(Modifier.fillMaxSize().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        SongArtwork(app, song, Modifier.size(260.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(versionLabel(song.optString("version")), color = Muted, fontSize = 15.sp, lineHeight = 19.sp)
            Heading(song.optString("title"), song.artists())
            Text(listOfNotNull(song.optString("album").takeIf { it.isNotBlank() && it != "null" }, song.optString("releaseDate").takeIf { it.isNotBlank() && it != "null" }).joinToString(" · "), color = Muted, fontSize = 15.sp, lineHeight = 19.sp)
            if (song.optLong("durationMs") > 0) Text(time(song.optLong("durationMs")), color = Muted, fontSize = 15.sp, lineHeight = 19.sp)
            Text(song.optString("sourceName"), color = Muted, fontSize = 15.sp, lineHeight = 19.sp)
            Action("加入待唱", Modifier.focusRequester(focus)) { app.command("ADD", songId = song.getString("id")); app.selected = null }
            Action("返回曲库") { app.selected = null }
        }
    }
}

@Composable private fun Queue(app: TvApp) {
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val current = queue.firstOrNull { it.optString("status") in listOf("HANDOFF", "PLAYING") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Heading("已点", "${queue.count { it.optString("status") == "WAITING" }} 首待唱")
            Spacer(Modifier.weight(1f))
            Action("开始下一首") { app.command("START") }
        }
        current?.let { item -> Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SongArtwork(app, item.optJSONObject("song"), Modifier.size(64.dp))
            Column(Modifier.weight(1f)) { Text(item.optJSONObject("song")?.optString("title").orEmpty(), color = Paper, fontSize = 21.sp, lineHeight = 27.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (item.optString("status") == "PLAYING") stateLabel(app.playerState) else "等待电视确认开唱", color = Accent, fontSize = 14.sp, lineHeight = 18.sp) }
            Action("返回演唱") { app.page = "演唱" }
        } }
        if (queue.isEmpty()) EmptyState("还没有点歌", "从曲库中选一首喜欢的歌。", Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(queue.filter { it != current }, key = { it.getString("id") }) { item -> Action("${item.optJSONObject("song")?.optString("title").orEmpty()}    ${item.optString("ownerName")} · ${stateLabel(item.optString("status"))}", Modifier.fillMaxWidth().height(52.dp)) { selected = item } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action(if (app.snapshot.optBoolean("acceptingRequests")) "暂停接单" else "恢复接单") { app.command(if (app.snapshot.optBoolean("acceptingRequests")) "LOCK" else "UNLOCK") }
            Action("清空待唱") { app.pendingConfirm = "清空待唱" }
            Action("结束聚会") { app.pendingConfirm = "散场" }
        }
    }
    selected?.let { item -> Modal(item.optJSONObject("song")?.optString("title").orEmpty(), stateLabel(item.optString("status")), { selected = null }) {
        val focus = remember { FocusRequester() }; LaunchedEffect(Unit) { focus.requestFocus() }
        Action("返回", Modifier.focusRequester(focus)) { selected = null }
        if (item.optString("status") == "WAITING") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("置顶" to "TOP", "上移" to "UP", "下移" to "DOWN", "移除" to "REMOVE").forEach { (label, action) -> Action(label) { app.command(action, item.getString("id")); selected = null } }
        }
    } }
}

@Composable private fun Settings(app: TvApp) {
    var group by remember { mutableStateOf("连接") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading("设置")
            listOf("连接", "播放设置", "本场参与者", "设备信息", "应用更新").forEach { label -> Action(label, Modifier.fillMaxWidth(), selected = label == group) { group = label } }
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(22.dp)).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (group) {
                "连接" -> { Heading(app.serverName, app.client.base); Action("检查连接") { app.connect() }; Action("更换 NAS / 忘记连接") { app.pendingConfirm = "更换 NAS" }; Action("停止本机播放") { app.stopLocal() } }
                "播放设置" -> PlaybackSettings(app)
                "应用更新" -> { Heading("应用更新", app.updateStatus); Text("当前 ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",color=Muted); Action("检查更新") { app.checkUpdate() }; if(app.updateManifest?.optBoolean("available")==true) Action(if(app.busy) "处理中…" else "下载并校验") { if(!app.busy) app.downloadUpdate() }; if(app.updateFile!=null) Action("确认安装 / 开启安装权限后重试") { app.installUpdate() } }
                "设备信息" -> { Heading(android.os.Build.MODEL); Text("Android ${android.os.Build.VERSION.RELEASE}", color = Muted, fontSize = 16.sp, lineHeight = 20.sp); Text("SingVerse TV · 原始媒体播放", color = Muted, fontSize = 16.sp, lineHeight = 20.sp) }
                else -> {
                    Heading("本场参与者", "主持权限只在电视上授予。")
                    if (app.participants.isEmpty()) EmptyState("还没有亲友加入", "在顶部打开「手机加入」邀请亲友。")
                    app.participants.forEach { participant ->
                        Text(participant.optString("name"), color = Paper, fontSize = 20.sp, lineHeight = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Action("授予主持") { app.command("HOST", participantId = participant.getString("id")) }; Action("撤销访问") { app.command("REVOKE", participantId = participant.getString("id")) } }
                    }
                }
            }
        }
    }
}

@Composable private fun PlayerScreen(app: TvApp) {
    var optionsOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(app.controlVisible) { if (!app.controlVisible) focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent {
        if (!app.controlVisible && it.type == KeyEventType.KeyUp && it.key in listOf(Key.DirectionCenter, Key.Enter)) { app.controlVisible = true; true } else false
    }.focusable()) {
        AndroidView(factory = { context -> PlayerView(context).apply { player = app.player; useController = false; isFocusable = false } }, modifier = Modifier.fillMaxSize())
        val text=app.playbackOptions.activeLine
        if(text!=null && !app.controlVisible && app.playerState!="IDLE") Text(buildAnnotatedString {
            if(text.words.isEmpty()) append(text.text) else text.words.forEach { word -> withStyle(SpanStyle(color=if(word.at<=app.playbackOptions.lyricPosition) Paper else Muted)) { append(word.text) } }
        },color=Paper,fontSize=30.sp,lineHeight=40.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.align(Alignment.BottomCenter).padding(40.dp).background(Color.Black.copy(alpha=.65f),RoundedCornerShape(12.dp)).padding(16.dp),maxLines=3,overflow=TextOverflow.Ellipsis)
        if (app.controlVisible || app.playerState == "ERROR") Column(Modifier.align(Alignment.BottomCenter).padding(28.dp).fillMaxWidth().background(Ink.copy(alpha = .94f), RoundedCornerShape(24.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val primary = remember { FocusRequester() }; LaunchedEffect(Unit) { primary.requestFocus() }
            Row(Modifier.fillMaxWidth()) { Text(stateLabel(app.playerState), color = Paper, fontSize = 21.sp, lineHeight = 27.sp); Spacer(Modifier.weight(1f)); Text("${time(app.position)} / ${time(app.duration)}", color = Muted, fontSize = 15.sp, lineHeight = 19.sp) }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Elevated, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(if (app.duration > 0) (app.position.toFloat() / app.duration).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(Accent))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action(if (app.playing) "暂停" else "继续", Modifier.focusRequester(primary)) { if (app.playing) app.pauseLocal() else app.command("RESUME") }
                Action("重唱") { app.command("RESTART") }
                Action("切歌") { app.pendingConfirm = "切歌" }
                Action("已点") { app.page = "已点" }
                Action("收起") { app.controlVisible = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("−10 秒") { app.seekAbsolute((app.position - 10000).coerceAtLeast(0)) }
                Action("+10 秒") { app.seekAbsolute((app.position + 10000).coerceAtMost(app.duration.coerceAtLeast(0))) }
                Action("本机停止") { app.stopLocal() }; Action("播放设置") { optionsOpen=true }; if(app.playerState=="ERROR")Action("重试播放") { app.retryPlayback() }
            }
        }
    }
    if(optionsOpen) Modal("播放设置", dismiss={optionsOpen=false}) { Column(Modifier.heightIn(max=330.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) { Action("完成") { optionsOpen=false }; PlaybackSettings(app) } }
}

@Composable private fun PlaybackSettings(app: TvApp) {
    val options=app.playbackOptions
    var tab by remember { mutableStateOf("音轨") }
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(listOf("音轨","歌词字幕","声音输出","同步")) { name -> Action(name,selected=tab==name) { tab=name } } }
    when(tab) {
        "音轨" -> {
            Text("仅列出设备可解码的轨道；听辨后可记录原唱/伴奏。",color=Muted,fontSize=14.sp)
            val audio=options.tracks.filter { it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO }
            if(audio.isEmpty()) Text("播放歌曲后显示可用音轨",color=Muted)
            audio.forEach { track -> Action("${if(track.selected) "✓ " else ""}${track.label}",Modifier.fillMaxWidth()) { options.selectTrack(track) } }
            if(audio.any { it.selected && it.channels==2 }) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(listOf("STEREO" to "立体声","LEFT" to "左声道","RIGHT" to "右声道","MONO" to "混合单声道")) { (mode,label) -> Action(label,selected=options.channel==mode) { options.selectChannel(mode) } } }
            if(audio.isNotEmpty()) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Action("当前标记为原唱") { options.markRole(true) }; Action("当前标记为伴奏") { options.markRole(false) } }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { if(options.roleOriginal.isNotBlank())Action("使用原唱") { if(!options.useRole(true))app.error="原唱轨道在此设备不可用，请重新标记" };if(options.roleBacking.isNotBlank())Action("使用伴奏") { if(!options.useRole(false))app.error="伴奏轨道在此设备不可用，请重新标记" } }
            }
        }
        "歌词字幕" -> {
            Text(options.textStatus,color=Muted,fontSize=14.sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(listOf("关闭","内嵌字幕")+options.sidecars.map { it.first }) { name -> Action(name,selected=options.textSelection==name) { options.chooseText(name) } } }
            options.tracks.filter { it.type==androidx.media3.common.C.TRACK_TYPE_TEXT }.forEach { track -> Action("${if(track.selected) "✓ " else ""}${track.label}") { options.selectTrack(track) } }
            Action("重新读取同名歌词/字幕") { app.reloadText() }
        }
        "声音输出" -> {
            Text("优先输出：${options.route}；实际路由由 Android 系统协商。",color=Muted,fontSize=14.sp)
            Action("系统默认输出") { options.chooseRoute(null) }
            options.routes.forEach { route -> Action(route.name) { options.chooseRoute(route.id) } }
            Text("应用音量 ${options.volume}% · 音调 ${options.semitones} 半音",color=Paper)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Action("音量 −") { options.changeVolume(options.volume-10) };Action("音量 +") { options.changeVolume(options.volume+10) };Action("降调") { options.setPitch(options.semitones-1) };Action("升调") { options.setPitch(options.semitones+1) };Action("原调") { options.setPitch(0) } }
        }
        else -> {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("auto","1080p","720p","480p").forEach { quality -> Action(if(quality=="auto") "自动画质" else quality,selected=app.playbackQuality==quality) { app.changeQuality(quality) } } }
            Text("外置歌词偏移 ${options.lyricOffset} ms（正数延后）",color=Paper)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Action("歌词提前 100ms") { options.offset(options.lyricOffset-100) };Action("歌词延后 100ms") { options.offset(options.lyricOffset+100) } }
            Text("音频延迟 ${options.audioDelay} ms · 仅 PCM 输出；按实际音响效果调节",color=Muted,fontSize=14.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Action("音频 −50ms") { options.delayAudio(options.audioDelay-50) };Action("音频 +50ms") { options.delayAudio(options.audioDelay+50) };Action("恢复同步默认值") { options.offset(0);options.delayAudio(0) } }
            Text("内嵌字幕使用媒体自身时间轴。",color=Muted,fontSize=14.sp)
        }
    }
}
