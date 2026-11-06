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
                    "enabled=${it.isEnabled} clickable=${it.isClickable} visible=${it.isVisibleToUser} bounds=$bounds state=${state(it)} ${label(it)}"
                })
                File(output, "$name-keys.txt").writeText(history.joinToString("\n"))
                File(output, "$name-http.txt").writeText(fixture.httpEvents.joinToString("\n"))
                onMain { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            if (::fixture.isInitialized) fixture.close()
            super.tearDown()
        }
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
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("遥控重唱创建新播放实例并从头真实播放", 20000) {
            onMain {
                app.snapshot.optJSONObject("playback")?.optString("attemptId") != originalAttempt &&
                    app.player.playbackState == Player.STATE_READY && app.playing && app.position in 0L..2000L
            } && playback.resourceRequests.count { it.optString("songId") == "test-song-1" } > originalResources
        }
        assertTrue("重唱须通过真实RESTART命令", fixture.actions.contains("RESTART"))
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
        waitFor("遥控聚焦已点导航") { focused() == "首页:nav:已点" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("打开完整待唱队列") { onMain { app.page == "已点" } }
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

    fun testVideoControlsAutoHideAndSettingsCategoriesRestoreFocus() {
        start("test-song-2")
        focusAction("pause")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("暂停视频以验收全部设置分类") { onMain { !app.playing && app.player.playbackState == Player.STATE_READY } }
        focusAction("lyrics")
        val settingsOrigin = focused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("播放设置默认完成按钮") { focused().startsWith("dialog:") && focused().endsWith(":action:完成") }
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
        if (focused().endsWith(":player:progress")) key(KeyEvent.KEYCODE_DPAD_DOWN)
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
        assertTrue("待唱编号从01开始，歌名歌手来自真实歌曲", label(row!!).contains("01") && label(row).contains("合成测试信号"))
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
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        assertEquals("播放器二维码必须能从最终像素解出同一个动态邀请", fixture.invitationUrl, decoded.text)
        image.recycle()
    }

    private fun assertVisibleFocus() {
        assertEquals("播放器只能有一个真实Compose遥控焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        val target = focused()
        waitFor("白色遥控焦点完成绘制") {
            assertEquals("等待绘制时不能改变焦点", target, focused())
            val image = instrumentation.uiAutomation.takeScreenshot() ?: return@waitFor false
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val visible = pixels.count { abs(Color.red(it) - 245) < 12 && abs(Color.green(it) - 245) < 12 && abs(Color.blue(it) - 247) < 12 } > 100
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
