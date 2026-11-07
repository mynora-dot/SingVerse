package com.mynora.singverse.media.service;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class CancellableWebDavConnectionTest {
    HttpServer server;
    HttpClient client;
    ExecutorService workers;
    final CountDownLatch requested = new CountDownLatch(1);
    final CountDownLatch headers = new CountDownLatch(1);
    final CountDownLatch tail = new CountDownLatch(1);
    final AtomicReference<String> range = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        workers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/media", exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            requested.countDown();
            try {
                if (!headers.await(10, TimeUnit.SECONDS)) return;
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-9/10");
                exchange.sendResponseHeaders(206, 10);
                try (var output = exchange.getResponseBody()) {
                    output.write(42); output.flush();
                    if (tail.await(10, TimeUnit.SECONDS)) output.write(new byte[9]);
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException cancelled) { /* Closing the client while a server gate is held is expected. */ }
            finally { exchange.close(); }
        });
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterEach void close() throws Exception {
        headers.countDown(); tail.countDown();
        if (client != null) { client.shutdownNow(); assertThat(client.awaitTermination(Duration.ofSeconds(2))).isTrue(); }
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }
    CancellableWebDavConnection connection() throws Exception {
        return new CancellableWebDavConnection(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media").toURL(), client);
    }

    @Test void cancellingBeforeResponseHeadersReturnsImmediatelyAndUnblocksTheWaitingRequest() throws Exception {
        var connection = connection();
        Future<Integer> waiting = workers.submit(() -> { return connection.getResponseCode(); });
        assertThat(requested.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(headers.getCount()).isEqualTo(1);
        long started = System.nanoTime();
        connection.disconnect(); connection.disconnect();
        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(1));
        assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(connection::getInputStream).isInstanceOf(InterruptedIOException.class);
        headers.countDown(); // A late server response must not publish a usable body after cancellation.
    }

    @Test void cancellingAStalledBodyDoesNotWaitForTheReaderLockOrTheBodyGate() throws Exception {
        headers.countDown();
        var connection = connection();
        connection.setRequestMethod("GET"); connection.setRequestProperty("Range", "bytes=0-9");
        assertThat(connection.getResponseCode()).isEqualTo(206);
        assertThat(connection.getHeaderField("Content-Range")).isEqualTo("bytes 0-9/10");
        assertThat(connection.getContentLengthLong()).isEqualTo(10);
        assertThat(range.get()).isEqualTo("bytes=0-9");
        var input = connection.getInputStream();
        assertThat(input.read()).isEqualTo(42);
        Future<Integer> reading = workers.submit(() -> { return input.read(); });
        assertThatThrownBy(() -> reading.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        long started = System.nanoTime();
        connection.disconnect();
        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(tail.getCount()).isEqualTo(1);
        assertThatThrownBy(() -> reading.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IOException.class);
        input.close(); connection.disconnect();
    }

    @Test void stalledBodyStillExpiresAfterFiveSecondsAndClosesItsResponse() throws Exception {
        headers.countDown();
        var connection = connection();
        var input = connection.getInputStream();
        assertThat(input.read()).isEqualTo(42);
        long started = System.nanoTime();
        assertThatThrownBy(input::read).isInstanceOf(HttpTimeoutException.class);
        assertThat(System.nanoTime() - started).isBetween(TimeUnit.SECONDS.toNanos(4), TimeUnit.SECONDS.toNanos(8));
        connection.disconnect();
    }

    @Test void disconnectBeforeConnectNeverSendsARequestAndRedirectsRemainDisabled() throws Exception {
        var connection = connection();
        connection.disconnect();
        assertThatThrownBy(connection::getResponseCode).isInstanceOf(InterruptedIOException.class);
        assertThat(requested.getCount()).isEqualTo(1);
        try (var redirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            assertThatThrownBy(() -> new CancellableWebDavConnection(URI.create("http://127.0.0.1/").toURL(), redirects))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
