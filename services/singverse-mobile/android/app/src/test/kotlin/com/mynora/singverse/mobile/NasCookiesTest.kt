package com.mynora.singverse.mobile

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class NasCookiesTest {
    @Test fun refreshedCsrfReplacesExpiredRestoredCookie() {
        for (origin in listOf("http://192.168.1.102:18080", "http://localhost:18181", "https://nas.local")) {
            val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
            NasCookies.restore(jar, origin, "singverse_csrf", "expired", false)
            val endpoint = URI("$origin/api/v1/auth/csrf")
            jar.put(endpoint, mapOf("Set-Cookie" to listOf("singverse_csrf=fresh; Path=/; HttpOnly; SameSite=Lax")))
            assertEquals(origin, 1, jar.cookieStore.cookies.size)
            val header = jar.get(endpoint, emptyMap())["Cookie"].orEmpty().joinToString("; ")
            assertTrue(header.contains("singverse_csrf=fresh"))
            assertFalse(header.contains("expired"))
            jar.put(endpoint, mapOf("Set-Cookie" to listOf("singverse_csrf=; Path=/; Max-Age=0")))
            assertTrue(jar.cookieStore.cookies.isEmpty())
        }
    }

    @Test fun secureRestoredCookiesStayOnTheirHttpsHost() {
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        NasCookies.restore(jar, "https://nas.local", "singverse_session", "session", true)
        assertTrue(jar.get(URI("https://nas.local/api/v1/auth/session"), emptyMap())["Cookie"].orEmpty().isNotEmpty())
        assertTrue(jar.get(URI("http://nas.local/api/v1/auth/session"), emptyMap())["Cookie"].orEmpty().isEmpty())
        assertTrue(jar.get(URI("https://other.local/api/v1/auth/session"), emptyMap())["Cookie"].orEmpty().isEmpty())
    }
}
