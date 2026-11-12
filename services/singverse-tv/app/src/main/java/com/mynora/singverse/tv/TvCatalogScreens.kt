package com.mynora.singverse.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The catalog shell owns the four-method sidebar; these screens render its content. */
@Composable internal fun Library(app: TvApp, list: LazyListState) {
    val category = when {
        app.unclassified -> "未分类歌曲"
        app.selectedTags.size == 1 -> app.tags.firstOrNull { it.optString("id") in app.selectedTags }?.optString("name").orEmpty()
        else -> "分类歌曲"
    }.ifBlank { "分类歌曲" }
    val filtered = app.query.isNotBlank() || app.selectedTags.isNotEmpty() || app.unclassified
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogTitle(
            if (app.categoryResults) category else "搜索歌曲",
            if (app.categoryResults) "从当前分类里选择想唱的歌" else "歌名、歌手、拼音或歌曲编号",
        ) {
            if (app.categoryResults) Action("返回分类") { app.navigate("分类") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Input(app.query, "歌名 / 歌手 / 拼音 / 编号") { app.query = it } }
            Action("搜索", selected = true) { app.artistId = ""; app.search() }
            Action("分类") { app.navigate("分类") }
            if (filtered) Action("清除筛选") {
                app.query = ""
                app.selectedTags = emptySet()
                app.unclassified = false
                app.artistId = ""
                app.number = 0
                app.navigate("点歌")
            }
        }
        CatalogResultTitle(if (app.categoryResults) "分类歌曲" else "曲库结果", if (app.online) "${app.total} 首歌曲" else "未连接 NAS")
        when {
            !app.online -> CatalogEmpty("暂时无法读取曲库", "恢复与 NAS 的连接后重试。", Modifier.weight(1f)) {
                Action("重新连接") { app.run { app.refresh(); if (app.online) app.loadSongs(resetList = true) } }
            }
            app.songs.isEmpty() -> CatalogEmpty(
                if (app.busy) "正在找歌" else "没有找到歌曲",
                if (app.busy) "正在读取 NAS 曲库。" else "试试其他关键词，或清除筛选条件。",
                Modifier.weight(1f),
            )
            else -> SongTable(app, list, Modifier.weight(1f))
        }
        CatalogSongPagination(app)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun Categories(app: TvApp, grid: LazyGridState, dimension: String, onDimensionChange: (String) -> Unit) {
    val dimensions = linkedMapOf("LANGUAGE" to "语种", "DECADE" to "年代", "GENRE" to "风格", "MOOD" to "情绪", "SCENE" to "场景", "CUSTOM" to "自定义")
    val scope = rememberCoroutineScope()
    val memory = LocalTvFocusMemory.current
    fun selectDimension(key: String) {
        if (dimension == key) return
        onDimensionChange(key)
        scope.launch { grid.scrollToItem(0) }
    }
    val focusMargin = with(LocalDensity.current) { 8.dp.toPx() }
    val focusScroll = remember(focusMargin) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < focusMargin -> offset - focusMargin
                offset + size > containerSize - focusMargin -> offset + size - containerSize + focusMargin
                else -> 0f
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogTitle("分类点歌", "选一个分类，看看你的曲库")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(8.dp)) {
            items(dimensions.keys.toList(), key = { it }) { key ->
                Action(
                    dimensions.getValue(key),
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused && memory.directionalInput && !memory.restoring && memory.activeDialogs == 0) selectDimension(key)
                    },
                    selected = dimension == key,
                    textSize = 13.sp,
                ) { selectDimension(key) }
            }
        }
        val tags = app.tags.filter { it.optString("dimension") == dimension }
        when {
            !app.online -> CatalogEmpty("暂时无法读取分类", "恢复与 NAS 的连接后重试。", Modifier.weight(1f)) {
                Action("重新连接") { app.run { app.refresh(); if (app.online) app.loadTags() } }
            }
            tags.isEmpty() -> CatalogEmpty(
                if (app.busy) "正在读取分类" else "暂无${dimensions[dimension].orEmpty()}分类",
                "可以在 NAS 管理端整理歌曲分类。",
                Modifier.weight(1f),
            )
            else -> CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
                LazyVerticalGrid(
                    GridCells.Fixed(3), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tags, key = { it.getString("id") }) { tag ->
                        CatalogCategoryCard(app, tag, tag.getString("id") in app.selectedTags) { app.openCategory(tag.getString("id")) }
                    }
                }
            }
        }
        Action("未分类") { app.openCategory() }
    }
}

@Composable internal fun Artists(app: TvApp, grid: LazyGridState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogTitle("歌手点歌", "只显示你的曲库里已有的歌手")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Input(app.artistQuery, "查找歌手") { app.artistQuery = it } }
            Action("查找", selected = true) { app.artistPage = 0; app.loadArtists() }
        }
        when {
            !app.online -> CatalogEmpty("暂时无法读取歌手", "恢复与 NAS 的连接后重试。", Modifier.weight(1f)) {
                Action("重新连接") { app.run { app.refresh(); if (app.online) app.loadArtists() } }
            }
            app.artists.isEmpty() -> CatalogEmpty(
                if (app.busy) "正在读取歌手" else if (app.artistQuery.isNotBlank()) "没有找到歌手" else "还没有歌手",
                if (app.artistQuery.isNotBlank()) "试试其他姓名或拼音。" else "保存歌曲元数据后，歌手会出现在这里。",
                Modifier.weight(1f),
            )
            else -> LazyVerticalGrid(
                GridCells.Fixed(4), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(app.artists, key = { it.getString("id") }) { artist -> CatalogArtistCard(app, artist) }
            }
        }
        CatalogPagination(app.artistPage, app.artistTotal,
            { if (app.artistPage > 0) { app.artistPage--; app.loadArtists() } },
            { if ((app.artistPage + 1) * 20 < app.artistTotal) { app.artistPage++; app.loadArtists() } })
    }
}

@Composable internal fun Playlists(app: TvApp, grid: LazyGridState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogTitle("共享歌单", "来自 NAS 的共享歌单，全家都能点唱")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Input(app.playlistQuery, "查找歌单") { app.playlistQuery = it } }
            Action("查找", selected = true) { app.playlistPage = 0; app.loadPlaylists() }
        }
        when {
            !app.online -> CatalogEmpty("暂时无法读取歌单", "恢复与 NAS 的连接后重试。", Modifier.weight(1f)) {
                Action("重新连接") { app.run { app.refresh(); if (app.online) app.fetchPlaylists() } }
            }
            app.playlists.isEmpty() -> CatalogEmpty(
                if (app.busy) "正在读取歌单" else if (app.playlistQuery.isNotBlank()) "没有找到歌单" else "还没有共享歌单",
                if (app.playlistQuery.isNotBlank()) "试试其他歌单名称。" else "在 NAS 管理端创建歌单后，就能在这里找到。",
                Modifier.weight(1f),
            )
            else -> LazyVerticalGrid(
                GridCells.Fixed(3), state = grid, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(app.playlists, key = { it.getString("id") }) { playlist -> CatalogPlaylistCard(app, playlist) }
            }
        }
        CatalogPagination(app.playlistPage, app.playlistTotal,
            { if (app.playlistPage > 0) { app.playlistPage--; app.loadPlaylists() } },
            { if ((app.playlistPage + 1) * 20 < app.playlistTotal) { app.playlistPage++; app.loadPlaylists() } })
    }
}

@Composable internal fun PlaylistDetail(app: TvApp, list: LazyListState) {
    val playlist = app.playlistDetail ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogCollectionHeader(
            playlist.optString("name"), "${playlist.optInt("count")} 首歌曲 · NAS 共享歌单",
            playlist.optString("description").takeUnless { it == "null" }.orEmpty(),
            artwork = { SongArtwork(app, playlistArtwork(playlist), Modifier.size(64.dp)) },
            back = { Action("返回歌单") { app.closePlaylist() } },
        )
        CatalogResultTitle("歌单歌曲", "沿歌单顺序，选一首想唱的歌")
        when {
            !app.online -> CatalogEmpty("暂时无法读取歌曲", "恢复与 NAS 的连接后重试。", Modifier.weight(1f)) {
                Action("重新连接") { app.run { app.refresh(); if (app.online) app.loadSongs(resetList = true) } }
            }
            app.songs.isEmpty() -> CatalogEmpty(
                if (app.busy) "正在读取歌曲" else "歌单暂无可用歌曲",
                "可在 NAS 管理端重新选择曲库中的歌曲。",
                Modifier.weight(1f),
            )
            else -> SongTable(app, list, Modifier.weight(1f))
        }
        CatalogSongPagination(app)
    }
}

@Composable internal fun ArtistDetail(app: TvApp, list: LazyListState) {
    val artist = app.artistDetail ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogCollectionHeader(
            artist.optString("name"),
            "${artist.optInt("count")} 首歌曲 · ${if (artist.optBoolean("managed")) "曲库歌手" else "曲库原署名"}",
            artist.optString("biography").takeUnless { it == "null" }.orEmpty(),
            artwork = { ArtistArtwork(app, artist, "portrait", Modifier.size(64.dp)) },
            back = { Action("返回歌手") { app.closeArtist() } },
            background = {
                if (artist.optString("backgroundPath").isNotBlank()) {
                    ArtistArtwork(app, artist, "background", Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(Panel.copy(alpha = .9f)))
                }
            },
        )
        CatalogResultTitle("歌曲", "确认加入待唱，右键查看详情")
        when {
            !app.online -> CatalogEmpty("暂时无法读取歌曲", "恢复与 NAS 的连接后重试。", Modifier.weight(1f)) {
                Action("重新连接") { app.run { app.refresh(); if (app.online) app.loadSongs(resetList = true) } }
            }
            app.songs.isEmpty() -> CatalogEmpty(
                if (app.busy) "正在读取歌曲" else "暂无关联歌曲",
                "可以返回歌手页，选择其他歌手。",
                Modifier.weight(1f),
            )
            else -> SongTable(app, list, Modifier.weight(1f))
        }
        CatalogSongPagination(app)
    }
}

@Composable private fun CatalogTitle(title: String, subtitle: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Paper, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        action()
    }
}

@Composable private fun CatalogResultTitle(title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Paper, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        Text(detail, Modifier.weight(1f), color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun CatalogEmpty(title: String, message: String, modifier: Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(32.dp).background(Panel, TvShape.control), contentAlignment = Alignment.Center) {
            MusicMark(Modifier.size(20.dp))
        }
        Text(title, color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(message, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        action()
    }
}

@Composable private fun CatalogPagination(page: Int, total: Long, previous: () -> Unit, next: () -> Unit, hint: String = "方向键选择 · 确认打开") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Action("上一页", enabled = page > 0, onClick = previous)
        Text("${page + 1} / ${maxOf(1, (total + 19) / 20)}", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Action("下一页", enabled = (page + 1) * 20 < total, onClick = next)
        Spacer(Modifier.weight(1f))
        Text(hint, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun CatalogSongPagination(app: TvApp) {
    CatalogPagination(app.number, app.total,
        { if (app.number > 0) { app.number--; app.search(false, focusFirst = true) } },
        { if ((app.number + 1) * 20 < app.total) { app.number++; app.search(false, focusFirst = true) } },
        hint = "上下选歌 · 确认点歌 · 右键详情")
}

@Composable private fun CatalogCollectionHeader(
    title: String,
    subtitle: String,
    description: String,
    artwork: @Composable () -> Unit,
    back: @Composable () -> Unit,
    background: @Composable BoxScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().clip(TvShape.card)) {
        background()
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            artwork()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Paper, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description.isNotBlank()) Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            back()
        }
    }
}

@Composable private fun CatalogCategoryCard(app: TvApp, tag: JSONObject, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).tvFocusTarget("category:${tag.optString("id")}")
            .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.card, focusedScale = 1f),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.card), contentPadding = PaddingValues(12.dp),
        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Elevated, contentColor = Paper, focusedContentColor = Paper)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tag.optString("name"), color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${tag.optInt("count")} 首歌曲${if (selected) " · 已选" else ""}", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tag.optString("coverPath").isNotBlank()) SongArtwork(app, playlistArtwork(tag), Modifier.size(44.dp))
        }
    }
}

@Composable private fun CatalogArtistCard(app: TvApp, artist: JSONObject) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = { app.openArtist(artist) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 118.dp).tvFocusTarget("artist:${artist.optString("id")}")
            .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.card, focusedScale = 1f),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.card), contentPadding = PaddingValues(10.dp),
        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Elevated, contentColor = Paper, focusedContentColor = Paper)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ArtistArtwork(app, artist, "portrait", Modifier.size(54.dp))
            Text(artist.optString("name"), color = Paper, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${artist.optInt("count")} 首歌曲", color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun CatalogPlaylistCard(app: TvApp, playlist: JSONObject) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = { app.openPlaylist(playlist) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).tvFocusTarget("playlist:${playlist.optString("id")}")
            .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.card, focusedScale = 1f),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.card), contentPadding = PaddingValues(10.dp),
        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Elevated, contentColor = Paper, focusedContentColor = Paper)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SongArtwork(app, playlistArtwork(playlist), Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(playlist.optString("name"), color = Paper, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${playlist.optInt("count")} 首歌曲", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun playlistArtwork(value: JSONObject) = JSONObject(value.toString()).put("revision", value.optString("coverVersion"))
