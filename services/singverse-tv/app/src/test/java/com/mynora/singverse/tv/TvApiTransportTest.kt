package com.mynora.singverse.tv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvApiTransportTest {
    @Test fun cancellationClosesAStalledResponseWithoutWaitingForReadTimeout() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val disconnected = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n{".toByteArray())
                        socket.getOutputStream().flush(); started.countDown()
                        input.read() == -1
                    }
                }
                val job = launch(Dispatchers.Default) { TvApiTransport.request("http://127.0.0.1:${server.localPort}" to "fixture", "/api/v1/tv/test", "GET", null) }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                withTimeout(1500) { job.cancelAndJoin() }
                assertTrue("取消必须关闭socket，不能只丢弃迟到响应", disconnected.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun slowCancellationDoesNotSerializeTheNextPlaybackRequest() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val headers = executor.submit<List<String>> {
                    server.accept().use { first ->
                        val input = first.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        started.countDown()
                        server.accept().use { second ->
                            val lines = mutableListOf<String>()
                            val reader = second.getInputStream().bufferedReader()
                            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                            second.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                            lines
                        }
                    }
                }
                val connection = "http://127.0.0.1:${server.localPort}" to "fixture-current"
                val cancel = launch(Dispatchers.Default) { runCatching { TvApiTransport.request(connection, "/api/v1/tv/transcoding/old/cancel", "POST", null) } }
                try {
                    assertTrue(started.await(3, TimeUnit.SECONDS))
                    val response = withTimeout(2000) { TvApiTransport.request(connection, "/api/v1/tv/playback-resources", "POST", "{}") }
                    assertEquals(200, response.status)
                    val received = headers.get(3, TimeUnit.SECONDS)
                    assertTrue(received.first().contains("/playback-resources"))
                    assertTrue(received.any { it == "Authorization: Bearer fixture-current" })
                } finally { cancel.cancelAndJoin() }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun apiRedirectNeverSendsTheCredentialToAnotherServer() = runBlocking<Unit> {
        ServerSocket(0).use { server -> ServerSocket(0).use { external ->
            external.soTimeout = 400
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${external.localPort}/api/v1/stolen\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
                assertEquals(302, TvApiTransport.request("http://127.0.0.1:${server.localPort}" to "fixture", "/api/v1/test", "GET", null).status)
                request.get(3, TimeUnit.SECONDS)
                assertThrows(SocketTimeoutException::class.java) { external.accept().close() }
            } finally { executor.shutdownNow() }
        } }
    }
}
