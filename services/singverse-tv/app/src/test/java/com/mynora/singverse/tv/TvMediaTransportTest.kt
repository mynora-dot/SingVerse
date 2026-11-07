package com.mynora.singverse.tv

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvMediaTransportTest {
    @Test fun rejectsCrossOriginAndTraversalResources() {
        val origin = "https://nas.local"
        TvMediaTransport.requireSameOrigin(origin, "https://nas.local:443/api/v1/tv/media/segment.ts?token=public")
        for (url in listOf("http://nas.local/api/v1/a", "https://other/api/v1/a", "https://nas.local:444/api/v1/a",
            "https://user@nas.local/api/v1/a", "https://nas.local/admin/", "https://nas.local/api/v1/../secret",
            "https://nas.local/api/v1/%2e%2e/secret", "https://nas.local/api/v1/a#fragment")) {
            assertThrows("accepted $url", Exception::class.java) { TvMediaTransport.requireSameOrigin(origin, url) }
        }
    }

    @Test fun attachesCurrentCredentialButNeverFollowsRedirects() {
        ServerSocket(0).use { server -> ServerSocket(0).use { untrusted ->
            val origin = "http://127.0.0.1:${server.localPort}"
            val external = "http://127.0.0.1:${untrusted.localPort}/api/v1/stolen"
            untrusted.soTimeout = 500
            fun assertUntrustedWasNotContacted() {
                assertThrows(SocketTimeoutException::class.java) { untrusted.accept().use { fail("跨源服务器不应收到连接或凭据") } }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val headers = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                        socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: $external\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        lines
                    }
                }
                val client = TvMediaTransport.client { origin to "device-only-fixture" }
                client.newCall(Request.Builder().url("$origin/api/v1/tv/media/playlist.m3u8").build()).execute().use {
                    assertEquals(302, it.code)
                }
                assertTrue(headers.get(5, TimeUnit.SECONDS).any { it == "Authorization: Bearer device-only-fixture" })
                assertUntrustedWasNotContacted()
                val rejected = assertThrows(java.io.IOException::class.java) {
                    client.newCall(Request.Builder().url(external).build()).execute()
                }
                assertEquals("媒体地址不属于当前 NAS", rejected.message)
                assertUntrustedWasNotContacted()
            } finally { executor.shutdownNow() }
        } }
    }
}
