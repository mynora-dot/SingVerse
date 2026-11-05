@file:Suppress("DEPRECATION")

package com.mynora.singverse.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    fun testNavigationKeepsGoldActiveSeparateFromBlueRemoteFocus() {
        val homeBounds = navigationBounds("首页")
        val songCenterBounds = navigationBounds("点歌")
        assertEquals("首页初始页面", "首页", onMain { app.page })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("右键到点歌导航") { focusedLabel().contains("点歌") }
        assertEquals("移动焦点不能切换页面", "首页", onMain { app.page })
        val image = screenshot("home-active-and-focus")
        assertTrue("首页须继续保留香槟金选中背景", colorPixels(image, 232, 201, 155, homeBounds) > 100)
        assertEquals("仅聚焦的点歌导航不能得到金色页面选中背景", 0, colorPixels(image, 232, 201, 155, songCenterBounds))
        assertTrue("点歌当前遥控焦点应有可见蓝色描边", colorPixels(image, 40, 169, 255, songCenterBounds) > 100)
        assertSingleVisibleFocus()

        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认进入点歌中心") { onMain { app.page == "点歌" } }
        assertTrue("点歌中心保留四项真实功能", listOf("搜索歌曲", "歌手点歌", "分类点歌", "我的歌单").all { text -> allNodes().any { nodeLabel(it).contains(text) } })
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(3) { if (!focusedLabel().contains("搜索歌曲")) key(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("点歌中心可用方向键进入搜索入口", focusedLabel().contains("搜索歌曲"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("真实搜索页面") { onMain { app.page == "找歌" } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("搜索输入行遥控焦点") { focusedLabel().contains("歌名 / 歌手 / 拼音 / 编号") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("确认进入可编辑输入框") { focusedAddress().contains(":input:") }
        instrumentation.sendStringSync("remote")
        waitFor("硬件按键输入进入真实搜索状态") { onMain { app.query == "remote" } }
        screenshot("search-input-editing")
        key(KeyEvent.KEYCODE_BACK)
        // An IME may consume the first Back to hide itself before the editor receives Back.
        if (focusedAddress().contains(":input:")) key(KeyEvent.KEYCODE_BACK)
        waitFor("结束编辑恢复原输入行") { focusedAddress().endsWith(":action:remote") }
        assertEquals("退出键盘不能离开搜索页面", "找歌", onMain { app.page })
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("搜索返回点歌中心并恢复搜索入口焦点") { onMain { app.page == "点歌" } && focusedAddress() == "点歌:quick:0" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("返回首页并恢复离开时的点歌导航焦点") { onMain { app.page == "首页" } && focusedAddress() == "首页:nav:点歌" }
        assertSingleVisibleFocus()
    }

    fun testSongCenterEntriesRestoreTheirRemoteFocus() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("首页右键聚焦点歌导航") { focusedAddress() == "首页:nav:点歌" }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("遥控确认进入点歌中心") { onMain { app.page == "点歌" } && focusedAddress() == "点歌:nav:点歌" }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(3) {
            if (focusedAddress() != "点歌:quick:0") key(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        assertEquals("方向键进入第一张点歌方式卡片", "点歌:quick:0", focusedAddress())

        listOf("歌手" to 1, "分类" to 2, "歌单" to 3).forEach { (page, index) ->
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val originalFocus = "点歌:quick:$index"
            waitFor("聚焦${page}点歌入口") { focusedAddress() == originalFocus }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("遥控进入${page}页面") { onMain { app.page == page } && focusedAddress().startsWith("$page:") }
            assertSingleVisibleFocus()
            assertTrue("${page}页面保留可见手机点歌入口", allNodes().any {
                it.isVisibleToUser && it.isClickable && nodeLabel(it) == "手机点歌"
            })
            screenshot("song-center-entry-$index")

            key(KeyEvent.KEYCODE_BACK)
            waitFor("${page}返回点歌中心恢复原入口") {
                onMain { app.page == "点歌" } && focusedAddress() == originalFocus
            }
            assertSingleVisibleFocus()
        }
    }

    fun testSongRowScrollsAndRestoresFocusAfterDetailAndDialog() {
        repeat(6) {
            if (!focusedLabel().contains("测试歌曲")) key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertTrue("方向键应能从顶部进入曲库", focusedLabel().contains("测试歌曲"))
        repeat(12) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("左右切换须留在歌曲横排", focusedLabel().contains("测试歌曲"))
            assertSingleVisibleFocus()
        }
        val label = focusedLabel()
        val number = Regex("测试歌曲 (\\d+)").find(label)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue("应能滚到首屏之外的真实曲库卡片", number >= 10)
        screenshot("home-scrolled-song-focus")

        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("详情页面加载并聚焦加入待唱") {
            onMain { app.selected?.optString("id") == "test-song-$number" } && focusedAddress().endsWith(":action:加入待唱")
        }
        screenshot("song-detail-primary-focus")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("详情横排操作可向右聚焦返回曲库") { focusedAddress().endsWith(":action:返回曲库") }
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("详情向左恢复点唱焦点") { focusedAddress().endsWith(":action:加入待唱") }
        assertSingleVisibleFocus()
        key(KeyEvent.KEYCODE_BACK)
        waitFor("详情返回恢复原曲目焦点") { onMain { app.selected == null } && focusedLabel().contains("测试歌曲 ${number.toString().padStart(2, '0')}") }
        assertSingleVisibleFocus()

        key(KeyEvent.KEYCODE_BACK)
        waitFor("首页返回打开确认弹窗") { onMain { app.pendingConfirm != null } && focusedLabel().contains("取消") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("弹窗方向键可移动到确认", focusedLabel().contains("确认"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor("关闭弹窗恢复原曲目焦点") { onMain { app.pendingConfirm == null } && focusedLabel().contains("测试歌曲 ${number.toString().padStart(2, '0')}") }
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
        waitFor("通过真实图片HTTP加载歌曲封面") { visibleCoverImages().size >= 5 }
        visibleCoverImages().forEach { cover ->
            val rect = Rect(); cover.getBoundsInScreen(rect)
            assertTrue("横图、竖图和方图原件都必须按1:1封面显示：$rect", kotlin.math.abs(rect.width() - rect.height()) <= 1)
        }
        val image = screenshot("home-dynamic-qr")
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val result = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        )
        assertEquals("从最终屏幕像素解码应得到本次动态邀请URL", fixture.invitationUrl, result.text)

        assertEquals("空队列数量", 0, onMain { queuedSongCount(app) })
        assertTrue("空队列仍有可用的底栏入口", allNodes().any { nodeLabel(it).contains("已点歌曲 (0)") })
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        if (focusedLabel().contains("点唱这首")) key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("推荐区加入已点按钮可用遥控到达", focusedLabel().contains("加入已点"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("真实ADD命令更新队列") { fixture.actions.contains("ADD") && onMain { queuedSongCount(app) == 1 } }
        repeat(6) { if (!focusedLabel().contains("已点歌曲")) key(KeyEvent.KEYCODE_DPAD_DOWN) }
        if (focusedLabel().contains("确认开唱")) key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("底栏已点入口须可达且绑定真实数量", focusedAddress().endsWith(":bar:queue") && onMain { queuedSongCount(app) == 1 })
        screenshot("home-added-queue-count")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("底栏进入真实已点页面") { onMain { app.page == "已点" } }
        assertEquals("已点页面继续保留刚加入的歌曲", 1, onMain { queuedSongCount(app) })
        screenshot("queue-added-song")
    }

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
        return instrumentation.uiAutomation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
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
            address.contains(":quick:") -> listOf("搜索歌曲", "歌手点歌", "分类点歌", "我的歌单")[address.substringAfterLast(":quick:").toInt()]
            address.endsWith(":featured:sing") -> "点唱这首"
            address.endsWith(":featured:add") -> "加入已点"
            address.endsWith(":bar:queue") -> "已点歌曲"
            address.endsWith(":bar:start") -> "确认开唱"
            address.contains(":song:") -> onMain { app.homeSongs.firstOrNull { it.optString("id") == address.substringAfterLast(":song:") }?.optString("title") }.orEmpty()
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

    private fun assertSingleVisibleFocus() {
        assertEquals("不允许焦点丢失或多个控件同时持有真实Compose焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        val expected = focusedAddress()
        val deadline = SystemClock.uptimeMillis() + 8000
        var settled: Bitmap? = null
        var capturedPendingFrame = false
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals("等待蓝框绘制期间遥控焦点不能改变", expected, focusedAddress())
            val frame = instrumentation.uiAutomation.takeScreenshot() ?: error("焦点可见性检查需要屏幕截图")
            if (colorPixels(frame, 40, 169, 255) > 100) {
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
        assertNotNull("实际焦点蓝框必须在8秒内绘制到可见屏幕：$expected", settled)
        val bitmap = settled!!
        assertEquals("绘制后仍只能有一个真实焦点", 1, onMain { activity.focusMemory.focusedTargets.size })
        assertEquals("绘制后的焦点目标保持一致", expected, focusedAddress())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val boundary = pixels.indices.any { index ->
            val x = index % bitmap.width; val y = index / bitmap.width
            (x <= 1 || y <= 1 || x >= bitmap.width - 2 || y >= bitmap.height - 2) && isColor(pixels[index], 40, 169, 255)
        }
        assertFalse("蓝框不能被屏幕边缘裁切", boundary)
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

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
