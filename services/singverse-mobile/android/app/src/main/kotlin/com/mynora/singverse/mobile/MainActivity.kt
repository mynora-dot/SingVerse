@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.ui.PlayerView
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.*
import io.flutter.plugin.platform.*
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val videoViews = mutableSetOf<PlayerView>()
    private var events: EventChannel.EventSink? = null
    private var permissionResult: MethodChannel.Result? = null
    private var notificationResult: MethodChannel.Result? = null
    private var exportResult: MethodChannel.Result? = null
    private var exportingId = ""
    private fun withPlayer(result: MethodChannel.Result, block: (PlaybackService) -> Unit) {
        startService(Intent(this, PlaybackService::class.java))
        fun attach(tries: Int) {
            val service = PlaybackService.current
            if (service != null) { try { block(service) } catch (e: Exception) { result.error("karaoke", e.message ?: "操作失败", null) } }
            else if (tries > 0) main.postDelayed({ attach(tries - 1) }, 50)
            else result.error("player", "演唱引擎启动失败", null)
        }
        attach(40)
    }
    private fun snapshotForIntent(service: PlaybackService): Map<String, Any?> {
        val value = service.snapshot().toMutableMap()
        if (intent?.action == "RETURN_SINGING") { value["openSinging"] = true; intent.action = null }
        return value
    }
    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value); setIntent(value)
        if (value.action == "RETURN_SINGING" && events != null) PlaybackService.current?.let { events?.success(snapshotForIntent(it)) }
    }
    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        val transport = NasTransport.get(this)
        MethodChannel(engine.dartExecutor.binaryMessenger, "singverse/nas").setMethodCallHandler { call, result ->
            worker.execute {
                try {
                    val value: Any? = when (call.method) {
                        "restore" -> transport.origin
                        "configure" -> transport.configure(call.argument<String>("origin")!!)
                        "forget" -> { transport.forget(); null }
                        "request" -> transport.request(call.argument<String>("path")!!, call.argument<String>("method") ?: "GET", call.argument<String>("body"))
                        "bytes" -> transport.bytes(call.argument<String>("path")!!)
                        "inspect" -> transport.inspect(call.argument<String>("origin")!!, call.argument<String>("invitation"))
                        else -> throw IllegalArgumentException("Unknown NAS method")
                    }
                    main.post { result.success(value) }
                } catch (e: Exception) {
                    main.post { result.error(if (e is NasFailure) "${e.status}" else "network", if (e is NasFailure) e.message else "连接 NAS 失败，请检查地址和网络", if (e is NasFailure) e.retryAfter else null) }
                }
            }
        }
        EventChannel(engine.dartExecutor.binaryMessenger, "singverse/playback-events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                events = sink
                PlaybackService.listener = { main.post { events?.success(it) } }
                PlaybackService.current?.let { sink.success(snapshotForIntent(it)) }
            }
            override fun onCancel(arguments: Any?) { events = null; PlaybackService.listener = null }
        })
        MethodChannel(engine.dartExecutor.binaryMessenger, "singverse/player").setMethodCallHandler { call, result ->
            withPlayer(result) { service ->
                when (call.method) {
                    "open" -> {
                        service.open(JSONObject(call.argument<String>("song")!!), call.argument<String>("instanceId")!!,
                            call.argument<Number>("positionMs")?.toLong() ?: 0, call.argument<Boolean>("force") ?: false,
                            call.argument<Boolean>("autoplay") ?: true)
                        videoViews.forEach { it.player = service.player }; result.success(null)
                    }
                    "command" -> { service.command(call.argument<String>("action")!!, call.argument<Any>("value")); result.success(null) }
                    "snapshot" -> result.success(service.snapshot())
                    else -> result.notImplemented()
                }
            }
        }
        MethodChannel(engine.dartExecutor.binaryMessenger, "singverse/karaoke").setMethodCallHandler { call, result ->
            if (call.method == "permission") {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) result.success("granted")
                else if (permissionResult != null) result.error("permission", "正在申请麦克风权限", null)
                else {
                    val prefs = getSharedPreferences("mic-permission", MODE_PRIVATE)
                    if (prefs.getBoolean("asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) result.success("settings")
                    else { permissionResult = result; prefs.edit().putBoolean("asked", true).apply(); requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7402) }
                }
            } else if (call.method == "notifications") {
                val prefs = getSharedPreferences("mic-permission", MODE_PRIVATE)
                if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || prefs.getBoolean("notificationsAsked", false)) result.success(null)
                else if (notificationResult != null) result.error("permission", "正在申请通知权限", null)
                else { notificationResult = result; prefs.edit().putBoolean("notificationsAsked", true).apply(); requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7404) }
            } else if (call.method == "settings") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); result.success(null)
            } else withPlayer(result) { service ->
                val singing = service.karaoke
                val id = call.argument<String>("id").orEmpty()
                when (call.method) {
                    "test" -> { singing.testMicrophone(call.argument<Boolean>("start") == true); result.success(null) }
                    "end" -> { require(id == singing.takeId) { "录音标识已改变" }; service.finishRecording("主动结束录唱"); result.success(null) }
                    "render" -> { singing.render(id, call.argument<Number>("backing")?.toDouble() ?: .7, call.argument<Number>("voice")?.toDouble() ?: 1.0, call.argument<Number>("shift")?.toInt() ?: 0); result.success(null) }
                    "works" -> worker.execute { try { val data = org.json.JSONArray(singing.works.all()).toString(); main.post { result.success(data) } } catch (e: Exception) { main.post { result.error("works", e.message, null) } } }
                    "get" -> result.success(singing.works.get(id).toString())
                    "save" -> worker.execute { try { val data = singing.save(id, call.argument<String>("name").orEmpty()).toString(); main.post { result.success(data) } } catch (e: Exception) { main.post { result.error("save", e.message, null) } } }
                    "rename" -> { val record = singing.works.get(id); val name = call.argument<String>("name").orEmpty().trim(); require(name.isNotEmpty() && name.length <= 120); record.put("name", name); singing.works.put(record); result.success(null) }
                    "delete" -> { require(id != singing.takeId || singing.phase !in listOf("RECORDING", "FINALIZING", "RENDERING")); if (service.snapshot()["workId"] == id) service.command("stop", null); singing.discard(id); result.success(null) }
                    "play" -> { service.playWork(id); result.success(null) }
                    "export" -> {
                        require(exportResult == null) { "请先完成上一次导出" }
                        val record = singing.works.get(id); singing.works.playable(id)
                        exportingId = id; exportResult = result
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("audio/mp4").addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, record.optString("name", "我的演唱") + ".m4a"), 7403)
                    }
                    else -> result.notImplemented()
                }
            }
        }
        engine.platformViewsController.registry.registerViewFactory("singverse/video", object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
            override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
                val view = PlayerView(context).apply { useController = false; player = PlaybackService.current?.player }
                videoViews.add(view)
                return object : PlatformView {
                    override fun getView(): View = view
                    override fun dispose() { view.player = null; videoViews.remove(view) }
                }
            }
        })
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 7404) { notificationResult?.success(null); notificationResult = null }
        if (requestCode == 7402) {
            permissionResult?.success(if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) "granted" else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) "settings" else "denied")
            permissionResult = null
        }
    }
    @Deprecated("Activity result API is retained for the platform bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7403) {
            val result = exportResult ?: return; exportResult = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { result.success(false); return }
            val id = exportingId
            worker.execute {
                try {
                    val file = PlaybackService.current!!.karaoke.works.playable(id)
                    contentResolver.openOutputStream(uri, "w").use { output -> requireNotNull(output); file.inputStream().use { it.copyTo(output) } }
                    main.post { result.success(true) }
                } catch (e: Exception) { main.post { result.error("export", "导出失败，原作品仍保留", null) } }
            }
        }
    }
    override fun onStart() { super.onStart(); videoViews.forEach { it.player = PlaybackService.current?.player } }
    override fun onStop() { PlaybackService.current?.karaoke?.testMicrophone(false); videoViews.forEach { it.player = null }; super.onStop() }
    override fun onDestroy() {
        events = null; PlaybackService.listener = null
        worker.shutdown(); super.onDestroy()
    }
}
