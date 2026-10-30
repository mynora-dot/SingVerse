package com.mynora.singverse.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*

/** DNS-SD discovery only; every chosen address is verified through server-info before authorization. */
class NasDiscovery(context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    var services by mutableStateOf(listOf<Pair<String,String>>()); private set
    var status by mutableStateOf(""); private set
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0
    @Suppress("DEPRECATION") private fun resolve() {
        if (resolving || pending.isEmpty() || listener == null) return
        resolving = true
        val epoch=generation
        manager.resolveService(pending.removeFirst(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post { resolving = false; resolve() } }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                if(epoch!=generation){resolving=false;resolve();return@post}
                val host = info.host?.hostAddress
                if (host != null && info.port in 1..65535) {
                    val formatted = if (':' in host) "[$host]" else host
                    val address = "http://$formatted:${info.port}"
                    services = (services.filterNot { it.first == info.serviceName } + (info.serviceName to address)).take(20)
                }
                resolving = false; resolve()
            } }
        })
    }
    fun start() {
        stop(); services = emptyList(); status = "正在查找局域网 NAS…"
        val next = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { main.post { status = "正在查找；也可手动输入地址" } }
            override fun onDiscoveryStopped(type: String) { }
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if(listener===this){ status = "发现不可用，请手动输入 NAS 地址"; stop() } } }
            override fun onStopDiscoveryFailed(type: String, code: Int) { }
            override fun onServiceFound(info: NsdServiceInfo) { main.post { if (listener != null) { pending.add(info); resolve() } } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { services = services.filterNot { it.first == info.serviceName } } }
        }
        listener = next
        runCatching { manager.discoverServices("_singverse._tcp.", NsdManager.PROTOCOL_DNS_SD, next) }.onFailure { status = "发现不可用，请手动输入 NAS 地址"; listener = null }
        main.postDelayed({ if (listener === next) { stop(); status = if (services.isEmpty()) "未发现 NAS；请确认服务已发布 mDNS，或手动输入" else "已发现 ${services.size} 个服务" } }, 15000)
    }
    fun stop() { generation++;listener?.let { runCatching { manager.stopServiceDiscovery(it) } }; listener = null; pending.clear() }
}
