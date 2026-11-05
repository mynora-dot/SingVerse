package com.mynora.singverse.tv

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Owns only the unconnected login flow; leaving a flow invalidates every delayed response. */
internal class TvLogin(context: Context, private val discovery: NasDiscovery, private val authorized: suspend (String, String) -> Unit) {
    private val preferences = context.getSharedPreferences("recent-nas", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var admin: LoginHttp? = null
    private var generation = 0
    private var pending: JSONObject? = null
    private var deadline = 0L
    var page by mutableStateOf(LoginPage.METHODS); private set
    var protocol by mutableStateOf("http")
    var host by mutableStateOf("")
    var username by mutableStateOf("admin")
    var password by mutableStateOf("")
    var mfaCode by mutableStateOf("")
    var mfaRequired by mutableStateOf(false); private set
    var showPassword by mutableStateOf(false)
    var serverName by mutableStateOf("") ; private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var qrUrl by mutableStateOf(""); private set
    var authorizationCode by mutableStateOf(""); private set
    var remaining by mutableIntStateOf(0); private set
    var qrRequested by mutableStateOf(false); private set
    private var loginUsername = ""
    var recent by mutableStateOf(readHistory()); private set

    private fun readHistory(): List<RecentNas> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until minOf(array.length(), 10)).mapNotNull { i -> runCatching {
            val item = array.getJSONObject(i)
            RecentNas(NasAddress.normalize(item.getString("address")), item.getString("name").take(80), item.optString("username").take(80), item.getLong("lastLogin"))
        }.getOrNull() }
    }.getOrDefault(emptyList())

    fun recordSuccess(address: String, name: String) {
        val base = NasAddress.normalize(address)
        recent = rememberNas(recent, RecentNas(base, name.ifBlank { serverName }.ifBlank { NasAddress.authority(base) }.take(80), (loginUsername.ifBlank { recent.firstOrNull { it.address == base }?.username.orEmpty() }).take(80), System.currentTimeMillis()))
        preferences.edit().putString("items", JSONArray().apply {
            recent.forEach { put(JSONObject().put("address", it.address).put("name", it.name).put("username", it.username).put("lastLogin", it.lastLogin)) }
        }.toString()).apply()
    }

    fun setAddress(value: String) {
        val base = NasAddress.normalize(value)
        protocol = NasAddress.protocol(base); host = NasAddress.authority(base)
    }

    fun open(destination: LoginPage, forQr: Boolean = false) {
        cancel()
        qrRequested = forQr
        page = destination
        if (destination == LoginPage.DISCOVERY) discovery.start()
    }

    fun select(name: String, address: String, account: String = "") {
        val qr = qrRequested
        cancel()
        setAddress(address); serverName = name; username = account.ifBlank { "admin" }
        qrRequested = qr; page = LoginPage.MANUAL
        if (qr) startQr()
    }

    fun back(): Boolean {
        if (page == LoginPage.METHODS) return false
        val target = if (page == LoginPage.QR) LoginPage.MANUAL else LoginPage.METHODS
        open(target)
        return true
    }

    fun cancel() {
        generation++; val previousJob = job; previousJob?.cancel(); job = null
        val session = admin; admin = null
        if (session != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { previousJob?.join(); runCatching { session.logout() } } }
        discovery.stop(); pending = null; qrUrl = ""; authorizationCode = ""; remaining = 0
        password = ""; mfaCode = ""; mfaRequired = false; showPassword = false; error = ""; busy = false
    }

    private fun launch(task: suspend (Int) -> Unit) {
        if (busy) return
        val turn = ++generation
        job = scope.launch {
            busy = true; error = ""
            try { task(turn) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (turn == generation) {
                    error = failure.message ?: "连接失败，请重试"
                    if (page == LoginPage.QR) { qrUrl = ""; authorizationCode = ""; remaining = 0 }
                    // A failed MFA attempt may be retried with the same restricted challenge.
                    if (!mfaRequired) { val session = admin; admin = null; if (session != null) runCatching { session.logout() } }
                }
            } finally { if (turn == generation) busy = false }
        }
    }

    private suspend fun prepare(turn: Int): LoginHttp {
        val session = LoginHttp(NasAddress.normalize(host, protocol))
        val info = session.request("/api/v1/server-info")
        check(turn == generation)
        require(info.optString("service") == "singverse" && info.optInt("apiVersion") == 1) { "不是兼容的 SingVerse 服务" }
        serverName = info.optString("name").ifBlank { serverName }.ifBlank { NasAddress.authority(session.base) }
        pending = session.request("/api/v1/device-authorizations", "POST", JSONObject().put("name", android.os.Build.MODEL.take(60)))
        check(turn == generation)
        deadline = SystemClock.elapsedRealtime() + 600000
        return session
    }

    fun startQr() {
        if (busy) return
        cancel(); page = LoginPage.QR; qrRequested = true; loginUsername = ""
        launch { turn ->
            val session = prepare(turn)
            val request = pending ?: return@launch
            authorizationCode = request.getString("code")
            qrUrl = NasAddress.approvalUrl(session.base, request.getString("id"), authorizationCode)
            busy = false
            poll(session, turn)
        }
    }

    fun submit() {
        if (mfaRequired) { verifyMfa(); return }
        if (busy) return
        val suppliedPassword = password
        password = ""
        launch { turn ->
            require(username.isNotBlank() && suppliedPassword.isNotEmpty()) { "请输入账号和密码" }
            val session = prepare(turn); admin = session; loginUsername = username.trim()
            val state = session.adminPost("/api/v1/auth/login", JSONObject().put("username", loginUsername).put("password", suppliedPassword)).getString("state")
            if (state == "mfaRequired") mfaRequired = true else {
                require(state == "authenticated") { "未知登录状态" }
                approve(session, turn)
            }
        }
    }

    private fun verifyMfa() {
        val code = mfaCode.trim(); mfaCode = ""
        launch { turn ->
            require(code.isNotBlank()) { "请输入验证码或恢复码" }
            val session = admin ?: error("登录已过期，请返回重新登录")
            require(session.adminPost("/api/v1/auth/mfa/verify", JSONObject().put("code", code)).optString("state") == "authenticated") { "二次验证失败" }
            mfaRequired = false
            approve(session, turn)
        }
    }

    private suspend fun approve(session: LoginHttp, turn: Int) {
        try {
            val request = pending ?: error("设备授权已过期，请重新登录")
            session.adminPost("/api/v1/admin/device-authorizations/${request.getString("id")}/approve", JSONObject().put("code", request.getString("code")))
        } finally { withContext(NonCancellable) { runCatching { session.logout() }; if (admin === session) admin = null } }
        poll(session, turn)
    }

    private suspend fun poll(session: LoginHttp, turn: Int) {
        val request = pending ?: return
        while (turn == generation) {
            remaining = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000).toInt()
            require(remaining > 0) { "二维码或授权已过期，请重新申请" }
            val result = session.request("/api/v1/device-authorizations/${request.getString("id")}/poll", "POST", JSONObject().put("pollSecret", request.getString("pollSecret")))
            if (turn != generation) return
            when (result.optString("status")) {
                "DENIED" -> error("管理员拒绝了本次连接，请核对 NAS 后重试")
                "APPROVED" -> {
                    busy = true; pending = null; qrUrl = ""; password = ""; mfaCode = ""
                    authorized(session.base, result.getString("token")); return
                }
            }
            delay(1000)
        }
    }

    fun clearError() { error = "" }

    fun close() { cancel(); scope.cancel() }
}
