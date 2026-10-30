package com.mynora.singverse.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasCookiesInstrumentedTest {
    @Test fun expiredRestoredCookieIsReplacedAndClearedOnAndroid() {
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val origin = "http://192.168.1.102:18080"
        NasCookies.restore(jar, origin, "singverse_csrf", "expired", false)
        val endpoint = URI("$origin/api/v1/auth/csrf")
        jar.put(endpoint, mapOf("Set-Cookie" to listOf("singverse_csrf=fresh; Path=/; HttpOnly; SameSite=Lax")))
        val cookies = jar.get(endpoint, emptyMap())["Cookie"].orEmpty().joinToString("; ")
        assertEquals(1, jar.cookieStore.cookies.size)
        assertTrue(cookies.contains("singverse_csrf=fresh"))
        assertFalse(cookies.contains("expired"))
        jar.put(endpoint, mapOf("Set-Cookie" to listOf("singverse_csrf=; Path=/; Max-Age=0")))
        assertTrue(jar.cookieStore.cookies.isEmpty())
    }
}
