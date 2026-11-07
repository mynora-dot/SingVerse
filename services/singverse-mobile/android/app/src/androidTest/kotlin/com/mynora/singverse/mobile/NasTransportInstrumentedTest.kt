package com.mynora.singverse.mobile

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NasTransportInstrumentedTest {
    @Test fun returningToTheSameOriginStillRejectsAnOldPlaybackGenerationBeforeConnecting() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "transport-generation-${UUID.randomUUID()}"
        val testContext = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        }
        val transport = NasTransport(testContext)
        try {
            ServerSocket(0).use { first -> ServerSocket(0).use { second ->
                val origin = "http://127.0.0.1:${first.localPort}"
                transport.configure(origin); val old = transport.connectionIdentity()
                transport.configure("http://127.0.0.1:${second.localPort}"); transport.configure(origin)
                try {
                    transport.scopedRequest(old, "/api/v1/mobile/playback-attempts", "POST", "{}", NasRequestScope(1000))
                    fail("old generation must be rejected")
                } catch (failure: NasFailure) { assertEquals(409, failure.status) }
                first.soTimeout = 250
                try { first.accept().close(); fail("old attempt must not connect or send current credentials") }
                catch (_: java.net.SocketTimeoutException) { }
            } }
        } finally { target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun switchingNasRejectsLateCookiesAndKeepsTheNewNasSession() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "transport-switch-test-${UUID.randomUUID()}"
        val testContext = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        }
        val transport = NasTransport(testContext)
        val executor = Executors.newFixedThreadPool(3)
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ServerSocket(0).use { first -> ServerSocket(0).use { second ->
                val originA = "http://127.0.0.1:${first.localPort}"
                val originB = "http://127.0.0.1:${second.localPort}"
                transport.configure(originA)
                val replyA = executor.submit {
                    first.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        received.countDown(); check(release.await(5, TimeUnit.SECONDS))
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nSet-Cookie: singverse_session=late-A; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    }
                }
                val requestA = executor.submit<Int> {
                    try { transport.request("/api/v1/slow"); 200 } catch (failure: NasFailure) { failure.status }
                }
                assertTrue(received.await(3, TimeUnit.SECONDS))
                val replyB = executor.submit {
                    second.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nSet-Cookie: singverse_session=current-B; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    }
                }
                transport.configure(originB)
                assertEquals("{}", transport.request("/api/v1/current"))
                replyB.get(3, TimeUnit.SECONDS)
                assertTrue(transport.cookieHeader("$originB/api/v1/media").contains("current-B"))
                release.countDown()
                assertEquals(409, requestA.get(3, TimeUnit.SECONDS)); replyA.get(3, TimeUnit.SECONDS)
                assertEquals(originB, transport.origin)
                val currentCookies = transport.cookieHeader("$originB/api/v1/media")
                assertTrue(currentCookies.contains("current-B")); assertFalse(currentCookies.contains("late-A"))
                val restored = NasTransport(testContext)
                assertEquals(originB, restored.origin)
                assertEquals(currentCookies, restored.cookieHeader("$originB/api/v1/media"))
            } }
        } finally {
            release.countDown(); executor.shutdownNow()
            target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun slowRequestDoesNotHoldCookieLockAndCannotRestoreForgottenSession() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "transport-test-${UUID.randomUUID()}"
        val testContext = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        }
        val transport = NasTransport(testContext)
        val executor = Executors.newFixedThreadPool(3)
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ServerSocket(0).use { server ->
                val origin = "http://127.0.0.1:${server.localPort}"
                transport.configure(origin)
                val reply = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        received.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nSet-Cookie: singverse_session=late; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    }
                }
                val request = executor.submit<Int> {
                    try { transport.request("/api/v1/slow"); 200 } catch (failure: NasFailure) { failure.status }
                }
                assertTrue(received.await(3, TimeUnit.SECONDS))
                assertEquals("", executor.submit<String> { transport.cookieHeader("$origin/api/v1/media") }.get(1, TimeUnit.SECONDS))
                executor.submit { transport.forget() }.get(1, TimeUnit.SECONDS)
                release.countDown()
                assertEquals(409, request.get(3, TimeUnit.SECONDS))
                reply.get(3, TimeUnit.SECONDS)
                assertEquals("", transport.cookieHeader("$origin/api/v1/media"))
            }
        } finally {
            release.countDown(); executor.shutdownNow()
            target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
