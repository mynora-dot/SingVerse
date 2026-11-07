package com.mynora.singverse.mobile

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackRequestsTest {
    @Test fun closingStillRunsTheFinalQueuedLeaseCancellation() {
        val requests = PlaybackRequests()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val last = CountDownLatch(1)
        try {
            requests.cancel { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            requests.cancel { last.countDown() }
            requests.close(); release.countDown()
            assertTrue("service release must drain its final bounded lease cleanup", last.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); requests.close() }
    }
    @Test fun slowCleanupHeartbeatAndMetadataCannotBlockANewSong() {
        val requests = PlaybackRequests()
        val blocked = CountDownLatch(3); val release = CountDownLatch(1)
        val scope = NasRequestScope()
        val hold = { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)); Unit }
        try {
            requests.cancel(hold); requests.poll(scope, hold); requests.metadata(scope, hold)
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            val opened = CountDownLatch(1)
            requests.open(NasRequestScope()) { opened.countDown() }
            assertTrue("new song negotiates while old I/O is still blocked", opened.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); requests.close() }
    }

    @Test fun aSupersededQueuedSongNeverStartsItsRequest() {
        val requests = PlaybackRequests()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val old = NasRequestScope(); val stale = CountDownLatch(1); val latest = CountDownLatch(1)
        try {
            requests.open(NasRequestScope()) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            requests.open(old) { stale.countDown() }; old.cancel()
            requests.open(NasRequestScope()) { latest.countDown() }
            release.countDown()
            assertTrue(latest.await(2, TimeUnit.SECONDS)); assertEquals(1, stale.count)
        } finally { release.countDown(); requests.close() }
    }

    @Test fun cancellingAnAttemptClosesAnActuallyBlockedHttpCall() {
        val worker = Executors.newFixedThreadPool(2)
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        val client = OkHttpClient(); val scope = NasRequestScope()
        try {
            ServerSocket(0).use { server ->
                val response = worker.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        received.countDown(); release.await(5, TimeUnit.SECONDS)
                    }
                }
                val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/slow").build())
                scope.attach(call)
                val result = worker.submit<Boolean> {
                    try { call.execute().use { false } } catch (_: IOException) { true } finally { scope.detach(call) }
                }
                assertTrue(received.await(2, TimeUnit.SECONDS)); scope.cancel()
                assertTrue("cancel must interrupt the socket before server responds", result.get(1, TimeUnit.SECONDS))
                assertTrue(call.isCanceled())
                val later = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/must-not-run").build())
                assertThrows(IOException::class.java) { scope.attach(later) }; assertTrue(later.isCanceled())
                release.countDown(); response.get(2, TimeUnit.SECONDS)
            }
        } finally { release.countDown(); worker.shutdownNow(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
