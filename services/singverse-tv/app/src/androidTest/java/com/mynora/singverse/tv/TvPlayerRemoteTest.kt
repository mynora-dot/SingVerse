@file:Suppress("DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.C
import androidx.media3.common.Player
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Real Media3 decoding, production Compose UI and D-pad events; all media/API data is test-only. */
class TvPlayerRemoteTest : InstrumentationTestCase() {
    private lateinit var playback: TvPlaybackFixture
    private lateinit var fixture: TvUiFixture
    private lateinit var activity: TvUiFixtureActivity
    private val app get() = activity.app
    private val history = mutableListOf<String>()
    private val output get() = File(instrumentation.targetContext.getExternalFilesDir(null), "tv-player-checks").apply { mkdirs() }

    override fun setUp() {
        super.setUp()
        playback = TvPlaybackFixture(instrumentation.context.assets.open(TvPlaybackFixture.VIDEO_ASSET).use { it.readBytes() })
        fixture = TvUiFixture(playback)
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = info
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("tv-ui-fixture", fixture.base)
            putExtra("tv-ui-playback", true)
        }) as TvUiFixtureActivity
        waitFor("隔离首页和遥控焦点") { focused() == "首页:nav:首页" }
    }

    override fun tearDown() {
        try {
            if (::activity.isInitialized) {
                screenshot("$name-final").recycle()
                File(output, "$name-tree.txt").writeText(nodes().joinToString("\n") {
                    val bounds = Rect(); it.getBoundsInScreen(bounds)
                    "enabled=${it.isEnabled} clickable=${it.isClickable} focused=${it.isFocused} visible=${it.isVisibleToUser} bounds=$bounds state=${state(it)} ${label(it)}"
                })
                File(output, "$name-keys.txt").writeText(history.joinToString("\n"))
                File(output, "$name-http.txt").writeText(fixture.httpEvents.joinToString("\n"))
                File(output, "$name-events.json").writeText(org.json.JSONArray(playback.playbackEvents).toString(2))
                onMain { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            if (::fixture.isInitialized) fixture.close()
            super.tearDown()
        }
    }

    fun testNaturalCompletionContinuesAudioAndVideoQueueAndStopsWhenEmpty() {
        start("test-song-1", next = "test-song-2")
        onMain { app.command("ADD", songId = "test-song-3") }
        waitFor("三首歌曲已入队") { onMain { queuedSongCount(app) == 3 && app.pendingOperation == null } }
        val commandIndex = fixture.actions.size
        listOf("test-song-2", "test-song-3").forEachIndexed { index, nextSong ->
            // Seek near EOF, then let the real decoder finish; never synthesize STATE_ENDED.
            if (index == 0) playback.holdEndedResponse = true
            try {
                onMain { app.player.seekTo(app.player.duration - 700) }
                if (index == 0) {
                    waitFor("自然结束响应暂缓，准备重复回报") { playback.heldEndedResponse != null }
                    onMain { repeat(3) { app.report(continueQueue = true) } }
                    waitFor("重复结束事件均已到达服务端") { playback.playbackEvents.count { it.optString("state") == "ENDED" } >= 4 }
                }
            } finally {
                if (index == 0) { playback.holdEndedResponse = false; playback.releaseEndedResponse() }
            }
            waitFor("自然播完后自动播放$nextSong", 20000) {
                onMain {
                    app.snapshot.optJSONObject("playback")?.optString("songId") == nextSong &&
                        app.hasConfirmedPlayback && app.player.playbackState == Player.STATE_READY &&
                        app.playing && app.pendingOperation == null
                }
            }
            assertEquals("自然结束只START一次，不能用NEXT跳过已完成歌曲",
                List(index + 1) { "START" }, fixture.actions.drop(commandIndex))
            assertEquals("刚播完的歌曲必须保留已唱状态", "COMPLETED",
                onMain { app.snapshot.getJSONArray("queue").getJSONObject(index).getString("status") })
            assertNull("自动续播不能弹出确认框", onMain { app.pendingConfirm })
            assertEquals("自动续播不能打开弹窗", 0, onMain { activity.focusMemory.activeDialogs })
        }
        onMain { app.player.seekTo(app.player.duration - 700) }
        waitFor("空队列保持演唱结束") {
            onMain { app.playerState == "ENDED" && app.snapshot.getJSONArray("queue").objects().all { it.optString("status") == "COMPLETED" } }
        }
        onMain { repeat(3) { app.report(continueQueue = true) } }
        SystemClock.sleep(1200)
        assertEquals("空队列和重复结束回报不能再启动歌曲", listOf("START", "START"), fixture.actions.drop(commandIndex))
        assertEquals("每首歌只加载一次", listOf("test-song-1", "test-song-2", "test-song-3"), playback.resourceRequests.map { it.getString("songId") })
        assertTrue("每次资源请求都必须已确认", playback.unconfirmedResourceRequests.isEmpty())
        waitFor("播完后保留可操作的遥控焦点") { focused().contains(":action:player:") }
        assertVisibleFocus()
    }

    fun testLateCompletionCannotStartRemoteHandoff() {
        start("test-song-1", next = "test-song-2")
        val starts = fixture.actions.count { it == "START" }
        playback.holdEndedResponse = true
        try {
            onMain { app.player.seekTo(app.player.duration - 700) }
            waitFor("服务端已收到自然结束，暂缓响应") { playback.heldEndedResponse != null }
            playback.remoteNext()
            onMain { app.reconnect() }
            waitFor("远程切歌已处理为待确认交接") {
                onMain { app.snapshot.optJSONObject("playback")?.optString("songId") == "test-song-2" && app.playerState == "IDLE" }
            }
        } finally {
            playback.releaseEndedResponse()
        }
        SystemClock.sleep(1200)
        assertEquals("迟到的结束响应不能确认远程交接", starts, fixture.actions.count { it == "START" })
        assertFalse("远程交接仍待电视确认", onMain { app.snapshot.getJSONObject("playback").getBoolean("confirmed") })
        assertEquals("不能擅自加载下一首", 1, playback.resourceRequests.size)
    }

    fun testPauseAndLocalStopDoNotContinueQueue() {
        start("test-song-1", next = "test-song-2")
        val starts = fixture.actions.count { it == "START" }
        onMain { app.pauseLocal(); app.player.seekTo(app.player.duration - 700) }
        SystemClock.sleep(1200)
        assertEquals("暂停不应自动切歌", starts, fixture.actions.count { it == "START" })
        assertFalse("暂停在结尾附近也不能续播", onMain { app.playing })
        onMain { app.stopLocal() }
        SystemClock.sleep(1200)
        assertEquals("本机停止不应自动切歌", starts, fixture.actions.count { it == "START" })
        assertEquals("等待歌曲保持WAITING", "WAITING", onMain { app.snapshot.getJSONArray("queue").getJSONObject(1).getString("status") })
        assertEquals("暂停/停止不能请求下一首资源", 1, playback.resourceRequests.size)
    }

    fun testActualPlaybackCountersExcludeSeekAndPausedTime() {
        start("test-song-1", next = "test-song-2")
        val attempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        fun latestEvent() = playback.playbackEvents.filter { it.optString("attemptId") == attempt && it.has("eventSeq") }
            .maxByOrNull { it.getLong("eventSeq") }
        SystemClock.sleep(1200)
        onMain { app.pauseLocal(); app.report() }
        waitFor("暂停结算真实播放") { latestEvent()?.optString("state") == "PAUSED" }
        val before = checkNotNull(latestEvent())
        assertTrue("实际播放区间已计时", before.getLong("actualPlayedMs") >= 500)
        SystemClock.sleep(1200)
        onMain { app.player.seekTo(10000); app.report(); app.report() }
        waitFor("暂停后重复事件不重复累计") { (latestEvent()?.optLong("elapsedMs", 0) ?: 0) >= before.getLong("elapsedMs") + 1000 }
        val after = checkNotNull(latestEvent())
        assertEquals(attempt, after.getString("attemptId"))
        assertEquals("跳转和暂停不增加真实播放", before.getLong("actualPlayedMs"), after.getLong("actualPlayedMs"))
        assertEquals("暂停心跳不能刷新最近真实播放", before.getLong("lastActivityAt"), after.getLong("lastActivityAt"))
        assertTrue(after.getLong("occurredAt") >= before.getLong("occurredAt") + 1000)
        assertTrue("累计真实播放不超过单调经过时间", after.getLong("actualPlayedMs") <= after.getLong("elapsedMs"))
        onMain { app.player.play() }
        waitFor("同实例恢复播放") { latestEvent()?.optString("state") == "PLAYING" }
        assertEquals(attempt, checkNotNull(latestEvent()).getString("attemptId"))
        // Concurrent reports may arrive at the NAS out of order. eventSeq is the actual
        // client sampling order and the server's stale-event rejection boundary.
        val received = playback.playbackEvents.filter { it.optString("attemptId") == attempt }
        assertTrue("同实例每个实际回报必须有正eventSeq", received.all { it.has("eventSeq") && it.getLong("eventSeq") > 0 })
        val events = received.sortedBy { it.getLong("eventSeq") }
        val counters = events.map { "${it.getLong("eventSeq")}:${it.getLong("actualPlayedMs")}/${it.getLong("elapsedMs")}" }
        assertTrue("同实例eventSeq必须唯一且递增：$counters", events.zipWithNext().all { (first, second) ->
            second.getLong("eventSeq") > first.getLong("eventSeq")
        })
        assertTrue("真实累计必须按eventSeq单调增加：$counters", events.zipWithNext().all { (first, second) ->
            second.getLong("actualPlayedMs") >= first.getLong("actualPlayedMs") && second.getLong("elapsedMs") >= first.getLong("elapsedMs")
        })
        assertTrue("每个事件的真实播放累计都不超过经过时间：$counters", events.all { it.getLong("actualPlayedMs") <= it.getLong("elapsedMs") })
    }

    fun testDurationEventBelongsOnlyToTheCurrentPlayingAttempt() {
        start("test-song-1", next = "test-song-2")
        val attempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        val playingIndex = playback.playbackEvents.size
        onMain { app.report() }
        waitFor("当前真实播放事件携带源总时长") {
            playback.playbackEvents.drop(playingIndex).any {
                it.optString("attemptId") == attempt && it.optString("state") == "PLAYING" && it.has("durationMs")
            }
        }
        assertEquals(TvPlaybackFixture.DURATION_MS, playback.playbackEvents.drop(playingIndex)
            .first { it.optString("state") == "PLAYING" }.getLong("durationMs"))

        val pausedIndex = playback.playbackEvents.size
        onMain { app.pauseLocal(); app.report() }
        waitFor("暂停事件仍正常回报") {
            playback.playbackEvents.drop(pausedIndex).any { it.optString("state") == "PAUSED" }
        }
        assertTrue("暂停时保留的播放器时长不能触发预缓存", playback.playbackEvents.drop(pausedIndex)
            .filter { it.optString("state") == "PAUSED" }.all { !it.has("durationMs") })

        playback.remoteNext()
        onMain { app.reconnect() }
        waitFor("切歌后旧媒体仍在播放器但新实例尚未确认") {
            onMain { app.snapshot.getJSONObject("playback").getString("attemptId") != attempt &&
                !app.hasConfirmedPlayback && app.playerState == "IDLE" && app.pendingOperation == null }
        }
        val handoffIndex = playback.playbackEvents.size
        onMain { app.report() }
        assertTrue("旧歌曲时长不能附到新的待确认实例", playback.playbackEvents.drop(handoffIndex).none { it.has("durationMs") })
        assertEquals("上报时长不应申请或启动下一首资源", 1, playback.resourceRequests.size)
    }

    fun testAudioPlaybackProgressLyricsQrSettingsAndQueue() {
        start("test-song-1", next = "test-song-2")
        assertLyricsBeforeFirstLine()
        assertEquals(TvPlayerMode.AUDIO, onMain { app.playerMode })
        assertFalse("WAV不能产生视频轨道", onMain { app.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO } })
        assertTrue("WAV须有真实已解码音轨", onMain { app.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } })
        waitFor("真实播放进度前进") { onMain { app.position > 800 && app.playing } }
        assertTrue("音质采样率依据真正解码的WAV格式", onMain { app.audioQualityLabels.contains("24 kHz") })
        waitFor("纯音频自动同步关联LRC") { onMain { app.playbackOptions.activeLine != null && app.playbackOptions.nextLine != null } }
        assertLyricSynchronization()
        assertTrue("音频布局应有模式语义", nodes().any { state(it) == "音频播放器" })
        listOf("专辑" to "仅限播放器自动化验收", "年份" to "2026", "风格" to "合成测试").forEach { (caption, value) ->
            waitFor("显示实际歌曲元数据：$caption $value") {
                nodes().any { it.isVisibleToUser && label(it).replace(Regex("\\s+"), " ").contains("$caption $value") }
            }
        }
        assertTrue("只显示真实下一首歌曲", visibleText("即将播放") && visibleText("测试双音轨视频"))
        assertSquareCovers()
        assertQr("audio-real-playback")

        val favoriteSong = onMain { app.currentSong?.optString("id") }
        assertEquals("收藏针对当前真实播放歌曲", "test-song-1", favoriteSong)
        assertFalse("随机测试偏好中初始没有收藏", onMain { app.isFavorite(favoriteSong!!) })
        focusAction("favorite")
        val favoriteFocus = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("遥控收藏当前歌曲") { onMain { app.isFavorite(favoriteSong!!) } }
        assertEquals("收藏文案变化不能丢失焦点", favoriteFocus, focused())
        assertVisibleFocus()
        screenshot("audio-local-favorite-selected").recycle()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("遥控取消当前歌曲收藏") { onMain { !app.isFavorite(favoriteSong!!) } }
        assertEquals("取消收藏后仍保留原按钮焦点", favoriteFocus, focused())

        val originalAttempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        val originalResources = playback.resourceRequests.count { it.optString("songId") == "test-song-1" }
        focusAction("restart")
        val restartOrigin = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("遥控重唱创建新播放实例并从头真实播放", 20000) {
            onMain {
                app.snapshot.optJSONObject("playback")?.optString("attemptId") != originalAttempt &&
                    app.player.playbackState == Player.STATE_READY && app.playing && app.position in 0L..2000L
            } && playback.resourceRequests.count { it.optString("songId") == "test-song-1" } > originalResources
        }
        assertTrue("重唱须通过真实RESTART命令", fixture.actions.contains("RESTART"))
        waitFor("重唱准备完成后恢复重唱来源焦点") { focused() == restartOrigin }
        assertVisibleFocus()
        screenshot("audio-real-restart-at-beginning").recycle()

        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("遥控暂停真实Media3") { onMain { !app.playing && app.player.playbackState == Player.STATE_READY } }
        val paused = onMain { app.player.currentPosition }
        SystemClock.sleep(650)
        assertTrue("暂停后真实媒体时钟停止", abs(onMain { app.player.currentPosition } - paused) <= 100)
        val beforeSeek = onMain { app.position }
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("遥控进入进度条") { focused().endsWith(":player:progress") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("进度预览在确认前不能改变真实媒体时钟", abs(onMain { app.player.currentPosition } - paused) <= 100)
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回只取消进度预览，保留播放器与进度焦点") {
            onMain { app.page == "演唱" && !app.playing } && focused().endsWith(":player:progress")
        }
        assertTrue("取消预览不能提交seek", abs(onMain { app.player.currentPosition } - paused) <= 100)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("离开进度条取消预览") { focused().contains(":action:player:") }
        assertTrue("焦点离开不能提交seek", abs(onMain { app.player.currentPosition } - paused) <= 100)
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("重新进入进度条") { focused().endsWith(":player:progress") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认提交真实10秒seek") { onMain { app.position >= beforeSeek + 9500 } }
        assertFalse("暂停状态下拖动进度不应自行播放", onMain { app.playing })
        assertLyricSynchronization()
        screenshot("audio-paused-after-remote-seek").recycle()

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("进度条下键返回播放控制") { focused().contains(":action:player:") }
        listOf("lyrics", "output", "more").forEach { control ->
            focusAction(control)
            val origin = focused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("$control 设置弹窗取得焦点") { onMain { activity.focusMemory.activeDialogs == 1 } && focused().startsWith("dialog:") }
            assertVisibleFocus()
            screenshot("audio-settings-$control").recycle()
            key(KeyEvent.KEYCODE_BACK)
            waitFor("$control 弹窗关闭恢复原按钮") { onMain { activity.focusMemory.activeDialogs == 0 } && focused() == origin }
            assertVisibleFocus()
        }
        focusAction("queue")
        val queueOrigin = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("播放器已点入口打开真实队列弹窗") { onMain { app.page == "演唱" && activity.focusMemory.activeDialogs == 1 } && focused().startsWith("dialog:") }
        assertWaitingQueueSong("测试双音轨视频", "test-song-2", "test-song-1")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("已点返回播放器恢复入口焦点") { onMain { app.page == "演唱" && activity.focusMemory.activeDialogs == 0 } && focused() == queueOrigin }
        assertVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("音频播放器返回首页") { onMain { app.page == "首页" } && focused() == "首页:nav:首页" }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("遥控右键直接打开完整待唱队列") { onMain { app.page == "已点" } && focused() == "已点:nav:已点" }
        assertWaitingQueueSong("测试双音轨视频", "test-song-2", "test-song-1")
        screenshot("tv-queue-cover-no-owner").recycle()
    }

    fun testVideoNativeAudioRolesAndDirectNextSong() {
        start("test-song-2", next = "test-song-3")
        assertEquals(TvPlayerMode.VIDEO, onMain { app.playerMode })
        assertTrue("视频布局应有模式语义", nodes().any { state(it) == "视频播放器" })
        assertTrue("H264视频须真正选中视频轨道", onMain { app.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } })
        assertEquals("测试视频提供两条真实AAC音轨", 2, onMain { app.playbackOptions.tracks.count { it.type == C.TRACK_TYPE_AUDIO } })
        assertTrue("播放器保持素材原始16:9比例", onMain { app.player.videoSize.width * 9 == app.player.videoSize.height * 16 })
        assertQr("video-real-playback")

        waitFor("视频时钟离开起点，能够识别切轨是否回零") { onMain { app.player.currentPosition >= 1600 } }
        assertNativeRoleSwitch("backing", "stream:2", paused = false)
        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("暂停后验收原生切轨不会自行恢复播放") { onMain { !app.playing && !app.player.playWhenReady } }
        assertNativeRoleSwitch("original", "stream:1", paused = true)
        assertNativeRoleSwitch("backing", "stream:2", paused = true)
        assertNativeRoleSwitch("original", "stream:1", paused = true)
        screenshot("video-original-role").recycle()

        focusAction("next")
        val previousAttempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        val commandIndex = fixture.actions.size
        val nextCommands = fixture.actions.count { it == "NEXT" }
        val startCommands = fixture.actions.count { it == "START" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("一次切歌依次NEXT和START，真实播放下一首", 20000) {
            fixture.actions.count { it == "NEXT" } == nextCommands + 1 &&
                fixture.actions.count { it == "START" } == startCommands + 1 &&
                onMain {
                    val current = app.snapshot.optJSONObject("playback")
                    current != null && current.optString("songId") == "test-song-3" &&
                        current.optString("attemptId").let { it.isNotBlank() && it != previousAttempt } &&
                        current.optBoolean("confirmed") && app.playerMode == TvPlayerMode.AUDIO &&
                        app.player.playbackState == Player.STATE_READY && app.playing
                }
        }
        assertEquals("明确切歌应按顺序仅发送NEXT和START", listOf("NEXT", "START"), fixture.actions.drop(commandIndex))
        assertNull("遥控切歌直接执行，不应再次要求确认", onMain { app.pendingConfirm })
        assertEquals("切歌不能打开额外确认弹窗", 0, onMain { activity.focusMemory.activeDialogs })
        assertTrue("NEXT与START之间不能借用旧角色提前加载资源", playback.unconfirmedResourceRequests.isEmpty())
        assertTrue("模式切换请求真实下一首资源", playback.resourceRequests.any { it.optString("songId") == "test-song-3" })
        screenshot("video-to-audio-next-song").recycle()

        focusAction("next")
        val resourcesAtEnd = playback.resourceRequests.size
        val startsAtEnd = fixture.actions.count { it == "START" }
        val nextAtEnd = fixture.actions.count { it == "NEXT" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("空队列切歌只停止当前歌曲") {
            fixture.actions.count { it == "NEXT" } == nextAtEnd + 1 &&
                onMain { app.snapshot.optJSONObject("playback") == null && !app.playing && app.player.playbackState == Player.STATE_IDLE && app.pendingOperation == null }
        }
        SystemClock.sleep(500)
        assertEquals("没有下一首不能发送多余START", startsAtEnd, fixture.actions.count { it == "START" })
        assertEquals("空队列不能重新加载或重播上一首", resourcesAtEnd, playback.resourceRequests.size)
        assertNull("空队列切歌不打开确认框", onMain { app.pendingConfirm })
        screenshot("direct-next-empty-queue-stopped").recycle()
    }

    fun testGeneratedBackingMergesOnceThenUsesNativeAudioTracks() {
        playback.generatedBacking = true
        start("test-song-1")
        assertEquals("首次开唱只加载原文件的单条真实音轨", 1, onMain { app.playbackOptions.tracks.count { it.type == C.TRACK_TYPE_AUDIO } })
        assertEquals("已有生成伴奏应展示真实可用角色", "generated", onMain { app.playbackOptions.roleBacking })
        assertFalse("未选择伴奏时不能提前协商生成伴奏资源", playback.resourceRequests.any { it.optString("audioRole") == "backing" })
        waitFor("原曲时钟离开起点") { onMain { app.player.currentPosition >= 1600 } }
        focusAction("role:backing")
        val initialResources = playback.resourceRequests.size
        val attempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        val before = onMain { app.player.currentPosition }
        val started = SystemClock.elapsedRealtime()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("首次生成伴奏合并后选中真实derived音轨", 20000) {
            playback.resourceRequests.size == initialResources + 1 && onMain {
                app.player.playbackState == Player.STATE_READY && app.playing && app.activeAudioRole == "backing" &&
                    app.playbackOptions.tracks.count { it.type == C.TRACK_TYPE_AUDIO } == 2 &&
                    app.playbackOptions.tracks.any { it.type == C.TRACK_TYPE_AUDIO && it.selected && it.sourceId == "derived:backing" }
            }
        }
        assertEquals("首次合并只需一次伴奏资源协商", "backing", playback.resourceRequests.last().optString("audioRole"))
        assertEquals("首次合并不能创建新的演唱实例", attempt, onMain { app.snapshot.getJSONObject("playback").getString("attemptId") })
        val after = onMain { app.player.currentPosition }
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("首次合并保留原曲进度：$before → $after，耗时 $elapsed", after >= before - 150 && after <= before + elapsed + 1500)
        assertVisibleFocus()
        screenshot("generated-backing-first-real-merge").recycle()

        assertNativeRoleSwitch("backing", "derived:backing", paused = false)
        assertNativeRoleSwitch("original", "stream:0", paused = false)
        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("暂停合并媒体后验收原伴切换") { onMain { !app.playing && !app.player.playWhenReady } }
        assertNativeRoleSwitch("backing", "derived:backing", paused = true)
        assertNativeRoleSwitch("backing", "derived:backing", paused = true)
        assertNativeRoleSwitch("original", "stream:0", paused = true)
        assertEquals("合并后反复选择原唱伴奏均不再协商资源", initialResources + 1, playback.resourceRequests.size)
        screenshot("generated-backing-paused-native-original").recycle()
    }

    fun testDelayedNextResponseCannotAuthorizeANewerRemoteHandoff() {
        start("test-song-1", next = "test-song-2")
        onMain { app.command("ADD", songId = "test-song-3") }
        waitFor("三首真实队列中有两首待唱") { onMain {
            queuedSongCount(app) == 3 && app.snapshot.getJSONArray("queue").objects().count { it.optString("status") == "WAITING" } == 2 && app.pendingOperation == null
        } }
        val starts = fixture.actions.count { it == "START" }
        playback.holdNextResponse = true
        focusAction("next")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("本次NEXT响应已在服务器产生但尚未送达") { playback.heldNextResponse != null }
        val authorized = org.json.JSONObject(playback.heldNextResponse!!).getJSONObject("playback")
        assertEquals("本次明确点击只授权第二首", "test-song-2", authorized.getString("songId"))

        playback.remoteNext()
        onMain { app.reconnect() }
        waitFor("真实HTTP刷新先收到另一控制器产生的第三首HANDOFF") {
            onMain {
                val current = app.snapshot.optJSONObject("playback")
                current?.optString("songId") == "test-song-3" && !current.optBoolean("confirmed") &&
                    current.optString("attemptId") != authorized.optString("attemptId") && app.player.playbackState == Player.STATE_IDLE
            }
        }
        val remoteAttempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        playback.releaseNextResponse()
        waitFor("过期NEXT响应完成后保留较新的远端HANDOFF") {
            onMain { app.pendingOperation == null && !app.busy && app.snapshot.getJSONObject("playback").optString("attemptId") == remoteAttempt }
        }
        SystemClock.sleep(400)
        assertEquals("本次NEXT响应不能授权别的HANDOFF自动START", starts, fixture.actions.count { it == "START" })
        assertTrue("过期响应后第三首仍须明确开唱", onMain { !app.snapshot.getJSONObject("playback").optBoolean("confirmed") && !app.playing })
        assertEquals("未授权的第三首不能加载媒体资源", 0, playback.resourceRequests.count { it.optString("songId") == "test-song-3" })
        screenshot("delayed-next-keeps-remote-handoff-unconfirmed").recycle()
    }

    fun testFailedNextResourceRetryCannotReplayPreviousMedia() {
        start("test-song-1", next = "test-song-3")
        val previousAttempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        playback.unavailableResourceSongs.add("test-song-3")
        focusAction("next")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("下一首资源失败后保留确认的请求并停止旧媒体") {
            onMain { app.snapshot.optJSONObject("playback")?.optString("songId") == "test-song-3" &&
                app.snapshot.optJSONObject("playback")?.optBoolean("confirmed") == true &&
                app.error.contains("测试资源暂不可用") && !app.playing && app.player.playbackState == Player.STATE_IDLE && !app.busy }
        }
        val nextAttempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        assertTrue("下一首必须有独立演唱实例", nextAttempt != previousAttempt)
        assertFalse("资源尚未成功不能把旧媒体视为新实例已加载", onMain { app.hasConfirmedPlayback })
        val wrongPlayback = AtomicBoolean()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !app.hasConfirmedPlayback) wrongPlayback.set(true)
            }
        }
        onMain { app.player.addListener(listener) }
        try {
            val failures = playback.resourceRequests.count { it.optString("songId") == "test-song-3" }
            onMain { app.retryPlayback() }
            waitFor("重试再次协商当前歌曲资源，而非prepare旧歌曲") {
                playback.resourceRequests.count { it.optString("songId") == "test-song-3" } > failures && onMain { !app.busy }
            }
            SystemClock.sleep(400)
            assertFalse("失败重试期间不能瞬间播放上一首", wrongPlayback.get())
            assertFalse("资源仍不可用时必须保持停止", onMain { app.playing || app.player.playWhenReady })
            screenshot("failed-next-resource-does-not-replay-old-media").recycle()

            playback.unavailableResourceSongs.remove("test-song-3")
            onMain { app.retryPlayback() }
            waitFor("资源恢复后只加载当前实例并真实播放", 20000) {
                onMain { app.hasConfirmedPlayback && app.playing && app.player.playbackState == Player.STATE_READY &&
                    app.snapshot.getJSONObject("playback").getString("attemptId") == nextAttempt && app.currentSong?.optString("id") == "test-song-3" }
            }
            assertFalse("资源恢复前后都不能以旧实例开唱", wrongPlayback.get())
            screenshot("failed-next-resource-retry-current-song").recycle()
        } finally {
            onMain { app.player.removeListener(listener) }
        }
    }

    fun testConfirmedLoadReportsPreparingBeforeResourceAndKeepsClockWhenReady() {
        playback.holdResourceReadiness = true
        onMain { app.command("ADD", songId = "test-song-1") }
        waitFor("歌曲已入队") { onMain { app.pendingOperation == null && queuedSongCount(app) == 1 } }
        try {
            onMain { app.command("START") }
            waitFor("资源尚未就绪时已发送PREPARING") {
                playback.resourceRequests.isNotEmpty() && playback.playbackEvents.any { it.optString("state") == "PREPARING" }
            }
            val first = playback.playbackEvents.first { it.optString("state") == "PREPARING" }
            waitFor("资源未到达仍每15秒续准备租约", 20000) {
                playback.playbackEvents.any { it.optString("attemptId") == first.getString("attemptId") &&
                    it.optString("state") == "PREPARING" && it.optLong("elapsedMs") >= 15000 }
            }
            val preparing = playback.playbackEvents.filter { it.optString("attemptId") == first.getString("attemptId") }
            assertTrue(preparing.all { it.getLong("actualPlayedMs") == 0L && !it.has("lastActivityAt") && !it.has("durationMs") })
            assertTrue(preparing.all { it.getLong("startedAt") == first.getLong("startedAt") })
            assertFalse("准备心跳不能安装或播放尚未返回的媒体", onMain { app.hasConfirmedPlayback || app.playing })
        } finally { playback.holdResourceReadiness = false }
        waitFor("资源到达后同实例开始真实播放", 20000) { onMain { app.hasConfirmedPlayback && app.playing } }
        val preparing = playback.playbackEvents.first { it.optString("state") == "PREPARING" }
        waitFor("PLAYING沿用原始准备时钟") { playback.playbackEvents.any { it.optString("state") == "PLAYING" } }
        val playing = playback.playbackEvents.first { it.optString("state") == "PLAYING" }
        assertEquals(preparing.getString("attemptId"), playing.getString("attemptId"))
        assertEquals(preparing.getLong("startedAt"), playing.getLong("startedAt"))
        assertTrue(playing.getLong("elapsedMs") >= 15000)
    }

    fun testExpiredTelemetryStillCompletesAndContinuesTheQueue() {
        start("test-song-1", next = "test-song-2")
        val expired = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        playback.expiredActivityAttempts.add(expired)
        onMain { app.report() }
        waitFor("服务端已拒绝过期遥测") { playback.rejectedActivityEvents.any { it.optString("attemptId") == expired } }
        SystemClock.sleep(300)
        val rejected = playback.rejectedActivityEvents.size
        onMain { repeat(3) { app.report() } }
        SystemClock.sleep(400)
        assertEquals("410后停止当前实例遥测", rejected, playback.rejectedActivityEvents.size)
        onMain { app.player.seekTo(app.player.duration - 700) }
        waitFor("过期遥测不阻断自然结束与待唱续播", 20000) {
            onMain { app.snapshot.optJSONObject("playback")?.optString("songId") == "test-song-2" &&
                app.hasConfirmedPlayback && app.playing && app.pendingOperation == null }
        }
        assertEquals("COMPLETED", onMain { app.snapshot.getJSONArray("queue").getJSONObject(0).getString("status") })
        assertTrue(playback.playbackEvents.any { it.optString("attemptId") == expired && it.optString("state") == "ENDED" })
    }

    fun testRemoteStopInterruptsResourcePreparationAndLateResourceCannotStart() {
        playback.holdResourceResponse = true
        onMain { app.command("ADD", songId = "test-song-1") }
        waitFor("第一首已入队") { onMain { app.pendingOperation == null && queuedSongCount(app) == 1 } }
        onMain { app.command("ADD", songId = "test-song-2") }
        waitFor("第二首已入队") { onMain { app.pendingOperation == null && queuedSongCount(app) == 2 } }
        try {
            onMain { app.command("START") }
            waitFor("正在等待资源响应") { playback.heldResourceResponse != null }
            assertFalse("等待媒体不应占住全局操作", onMain { app.busy })
            playback.remoteNext()
            onMain { app.reconnect() }
            waitFor("资源仍被阻塞时即可处理远程STOP", 3000) {
                onMain { app.snapshot.optJSONObject("playback")?.optString("songId") == "test-song-2" && app.playerState == "IDLE" && !app.playing }
            }
        } finally { playback.holdResourceResponse = false; playback.releaseResourceResponse() }
        SystemClock.sleep(800)
        assertFalse("迟到资源不能自动播放下一首或旧媒体", onMain { app.playing || app.hasConfirmedPlayback })
        assertEquals("下一首仍需明确确认，不得擅自申请资源", 1, playback.resourceRequests.size)
        assertFalse(onMain { app.snapshot.getJSONObject("playback").getBoolean("confirmed") })
    }

    fun testPauseDuringResourcePreparationRemainsPausedWhenMediaArrives() {
        playback.holdResourceResponse = true
        onMain { app.command("ADD", songId = "test-song-1") }
        waitFor("歌曲已入队") { onMain { app.pendingOperation == null && queuedSongCount(app) == 1 } }
        try {
            onMain { app.command("START") }
            waitFor("等待媒体准备") { playback.heldResourceResponse != null }
            playback.remotePause()
            onMain { app.reconnect() }
            waitFor("等待媒体时已确认远程暂停", 3000) { playback.playbackEvents.any { event ->
                event.has("commandId") && onMain { app.snapshot.optJSONArray("commands")?.length() == 0 }
            } }
        } finally { playback.holdResourceResponse = false; playback.releaseResourceResponse() }
        waitFor("媒体准备完成且保持暂停", 15000) {
            onMain { app.hasConfirmedPlayback && app.player.playbackState == Player.STATE_READY && !app.player.playWhenReady && !app.playing }
        }
        assertEquals("暂停不能重复协商同一资源", 1, playback.resourceRequests.size)
    }

    fun testFailedResourceCanRetryAfterPauseAcknowledgedTheLoadCommand() {
        playback.unavailableResourceSongs.add("test-song-1")
        playback.holdResourceResponse = true
        onMain { app.command("ADD", songId = "test-song-1") }
        waitFor("歌曲已入队") { onMain { app.pendingOperation == null && queuedSongCount(app) == 1 } }
        try {
            onMain { app.command("START") }
            waitFor("等待失败资源响应") { playback.heldResourceResponse != null }
            playback.remotePause()
            onMain { app.reconnect() }
            waitFor("暂停累计确认已移除LOAD命令", 3000) { onMain { app.snapshot.optJSONArray("commands")?.length() == 0 } }
        } finally { playback.holdResourceResponse = false; playback.releaseResourceResponse() }
        waitFor("资源失败后保留当前确认实例") { onMain { app.error.contains("测试资源暂不可用") && app.playerState == "ERROR" && !app.hasConfirmedPlayback } }
        val attempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        playback.unavailableResourceSongs.remove("test-song-1")
        onMain { app.retryPlayback() }
        waitFor("LOAD已确认后仍可重试同一个实例", 15000) {
            onMain { app.hasConfirmedPlayback && app.playing && app.player.playbackState == Player.STATE_READY && app.error.isBlank() }
        }
        assertEquals("重试不能生成新的演唱实例", attempt, onMain { app.snapshot.getJSONObject("playback").getString("attemptId") })
        assertEquals("明确重试只再申请一次资源", 2, playback.resourceRequests.size)
    }

    fun testQualityChangeDuringPreparationKeepsCurrentAttemptAndPause() {
        playback.holdResourceResponse = true
        onMain { app.command("ADD", songId = "test-song-2") }
        waitFor("视频已入队") { onMain { app.pendingOperation == null && queuedSongCount(app) == 1 } }
        val attempt: String
        try {
            onMain { app.command("START") }
            waitFor("视频资源准备中") { playback.heldResourceResponse != null }
            attempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
            playback.remotePause()
            onMain { app.reconnect() }
            waitFor("准备期暂停已确认", 3000) { onMain { app.snapshot.optJSONArray("commands")?.length() == 0 } }
            onMain { app.changeQuality("720p") }
            waitFor("换画质只替换准备中的资源") { playback.resourceRequests.size == 2 }
            assertEquals("720p", playback.resourceRequests.last().getString("quality"))
        } finally { playback.holdResourceResponse = false; playback.releaseResourceResponse() }
        waitFor("换画质后仍绑定同一实例并保留暂停", 15000) {
            onMain { app.hasConfirmedPlayback && app.player.playbackState == Player.STATE_READY && !app.player.playWhenReady && !app.playing }
        }
        assertEquals(attempt, onMain { app.snapshot.getJSONObject("playback").getString("attemptId") })
        onMain { app.changeQuality("720p") }
        SystemClock.sleep(400)
        assertEquals("重复选择当前画质不重建资源", 2, playback.resourceRequests.size)
    }

    private fun assertLyricsBeforeFirstLine() {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("暂停后验证首句前状态") { onMain { !app.playing && app.player.playbackState == Player.STATE_READY } }
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("进度条取得焦点") { focused().endsWith(":player:progress") }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("遥控回到有效歌词首句前") {
            onMain { app.position < 100 && app.playbackOptions.sidecars.isNotEmpty() && app.playbackOptions.nextLine?.start == 2000L }
        }
        assertNull("有歌词但尚未到首句不应伪造当前句", onMain { app.playbackOptions.activeLine })
        assertFalse("首句前不能将元数据歌词误报为暂无歌词", visibleText("暂无歌词") || visibleText("没有可用歌词"))
        assertTrue("首句前仍应显示真实下一句歌词预览", visibleText("测试歌词第一句"))
        screenshot("audio-metadata-lyrics-before-first-line").recycle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("恢复播放至首句") { onMain { app.playing && app.position >= 2100 } }
    }

    private fun assertNativeRoleSwitch(role: String, source: String, paused: Boolean) {
        focusAction("role:$role")
        val resources = playback.resourceRequests.size
        val item = onMain { app.player.currentMediaItem }
        val attempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        val before = onMain { app.player.currentPosition }
        val started = SystemClock.elapsedRealtime()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("$role 原生选择真实 $source 音轨") {
            onMain { app.activeAudioRole == role && app.playbackOptions.tracks.any { it.type == C.TRACK_TYPE_AUDIO && it.selected && it.sourceId == source } }
        }
        SystemClock.sleep(400)
        assertEquals("已解码的双音轨切换不应重新申请媒体资源", resources, playback.resourceRequests.size)
        assertSame("原生切轨应保留当前媒体实例", item, onMain { app.player.currentMediaItem })
        assertEquals("原伴切换不能创建新的演唱实例", attempt, onMain { app.snapshot.getJSONObject("playback").getString("attemptId") })
        val after = onMain { app.player.currentPosition }
        if (paused) {
            assertFalse("暂停中切换原伴不能自行恢复播放", onMain { app.playing || app.player.playWhenReady })
            assertTrue("暂停中切轨不能跳动真实时钟：$before → $after", abs(after - before) <= 100)
        } else {
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("播放中切轨保留播放意图", onMain { app.player.playWhenReady })
            assertTrue("播放中切轨不能回零或跳跃：$before → $after，耗时 $elapsed", after >= before - 150 && after <= before + elapsed + 750)
            waitFor("原生切轨后真实播放继续前进") { onMain { app.playing && app.player.currentPosition > after + 200 } }
        }
        assertVisibleFocus()
    }

    fun testAudioWithoutLyricsDoesNotInventLyricsOrBackingTrack() {
        start("test-song-3")
        waitFor("无关联歌词已完成读取") { onMain { app.playbackOptions.textStatus.contains("没有可用") } }
        assertEquals(TvPlayerMode.AUDIO, onMain { app.playerMode })
        assertNull("没有歌词时当前句为空", onMain { app.playbackOptions.activeLine })
        assertNull("没有歌词时下一句为空", onMain { app.playbackOptions.nextLine })
        assertTrue("无歌词状态应明确显示", visibleText("暂无歌词") || visibleText("没有可用歌词") || visibleText("无歌词"))
        assertFalse("空队列不显示下一首卡片", visibleText("即将播放"))
        assertEquals("单音轨纯音频没有伴奏角色", "", onMain { app.playbackOptions.roleBacking })
        val backing = nodes().filter { !it.isEnabled && label(it).contains("伴奏") }
        assertTrue("没有伴奏时应显示不可用的分段选项", backing.isNotEmpty())
        assertFalse("不应为无伴奏歌曲请求伴奏资源", playback.resourceRequests.any { it.optString("audioRole") == "backing" })
        assertQr("audio-without-lyrics")
        assertVisibleFocus()
    }

    fun testAudioSynchronizationSettingsHideVideoQualityAndRestoreNestedFocus() {
        start("test-song-1")
        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("暂停音频后验收嵌套播放设置") { onMain { !app.playing } }
        focusAction("more")
        val playerOrigin = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("更多设置完成按钮取得焦点") { focused().startsWith("dialog:") && focused().endsWith(":action:完成") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("更多设置首项为音轨与声道") { focused().endsWith(":action:音轨与声道") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("音频模式显示歌词与声音同步") { focused().endsWith(":action:歌词与声音同步") }
        val moreOrigin = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("嵌套同步设置打开") { onMain { activity.focusMemory.activeDialogs == 2 } && focused().endsWith(":action:完成") }
        assertFalse("纯音频同步设置不显示视频画质", visibleText("视频画质") || visibleText("自动画质"))
        assertTrue("音频仍能调整真实歌词偏移", visibleText("外置歌词偏移"))
        assertVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭同步设置恢复更多来源项") { onMain { activity.focusMemory.activeDialogs == 1 } && focused() == moreOrigin }
        assertSettingActionInViewport("歌词与声音同步")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭更多恢复播放器来源控件") { onMain { activity.focusMemory.activeDialogs == 0 } && focused() == playerOrigin }
        assertVisibleFocus()
        assertEquals("仅浏览设置不能重新申请媒体", 1, playback.resourceRequests.size)
    }

    fun testPlayerQueueReorderAndRemoveRestoreTheRemainingQueueRow() {
        start("test-song-1", next = "test-song-2")
        onMain { app.command("ADD", songId = "test-song-3") }
        waitFor("当前演唱之外准备两首待唱") {
            onMain { app.pendingOperation == null && app.snapshot.getJSONArray("queue").objects().count { it.optString("status") == "WAITING" } == 2 }
        }
        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("暂停当前歌曲后调整待唱") { onMain { !app.playing } }
        val attempt = onMain { app.snapshot.getJSONObject("playback").getString("attemptId") }
        val first = onMain { app.snapshot.getJSONArray("queue").objects().first { it.optString("songId") == "test-song-2" }.getString("id") }
        val second = onMain { app.snapshot.getJSONArray("queue").objects().first { it.optString("songId") == "test-song-3" }.getString("id") }
        focusAction("queue")
        val playerOrigin = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("播放器队列作为第一层弹窗打开") { onMain { activity.focusMemory.activeDialogs == 1 } && focused().endsWith(":action:完成") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("进入第一首待唱行") { focused().endsWith(":action:queue:$first") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("进入第二首待唱行") { focused().endsWith(":action:queue:$second") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("排序操作作为第二层弹窗打开") { onMain { activity.focusMemory.activeDialogs == 2 } && focused().endsWith(":action:完成") }
        repeat(4) { if (!focused().endsWith(":action:queue:$second:UP")) key(KeyEvent.KEYCODE_DPAD_UP) }
        waitFor("上移操作可从遥控到达") { focused().endsWith(":action:queue:$second:UP") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("上移后仍回到移动的队列行") {
            onMain { activity.focusMemory.activeDialogs == 1 && app.pendingOperation == null &&
                app.snapshot.getJSONArray("queue").objects().filter { it.optString("status") == "WAITING" }.map { it.optString("id") } == listOf(second, first) } &&
                focused().endsWith(":action:queue:$second")
        }
        assertVisibleFocus()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("再次打开第一首待唱的操作弹窗") { onMain { activity.focusMemory.activeDialogs == 2 } && focused().endsWith(":action:完成") }
        repeat(3) { if (!focused().endsWith(":action:queue:$second:REMOVE")) key(KeyEvent.KEYCODE_DPAD_UP) }
        waitFor("移除操作可从遥控到达") { focused().endsWith(":action:queue:$second:REMOVE") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("移除后第一层弹窗恢复相邻的待唱行") {
            onMain { activity.focusMemory.activeDialogs == 1 && app.pendingOperation == null &&
                app.snapshot.getJSONArray("queue").objects().filter { it.optString("status") == "WAITING" }.map { it.optString("id") } == listOf(first) } &&
                focused().endsWith(":action:queue:$first")
        }
        assertVisibleFocus()
        assertEquals("待唱排序和移除不改变当前演唱实例", attempt, onMain { app.snapshot.getJSONObject("playback").getString("attemptId") })
        assertFalse("调整队列不能恢复暂停的媒体", onMain { app.playing })
        assertEquals("调整队列不能重新协商当前媒体", 1, playback.resourceRequests.size)
        assertTrue("排序和移除通过真实命令请求完成", fixture.actions.containsAll(listOf("UP", "REMOVE")))
        screenshot("player-queue-nested-remove-focus-restored").recycle()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭第一层队列恢复播放器入口") { onMain { activity.focusMemory.activeDialogs == 0 } && focused() == playerOrigin }
        assertVisibleFocus()
    }

    fun testVideoControlsAutoHideAndSettingsCategoriesRestoreFocus() {
        start("test-song-2")
        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("暂停视频以验收全部设置分类") { onMain { !app.playing && app.player.playbackState == Player.STATE_READY } }
        focusAction("lyrics")
        val settingsOrigin = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("播放设置默认完成按钮") { focused().startsWith("dialog:") && focused().endsWith(":action:完成") }
        val canvasScale = onMain {
            minOf(activity.window.decorView.width / 960f, activity.window.decorView.height / 540f)
        }
        val complete = nodes().first { it.isVisibleToUser && it.isClickable && label(it) == "完成" }
        val completeBounds = Rect().also { complete.getBoundsInScreen(it) }
        assertTrue("弹窗操作应沿用TV画布缩放，避免独立窗口把文字缩小：$completeBounds scale=$canvasScale",
            completeBounds.height() >= 35f * canvasScale)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键进入设置分类行") { focused().contains(":action:playback:tab:") }

        val categories = listOf(
            Triple("音轨", "playback:role:backing", "当前标记为伴奏"),
            Triple("歌词字幕", "playback:text:reload", "重新读取歌词 / 字幕"),
            Triple("声音输出", "playback:pitch:reset", "恢复原调"),
            Triple("同步", "playback:sync:reset", "恢复同步默认值"),
        )
        categories.forEachIndexed { index, (category, lastKey, lastCaption) ->
            val tabKey = ":action:playback:tab:$category"
            repeat(3) { if (!focused().endsWith(tabKey)) key(KeyEvent.KEYCODE_DPAD_LEFT) }
            // Native edge navigation may leave the tab row for the preceding Complete button.
            // Allow that extra focus step when searching right; stop at the actual target.
            repeat(6) { if (!focused().endsWith(tabKey)) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            waitFor("遥控聚焦$category 分类") { focused().endsWith(tabKey) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertVisibleFocus()
            if (category != "歌词字幕") assertFalse("$category 长列表末项不应从初始视口直接可见", nodes().any {
                it.isVisibleToUser && it.isClickable && label(it).contains(lastCaption)
            })
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor("$category 下键进入分类内容") { focused().contains(":action:playback:") && !focused().contains(":action:playback:tab:") }
            repeat(18) {
                if (!focused().endsWith(":action:$lastKey")) {
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    assertVisibleFocus()
                }
            }
            assertTrue("$category 可通过下键滚动至最后设置项", focused().endsWith(":action:$lastKey"))
            assertSettingActionInViewport(lastCaption)
            screenshot("video-settings-scrolled-$index").recycle()
            repeat(18) {
                if (!focused().contains(":action:playback:tab:")) {
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    assertVisibleFocus()
                }
            }
            assertTrue("$category 从列表上键可返回分类行", focused().contains(":action:playback:tab:"))
        }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("设置关闭恢复歌词字幕入口") { onMain { activity.focusMemory.activeDialogs == 0 } && focused() == settingsOrigin }
        assertVisibleFocus()

        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("恢复视频真实播放") { onMain { app.playing } }
        waitFor("无操作后视频控制栏自动隐藏", 12000) { onMain { !app.controlVisible } && focused().endsWith(":player") }
        assertEquals("隐藏控制栏后视频根目标仍持有唯一焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        screenshot("video-controls-auto-hidden").recycle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键唤回视频控制栏") { onMain { app.controlVisible } && focused().endsWith(":action:player:pause") }
        assertVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("首次返回收起视频控制栏") { onMain { app.page == "演唱" && !app.controlVisible } && focused().endsWith(":player") }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("第二次返回首页并恢复首页焦点") { onMain { app.page == "首页" } && focused() == "首页:nav:首页" }
        repeat(8) { if (!focused().contains(":bar:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(3) { if (!focused().endsWith(":bar:sing")) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue("首页返回演唱入口可由真实方向键访问", focused().endsWith(":bar:sing"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("首页返回同一视频播放器") { onMain { app.page == "演唱" && app.playerMode == TvPlayerMode.VIDEO } && focused().endsWith(":player") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认唤回并恢复播放控制焦点") { focused().endsWith(":action:player:pause") }
        assertVisibleFocus()
        screenshot("video-page-focus-restored").recycle()
    }

    private fun start(song: String, next: String? = null) {
        // Seed through the existing real HTTP command path, rather than setting Media3 or playback state.
        listOfNotNull(song, next).forEachIndexed { index, id ->
            onMain { app.command("ADD", songId = id) }
            waitFor("ADD测试歌曲$id") { onMain { queuedSongCount(app) == index + 1 && app.pendingOperation == null } }
        }
        onMain { app.command("START") }
        waitFor("真实Media3准备完成并开始播放", 20000) {
            onMain { app.page == "演唱" && app.player.playbackState == Player.STATE_READY && app.playing && app.duration >= TvPlaybackFixture.DURATION_MS - 100 }
        }
        if (!onMain { app.controlVisible }) key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("播放器初始播放控制焦点") { focused().endsWith(":action:player:pause") }
        assertEquals("资源请求应匹配待唱歌曲", song, playback.resourceRequests.first().getString("songId"))
        assertTrue("媒体请求必须由现有LOAD流程产生", fixture.actions.contains("START"))
    }

    private fun focusAction(control: String) {
        val target = ":action:player:$control"
        // The redesigned player has a real header action above the progress/control
        // rows. Leave it through Down before horizontal control navigation or seeking.
        repeat(3) {
            if (focused().endsWith(":action:player:return-catalog") || focused().endsWith(":player:progress") || focused().endsWith(":player")) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        repeat(14) { if (!focused().endsWith(target)) key(KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(14) { if (!focused().endsWith(target)) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFor("遥控聚焦$control") { focused().endsWith(target) }
        assertVisibleFocus()
    }

    private fun assertLyricSynchronization() {
        val timeline = TextTimeline.parse("lrc", TvPlaybackFixture.LYRICS)
        waitFor("歌词当前句和下一句与真实播放时钟同步") {
            onMain {
                val position = app.playbackOptions.lyricPosition
                app.playbackOptions.activeLine == TextTimeline.at(timeline, position) &&
                    app.playbackOptions.nextLine == timeline.firstOrNull { it.start > position }
            }
        }
        val lines = onMain { app.playbackOptions.activeLine?.text to app.playbackOptions.nextLine?.text }
        assertNotNull("当前歌词必须可见", lines.first)
        assertNotNull("下一句歌词必须预览", lines.second)
        waitFor("当前句和下一句同时显示") { visibleText(lines.first!!) && visibleText(lines.second!!) }
    }

    private fun assertSquareCovers() {
        waitFor("真实封面HTTP加载") { nodes().any { it.contentDescription?.toString() == "歌曲封面" && it.childCount == 0 } }
        nodes().filter { it.isVisibleToUser && it.childCount == 0 && it.contentDescription?.toString() == "歌曲封面" }.forEach {
            val bounds = Rect(); it.getBoundsInScreen(bounds)
            assertTrue("播放器封面容器保持1:1：$bounds", abs(bounds.width() - bounds.height()) <= 1)
        }
    }

    private fun assertWaitingQueueSong(title: String, waitingSongId: String, currentSongId: String) {
        val waiting = onMain { app.snapshot.getJSONArray("queue").objects().filter { it.optString("status") == "WAITING" } }
        assertEquals("当前演唱不能误算入待唱数量", 1, waiting.size)
        assertEquals("全场已点数量仍包括当前演唱与待唱", 2, onMain { queuedSongCount(app) })
        assertEquals("唯一待唱项对应真实下一首", listOf(waitingSongId), waiting.map { it.optString("songId") })
        assertFalse("正在演唱的歌曲不进入待唱列表", waiting.any { it.optString("songId") == currentSongId })
        waitFor("待唱队列实际显示下一首和正确数量") { visibleText(title) && visibleText("1 首待唱") }
        val row = nodes().firstOrNull { it.isVisibleToUser && it.isClickable && label(it).contains(title) }
        assertNotNull("下一首应有真实可操作待唱行", row)
        assertTrue("待唱编号从1开始，歌名歌手来自真实歌曲", Regex("(^|\\s)1(\\s|$)").containsMatchIn(label(row!!)) && label(row).contains("合成测试信号"))
        assertFalse("个人队列不展示点歌人表头", visibleText("点歌人"))
        assertFalse("个人队列不展示fixture的点歌人", visibleText("播放器验收测试") || visibleText("测试电视"))
        val rowBounds = Rect(); row.getBoundsInScreen(rowBounds)
        val covers = nodes().filter { it.isVisibleToUser && it.contentDescription?.toString() == "歌曲封面" }.filter {
            val bounds = Rect(); it.getBoundsInScreen(bounds)
            !bounds.isEmpty && rowBounds.contains(bounds)
        }
        assertTrue("每首待唱都显示真实封面组件", covers.isNotEmpty())
        covers.forEach {
            val bounds = Rect(); it.getBoundsInScreen(bounds)
            assertTrue("队列封面保持正方形：$bounds", abs(bounds.width() - bounds.height()) <= 1)
        }
        screenshot("player-queue-next-song-cover-no-owner").recycle()
    }

    private fun assertSettingActionInViewport(caption: String) {
        val action = nodes().firstOrNull { it.isVisibleToUser && it.isClickable && label(it).contains(caption) }
        assertNotNull("当前设置焦点必须位于可见UI：$caption", action)
        val bounds = Rect(); action!!.getBoundsInScreen(bounds)
        assertFalse("当前设置焦点不能是空区域", bounds.isEmpty)
        var ancestor = action.parent
        while (ancestor != null && !ancestor.isScrollable) ancestor = ancestor.parent
        assertNotNull("设置内容使用真实可滚动视口", ancestor)
        val viewport = Rect(); ancestor!!.getBoundsInScreen(viewport)
        assertTrue("焦点应随滚动完整进入设置视口：$bounds in $viewport", viewport.contains(bounds))
        assertVisibleFocus()
    }

    private fun assertQr(name: String) {
        waitFor("复用现有动态邀请") { onMain { app.invitation == fixture.invitationUrl && app.participantsKnown } }
        assertEquals("有效设备数量排除已撤销设备", 1, onMain { app.participantCount })
        val image = screenshot(name)
        try {
            val qr = nodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString() == "本场邀请二维码" }
            assertNotNull("播放器真实二维码应有可见边界", qr)
            val area = Rect().also { qr!!.getBoundsInScreen(it) }
            assertTrue("二维码完整位于原始截图内", Rect(0, 0, image.width, image.height).contains(area) && !area.isEmpty)
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val source = RGBLuminanceSource(image.width, image.height, pixels)
            val wholeFrame = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints) }.getOrNull()
            // Artwork elsewhere can distract ZXing's whole-image finder search. A retry
            // uses only the real QR bounds from this screenshot, with no pixel resizing.
            val decoded = wholeFrame ?: MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                source.crop(area.left, area.top, area.width(), area.height()))), hints)
            File(output, "$name-qr-decode.txt").writeText("wholeFrame=${wholeFrame != null}; originalQrBounds=$area; resized=false")
            assertEquals("播放器二维码必须能从最终原尺寸像素解出同一个动态邀请", fixture.invitationUrl, decoded.text)
        } finally { image.recycle() }
    }

    private fun assertVisibleFocus() {
        assertEquals("播放器只能有一个真实Compose遥控焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        val target = focused()
        waitFor("遥控焦点边框按共享焦点色完成绘制") {
            assertEquals("等待绘制时不能改变焦点", target, focused())
            val image = instrumentation.uiAutomation.takeScreenshot() ?: return@waitFor false
            val focusedNode = focusedNode()
            val area = Rect()
            focusedNode?.getBoundsInScreen(area)
            val margin = (4f * minOf(image.width / 960f, image.height / 540f)).toInt().coerceAtLeast(2)
            area.inset(-margin, -margin)
            val intersects = focusedNode != null && area.intersect(0, 0, image.width, image.height) && !area.isEmpty
            val visible = if (intersects) {
                val pixels = IntArray(area.width() * area.height())
                image.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
                val tint = onMain { Focus }
                pixels.count { abs(Color.red(it) - tint.red * 255) < 16 && abs(Color.green(it) - tint.green * 255) < 16 && abs(Color.blue(it) - tint.blue * 255) < 16 } > 60
            } else false
            image.recycle()
            visible
        }
        assertEquals("绘制后仍保持唯一遥控焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
    }

    private fun focused() = onMain { activity.focusMemory.focusedTargets.singleOrNull().orEmpty() }
    private fun key(code: Int) {
        val before = focused()
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(230)
        history.add("${KeyEvent.keyCodeToString(code)}: $before -> ${focused()} page=${onMain { app.page }} position=${onMain { app.position }} state=${onMain { app.playerState }}")
        File(output, "$name-keys.txt").writeText(history.joinToString("\n"))
    }

    private fun waitFor(description: String, timeout: Long = 8000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return
            SystemClock.sleep(70)
        }
        fail("超时：$description focus=${focused()} page=${onMain { app.page }} state=${onMain { app.playerState }} error=${onMain { app.error }}")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun focusedNode(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        // findFocus can retain a previous Compose node even after clearing its cache.
        // Traverse current window nodes and read their actual focus flags instead.
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { focused -> return focused } }
            return node.takeIf { it.isFocused && it.isVisibleToUser }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun visibleText(text: String) = nodes().any { it.isVisibleToUser && label(it).contains(text) }
    private fun state(node: AccessibilityNodeInfo) = if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty() else ""
    private fun label(node: AccessibilityNodeInfo): String = buildString {
        append(node.text?.toString().orEmpty()); append(' '); append(node.contentDescription?.toString().orEmpty())
        for (index in 0 until node.childCount) node.getChild(index)?.let { append(' '); append(label(it)) }
    }.trim()

    private fun screenshot(label: String): Bitmap = (instrumentation.uiAutomation.takeScreenshot() ?: error("需要模拟器截图")).also { image ->
        File(output, "$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
