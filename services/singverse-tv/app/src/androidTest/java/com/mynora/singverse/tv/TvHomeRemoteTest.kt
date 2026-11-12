@file:Suppress("DEPRECATION")

package com.mynora.singverse.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File

/** D-pad checks run against production Compose UI; fixtures never enter the production source set. */
class TvHomeRemoteTest : InstrumentationTestCase() {
    private lateinit var fixture: TvUiFixture
    private lateinit var activity: TvUiFixtureActivity
    private val keyHistory = mutableListOf<String>()
    private val app get() = activity.app
    private val output get() = File(instrumentation.targetContext.getExternalFilesDir(null), "tv-ui-checks").apply { mkdirs() }

    override fun setUp() {
        super.setUp()
        fixture = TvUiFixture()
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = info
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("tv-ui-fixture", fixture.base)
        }) as TvUiFixtureActivity
        waitFor("首页初始遥控焦点") { focusedLabel().contains("首页") }
        waitFor("首页真实热歌与历史投影") { onMain { app.homeRecommendationsAvailable && app.homePopularSongs.isNotEmpty() && app.homeHistorySongs.isNotEmpty() && !app.busy } }
        keyHistory.add("初始 ${focusDetails()}")
    }

    override fun tearDown() {
        try {
            if (::activity.isInitialized) {
                screenshot("${name}-final")
                File(output, "${name}-tree.txt").writeText(allNodes().joinToString("\n") {
                    val rect = Rect(); it.getBoundsInScreen(rect)
                    "focused=${it.isFocused} selected=${it.isSelected} state=${stateDescription(it)} focusable=${it.isFocusable} clickable=${it.isClickable} visible=${it.isVisibleToUser} bounds=$rect ${nodeLabel(it)}"
                })
                File(output, "${name}-keys.txt").writeText(keyHistory.joinToString("\n"))
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            if (::fixture.isInitialized) fixture.close()
            super.tearDown()
        }
    }

    fun testHomePrimaryAndDetailActionsKeepTheirDistinctRoles() {
        val add = navigationBounds("加入待唱")
        val detail = navigationBounds("歌曲详情")
        val idle = screenshot("home-primary-and-detail-unfocused")
        assertTrue("推荐主操作使用浅底", colorPixels(idle, 242, 243, 242, add) > add.width() * add.height() / 2)
        assertTrue("详情操作保持深色", colorPixels(idle, 42, 46, 50, detail) > detail.width() * detail.height() / 2)
        idle.recycle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("下键进入推荐主操作") { focusedAddress() == "首页:featured:add" }
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键进入歌曲详情操作") { focusedAddress() == "首页:featured:sing" }
        assertSingleVisibleFocus()
        assertTrue("移动焦点不能发出播放或点歌命令", fixture.commandRequests.isEmpty())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("详情操作只打开歌曲详情") { onMain { app.selected?.optString("id") == "test-song-1" } }
        assertTrue("查看详情不能加入队列", fixture.commandRequests.isEmpty())
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情返回恢复推荐详情操作") { focusedAddress() == "首页:featured:sing" }
    }

    fun testHomeFeedEntriesOpenListsAndRestoreSourceFocus() {
        assertFalse("首页已移除四快捷入口", allNodes().any { nodeLabel(it) == "快捷点歌" })
        assertFalse("首页已移除曲库横排", allNodes().any { nodeLabel(it) == "我的曲库" })
        listOf("hot:all" to "热歌", "history:all" to "历史").forEach { (entry, page) ->
            focusTarget("首页:$entry")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("打开$page 真实列表") { onMain { app.page == page && !app.feedLoading && app.feedTotal > 0 } }
            assertEquals("读取第一页", 0, onMain { app.feedPage })
            assertTrue("列表读取设备授权接口", fixture.feedRequests.any { it.startsWith(if (page == "热歌") "/api/v1/tv/popular?" else "/api/v1/tv/playback-history?") })
            key(KeyEvent.KEYCODE_BACK)
            waitFor("$page 返回首页来源入口") { onMain { app.page == "首页" } && focusedAddress() == "首页:$entry" }
            assertSingleVisibleFocus()
        }
        assertTrue("只浏览列表不能发出点歌命令", fixture.commandRequests.isEmpty())
    }

    fun testHomeArtistsKeepFullLineHeightAndVisibleGlyphs() {
        waitFor("首页歌曲和封面加载") { onMain { app.homePopularSongs.size >= 4 } && visibleCoverImages().size >= 5 }
        val songs = onMain { app.homePopularSongs.take(4) }
        val image = screenshot("home-complete-artist-lines")
        val textPaint = screenTextPaint(12f, image)
        songs.forEach { song ->
            val artist = allNodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == song.artists() }
            assertNotNull("可见曲目应保留歌手文本：${song.artists()}", artist)
            val bounds = Rect().also { artist!!.getBoundsInScreen(it) }
            val glyphs = Rect().also { textPaint.getTextBounds(song.artists(), 0, song.artists().length, it) }
            assertTrue("歌手必须容纳完整字形高度：${song.artists()} $bounds", bounds.height() >= glyphs.height())
            val lowerHalf = Rect(bounds.left, bounds.centerY(), bounds.right, bounds.bottom)
            assertTrue("歌手字形下半部必须实际绘制", readableTextPixels(image, lowerHalf) > 3)
        }
        image.recycle()
    }

    fun testNavigationSwitchesOnFocusAndKeepsSearchInputUsable() {
        val bounds = navigationBounds("点歌")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键直接进入点歌搜索并保留导航焦点") { onMain { app.page == "点歌" && app.songs.isNotEmpty() } && focusedAddress() == "点歌:nav:点歌" }
        assertEquals("当前导航同步选中", "当前页面", stateDescription(allNodes().first { it.isClickable && nodeLabel(it) == "点歌" }))
        val image = screenshot("navigation-focus-selects-page")
        assertTrue("当前导航保持深色容器", colorPixels(image, 42, 46, 50, bounds) > 100)
        val focusBounds = Rect((bounds.left - 12).coerceAtLeast(0), (bounds.top - 12).coerceAtLeast(0),
            (bounds.right + 12).coerceAtMost(image.width), (bounds.bottom + 12).coerceAtMost(image.height))
        assertTrue("导航有可见蓝色焦点描边", focusPixels(image, focusBounds) > 100)
        image.recycle()
        assertTrue("点歌侧栏保留四种真实找法", listOf("搜索歌曲", "歌手点歌", "分类点歌", "共享歌单").all { text -> allNodes().any { it.isClickable && nodeLabel(it) == text } })
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(4) { if (!focusedAddress().endsWith(":action:歌名 / 歌手 / 拼音 / 编号")) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFor("方向键进入搜索输入行") { focusedAddress().endsWith(":action:歌名 / 歌手 / 拼音 / 编号") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认进入编辑输入框") { focusedAddress().contains(":input:") }
        instrumentation.sendStringSync("remote")
        waitFor("输入进入真实搜索状态") { onMain { app.query == "remote" } }
        screenshot("search-input-editing").recycle()
        key(KeyEvent.KEYCODE_BACK)
        if (focusedAddress().contains(":input:")) key(KeyEvent.KEYCODE_BACK)
        waitFor("结束编辑恢复输入行") { focusedAddress().endsWith(":action:remote") }
        assertEquals("退出键盘不能离开搜索页", "点歌", onMain { app.page })
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("搜索直接返回首页") { onMain { app.page == "首页" } && focusedAddress() == "首页:nav:首页" }
        assertSingleVisibleFocus()
    }

    fun testTopNavigationSwitchesWithoutConfirmAndRetainsFocus() {
        listOf("点歌", "已点", "设置", "已点", "点歌", "首页").forEachIndexed { index, destination ->
            key(if (index < 3) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            waitFor("方向键直接切换至$destination") { onMain { app.page == destination && !activity.focusMemory.restoring } && focusedAddress() == "$destination:nav:$destination" }
            assertTrue("页面选中状态同步导航", allNodes().any { it.isClickable && nodeLabel(it).startsWith(destination) && stateDescription(it) == "当前页面" })
            assertSingleVisibleFocus()
        }
        repeat(2) {
            repeat(3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); SystemClock.sleep(70) }
            waitFor("快速连续右键仍停留设置导航") { onMain { app.page == "设置" && !activity.focusMemory.restoring } && focusedAddress() == "设置:nav:设置" }
            repeat(3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); SystemClock.sleep(70) }
            waitFor("快速连续左键仍停留首页导航") { onMain { app.page == "首页" && !activity.focusMemory.restoring } && focusedAddress() == "首页:nav:首页" }
        }
        assertTrue("浏览导航不能发出点歌或播放命令", fixture.commandRequests.isEmpty())
        waitFor("连续切换的在途读取已完成") { onMain { !app.busy } }
        val requests = fixture.httpEvents.count { it == "begin GET /api/v1/tv/home" }
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals("当前导航重复确认不能重复加载或抢走焦点", requests, fixture.httpEvents.count { it == "begin GET /api/v1/tv/home" })
        assertEquals("首页:nav:首页", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("切换后下键仍进入页面内容") { focusedAddress().startsWith("首页:featured:") }
        assertSingleVisibleFocus()
    }

    fun testCategoryTabsSwitchOnFocusAndRestoreWithoutNavigating() {
        onMain { app.navigate("分类") }
        waitFor("分类页面恢复导航焦点不跳回点歌") { onMain { app.page == "分类" && !app.busy && !activity.focusMemory.restoring } && focusedAddress() == "分类:nav:点歌" }
        val navigation = focusedAddress()
        onMain { app.error = "测试导航恢复" }
        waitFor("打开操作提示") { onMain { activity.focusMemory.activeDialogs == 1 } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭提示恢复分类页导航，不自动进入点歌中心") { onMain { app.page == "分类" && activity.focusMemory.activeDialogs == 0 } && focusedAddress() == navigation }
        val dimensions = listOf("语种", "年代", "风格", "情绪", "场景", "自定义")
        repeat(4) { if (focusedAddress().removePrefix("分类:action:") !in dimensions) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(5) { if (focusedAddress() != "分类:action:语种") key(KeyEvent.KEYCODE_DPAD_LEFT) }
        val requests = fixture.songRequests.size
        dimensions.forEachIndexed { index, label ->
            if (index > 0) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("焦点进入$label 后直接切换分类内容") {
                focusedAddress() == "分类:action:$label" && allNodes().any { nodeLabel(it).contains(when (label) { "语种" -> "国语"; "风格" -> "流行"; else -> "暂无${label}分类" }) }
            }
            assertEquals("切换维度仍留在分类页面", "分类", onMain { app.page })
            assertSingleVisibleFocus()
        }
        assertEquals("浏览维度不能打开歌曲结果", requests, fixture.songRequests.size)
        assertTrue("浏览维度不能修改已点队列", fixture.commandRequests.isEmpty())
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        waitFor("左键直接回到风格分类") { focusedAddress() == "分类:action:风格" && allNodes().any { nodeLabel(it).contains("流行") } }
        onMain { app.error = "测试分类焦点恢复" }
        waitFor("分类标签上打开提示") { onMain { activity.focusMemory.activeDialogs == 1 } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭提示保留原分类标签和内容") { focusedAddress() == "分类:action:风格" && onMain { app.page == "分类" } && allNodes().any { nodeLabel(it).contains("流行") } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("维度切换后下键进入当前分类") { focusedAddress() == "分类:category:tag-genre" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认分类卡片仍直接进入歌曲") { onMain { app.page == "找歌" && app.selectedTags == setOf("tag-genre") } && focusedAddress() == "找歌:song:test-song-1" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("歌曲返回仍保留风格维度和来源卡片") { focusedAddress() == "分类:category:tag-genre" }
        assertSingleVisibleFocus()
    }

    fun testCatalogSidebarEntriesRestoreTheirRemoteFocus() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("进入默认搜索页") { onMain { app.page == "点歌" } && focusedAddress() == "点歌:nav:点歌" }
        focusTarget("点歌:catalog:点歌")
        assertSingleVisibleFocus()
        listOf("歌手", "分类", "歌单").forEach { page ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val source = "点歌:catalog:$page"
            waitFor("方向键选中$page 找法") { focusedAddress() == source }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("确认进入$page") { onMain { app.page == page && !app.busy } }
            assertTrue("$page 保留手机点歌入口", allNodes().any { it.isVisibleToUser && it.isClickable && nodeLabel(it) == "手机点歌" })
            assertSingleVisibleFocus()
            screenshot("catalog-entry-$page").recycle()
            key(KeyEvent.KEYCODE_BACK)
            waitFor("$page 返回搜索并恢复原侧栏") { onMain { app.page == "点歌" } && focusedAddress() == source }
            assertSingleVisibleFocus()
        }
        assertTrue("浏览找法不能创建点歌或播放命令", fixture.commandRequests.isEmpty())
    }

    fun testHomeSongTilesRestoreFocusAfterDetailAndDialog() {
        focusTarget("首页:hot:test-song-1")
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT); assertSingleVisibleFocus() }
        assertEquals("热歌横向选择到第四首", "首页:hot:test-song-4", focusedAddress())
        val original = focusedAddress()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("热歌卡片确认打开详情") { onMain { app.selected?.optString("id") == "test-song-4" } && focusedAddress().endsWith(":action:加入待唱") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("详情横向可达返回") { focusedAddress().endsWith(":action:返回") }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("详情可返回主操作") { focusedAddress().endsWith(":action:加入待唱") }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情返回恢复原热歌卡片") { onMain { app.selected == null } && focusedAddress() == original }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("首页返回打开确认弹窗") { onMain { app.pendingConfirm != null } && focusedLabel().contains("取消") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("弹窗方向键可到确认", focusedLabel().contains("确认"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭弹窗恢复原热歌卡片") { onMain { app.pendingConfirm == null } && focusedAddress() == original }
        focusTarget("首页:history:test-song-2")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("首页历史卡片确认同样打开详情") { onMain { app.selected?.optString("id") == "test-song-2" } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("历史详情返回恢复来源卡片") { focusedAddress() == "首页:history:test-song-2" }
        assertTrue("首页卡片看详情不能点歌", fixture.commandRequests.isEmpty())
        assertSingleVisibleFocus()
    }

    fun testNestedErrorDialogRestoresPhoneDialogAndHomeFocus() {
        waitFor("手机邀请已就绪") { onMain { app.invitation == fixture.invitationUrl } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(6) {
            if (!focusedAddress().endsWith(":phone")) key(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        assertTrue("首页手机点歌卡片可通过方向键到达", focusedAddress().endsWith(":phone"))
        val phoneCardAddress = focusedAddress()
        assertSingleVisibleFocus()

        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("手机点歌弹窗获得唯一焦点") {
            onMain { activity.focusMemory.activeDialogs == 1 } && focusedAddress().endsWith(":手机点歌:action:完成")
        }
        // Move away from the default so restoring the underlying dialog must retain its history.
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("手机弹窗聚焦刷新邀请") { focusedAddress().endsWith(":手机点歌:action:刷新邀请") }
        val phoneDialogAddress = focusedAddress()
        assertSingleVisibleFocus()
        screenshot("phone-dialog-before-async-error")

        onMain { app.error = "测试网络操作失败" }
        waitFor("异步错误覆盖手机弹窗") {
            onMain { activity.focusMemory.activeDialogs == 2 } && focusedAddress().endsWith(":操作提示:action:关闭提示")
        }
        assertSingleVisibleFocus()
        screenshot("phone-dialog-with-top-error")

        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭错误后恢复手机弹窗原焦点") {
            onMain { app.error.isEmpty() && activity.focusMemory.activeDialogs == 1 } && focusedAddress() == phoneDialogAddress
        }
        assertSingleVisibleFocus()
        screenshot("phone-dialog-restored-after-error")

        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭手机弹窗后恢复首页手机卡片") {
            onMain { activity.focusMemory.activeDialogs == 0 && app.page == "首页" && app.pendingConfirm == null } && focusedAddress() == phoneCardAddress
        }
        assertSingleVisibleFocus()
        screenshot("home-phone-focus-restored")
    }

    fun testRenderedInvitationQrDecodesAndRevokedDeviceIsNotCounted() {
        waitFor("动态邀请和参与者同步") { onMain { app.invitation == fixture.invitationUrl && app.participantsKnown } }
        assertEquals("已撤销设备不能算作已加入", 1, onMain { app.participantCount })
        waitFor("真实图片 HTTP 加载封面") { visibleCoverImages().size >= 5 }
        visibleCoverImages().forEach { cover ->
            val rect = Rect(); cover.getBoundsInScreen(rect)
            assertTrue("横图、竖图和方图原件均按 1:1 显示：$rect", kotlin.math.abs(rect.width() - rect.height()) <= 1)
        }
        assertFalse("首页只留邀请入口，不常驻二维码", allNodes().any { it.contentDescription?.toString() == "本场邀请二维码" })
        focusTarget("首页:phone")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("打开真实动态二维码弹窗") { onMain { activity.focusMemory.activeDialogs == 1 } && allNodes().any { it.contentDescription?.toString() == "本场邀请二维码" } }
        val image = screenshot("phone-modal-dynamic-qr")
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val result = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        )
        assertEquals("从最终弹窗像素解码为本次动态邀请 URL", fixture.invitationUrl, result.text)
        image.recycle()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("邀请关闭恢复首页入口") { focusedAddress() == "首页:phone" }
        assertEquals("空队列数量", 0, onMain { queuedSongCount(app) })
        assertTrue("空队列保留底栏入口", allNodes().any { nodeLabel(it).contains("已点歌曲 (0)") })
        focusTarget("首页:featured:add")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("推荐主操作真实 ADD 更新队列") { fixture.actions.contains("ADD") && onMain { queuedSongCount(app) == 1 } }
        assertTrue("推荐点歌不自动开唱", fixture.actions.none { it == "START" } && onMain { app.page == "首页" })
        focusTarget("首页:bar:queue")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("底栏进入真实已点页面") { onMain { app.page == "已点" } }
        assertEquals("已点继续保留刚加入的歌曲", 1, onMain { queuedSongCount(app) })
        screenshot("queue-added-song").recycle()
    }

    fun testSettingsGroupsKeepRemoteFocusAndRealParticipantState() {
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFor("进入设置") { onMain { app.page == "设置" } }
        onMain { activity.focusMemory.targets["设置:action:连接"]?.requestFocus() }
        waitFor("聚焦连接分组") { focusedAddress().endsWith(":action:连接") }
        assertTrue("参与者数据来自隔离快照", onMain { app.participantCount == 1 })
        screenshot("settings-connection")

        listOf(
            "播放设置" to "可用音轨",
            "聚会管理" to "亲友点歌",
            "本场参与者" to "测试亲友",
            "设备信息" to "Android",
            "应用更新" to "检查更新",
        ).forEach { (group, content) ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor("聚焦$group") { focusedAddress().endsWith(":action:$group") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("显示$group 内容") { allNodes().any { it.isVisibleToUser && nodeLabel(it).contains(content) } }
            assertSingleVisibleFocus()
            screenshot("settings-$group")
        }
    }

    fun testArtistAndPlaylistDetailsRestoreCardFocus() {
        onMain { app.navigate("歌手") }
        waitFor("真实歌手列表与来源导航恢复") {
            onMain { app.artists.isNotEmpty() && app.page == "歌手" && !app.busy && !activity.focusMemory.restoring } &&
                focusedAddress() == "歌手:nav:点歌"
        }
        repeat(4) { if (!focusedAddress().contains(":artist:test-artist")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFor("聚焦歌手卡片") { focusedAddress().contains(":artist:test-artist") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("打开歌手详情") { onMain { app.artistDetail?.optString("id") == "test-artist" } && focusedAddress().endsWith(":song:test-song-1") }
        assertTrue("歌手详情包含真实歌曲", allNodes().any { nodeLabel(it).contains("测试歌曲 01") })
        screenshot("artist-detail")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回歌手卡片") { onMain { app.artistDetail == null } && focusedAddress().contains(":artist:test-artist") }
        assertSingleVisibleFocus()

        onMain { app.navigate("歌单") }
        waitFor("真实歌单列表与来源导航恢复") {
            onMain { app.playlists.isNotEmpty() && app.page == "歌单" && !app.busy && !activity.focusMemory.restoring } &&
                focusedAddress() == "歌单:nav:点歌"
        }
        repeat(4) { if (!focusedAddress().contains(":playlist:test-playlist")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFor("聚焦歌单卡片") { focusedAddress().contains(":playlist:test-playlist") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("打开歌单详情") { onMain { app.playlistDetail?.optString("id") == "test-playlist" } && focusedAddress().endsWith(":song:test-song-1") }
        assertTrue("歌单详情包含真实歌曲", allNodes().any { nodeLabel(it).contains("测试歌曲 01") })
        screenshot("playlist-detail")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回歌单卡片") { onMain { app.playlistDetail == null } && focusedAddress().contains(":playlist:test-playlist") }
        assertSingleVisibleFocus()
    }

    fun testQueueRowActionsAreReachableWithRemote() {
        onMain { app.command("ADD", songId = "test-song-1") }
        waitFor("加入第一首") { onMain { queuedSongCount(app) == 1 && app.pendingOperation == null } }
        onMain { app.command("ADD", songId = "test-song-2") }
        waitFor("加入第二首") { onMain { queuedSongCount(app) == 2 && app.pendingOperation == null } }
        onMain { app.navigate("已点") }
        focusTarget("已点:action:queue:test-queue-0")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("调整弹窗默认安全焦点为完成") { focusedAddress().endsWith(":调整待唱:action:完成") }
        assertFalse("首首待唱不显示无效置顶与上移", allNodes().any { it.isClickable && nodeLabel(it) in listOf("置顶", "上移") })
        repeat(3) { if (!focusedAddress().endsWith(":action:queue:test-queue-0:DOWN")) key(KeyEvent.KEYCODE_DPAD_UP) }
        waitFor("方向键可聚焦下移") { focusedAddress().endsWith(":action:queue:test-queue-0:DOWN") }
        assertSingleVisibleFocus()
        screenshot("queue-row-actions").recycle()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("下移通过真实命令并返回来源行") { fixture.actions.contains("DOWN") && focusedAddress() == "已点:action:queue:test-queue-0" }
        assertEquals("下移真实改变待唱顺序", "test-queue-0", onMain { app.snapshot.getJSONArray("queue").getJSONObject(1).getString("id") })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("再次打开调整弹窗") { focusedAddress().endsWith(":调整待唱:action:完成") }
        assertFalse("末首不显示无效下移", allNodes().any { it.isClickable && nodeLabel(it) == "下移" })
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("可聚焦移除") { focusedAddress().endsWith(":action:queue:test-queue-0:REMOVE") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("移除后焦点恢复邻行") { onMain { queuedSongCount(app) == 1 } && focusedAddress() == "已点:action:queue:test-queue-1" }
        assertSingleVisibleFocus()
    }

    fun testSongTablesAddDirectlyAcrossAllFourEntryPaths() {
        (0..3).forEach { entry ->
            enterSongTable(entry)
            repeat(entry) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            val address = focusedAddress()
            val songId = address.substringAfterLast(":song:")
            val before = songRowBounds(songId)
            val count = onMain { queuedSongCount(app) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("第${entry + 1}条找歌路径直接入队") { onMain { queuedSongCount(app) == count + 1 && app.queueAddSongId.isEmpty() } }
            assertEquals("入队应保留原歌曲焦点", address, focusedAddress())
            assertEquals("入队不能改变列表滚动位置", before, songRowBounds(songId))
            assertTrue("列表加歌不能先打开歌曲详情", onMain { app.selected == null })
            assertTrue("应显示真实成功反馈", onMain { app.queueAddMessage.startsWith("已加入队列：") })
            assertEquals("命令绑定当前行歌曲", songId, fixture.commandRequests.last().optString("songId"))
            assertTrue("已点状态应直接出现在行内", nodeLabel(songRow(songId)).contains("已点 ×1"))
            assertSingleVisibleFocus()
            screenshot("song-table-entry-$entry-added").recycle()
        }
        assertEquals("四条路径各发一次 ADD", listOf("ADD", "ADD", "ADD", "ADD"), fixture.actions.toList())
        assertFalse("直接点歌不能请求歌曲详情", fixture.httpEvents.any {
            it.matches(Regex("begin GET /api/v1/tv/library/songs/test-song-\\d+"))
        })
    }

    fun testCategoryConfirmOpensSongsAndReplacesPreviousFilters() {
        onMain {
            app.query = "之前的关键词"
            app.selectedTags = setOf("old-language", "old-genre")
            app.unclassified = true
            app.number = 2
        }
        enterSongTable(2)
        assertEquals("确认分类直接聚焦首首歌曲", "找歌:song:test-song-1", focusedAddress())
        assertTrue("分类结果保留返回来源", onMain { app.categoryResults })
        assertEquals("当前分类替换以前的复合筛选", setOf("tag-language"), onMain { app.selectedTags })
        assertEquals("分类不沿用旧搜索词", "", onMain { app.query })
        assertFalse("普通分类关闭未分类筛选", onMain { app.unclassified })
        assertEquals("新分类从第一页开始", 0, onMain { app.number })
        val request = android.net.Uri.parse(fixture.songRequests.last())
        assertEquals(listOf("tag-language"), request.getQueryParameters("tagIds"))
        assertEquals("", request.getQueryParameter("q"))
        assertEquals("false", request.getQueryParameter("unclassified"))
        assertSingleVisibleFocus()
        screenshot("category-confirm-direct-song-table").recycle()

        key(KeyEvent.KEYCODE_BACK)
        waitFor("结果返回原分类卡片") { onMain { app.page == "分类" } && focusedAddress() == "分类:category:tag-language" }
        assertFalse("不再保留查看歌曲的额外步骤", allNodes().any { it.isClickable && nodeLabel(it).startsWith("查看歌曲") })
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("再次确认同一分类仍直接打开歌曲") { focusedAddress() == "找歌:song:test-song-1" && onMain { !app.busy } }
        assertEquals(setOf("tag-language"), onMain { app.selectedTags })
    }

    fun testCategoryReturnRestoresDimensionAndScrolledCard() {
        fixture.extraGenreTags = 23
        onMain { app.navigate("分类") }
        waitFor("多行分类词表加载") { onMain { app.tags.size == 25 && !app.busy && activity.focusMemory.view == "分类" && !activity.focusMemory.restoring } }
        val dimensions = setOf("语种", "年代", "风格", "情绪", "场景", "自定义")
        repeat(4) { if (focusedAddress().removePrefix("分类:action:") !in dimensions) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(5) { if (focusedAddress() != "分类:action:语种") key(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertEquals("分类:action:语种", focusedAddress())
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("分类:action:风格", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("风格卡片可用") { focusedAddress().startsWith("分类:category:tag-genre") }
        screenshot("category-compact-first-two-rows").recycle()
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        val original = focusedAddress()
        assertEquals("须滚到屏外的第五行分类", "分类:category:tag-genre-11", original)
        assertSingleVisibleFocus()
        assertCategoryFocusOutlineComplete("tag-genre-11")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("屏外分类直接进入歌曲") { focusedAddress() == "找歌:song:test-song-1" && onMain { !app.busy } }
        assertEquals(setOf("tag-genre-11"), onMain { app.selectedTags })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("分类歌曲右键查看详情") { onMain { app.selected != null } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情先返回当前分类歌曲行") { onMain { app.selected == null && app.page == "找歌" } && focusedAddress() == "找歌:song:test-song-1" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回保留风格维度与屏外分类焦点") { onMain { app.page == "分类" && !app.busy } && focusedAddress() == original }
        assertSingleVisibleFocus()
        assertCategoryFocusOutlineComplete("tag-genre-11")
        screenshot("category-return-scrolled-genre").recycle()

        fixture.songCount = 0
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("空分类直接进入空结果") { onMain { app.page == "找歌" && app.songs.isEmpty() && !app.busy } }
        assertTrue("空分类有结果提示", allNodes().any { nodeLabel(it).contains("没有找到歌曲") })
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("空结果仍返回原分类") { focusedAddress() == original }
        repeat(5) { if (focusedAddress() != "分类:action:未分类") key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("分类:action:未分类", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("未分类仍直接打开结果") { onMain { app.page == "找歌" && app.unclassified && app.selectedTags.isEmpty() && !app.busy } }
        assertEquals("true", android.net.Uri.parse(fixture.songRequests.last()).getQueryParameter("unclassified"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor("未分类返回原按钮") { focusedAddress() == "分类:action:未分类" }
        assertSingleVisibleFocus()
    }

    fun testSongTableDetailRestoresScrolledRowAndPagination() {
        fixture.songCount = 45
        enterSongTable(0)
        repeat(12) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        val original = focusedAddress()
        val id = original.substringAfterLast(":song:")
        assertEquals("应滚到第13首歌曲", "test-song-13", id)
        val bounds = songRowBounds(id)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键打开当前行详情") { onMain { app.selected?.optString("id") == id } && focusedAddress().endsWith(":action:加入待唱") }
        assertEquals("查看详情不应点歌", 0, fixture.commandRequests.size)
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情返回恢复屏外来源行") { onMain { app.selected == null } && focusedAddress() == original }
        assertEquals("详情返回保留原滚动位置", bounds, songRowBounds(id))
        assertSingleVisibleFocus()
        repeat(12) { if (focusedAddress().contains(":song:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("最后一行向下可达分页", focusedAddress().endsWith(":action:下一页"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("翻页滚到新页第一行并恢复焦点") { onMain { app.number == 1 } && focusedAddress() == "点歌:song:test-song-21" }
        assertSingleVisibleFocus()
        repeat(24) { if (focusedAddress().contains(":song:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        if (!focusedAddress().endsWith(":action:上一页")) key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("可向左选择上一页", focusedAddress().endsWith(":action:上一页"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("返回上一页仍定位首行") { onMain { app.number == 0 } && focusedAddress() == "点歌:song:test-song-1" }
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertFalse("首行向上可返回搜索控件", focusedAddress().contains(":song:"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("搜索控件向下进入首行") { focusedAddress() == "点歌:song:test-song-1" }
        screenshot("song-table-paged-first-row").recycle()
    }

    fun testSongTablePendingConfirmDoesNotDuplicateAndCompletedAddsCanRepeat() {
        fixture.addDelayMs = 1800
        enterSongTable(0)
        val address = focusedAddress()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals("请求中连续确认仅提交一次", 1, fixture.commandRequests.size)
        assertTrue("当前行显示加入中", nodeLabel(songRow("test-song-1")).contains("加入中"))
        waitFor("延迟 ADD 返回真实成功") { onMain { app.queueAddSongId.isEmpty() && queuedSongCount(app) == 1 } }
        assertEquals("请求完成仍保留当前行", address, focusedAddress())
        fixture.addDelayMs = 0
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("完成后允许有意重复点歌") { onMain { queuedSongCount(app) == 2 } }
        assertEquals(2, fixture.commandRequests.size)
        assertTrue("第二次有意点歌使用新 requestId", fixture.commandRequests[0].optString("requestId") != fixture.commandRequests[1].optString("requestId"))
        assertEquals("第二次使用最新 revision", 2L, fixture.commandRequests[1].optLong("expectedRevision"))
        waitFor("重复点歌状态真实更新") { nodeLabel(songRow("test-song-1")).contains("已点 ×2") }
    }

    fun testSongTableOfflineAndRejectedAddsRestoreSourceFocus() {
        enterSongTable(0)
        val address = focusedAddress()
        fixture.snapshotFailureStatus = 503
        onMain { app.run { app.refresh() } }
        waitFor("真实同步读取失败使曲库离线") { onMain { !app.online && !app.busy && app.error.startsWith("与 NAS 同步失败") } }
        onMain { app.addSongFromList(app.songs.first()) }
        waitFor("离线点歌业务入口真实拒绝") { onMain { app.error.contains("NAS 离线") && app.pendingOperation == null } }
        assertEquals("离线不发网络命令", 0, fixture.commandRequests.size)
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭错误后显示可恢复离线态") {
            onMain {
                !app.online && activity.focusMemory.activeDialogs == 0 &&
                    (app.error.isEmpty() || app.error.startsWith("与 NAS 同步失败"))
            } && allNodes().any { it.isVisibleToUser && nodeLabel(it).contains("暂时无法读取曲库") }
        }
        focusTarget("点歌:action:重新连接")
        val reconnectBounds = Rect().also { focusedNode()!!.getBoundsInScreen(it) }
        val offlineFrame = screenshot("catalog-offline-reconnect-focused")
        val paginationTop = allNodes().filter { it.isVisibleToUser && nodeLabel(it) == "上一页" }.minOf { node ->
            Rect().also { node.getBoundsInScreen(it) }.top
        }
        assertTrue("离线重连按钮完整处于结果区视口", reconnectBounds.bottom <= paginationTop)
        assertSingleVisibleFocus()
        offlineFrame.recycle()
        fixture.snapshotFailureStatus = 0
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("重连重新读取真实曲库") { onMain { app.online && app.songs.isNotEmpty() && !app.busy } }
        focusTarget(address)
        fixture.rejectNextAdd.set(409)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("明确拒绝不占用未知请求") { onMain { app.error.contains("暂停接单") && app.pendingOperation == null } }
        assertEquals("拒绝不展示成功", "", onMain { app.queueAddMessage })
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭拒绝提示恢复原行") { focusedAddress() == address }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("解除拒绝后可重新点歌") { onMain { queuedSongCount(app) == 1 && app.error.isEmpty() } }
        assertEquals("拒绝和重新尝试共两次请求", 2, fixture.commandRequests.size)
        assertEquals(address, focusedAddress())
        assertSingleVisibleFocus()
    }

    fun testSongTableUnknownAddResultRetriesOriginalRequest() {
        enterSongTable(0)
        val address = focusedAddress()
        fixture.failNextAddResponse.set(503)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("服务端已收下但回报失败保留原请求") { onMain { app.pendingOperation != null && app.error.contains("回报中断") && queuedSongCount(app) == 1 } }
        val requestId = fixture.commandRequests.single().getString("requestId")
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭提示可回到歌曲行") { focusedAddress() == address }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("未知结果阻止新点歌") { onMain { app.error.contains("上次操作结果未知") } }
        assertEquals("未知结果不能创建另一条 ADD", 1, fixture.commandRequests.size)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("可遥控选择原请求重试") { focusedAddress().endsWith(":action:核对后重试") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("原 requestId 重试后关闭提示并恢复原行") { onMain { app.pendingOperation == null && app.error.isEmpty() } && focusedAddress() == address }
        assertEquals("幂等重试不重复入队", 1, onMain { queuedSongCount(app) })
        assertEquals(2, fixture.commandRequests.size)
        assertEquals(requestId, fixture.commandRequests[1].getString("requestId"))
        assertTrue("重试成功后显示确认反馈", onMain { app.queueAddMessage.startsWith("已加入队列：") })
        assertSingleVisibleFocus()
    }

    fun testSongTableNewSearchResetsScrollAndEmptyResultsStayUsable() {
        enterSongTable(0)
        val firstRowTop = songRowBounds("test-song-1").top
        repeat(12) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("点歌:song:test-song-13", focusedAddress())
        val version = onMain { app.songListResetVersion }
        onMain { app.query = "测试"; app.search() }
        waitFor("新搜索重置滚动及歌曲焦点") { onMain { app.songListResetVersion > version } && focusedAddress() == "点歌:song:test-song-1" }
        val row = songRowBounds("test-song-1")
        assertTrue("首行应回到新布局的表头下方：原=$firstRowTop，现=${row.top}", kotlin.math.abs(row.top - firstRowTop) <= 8)
        fixture.songCount = 0
        onMain { app.search() }
        waitFor("空结果不保留已回收行焦点") { onMain { app.songs.isEmpty() && !app.busy } && focusedAddress().contains(":nav:") }
        assertTrue("显示真实空态", allNodes().any { nodeLabel(it).contains("没有找到歌曲") })
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse("空结果仍可回到可用搜索控件", focusedAddress().isBlank())
        assertSingleVisibleFocus()
    }

    fun testSongTableOldNasResponseCannotReplaceNewConnectionFeedback() {
        enterSongTable(0)
        fixture.addDelayMs = 1800
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        TvUiFixture().use { next ->
            onMain {
                app.disconnect()
                app.client.base = next.base; app.client.token = "test-next-fixture-device"
                app.connected = true; app.online = true; app.snapshot = TvUiFixture.snapshot()
                app.page = "点歌"; app.songs = TvUiFixture.songs(); app.total = 20
            }
            waitFor("新 NAS 恢复可用页面且清空旧反馈") { onMain { app.queueAddSongId.isEmpty() && app.queueAddMessage.isEmpty() } && !onMain { activity.focusMemory.restoring } }
            repeat(8) { if (!focusedAddress().contains(":song:")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("新 NAS 成功入队") { next.commandRequests.size == 1 && onMain { queuedSongCount(app) == 1 && app.queueAddMessage.isNotBlank() } }
            val message = onMain { app.queueAddMessage }
            waitFor("旧 NAS 延迟请求完成") { fixture.actions.contains("ADD") && onMain { !app.busy } }
            assertEquals("旧结果不能覆盖新队列", 1, onMain { queuedSongCount(app) })
            assertEquals("旧结果不能抹掉新成功提示", message, onMain { app.queueAddMessage })
            assertEquals("旧连接错误不能覆盖新页面", "", onMain { app.error })
        }
    }

    fun testSongTableOldSessionResponseCannotAffectNewSession() {
        enterSongTable(0)
        fixture.addDelayMs = 1600
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        fixture.sessionId = "ui-test-next-session"
        onMain { app.newSession() }
        waitFor("新聚会清除旧点歌占用与反馈") { onMain {
            app.snapshot.optString("id") == fixture.sessionId && app.pendingOperation == null && app.queueAddSongId.isEmpty() && app.queueAddMessage.isEmpty()
        } }
        fixture.addDelayMs = 0
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("新聚会可独立点歌") { onMain { queuedSongCount(app) == 1 && app.queueAddMessage.isNotBlank() } }
        val message = onMain { app.queueAddMessage }
        waitFor("旧聚会迟到拒绝已返回") { onMain { !app.busy } }
        assertEquals("旧聚会错误不能进入新页面", "", onMain { app.error })
        assertEquals("新聚会队列保持一次入队", 1, onMain { queuedSongCount(app) })
        assertEquals(message, onMain { app.queueAddMessage })
    }

    fun testSongTableMetadataAndFocusRemainReadable() {
        fixture.mixedSongMetadata = true
        enterSongTable(0)
        val first = onMain { app.songs.first() }
        assertTrue("fixture 包含长中英文歌名", first.optString("title").contains("My Lovely Girl"))
        val row = songRowBounds("test-song-1")
        val artist = allNodes().first { it.text?.toString() == first.artists() }
        val artistBounds = Rect().also { artist.getBoundsInScreen(it) }
        val image = screenshot("song-table-long-and-missing-metadata")
        val textPaint = screenTextPaint(13f, image)
        val glyphs = Rect().also { textPaint.getTextBounds(first.artists(), 0, first.artists().length, it) }
        assertTrue("歌手字形完整容纳在行内", artistBounds.height() >= glyphs.height() && row.contains(artistBounds))
        assertTrue("聚焦歌曲行采用深色底", colorPixels(image, 42, 46, 50, row) > row.width() * row.height() / 2)
        val lowerHalf = Rect(artistBounds.left, artistBounds.centerY(), artistBounds.right, artistBounds.bottom)
        assertTrue("歌手下半部真实绘制", readableTextPixels(image, lowerHalf) > 3)
        image.recycle()
        val missing = nodeLabel(songRow("test-song-2"))
        assertTrue("缺少时长显示占位符", missing.contains("—"))
        assertTrue("缺少版本不猜测", missing.contains("版本待确认"))
        assertFalse("无封面行不伪造封面图", missing.contains("歌曲封面"))
        assertSingleVisibleFocus()
    }

    fun testHomeEmptyAndOlderNasDoNotInventRecommendations() {
        fixture.includeRecommendationFields = false
        onMain { app.run { app.loadHome() } }
        waitFor("旧版 NAS 不冒充热歌与历史") { onMain { !app.homeRecommendationsAvailable && app.homePopularSongs.isEmpty() && app.homeHistorySongs.isEmpty() && !app.busy } }
        assertTrue("保留最近扫描仅供推荐主区使用", onMain { app.homeSongs.isNotEmpty() })
        assertTrue("旧版接口给出升级提示", allNodes().any { nodeLabel(it).contains("请更新 NAS 服务") })
        fixture.includeRecommendationFields = true
        fixture.popularCount = 0
        fixture.historyCount = 0
        onMain { app.run { app.loadHome() } }
        waitFor("真实空数据保持空数组") { onMain { app.homeRecommendationsAvailable && app.homePopularSongs.isEmpty() && app.homeHistorySongs.isEmpty() && !app.busy } }
        assertTrue("热歌空态说明未有常唱数据", allNodes().any { nodeLabel(it).contains("还没有常唱歌曲") })
        assertTrue("历史空态说明设备未演唱", allNodes().any { nodeLabel(it).contains("这台电视还没有演唱记录") })
        assertTrue("空态不创建命令", fixture.commandRequests.isEmpty())
        screenshot("home-empty-recommendations").recycle()
    }

    fun testPopularAndHistoryUseTheirOwnRecordsAndPagination() {
        fixture.songCount = 45
        fixture.popularCount = 45
        onMain { app.navigate("热歌") }
        waitFor("热歌第一页真实投影") { onMain { !app.feedLoading && app.feedTotal == 45L && app.popularSongs.size == 20 } }
        assertTrue("热歌具有有效次数与最近时间", onMain { app.popularSongs.all { it.optLong("effectivePlayCount") > 0 && it.optString("lastPlayedAt").isNotBlank() } })
        focusTarget("热歌:popular:test-song-1")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("热歌确认仅 ADD") { onMain { queuedSongCount(app) == 1 && app.queueAddSongId.isEmpty() } }
        assertEquals("点歌后保留榜单行", "热歌:popular:test-song-1", focusedAddress())
        assertTrue("热歌点歌不自动开唱或打开详情", onMain { app.selected == null && app.page == "热歌" } && fixture.actions.none { it == "START" })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("热歌右键查看详情") { onMain { app.selected?.optString("id") == "test-song-1" } }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情恢复热歌来源行") { focusedAddress() == "热歌:popular:test-song-1" }
        focusTarget("热歌:action:下一页")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("热歌下一页不重复第一页") { onMain { app.feedPage == 1 && !app.feedLoading && app.popularSongs.firstOrNull()?.optString("id") == "test-song-21" } }
        val popularRequest = android.net.Uri.parse(fixture.feedRequests.last())
        assertEquals("1", popularRequest.getQueryParameter("page"))
        assertEquals("20", popularRequest.getQueryParameter("size"))

        onMain { app.navigate("历史") }
        waitFor("历史独立第一页") { onMain { app.feedPage == 0 && !app.feedLoading && app.feedTotal == 45L && app.historyRecords.size == 20 } }
        assertTrue("历史是演唱实例包装而不是歌曲数组", onMain { app.historyRecords.all { it.has("available") && it.has("startedAt") && it.optString("songId").isNotBlank() && it.optString("id").startsWith("test-history-") } })
        assertEquals("跳过不写成已唱完", "SKIPPED", onMain { app.historyRecords[1].getString("status") })
        focusTarget("历史:history:test-history-2")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("历史可用歌曲确认 ADD") { onMain { queuedSongCount(app) == 2 && app.queueAddSongId.isEmpty() } }
        assertEquals("历史实例映射真实 songId", "test-song-2", fixture.commandRequests.last().getString("songId"))
        focusTarget("历史:history:test-history-3")
        val before = fixture.commandRequests.size
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("已移除歌曲只读记录弹窗") { focusedAddress().endsWith(":演唱记录:action:完成") }
        assertEquals("不可用歌曲不能 ADD", before, fixture.commandRequests.size)
        assertTrue("只读弹窗保留真实失败终态", allNodes().any { nodeLabel(it).contains("播放失败") })
        assertTrue("只读记录不打开歌曲详情", onMain { app.selected == null })
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭只读弹窗回到历史实例") { focusedAddress() == "历史:history:test-history-3" }
        focusTarget("历史:action:下一页")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("历史第二页沿实例分页") { onMain { app.feedPage == 1 && !app.feedLoading && app.historyRecords.firstOrNull()?.optString("id") == "test-history-21" } }
        assertSingleVisibleFocus()
    }

    fun testFeedReadFailureRetriesWithoutCreatingCommands() {
        fixture.failNextPopular.set(503)
        onMain { app.navigate("热歌") }
        waitFor("读取失败有可操作状态") { onMain { app.feedError.contains("读取失败") && !app.feedLoading } }
        assertTrue("读取错误不作为点歌错误弹层", onMain { app.error.isEmpty() && activity.focusMemory.activeDialogs == 0 })
        focusTarget("热歌:action:重新读取")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("重试读取恢复真实榜单") { onMain { app.feedError.isEmpty() && !app.feedLoading && app.popularSongs.isNotEmpty() } }
        assertTrue("读取和重试都不产生队列命令", fixture.commandRequests.isEmpty())
        fixture.failNextHistory.set(404)
        onMain { app.navigate("历史") }
        waitFor("缺少新接口有升级说明") { onMain { !app.feedLoading && app.feedError.contains("请更新 NAS 服务") } }
        assertTrue("缺少接口不显示伪造历史", onMain { app.historyRecords.isEmpty() })
    }

    fun testFeedRefreshReturnsToAValidPageWhenRecordsShrink() {
        fixture.songCount = 45
        fixture.popularCount = 45
        onMain { app.navigate("热歌") }
        waitFor("热歌读取多页数据") { onMain { app.feedTotal == 45L && !app.feedLoading } }
        focusTarget("热歌:action:下一页")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("热歌位于第二页") { onMain { app.feedPage == 1 && !app.feedLoading } }
        fixture.popularCount = 1
        focusTarget("热歌:action:刷新")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("热歌数据缩小后重新读取有效第一页") {
            onMain { app.feedPage == 0 && app.feedTotal == 1L && app.popularSongs.singleOrNull()?.optString("id") == "test-song-1" && !app.feedLoading && !app.busy }
        }
        val popularPages = fixture.feedRequests.filter { it.startsWith("/api/v1/tv/popular?") }.takeLast(2)
            .map { android.net.Uri.parse(it).getQueryParameter("page") }
        assertEquals("先发现旧页失效，再请求有效页", listOf("1", "0"), popularPages)
        assertSingleVisibleFocus()

        onMain { app.navigate("历史") }
        waitFor("历史读取多页实例") { onMain { app.feedTotal == 45L && !app.feedLoading } }
        focusTarget("历史:action:下一页")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("历史位于第二页") { onMain { app.feedPage == 1 && !app.feedLoading } }
        fixture.historyCount = 0
        focusTarget("历史:action:刷新")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("历史清空后页码回零并显示真实空态") {
            onMain { app.feedPage == 0 && app.feedTotal == 0L && app.historyRecords.isEmpty() && !app.feedLoading && !app.busy } &&
                allNodes().any { nodeLabel(it).contains("还没有演唱记录") }
        }
        assertSingleVisibleFocus()
        assertTrue("刷新和自动纠正页码不产生点歌命令", fixture.commandRequests.isEmpty())
    }

    fun testCatalogSearchClearsReturnedCategoryAndUnclassifiedFilters() {
        enterSongTable(2)
        fun returnToSearch(source: String) {
            key(KeyEvent.KEYCODE_BACK)
            waitFor("分类结果返回原入口") { onMain { app.page == "分类" && !activity.focusMemory.restoring } && focusedAddress() == source }
            focusTarget("分类:catalog:点歌")
            val requests = fixture.songRequests.size
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("侧栏搜索读取全部曲库") {
                onMain { app.page == "点歌" && !app.categoryResults && app.selectedTags.isEmpty() && !app.unclassified && app.artistId.isEmpty() && app.number == 0 && !app.busy } && fixture.songRequests.size > requests
            }
            val request = android.net.Uri.parse(fixture.songRequests.last())
            assertTrue("搜索接口不得残留隐藏分类", request.getQueryParameters("tagIds").isEmpty())
            assertEquals("false", request.getQueryParameter("unclassified"))
            assertEquals("0", request.getQueryParameter("page"))
            assertSingleVisibleFocus()
        }
        returnToSearch("分类:category:tag-language")
        focusTarget("点歌:catalog:点歌")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("侧栏选择分类点歌") { focusedAddress() == "点歌:catalog:分类" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("分类页面可用") { onMain { app.page == "分类" && !app.busy } }
        focusTarget("分类:action:未分类")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("未分类结果确实带有对应筛选") { onMain { app.page == "找歌" && app.unclassified && !app.busy } }
        assertEquals("true", android.net.Uri.parse(fixture.songRequests.last()).getQueryParameter("unclassified"))
        returnToSearch("分类:action:未分类")
        assertTrue("切换查找方式不产生点歌命令", fixture.commandRequests.isEmpty())
    }

    fun testOldNasUnauthorizedHomeAndRefreshCannotClearNewConnection() {
        fixture.homeDelayMs = 1800
        fixture.snapshotDelayMs = 1800
        fixture.classificationDelayMs = 1800
        fixture.failNextHome.set(401)
        fixture.failNextSnapshot.set(401)
        fixture.failNextClassification.set(503)
        val snapshotPath = "/api/v1/tv/sessions/${fixture.sessionId}/snapshot"
        val classificationPath = "/api/v1/tv/library/classification-tags"
        onMain { app.run { app.loadHome() }; app.run { app.refresh() }; app.run { app.loadTags() } }
        waitFor("旧 NAS 首页、同步和分类的失败响应均已冻结") {
            fixture.httpEvents.contains("prepared GET /api/v1/tv/home") && fixture.httpEvents.contains("prepared GET $snapshotPath") &&
                fixture.httpEvents.contains("prepared GET $classificationPath")
        }
        TvUiFixture().use { next ->
            next.titlePrefix = "新 NAS 保留歌曲"
            next.extraGenreTags = 2
            val nextToken = "test-new-connection-survives-old-401"
            val newError = "新连接自己的提示"
            onMain {
                app.disconnect()
                app.client.base = next.base
                app.client.token = nextToken
                app.connected = true
                app.online = true
                app.snapshot = TvUiFixture.snapshot()
                app.page = "首页"
                app.run { app.loadHome() }
                app.run { app.loadTags() }
            }
            waitFor("新连接首页与分类已读取") { onMain { app.homePopularSongs.firstOrNull()?.optString("title")?.startsWith("新 NAS") == true && app.tags.size == 4 } }
            onMain { app.error = newError }
            waitFor("旧首页与同步 401、分类 503 均已返回") {
                fixture.httpEvents.any { it.startsWith("end GET /api/v1/tv/home status=401") } &&
                    fixture.httpEvents.any { it.startsWith("end GET $snapshotPath status=401") } &&
                    fixture.httpEvents.any { it.startsWith("end GET $classificationPath status=503") } && onMain { !app.busy }
            }
            assertTrue("旧 401 不能断开新连接", onMain { app.connected && app.online })
            assertEquals("旧 401 不能清除新凭据", nextToken, onMain { app.client.token })
            assertEquals("旧 401 不能更换新 NAS", next.base, onMain { app.client.base })
            assertEquals("旧错误不能覆盖新提示", newError, onMain { app.error })
            assertEquals("旧分类失败不能清除新分类", 4, onMain { app.tags.size })
            assertTrue("新 NAS 首页内容保留", onMain { app.homePopularSongs.all { it.optString("title").startsWith("新 NAS") } })
            assertTrue("连接隔离测试不提交任何命令", fixture.commandRequests.isEmpty() && next.commandRequests.isEmpty())
        }
    }

    fun testOldNasHomeAndPopularResponsesCannotReplaceNewConnection() {
        val completedHome = fixture.httpEvents.count { it.startsWith("end GET /api/v1/tv/home ") }
        val completedPopular = fixture.httpEvents.count { it.startsWith("end GET /api/v1/tv/popular ") }
        fixture.homeDelayMs = 1800
        fixture.popularDelayMs = 1800
        onMain { app.run { app.loadHome() }; app.navigate("热歌") }
        waitFor("旧 NAS 首页与榜单响应均已冻结") { fixture.httpEvents.contains("prepared GET /api/v1/tv/home") && fixture.httpEvents.contains("prepared GET /api/v1/tv/popular") }
        TvUiFixture().use { next ->
            next.titlePrefix = "新 NAS 歌曲"
            onMain {
                app.disconnect()
                app.client.base = next.base; app.client.token = "test-next-fixture-device"
                app.connected = true; app.online = true; app.snapshot = TvUiFixture.snapshot()
                app.navigate("热歌"); app.run { app.loadHome() }
            }
            waitFor("新 NAS 首页和热歌均已读取") { onMain { app.homePopularSongs.firstOrNull()?.optString("title")?.startsWith("新 NAS") == true && app.popularSongs.firstOrNull()?.optString("title")?.startsWith("新 NAS") == true && !app.feedLoading } }
            waitFor("旧 NAS 两个延迟读取都返回") { fixture.httpEvents.count { it.startsWith("end GET /api/v1/tv/home ") } > completedHome && fixture.httpEvents.count { it.startsWith("end GET /api/v1/tv/popular ") } > completedPopular && onMain { !app.busy } }
            assertTrue("旧首页不能覆盖新 NAS", onMain { app.homePopularSongs.all { it.optString("title").startsWith("新 NAS") } })
            assertTrue("旧榜单不能覆盖新 NAS", onMain { app.popularSongs.all { it.optString("title").startsWith("新 NAS") } })
            assertEquals("", onMain { app.error })
            assertEquals("", onMain { app.feedError })
            assertTrue("跨 NAS 读取不提交任何命令", fixture.commandRequests.isEmpty() && next.commandRequests.isEmpty())
        }
    }

    fun testOldNasHistoryFailureCannotAffectNewConnection() {
        fixture.historyDelayMs = 1800
        fixture.failNextHistory.set(503)
        onMain { app.navigate("历史") }
        waitFor("旧 NAS 历史失败响应已冻结") { fixture.httpEvents.contains("prepared GET /api/v1/tv/playback-history") }
        TvUiFixture().use { next ->
            next.titlePrefix = "新 NAS 历史歌曲"
            onMain {
                app.disconnect()
                app.client.base = next.base; app.client.token = "test-next-fixture-device"
                app.connected = true; app.online = true; app.snapshot = TvUiFixture.snapshot()
                app.navigate("历史")
            }
            waitFor("新 NAS 历史已读取") { onMain { app.historyRecords.firstOrNull()?.getJSONObject("song")?.optString("title")?.startsWith("新 NAS") == true && !app.feedLoading } }
            waitFor("旧 NAS 历史失败已返回") { fixture.httpEvents.any { it.startsWith("end GET /api/v1/tv/playback-history status=503") } && onMain { !app.busy } }
            assertTrue("旧历史不能覆盖新实例", onMain { app.historyRecords.all { it.optString("title").startsWith("新 NAS") } })
            assertEquals("迟到错误不能污染新页面", "", onMain { app.feedError })
            assertEquals("", onMain { app.error })
        }
    }

    fun testLoginManualEditorReturnsToItsFieldWithoutConnecting() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        onMain {
            app.connected = false
            app.login.open(LoginPage.METHODS)
            app.login.host = ""
        }
        fun loginFocus() = focusedNode()?.let(::nodeLabel).orEmpty()
        fun keyboardVisible() = automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        fun editorNode(): AccessibilityNodeInfo? {
            clearAccessibilityCache()
            fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                // The TV IME covers its focused field; visibility is not input focus.
                if (node.isFocused && node.isEditable) return node
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child -> find(child)?.let { return it } }
                }
                return null
            }
            automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }.forEach { window ->
                    window.root?.takeIf { it.packageName?.toString() == instrumentation.targetContext.packageName }
                        ?.let { root -> find(root)?.let { return it } }
                }
            return null
        }

        waitFor("连接入口首焦点为手机扫码") { loginFocus().contains("手机扫码") }
        screenshot("gallery-login").recycle()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键经过查找 NAS，但不启动发现") { loginFocus().contains("查找 NAS") }
        assertEquals("只移动焦点不能进入发现", LoginPage.METHODS, onMain { app.login.page })
        assertFalse("测试不启动局域网发现", onMain { app.discovery.searching })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键选择手动登录") { loginFocus().contains("手动登录") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("手动表单默认聚焦 NAS 地址") {
            onMain { app.login.page == LoginPage.MANUAL && !app.login.qrRequested } && loginFocus().contains("输入 NAS 地址与端口")
        }
        screenshot("gallery-login-manual").recycle()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("地址编辑器获得真实系统输入焦点") { editorNode() != null }
        waitFor("地址编辑器打开真实系统键盘") { keyboardVisible() }
        instrumentation.sendStringSync("192.0.2.10:8080")
        waitFor("系统输入进入尚未提交的编辑草稿") { editorNode()?.text?.toString() == "192.0.2.10:8080" }
        screenshot("gallery-login-editor").recycle()
        assertEquals("编辑时不能提前改写地址", "", onMain { app.login.host })
        key(KeyEvent.KEYCODE_BACK)
        if (!loginFocus().contains("输入 NAS 地址与端口")) key(KeyEvent.KEYCODE_BACK)
        waitFor("取消编辑恢复原 NAS 地址字段") { loginFocus().contains("输入 NAS 地址与端口") && !keyboardVisible() }
        assertEquals("取消草稿保留原字段值", "", onMain { app.login.host })
        assertEquals("取消键盘不能离开手动表单", LoginPage.MANUAL, onMain { app.login.page })
        key(KeyEvent.KEYCODE_BACK)
        waitFor("表单返回连接入口并恢复可用焦点") { onMain { app.login.page == LoginPage.METHODS } && loginFocus().contains("手机扫码") }
        assertFalse("全程不启动局域网发现", onMain { app.discovery.searching })
        assertFalse("全程不提交账号或设备授权请求", fixture.httpEvents.any { it.contains("/api/v1/auth/") || it.contains("/api/v1/device-authorizations") })
        assertTrue("浏览连接界面不能更改队列", fixture.commandRequests.isEmpty())
    }

    fun testNewScreenGallery() {
        listOf("首页", "点歌", "歌手", "分类", "歌单", "热歌", "历史", "已点", "设置").forEach { page ->
            onMain { app.navigate(page) }
            waitFor("$page 画面就绪") { onMain { app.page == page && !app.busy && !app.feedLoading && !activity.focusMemory.restoring } }
            assertSingleVisibleFocus()
            screenshot("gallery-$page").recycle()
        }
        listOf("连接", "播放设置", "聚会管理", "本场参与者", "设备信息", "应用更新").forEach { group ->
            focusTarget("设置:action:$group")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertSingleVisibleFocus()
            screenshot("gallery-settings-$group").recycle()
        }
        onMain { app.navigate("歌手") }
        waitFor("歌手卡片就绪") { onMain { app.artists.isNotEmpty() && !app.busy } }
        focusTarget("歌手:artist:test-artist")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("歌手详情首歌就绪") { focusedAddress().endsWith(":song:test-song-1") }
        screenshot("gallery-artist-detail").recycle()
        key(KeyEvent.KEYCODE_BACK)
        onMain { app.navigate("歌单") }
        waitFor("歌单卡片就绪") { onMain { app.playlists.isNotEmpty() && !app.busy } }
        focusTarget("歌单:playlist:test-playlist")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("歌单详情首歌就绪") { focusedAddress().endsWith(":song:test-song-1") }
        screenshot("gallery-playlist-detail").recycle()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("歌曲详情就绪") { onMain { app.selected != null } }
        screenshot("gallery-song-detail").recycle()
        assertTrue("画面浏览不能更改队列", fixture.commandRequests.isEmpty())
    }

    private fun enterSongTable(entry: Int) {
        onMain { app.navigate("点歌") }
        waitFor("点歌搜索和侧栏就绪") { onMain { app.page == "点歌" && activity.focusMemory.view == "点歌" && !activity.focusMemory.restoring } }
        focusTarget("点歌:catalog:点歌")
        repeat(entry) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        val destination = listOf("点歌", "歌手", "分类", "歌单")[entry]
        assertEquals("选择第${entry + 1}种真实找法", "点歌:catalog:$destination", focusedAddress())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        when (entry) {
            0 -> waitFor("读取搜索歌曲") { onMain { app.page == "点歌" && app.songs.isNotEmpty() && app.total == fixture.songCount.toLong() && !app.busy } }
            1 -> {
                waitFor("读取歌手") { onMain { app.page == "歌手" && app.artists.isNotEmpty() } }
                focusTarget("歌手:artist:test-artist")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("读取歌手歌曲并默认首歌") { onMain { app.artistDetail != null && app.songs.isNotEmpty() } && focusedAddress().endsWith(":song:test-song-1") }
            }
            2 -> {
                waitFor("读取分类") { onMain { app.page == "分类" && app.tags.isNotEmpty() } }
                focusTarget("分类:action:语种")
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor("下键进入语种分类") { focusedAddress() == "分类:category:tag-language" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("分类确认直接读取歌曲") { onMain { app.page == "找歌" && app.selectedTags == setOf("tag-language") && app.songs.isNotEmpty() && !app.busy } && focusedAddress() == "找歌:song:test-song-1" }
            }
            3 -> {
                waitFor("读取歌单") { onMain { app.page == "歌单" && app.playlists.isNotEmpty() } }
                focusTarget("歌单:playlist:test-playlist")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor("读取歌单歌曲并默认首歌") { onMain { app.playlistDetail != null && app.songs.isNotEmpty() } && focusedAddress().endsWith(":song:test-song-1") }
            }
        }
        waitFor("歌曲表格及焦点恢复完成") { onMain {
            val expected = when {
                app.playlistDetail != null -> "${app.page}:playlist:${app.playlistDetail!!.optString("id") }"
                app.artistDetail != null -> "${app.page}:artist:${app.artistDetail!!.optString("id") }"
                else -> app.page
            }
            !app.busy && app.songs.isNotEmpty() && activity.focusMemory.view == expected && !activity.focusMemory.restoring
        } }
        if (entry == 0) focusTarget("点歌:song:test-song-1")
        waitFor("进入真实歌曲表格") { focusedAddress().contains(":song:") && onMain { app.selected == null } }
    }

    /** Register an existing visible control as the test's starting position; operations use D-pad. */
    private fun focusTarget(address: String) {
        waitFor("控件已注册：$address") { onMain { activity.focusMemory.targets.containsKey(address) && !activity.focusMemory.restoring } }
        onMain { activity.focusMemory.targets.getValue(address).requestFocus() }
        waitFor("控件获得真实遥控焦点：$address") { focusedAddress() == address }
    }

    private fun assertCategoryFocusOutlineComplete(id: String) {
        val title = onMain { app.tags.first { it.optString("id") == id }.optString("name") }
        val node = allNodes().first { it.isClickable && nodeLabel(it).contains(title) }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val frame = screenshot("category-focus-outline-${keyHistory.size}")
        val lowerEdge = Rect(bounds.left + bounds.width() / 3, bounds.bottom - 20,
            bounds.right - bounds.width() / 3, minOf(frame.height, bounds.bottom + 20))
        // tvFocus draws the shared blue focus token over the dark card.
        assertTrue("分类焦点框底边不能被滚动视口裁切", focusPixels(frame, lowerEdge) > bounds.width() / 4)
        frame.recycle()
    }

    private fun songRow(id: String): AccessibilityNodeInfo {
        val title = onMain { app.songs.first { it.optString("id") == id }.optString("title") }
        return allNodes().first { it.isClickable && nodeLabel(it).contains(title) }
    }

    private fun songRowBounds(id: String) = Rect().also { songRow(id).getBoundsInScreen(it) }

    private fun key(code: Int) {
        val before = focusDetails()
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(230) // Wait for the production focus/scroll animation to settle.
        keyHistory.add("${KeyEvent.keyCodeToString(code)}: $before -> ${focusDetails()}")
        File(output, "${name}-keys.txt").writeText(keyHistory.joinToString("\n"))
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(70)
        }
        fail("超时：$description；当前焦点=${focusedLabel()}；页面=${onMain { app.page }}")
    }

    private fun clearAccessibilityCache() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
    }

    private fun focusedNode(): AccessibilityNodeInfo? {
        clearAccessibilityCache()
        val automation = instrumentation.uiAutomation
        // UiAutomation.findFocus can return a previous Compose node even after clearCache.
        // Read fresh node flags from the application window; an IME keeps its own window focus.
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { focused -> return focused } }
            return node.takeIf { it.isFocused && it.isVisibleToUser }
        }
        if (automation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) {
            automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }.forEach { window ->
                window.root?.takeIf { it.packageName?.toString() == instrumentation.targetContext.packageName }?.let { root ->
                    find(root)?.let { return it }
                }
            }
        }
        return automation.rootInActiveWindow?.let(::find)
    }
    private fun visibleCoverImages() = allNodes().filter {
        it.isVisibleToUser && it.childCount == 0 && it.contentDescription?.toString() == "歌曲封面"
    }
    private fun focusedAddress(): String = onMain { activity.focusMemory.focusedTargets.singleOrNull().orEmpty() }

    /** Observe actual Compose callbacks; Accessibility focus is stale on some TV system images. */
    private fun focusedLabel(): String {
        val address = focusedAddress()
        return when {
            address.contains(":action:") -> address.substringAfterLast(":action:")
            address.contains(":input:") -> "<编辑器> ${address.substringAfterLast(":input:")}"
            address.contains(":nav:") -> address.substringAfterLast(":nav:")
            address.contains(":catalog:") -> mapOf("点歌" to "搜索歌曲", "歌手" to "歌手点歌", "分类" to "分类点歌", "歌单" to "共享歌单")[address.substringAfterLast(":catalog:")].orEmpty()
            address.endsWith(":featured:sing") -> "歌曲详情"
            address.endsWith(":featured:add") -> "加入待唱"
            address.endsWith(":bar:queue") -> "已点歌曲"
            address.endsWith(":bar:start") -> "确认开唱"
            address.contains(":song:") -> onMain { (app.songs + app.homeSongs).firstOrNull { it.optString("id") == address.substringAfterLast(":song:") }?.optString("title") }.orEmpty()
            else -> address
        }
    }
    private fun stateDescription(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty()
        else node.extras.getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY")?.toString().orEmpty()

    private fun focusDetails(): String = focusedNode()?.let { node ->
        val rect = Rect(); node.getBoundsInScreen(rect)
        "actualTargets=${onMain { activity.focusMemory.focusedTargets.toList() }} history=${onMain { activity.focusMemory.last.toMap() }} a11yLabel=${nodeLabel(node)} bounds=$rect selected=${node.isSelected} state=${stateDescription(node)} focusable=${node.isFocusable} clickable=${node.isClickable}"
    } ?: "actualTargets=${onMain { activity.focusMemory.focusedTargets.toList() }} history=${onMain { activity.focusMemory.last.toMap() }} <a11y焦点缺失>"

    private fun nodeLabel(node: AccessibilityNodeInfo): String = buildString {
        if (!node.text.isNullOrBlank()) append(node.text).append(' ')
        if (!node.contentDescription.isNullOrBlank()) append(node.contentDescription).append(' ')
        for (index in 0 until node.childCount) node.getChild(index)?.let { append(nodeLabel(it)) }
    }.trim()

    private fun allNodes(): List<AccessibilityNodeInfo> {
        clearAccessibilityCache()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return nodes
    }

    private fun screenTextPaint(sp: Float, image: Bitmap) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val canvasDensity = minOf(image.width / 960f, image.height / 540f)
        textSize = sp * canvasDensity * instrumentation.targetContext.resources.configuration.fontScale
    }

    private fun assertSingleVisibleFocus() {
        assertEquals("不允许焦点丢失或多个控件同时持有真实Compose焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        val expected = focusedAddress()
        val deadline = SystemClock.uptimeMillis() + 8000
        var settled: Bitmap? = null
        var capturedPendingFrame = false
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals("等待蓝色焦点框绘制期间遥控焦点不能改变", expected, focusedAddress())
            val frame = instrumentation.uiAutomation.takeScreenshot() ?: error("焦点可见性检查需要屏幕截图")
            if (focusPixels(frame) > 100) {
                settled = frame
                break
            }
            if (!capturedPendingFrame) {
                File(output, "$name-focus-pending-${keyHistory.size}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                capturedPendingFrame = true
            }
            frame.recycle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(70)
        }
        assertNotNull("实际蓝色焦点框必须在8秒内绘制到可见屏幕：$expected", settled)
        val bitmap = settled!!
        assertEquals("绘制后仍只能有一个真实焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        assertEquals("绘制后的焦点目标保持一致", expected, focusedAddress())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val boundary = pixels.indices.any { index ->
            val x = index % bitmap.width; val y = index / bitmap.width
            (x <= 1 || y <= 1 || x >= bitmap.width - 2 || y >= bitmap.height - 2) && isFocusColor(pixels[index])
        }
        assertFalse("蓝色焦点框不能被屏幕边缘裁切", boundary)
        bitmap.recycle()
    }

    private fun navigationBounds(label: String): Rect {
        val node = allNodes().firstOrNull { it.isClickable && nodeLabel(it) == label }
        assertNotNull("导航可访问文本：$label", node)
        return Rect().also { node!!.getBoundsInScreen(it) }
    }

    private fun screenshot(label: String): Bitmap {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("模拟器应允许截图", bitmap)
        File(output, "$label.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap!!
    }

    private fun colorPixels(bitmap: Bitmap, red: Int, green: Int, blue: Int, region: Rect? = null): Int {
        val area = region ?: Rect(0, 0, bitmap.width, bitmap.height)
        val pixels = IntArray(area.width() * area.height())
        bitmap.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
        return pixels.count { isColor(it, red, green, blue) }
    }

    private fun isColor(pixel: Int, red: Int, green: Int, blue: Int) =
        kotlin.math.abs(Color.red(pixel) - red) < 12 && kotlin.math.abs(Color.green(pixel) - green) < 12 && kotlin.math.abs(Color.blue(pixel) - blue) < 12

    private fun focusPixels(bitmap: Bitmap, region: Rect? = null): Int {
        val area = region ?: Rect(0, 0, bitmap.width, bitmap.height)
        val pixels = IntArray(area.width() * area.height())
        bitmap.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
        return pixels.count(::isFocusColor)
    }

    // The .95-alpha outline is blended and antialiased when the canvas scales to 720p.
    private fun isFocusColor(pixel: Int) =
        kotlin.math.abs(Color.red(pixel) - 119) <= 16 && kotlin.math.abs(Color.green(pixel) - 173) <= 16 && kotlin.math.abs(Color.blue(pixel) - 247) <= 16

    private fun readableTextPixels(bitmap: Bitmap, region: Rect): Int {
        val pixels = IntArray(region.width() * region.height())
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        return pixels.count { Color.red(it) > 140 && Color.green(it) > 140 && Color.blue(it) > 140 }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
