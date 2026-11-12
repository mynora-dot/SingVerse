package com.mynora.singverse.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import org.json.JSONObject

@Composable internal fun TvRecommendationScreen(app: TvApp, list: LazyListState) {
    val history = app.page == "历史"
    val items = if (history) app.historyRecords else app.popularSongs
    var readOnlyRecord by remember { mutableStateOf<JSONObject?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (history) "这台电视唱过" else "家庭热歌", color = Paper, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
                Text(if (history) "本机跨场演唱记录 · ${app.feedTotal} 条" else "这台 NAS 近 30 天常唱 · ${app.feedTotal} 首", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Action("刷新", enabled = !app.feedLoading) { app.loadFeed() }
            Spacer(Modifier.width(10.dp))
            Action("返回首页") { app.navigate("首页") }
        }
        when {
            app.feedError.isNotBlank() -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                EmptyState("暂时无法读取", app.feedError)
                Action("重新读取", Modifier.align(Alignment.CenterHorizontally), selected = true) { app.loadFeed() }
            }
            app.feedLoading -> EmptyState("正在读取", "正在从 NAS 同步真实演唱数据", Modifier.weight(1f))
            items.isEmpty() -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                EmptyState(if (history) "还没有演唱记录" else "还没有常唱歌曲", if (history) "这台电视实际演唱过的歌曲会保存在这里" else "家庭热歌按真实有效演唱次数排序")
                Action("去点歌", Modifier.align(Alignment.CenterHorizontally), selected = true) { app.navigate("点歌") }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items, key = { _, item -> item.getString("id") }) { index, item ->
                    val song = if (history) item.optJSONObject("song") else item
                    val available = !history || item.optBoolean("available") && song != null
                    val displayed = if (history) item else song ?: item
                    val key = "${if (history) "history" else "popular"}:${item.getString("id")}"
                    var focused by remember { mutableStateOf(false) }
                    Button(onClick = { if (available && song != null) app.addSongFromList(song) else readOnlyRecord = item },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).tvFocusTarget(key)
                            .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.control, 1f)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionRight) {
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                        if (available && song != null) app.openSong(song) else readOnlyRecord = item
                                    }
                                    true
                                } else false
                            },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.control),
                        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Elevated, contentColor = Paper, focusedContentColor = Paper)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!history) Text("${app.feedPage * 20 + index + 1}", Modifier.width(30.dp), color = Muted, fontSize = 18.sp)
                            SongArtwork(app, song, Modifier.size(44.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(displayed.optString("title"), color = Paper, fontSize = 17.sp, lineHeight = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(displayed.artists(), color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(if (history) tvHistoryDate(item.optString("startedAt")) else "${item.optLong("effectivePlayCount")} 次有效演唱", color = Paper, fontSize = 14.sp)
                                Text(if (history) tvHistoryStatus(item.optString("status")) else "最近 ${tvHistoryDate(item.optString("lastPlayedAt"))}", color = Muted, fontSize = 12.sp)
                            }
                            Text(if (!available) "歌曲已不可用" else if (app.queueAddSongId == song?.optString("id")) "加入中…" else "＋ 点歌", Modifier.width(92.dp), color = if (available) Paper else Muted, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Action("上一页", enabled = !app.feedLoading && app.feedPage > 0) { app.loadFeed(app.feedPage - 1) }
            Text("${app.feedPage + 1} / ${maxOf(1, (app.feedTotal + 19) / 20)}", color = Muted, fontSize = 13.sp)
            Action("下一页", enabled = !app.feedLoading && (app.feedPage + 1) * 20 < app.feedTotal) { app.loadFeed(app.feedPage + 1) }
            Spacer(Modifier.weight(1f))
            Text("上下选歌 · 确认点歌 · 右键详情", color = Muted, fontSize = 12.sp)
        }
    }
    readOnlyRecord?.let { record ->
        Modal("演唱记录", "这首歌已不在当前可点曲库，保留演唱信息供查看", { readOnlyRecord = null }) {
            Text(record.optString("title"), color = Paper, fontSize = 24.sp, lineHeight = 32.sp)
            Text(record.artists(), color = Muted, fontSize = 16.sp)
            Text("${tvHistoryDate(record.optString("startedAt"))} · ${tvHistoryStatus(record.optString("status"))}", color = Muted, fontSize = 14.sp)
            Action("完成") { readOnlyRecord = null }
        }
    }
}
