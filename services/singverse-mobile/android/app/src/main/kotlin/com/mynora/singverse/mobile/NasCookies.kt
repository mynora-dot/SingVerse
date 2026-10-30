package com.mynora.singverse.mobile

import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI

/** Restore host-only cookies through the same path used for Set-Cookie responses. */
internal object NasCookies {
    fun restore(jar: CookieManager, origin: String, name: String, value: String, secure: Boolean) {
        val cookie = HttpCookie(name, value).apply { version = 0 }
        jar.put(URI(origin), mapOf("Set-Cookie" to listOf(
            "$cookie; Path=/; HttpOnly${if (secure) "; Secure" else ""}"
        )))
    }
}
