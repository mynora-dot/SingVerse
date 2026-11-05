package com.mynora.singverse.tv

import java.net.URI
import java.util.Locale

internal object NasAddress {
    fun normalize(value: String, protocol: String = "http"): String {
        val raw = value.trim().trimEnd('/')
        require(raw.isNotEmpty()) { "请输入 NAS 地址" }
        val uri = URI(if (raw.contains("://")) raw else "$protocol://$raw")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty() &&
            (uri.port == -1 || uri.port in 1..65535)) { "请输入 NAS 根地址，例如 192.168.1.10:18080" }
        val port = if ((scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)) -1 else uri.port
        return URI(scheme, null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toString()
    }

    fun protocol(value: String) = if (value.startsWith("https://", true)) "https" else "http"
    fun authority(value: String) = value.substringAfter("://", value)
    fun approvalUrl(base: String, id: String, code: String): String {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && code.matches(Regex("[0-9]{6}"))) { "授权信息无效" }
        return "${normalize(base)}/admin/#/system/tv-devices?authorization=$id&code=$code"
    }
}

internal data class RecentNas(val address: String, val name: String, val username: String, val lastLogin: Long)
internal fun rememberNas(previous: List<RecentNas>, next: RecentNas): List<RecentNas> =
    (listOf(next) + previous.filterNot { it.address == next.address }).take(10)

internal enum class LoginPage { METHODS, MANUAL, DISCOVERY, QR, HISTORY }
