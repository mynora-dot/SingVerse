@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import org.json.*

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun stateLabel(value: String) = mapOf("IDLE" to "已停止", "PREPARING" to "准备中", "BUFFERING" to "缓冲中", "PLAYING" to "正在演唱", "PAUSED" to "已暂停", "ENDED" to "演唱结束", "ERROR" to "播放失败", "WAITING" to "待唱", "HANDOFF" to "等待确认", "COMPLETED" to "已唱", "SKIPPED" to "已跳过", "FAILED" to "播放失败", "CANCELLED" to "已撤回")[value] ?: "等待同步"
private fun versionLabel(value: String) = mapOf("ORIGINAL" to "原唱", "ACCOMPANIMENT" to "伴奏", "INSTRUMENTAL" to "纯音乐", "DUET" to "合唱", "KARAOKE" to "卡拉 OK")[value] ?: "版本待确认"
private fun time(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable internal fun Modal(title: String, subtitle: String = "", dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val memory = LocalTvFocusMemory.current
    val parent = LocalTvFocusPage.current
    val parentFocus = LocalFocusManager.current
    val dialog = remember(title) { "dialog:${java.util.UUID.randomUUID()}:$title" }
    val returnTarget = remember(memory, parent, dialog) {
        // Save before Dialog creates its Android window: native focus fallback may happen
        // during applyChanges, before DisposableEffect has a chance to run.
        memory.restoring = true
        memory.last[parent]
    }
    DisposableEffect(memory, parent, dialog) {
        memory.restoring = true
        memory.dialogs.add(dialog)
        parentFocus.clearFocus(force = true)
        onDispose {
            memory.dialogs.remove(dialog)
            memory.last.remove(dialog)
            if (returnTarget != null) memory.last[parent] = returnTarget
            memory.restoring = true
            memory.restoreVersion++
        }
    }
    Dialog(onDismissRequest = dismiss) {
        CompositionLocalProvider(LocalTvFocusPage provides dialog) {
            val entry = remember { FocusRequester() }
            LaunchedEffect(dialog, memory.dialogs.lastOrNull(), memory.restoreVersion) {
                withFrameNanos { }; withFrameNanos { }
                if (memory.dialogs.lastOrNull() != dialog) return@LaunchedEffect
                memory.restoring = false
                if (!memory.restore(dialog)) {
                    val preferred = listOf("取消", "完成", "关闭提示", "返回").firstNotNullOfOrNull { memory.targets["$dialog:action:$it"] }
                    if (preferred != null) preferred.requestFocus() else entry.requestFocus()
                }
            }
            Column(Modifier.widthIn(max = 500.dp).fillMaxWidth().focusRequester(entry).focusGroup().background(Elevated, RoundedCornerShape(26.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Heading(title, subtitle)
                content()
            }
        }
    }
}

@Composable internal fun TvScreen(app: TvApp, focusMemory: TvFocusMemory = remember(app.client.base) { TvFocusMemory() }) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val density = LocalDensity.current
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            TvScreenContent(app, focusMemory)
        }
    }
}

@Composable private fun TvScreenContent(app: TvApp, memory: TvFocusMemory) {
    val view = when {
        app.selected != null -> "${app.page}:song:${app.selected?.optString("id")}"
        app.playlistDetail != null -> "${app.page}:playlist:${app.playlistDetail?.optString("id")}"
        app.artistDetail != null -> "${app.page}:artist:${app.artistDetail?.optString("id")}"
        else -> app.page
    }
    // Removing a focused subtree can temporarily focus a persistent navigation button.
    // Keep that native fallback from replacing the user's last target in either view.
    if (memory.view != view) { memory.view = view; memory.restoring = true }
    CompositionLocalProvider(LocalTvFocusMemory provides memory, LocalTvFocusPage provides view) {
        ConnectedTvContent(app, memory, view)
    }
}

@Composable private fun ConnectedTvContent(app: TvApp, memory: TvFocusMemory, view: String) {
    var invitationOpen by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val playlistsGrid = rememberLazyGridState()
    val playlistSongsGrid = rememberLazyGridState()
    val artistsGrid = rememberLazyGridState()
    val artistSongsGrid = rememberLazyGridState()
    val homeLibrary = rememberLazyListState()
    val homeFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val selectedNavigation = if (app.selected != null || app.page in listOf("找歌", "歌手", "分类", "歌单", "点歌")) "点歌" else app.page
    LaunchedEffect(app.connected, view, memory.restoreVersion, windowFocused) {
        if (app.connected && memory.activeDialogs == 0 && windowFocused) {
            memory.restoring = true
            withFrameNanos { }; withFrameNanos { }
            if (memory.activeDialogs > 0) return@LaunchedEffect
            val target = memory.last[view].orEmpty()
            if (app.selected == null) {
                val id = target.substringAfter(':', "")
                if (app.page == "首页" && target.startsWith("song:")) {
                    val index = app.homeSongs.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) {
                        val layout = homeLibrary.layoutInfo
                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                        // Disabling background focus releases LazyRow's pinned item. Recompose
                        // an off-screen saved card before asking its FocusRequester to restore it.
                        if (item == null || item.offset < layout.viewportStartOffset || item.offset + item.size > layout.viewportEndOffset)
                            homeLibrary.scrollToItem(index)
                    }
                } else {
                    val (state, items) = when {
                        app.playlistDetail != null && target.startsWith("song:") -> playlistSongsGrid to app.songs
                        app.artistDetail != null && target.startsWith("song:") -> artistSongsGrid to app.songs
                        app.page == "找歌" && target.startsWith("song:") -> grid to app.songs
                        app.page == "歌手" && target.startsWith("artist:") -> artistsGrid to app.artists
                        app.page == "歌单" && target.startsWith("playlist:") -> playlistsGrid to app.playlists
                        else -> grid to emptyList()
                    }
                    val index = items.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) {
                        val layout = state.layoutInfo
                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                        if (item == null || item.offset.y < layout.viewportStartOffset || item.offset.y + item.size.height > layout.viewportEndOffset)
                            state.scrollToItem(index)
                    }
                }
            }
            withFrameNanos { }
            if (!memory.restore(view) && app.page != "演唱") {
                val default = when {
                    app.selected != null -> "action:加入待唱"
                    app.playlistDetail != null -> "action:返回歌单"
                    app.artistDetail != null -> "action:返回歌手"
                    else -> null
                }
                if (default != null) {
                    memory.last[view] = default
                    memory.targets["$view:$default"]?.requestFocus()
                } else {
                    memory.last[view] = "nav:$selectedNavigation"
                    homeFocus.requestFocus()
                }
            }
            memory.restoring = false
            if (app.page == "演唱") memory.focusedTargets.singleOrNull()?.takeIf { it.startsWith("$view:") }?.let { memory.last[view] = it.removePrefix("$view:") }
        }
    }
    LaunchedEffect(app.connected, app.snapshot.optString("id")) { if (app.connected) app.ensureInvitation() }
    BackHandler(enabled = app.connected) {
        when {
            app.error.isNotEmpty() && !app.error.startsWith("与 NAS 同步失败") -> app.error = ""
            app.selected != null -> app.selected = null
            app.playlistDetail != null -> app.closePlaylist()
            app.artistDetail != null -> app.closeArtist()
            app.page == "演唱" && app.controlVisible -> app.controlVisible = false
            app.page in listOf("找歌", "歌手", "分类", "歌单") -> app.navigate("点歌")
            app.page != "首页" -> app.navigate("首页")
            else -> app.pendingConfirm = "退出演唱"
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101625), Ink)))) {
        when {
            !app.connected -> TvLoginScreen(app)
            app.page == "演唱" -> PlayerScreen(app)
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        HomeGlyph("brand", Modifier.size(21.dp), Gold)
                        Text("SingVerse", color = Paper, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("首页", "点歌", "已点", "设置").forEach { page ->
                            TvNavigation(if (page == "已点") "已点 ${queuedSongCount(app)}" else page,
                                (if (page == selectedNavigation) Modifier.focusRequester(homeFocus) else Modifier).tvFocusTarget("nav:$page"), selected = selectedNavigation == page) { app.navigate(page) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (app.page != "首页") HomeButton("手机点歌", focusKey = "phone") { invitationOpen = true; app.ensureInvitation() }
                    else Text(if (app.online) "NAS 已连接" else "网络异常", color = Muted, fontSize = 11.sp)
                }
                Box(Modifier.weight(1f)) {
                    if (app.selected != null) Detail(app)
                    else if (app.playlistDetail != null) PlaylistDetail(app, playlistSongsGrid)
                    else if (app.artistDetail != null) ArtistDetail(app, artistSongsGrid)
                    else when (app.page) {
                        "首页" -> TvHomeScreen(app, homeLibrary) { if (!app.online) app.reconnect(); app.ensureInvitation(); invitationOpen = true }
                        "点歌" -> SongCenter(app)
                        "找歌" -> Library(app, grid)
                        "歌单" -> Playlists(app, playlistsGrid)
                        "歌手" -> Artists(app, artistsGrid)
                        "分类" -> Categories(app)
                        "已点" -> Queue(app)
                        "设置" -> Settings(app)
                    }
                }
                if (app.page != "已点") SingingBar(app)
            }
        }
    }
    if (app.error.isNotEmpty() && !app.error.startsWith("与 NAS 同步失败")) Modal("操作提示", dismiss = { app.error = "" }) {
        Text(app.error, color = Paper, fontSize = 15.sp, lineHeight = 21.sp)
        Action("关闭提示") { app.error = "" }
        if (app.pendingOperation != null) {
            Action("核对后重试") { app.retryPending() }
            Action("放弃本次重试") { app.discardPending(); app.error = "" }
        }
    }
    if (invitationOpen) Modal("手机点歌", "微信扫一扫，加入这场聚会。", { invitationOpen = false }) {
        if (app.snapshot.optString("status") == "ENDED") {
            Text("本场聚会已结束，开始新聚会后即可邀请亲友。", color = Muted)
            Action("开始新聚会") { app.newSession() }
        } else {
            if (app.invitation.isNotEmpty() && app.online) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Qr(app.invitation, Modifier.size(190.dp)) }
            else Text(if (app.invitationLoading) "正在生成邀请…" else if (!app.online) "网络异常，请先重连 NAS。" else app.invitationError.ifBlank { "邀请尚未生成，请重试。" }, color = Muted)
            Text("同一网络连接 · ${if (app.participantsKnown) "已加入 ${app.participantCount} 台" else "正在同步设备"}\n此数量表示本场有效加入设备。刷新邀请后旧二维码失效。", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("刷新邀请") { app.invite() }
                if (!app.online) Action("重新连接") { app.reconnect() }
            }
        }
        Action("完成") { invitationOpen = false }
    }
    app.pendingConfirm?.let { action -> Modal("${action}？", "此操作会影响当前演唱。", { app.pendingConfirm = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("取消") { app.pendingConfirm = null }
            Action("确认") {
                app.pendingConfirm = null
                when (action) {
                    "散场" -> app.command("END")
                    "清空待唱" -> app.command("CLEAR")
                    "退出演唱" -> { app.stopLocal(); app.navigate("首页") }
                    "更换 NAS" -> app.disconnect()
                }
            }
        }
    } }
}

internal fun queuedSongCount(app: TvApp) = app.snapshot.optJSONArray("queue")?.objects().orEmpty().count { it.optString("status") in listOf("WAITING", "HANDOFF", "PLAYING") }

@Composable private fun SongCenter(app: TvApp) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Heading("点歌中心", "选一种方式，找到今晚想唱的歌")
        QuickSongEntries(app, large = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("刷新曲库") { app.run { app.loadHome(); app.loadSongs(); app.loadTags() } }
            if (app.snapshot.optString("status") == "ENDED") Action("开始新聚会") { app.newSession() }
        }
        Text("歌单由 NAS 管理端统一维护；可浏览共享歌单，按歌单顺序逐首点唱。", color = Muted, fontSize = 13.sp)
    }
}

@Composable private fun SingingBar(app: TvApp) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" }
    val next = queue.firstOrNull { it.optString("status") == "HANDOFF" } ?: queue.firstOrNull { it.optString("status") == "WAITING" }
    val song = (current ?: next)?.optJSONObject("song")
    Row(Modifier.fillMaxWidth().height(48.dp).background(Brush.horizontalGradient(listOf(Panel, Color(0xFF131A28))), RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SongArtwork(app, song, Modifier.size(34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (song == null) "今晚，唱你喜欢的歌" else song.optString("title"), color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when { app.snapshot.optString("status") == "ENDED" -> "聚会已结束"; current != null -> "${song?.artists()} · ${stateLabel(app.playerState)}"; next != null -> "${song?.artists()} · 等待确认开唱"; else -> "从曲库选一首，开启家庭音乐现场" }, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!app.online) HomeButton("网络异常 · 重连", focusKey = "bar:reconnect") { app.reconnect() }
        HomeButton("已点歌曲 (${queuedSongCount(app)})", focusKey = "bar:queue") { app.navigate("已点") }
        when {
            app.snapshot.optString("status") == "ENDED" -> HomeButton("开始新聚会", primary = true, focusKey = "bar:session") { app.newSession() }
            current == null && next != null -> HomeButton("确认开唱", primary = true, focusKey = "bar:start") { app.command("START") }
            current != null || app.snapshot.optJSONObject("playback")?.optString("songId").orEmpty().isNotEmpty() -> HomeButton("返回演唱", focusKey = "bar:sing") { app.page = "演唱" }
        }
    }
}

@Composable internal fun Qr(value: String, modifier: Modifier = Modifier.size(190.dp)) {
    BoxWithConstraints(modifier.background(Color.White)) {
        // Generate at the final display size so every QR module keeps an integer pixel width.
        // Downscaling a fixed bitmap can make some valid invitation patterns unreadable.
        val size = minOf(constraints.maxWidth, constraints.maxHeight).coerceAtLeast(1)
        val bitmap = remember(value, size) { runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
                mapOf(EncodeHintType.ERROR_CORRECTION to "M"))
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
                for (x in 0 until matrix.width) for (y in 0 until matrix.height)
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }.getOrNull() }
        if (bitmap != null) Image(bitmap.asImageBitmap(), "本场邀请二维码", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    }
}

@Composable private fun Library(app: TvApp, grid: LazyGridState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("搜索歌曲")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Input(app.query, "歌名 / 歌手 / 拼音 / 编号") { app.query = it } }
            Action("搜索") { app.artistId = ""; app.search() }
            Action("分类") { app.navigate("分类") }
            Action("清除筛选") { app.selectedTags = emptySet(); app.unclassified = false; app.artistId = ""; app.search() }
        }
        Text("${app.total} 首歌曲${if (app.selectedTags.isNotEmpty()) " · ${app.selectedTags.size} 项分类" else ""}", color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
        if (app.songs.isEmpty()) EmptyState(if (app.busy) "正在找歌" else "没有找到歌曲", "试试其他关键词，或清除筛选条件。", Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(6), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(10.dp)) { items(dimensions.keys.toList()) { key -> Action(dimensions.getValue(key), selected = dimension == key) { dimension = key } } }
        val tags = app.tags.filter { it.optString("dimension") == dimension }
        if (tags.isEmpty()) EmptyState("暂无${dimensions[dimension]}分类", "可以在 NAS 管理页整理歌曲分类。", Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
        else LazyVerticalGrid(GridCells.Fixed(4), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
        else LazyVerticalGrid(GridCells.Fixed(6),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(12.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            items(app.playlists,key={it.getString("id")}){playlist->
                PlaylistCard(app,playlist,Modifier.fillMaxWidth())
            }
        }
        Pagination(app.playlistPage,app.playlistTotal,{if(app.playlistPage>0){app.playlistPage--;app.loadPlaylists()}},{if((app.playlistPage+1)*20<app.playlistTotal){app.playlistPage++;app.loadPlaylists()}})
    }
}
@Composable private fun PlaylistDetail(app:TvApp,grid:LazyGridState){
    val playlist=app.playlistDetail?:return
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(20.dp)).padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)){
            SongArtwork(app,JSONObject(playlist.toString()).put("revision",playlist.optString("coverVersion")),Modifier.size(85.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(playlist.optString("name"),color=Paper,fontSize=30.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("${playlist.optInt("count")} 首歌曲",color=Muted,fontSize=14.sp,lineHeight=18.sp)
                Text(playlist.optString("description").ifBlank{"选择一首想唱的歌"},color=Muted,fontSize=14.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Action("返回歌单"){app.closePlaylist()}
        }
        if(app.songs.isEmpty())EmptyState(if(app.busy)"正在读取歌曲" else "歌单暂无可用歌曲","请在 NAS 管理端选择曲库中的歌曲。",Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(6),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(12.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){items(app.songs,key={it.getString("id")}){SongCard(app,it,Modifier.fillMaxWidth(),imageHeight=78.dp)}}
        Pagination(app.number,app.total,{if(app.number>0){app.number--;app.search(false)}},{if((app.number+1)*20<app.total){app.number++;app.search(false)}})
    }
}

@Composable private fun ArtistDetail(app: TvApp, grid: LazyGridState) {
    val artist=app.artistDetail ?: return
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
                Action("返回歌手") {app.closeArtist()}
            }
        }
        if(app.songs.isEmpty())EmptyState(if(app.busy)"正在读取歌曲" else "暂无关联歌曲","可以返回歌手页刷新资料。",Modifier.weight(1f))
        else LazyVerticalGrid(GridCells.Fixed(6),state=grid,modifier=Modifier.weight(1f),contentPadding=PaddingValues(12.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {items(app.songs,key={it.getString("id")}) {SongCard(app,it,Modifier.fillMaxWidth(),imageHeight=78.dp)}}
        Pagination(app.number,app.total,{if(app.number>0){app.number--;app.search(false)}},{if((app.number+1)*20<app.total){app.number++;app.search(false)}})
    }
}

@Composable private fun Detail(app: TvApp) {
    val song = app.selected ?: return
    fun field(name: String) = song.optString(name).takeIf { it.isNotBlank() && it != "null" }
    val album = listOfNotNull(field("album"), field("releaseDate")).joinToString(" · ")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("点歌 / 歌曲详情", color = Muted, fontSize = 14.sp, lineHeight = 18.sp)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.height * .7f
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0xFF123B43).copy(alpha = .55f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * .18f, size.height * .5f), radius = radius),
                    radius = radius, center = androidx.compose.ui.geometry.Offset(size.width * .18f, size.height * .5f))
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.CenterVertically) {
                SongArtwork(app, song, Modifier.size(286.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(song.optString("title"), color = Paper, fontSize = 38.sp, lineHeight = 44.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val artists = song.artists()
                    if (artists.isNotBlank()) Text(artists, color = Paper, fontSize = 22.sp, lineHeight = 28.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (album.isNotEmpty()) Text(album, color = Muted, fontSize = 17.sp, lineHeight = 23.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (song.optLong("durationMs") > 0) DetailTag(time(song.optLong("durationMs")))
                        DetailTag(versionLabel(song.optString("version")))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = .22f)))
                    field("sourceName")?.let {
                        Text("来源    $it", color = Muted, fontSize = 16.sp, lineHeight = 22.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Action("＋ 加入待唱", modifier = Modifier.height(48.dp).width(220.dp), selected = true,
                            focusKey = "加入待唱", textSize = 21.sp) {
                            app.command("ADD", songId = song.getString("id")); app.selected = null
                        }
                        Action("返回曲库", modifier = Modifier.height(48.dp).width(145.dp), textSize = 19.sp) { app.selected = null }
                    }
                    Text("方向键移动 · 确认键选择 · 返回键返回", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable private fun DetailTag(label: String) {
    Text(label, modifier = Modifier.background(Panel, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 7.dp),
        color = Paper, fontSize = 15.sp, lineHeight = 19.sp)
}

@Composable internal fun Queue(app: TvApp, compact: Boolean = false) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val waiting = tvWaitingQueue(queue) { it.optString("status") }
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" }
        ?: queue.firstOrNull { it.optString("status") == "HANDOFF" }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (compact) Text("${waiting.size} 首待唱", color = Muted, fontSize = 16.sp)
            else Heading("待唱队列", "${waiting.size} 首待唱")
            Spacer(Modifier.weight(1f))
            if (current == null && waiting.isNotEmpty()) Action("开始下一首") { app.command("START") }
        }
        if (!compact) current?.let { item ->
            val song = item.optJSONObject("song")
            val playback = app.snapshot.optJSONObject("playback")
            val confirmed = playback?.optString("itemId") == item.optString("id") && playback?.optBoolean("confirmed") == true
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SongArtwork(app, song, Modifier.size(54.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when {
                        item.optString("status") == "HANDOFF" && !confirmed -> "等待确认开唱"
                        !app.hasConfirmedPlayback -> "准备中"
                        else -> stateLabel(app.playerState)
                    }, color = Gold, fontSize = 13.sp)
                    Text(song?.optString("title").orEmpty(), color = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.artists().orEmpty(), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.optString("status") == "HANDOFF" && !confirmed) Action("确认开唱") { app.command("START") }
                else Action("返回演唱") { app.page = "演唱" }
            }
        }
        if (waiting.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState("暂无待唱歌曲", "去点歌，选一首喜欢的歌。")
                Action("去点歌") { app.navigate("点歌") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("顺序", Modifier.width(42.dp), color = Muted, fontSize = 13.sp)
                Text("封面", Modifier.width(46.dp), color = Muted, fontSize = 13.sp)
                Text("歌曲 / 歌手", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(waiting, key = { it.getString("id") }) { item ->
                    QueueRow(app, item, waiting.indexOf(item), waiting.size)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择歌曲可调整顺序", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Action("清空待唱") { app.pendingConfirm = "清空待唱" }
            }
        }
    }
}

@Composable private fun QueueRow(app: TvApp, item: JSONObject, index: Int, count: Int) {
    var focused by remember { mutableStateOf(false) }
    val firstAction = remember { FocusRequester() }
    val shape = RoundedCornerShape(14.dp)
    val id = item.getString("id")
    val song = item.optJSONObject("song")
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.focusGroup()
        .tvFocus(focused, shape).background(Panel, shape).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { firstAction.requestFocus() },
            modifier = Modifier.weight(1f).heightIn(min = 64.dp).tvFocusTarget("action:queue:$id"),
            scale = ButtonDefaults.scale(focusedScale = 1f),
            colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, contentColor = Paper, focusedContentColor = Paper)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text((index + 1).toString().padStart(2, '0'), Modifier.width(32.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                SongArtwork(app, song, Modifier.size(46.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.optString("title").orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.artists().orEmpty(), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (focused) {
            val actions = buildList {
                if (index > 0) { add("置顶" to "TOP"); add("上移" to "UP") }
                if (index < count - 1) add("下移" to "DOWN")
                add("移除" to "REMOVE")
            }
            actions.forEachIndexed { actionIndex, (label, command) ->
                Action(label, if (actionIndex == 0) Modifier.focusRequester(firstAction) else Modifier, focusKey = "queue:$id:$command") {
                    app.command(command, id)
                }
            }
        }
    }
}

@Composable private fun Settings(app: TvApp) {
    var group by remember { mutableStateOf("连接") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading("设置")
            listOf("连接", "播放设置", "聚会管理", "本场参与者", "设备信息", "应用更新").forEach { label -> Action(label, Modifier.fillMaxWidth(), selected = label == group) { group = label } }
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(22.dp)).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (group) {
                "连接" -> { Heading(app.serverName, app.client.base); Action("检查连接") { app.connect() }; Action("更换 NAS / 忘记连接") { app.pendingConfirm = "更换 NAS" }; Action("停止本机播放") { app.stopLocal() } }
                "聚会管理" -> {
                    Heading("聚会管理", if (app.snapshot.optBoolean("acceptingRequests")) "当前允许亲友点歌" else "当前已暂停接单")
                    if (app.snapshot.optString("status") == "ENDED") Action("开始新聚会") { app.newSession() }
                    else {
                        Action(if (app.snapshot.optBoolean("acceptingRequests")) "暂停接单" else "恢复接单") { app.command(if (app.snapshot.optBoolean("acceptingRequests")) "LOCK" else "UNLOCK") }
                        Action("结束聚会") { app.pendingConfirm = "散场" }
                    }
                }
                "播放设置" -> PlaybackSettings(app)
                "应用更新" -> { Heading("应用更新", app.updateStatus); Text("当前 ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",color=Muted); Action("检查更新") { app.checkUpdate() }; if(app.updateManifest?.optBoolean("available")==true) Action(if(app.busy) "处理中…" else "下载并校验") { if(!app.busy) app.downloadUpdate() }; if(app.updateFile!=null) Action("确认安装 / 开启安装权限后重试") { app.installUpdate() } }
                "设备信息" -> { Heading(android.os.Build.MODEL); Text("Android ${android.os.Build.VERSION.RELEASE}", color = Muted, fontSize = 16.sp, lineHeight = 20.sp); Text("SingVerse TV · 原始媒体播放", color = Muted, fontSize = 16.sp, lineHeight = 20.sp) }
                else -> {
                    Heading("本场参与者", "主持权限只在电视上授予。")
                    if (app.participants.isEmpty()) EmptyState("还没有亲友加入", "通过「手机点歌」邀请亲友。")
                    app.participants.forEach { participant ->
                        Text(participant.optString("name"), color = Paper, fontSize = 20.sp, lineHeight = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Action("授予主持", focusKey = "host:${participant.getString("id")}") { app.command("HOST", participantId = participant.getString("id")) }; Action("撤销访问", focusKey = "revoke:${participant.getString("id")}") { app.command("REVOKE", participantId = participant.getString("id")) } }
                    }
                }
            }
        }
    }
}
