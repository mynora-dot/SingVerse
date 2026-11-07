package com.mynora.singverse.media.service;

import com.mynora.singverse.media.transcoding.ProgressTimeoutInputStream;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** A per-request adapter whose cancellation never acquires the blocking reader's lock. */
final class CancellableWebDavConnection extends HttpURLConnection {
    private final HttpClient client;
    private final Object lifecycle = new Object();
    private CompletableFuture<HttpResponse<InputStream>> request;
    private HttpResponse<InputStream> response;
    private ProgressTimeoutInputStream body;
    private boolean cancelled;

    CancellableWebDavConnection(URL url, HttpClient client) {
        super(url);
        this.client = Objects.requireNonNull(client);
        if (client.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("WebDAV redirects must remain disabled");
    }

    private void install(HttpResponse<InputStream> received) {
        if (response == null) {
            response = received;
            body = new ProgressTimeoutInputStream(received.body(), Duration.ofSeconds(5));
            responseCode = received.statusCode();
        }
    }

    private HttpResponse<InputStream> response() throws IOException {
        CompletableFuture<HttpResponse<InputStream>> pending;
        synchronized (lifecycle) {
            if (cancelled) throw cancelled();
            if (request == null) {
                try {
                    var builder = HttpRequest.newBuilder(url.toURI())
                            .timeout(Duration.ofMillis(getReadTimeout() > 0 ? getReadTimeout() : 5000))
                            .method(getRequestMethod(), HttpRequest.BodyPublishers.noBody());
                    getRequestProperties().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                    connected = true;
                    request = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                    request.whenComplete((received, failure) -> {
                        if (received == null) return;
                        InputStream closing = null;
                        synchronized (lifecycle) {
                            install(received);
                            if (cancelled) closing = body;
                        }
                        close(closing);
                    });
                } catch (URISyntaxException | IllegalArgumentException failure) {
                    throw new IOException("Invalid WebDAV request", failure);
                }
            }
            pending = request;
        }
        final HttpResponse<InputStream> received;
        try { received = pending.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            disconnect();
            var failure = new InterruptedIOException("WebDAV request interrupted");
            failure.initCause(interrupted); throw failure;
        } catch (CancellationException stopped) {
            throw cancelled();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException("WebDAV request failed", failure.getCause());
        }
        synchronized (lifecycle) {
            install(received);
            if (cancelled) throw cancelled(); // disconnect/late completion also closes the installed body.
            return response;
        }
    }

    private static InterruptedIOException cancelled() { return new InterruptedIOException("WebDAV request cancelled"); }
    private static void close(InputStream stream) {
        if (stream != null) try { stream.close(); } catch (IOException ignored) { /* The reader observes cancellation. */ }
    }

    @Override public void connect() throws IOException { response(); }
    @Override public int getResponseCode() throws IOException { return response().statusCode(); }
    @Override public InputStream getInputStream() throws IOException {
        response();
        synchronized (lifecycle) {
            if (cancelled) throw cancelled();
            return body;
        }
    }
    @Override public String getHeaderField(String name) {
        try {
            var received = response();
            return name == null ? "HTTP/1.1 " + received.statusCode() : received.headers().firstValue(name).orElse(null);
        } catch (IOException failure) { return null; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        try { return response().headers().map(); }
        catch (IOException failure) { return Map.of(); }
    }
    @Override public long getContentLengthLong() {
        try { return Long.parseLong(Objects.toString(getHeaderField("Content-Length"), "-1")); }
        catch (NumberFormatException invalid) { return -1; }
    }
    @Override public boolean usingProxy() { return client.proxy().isPresent(); }
    @Override public void disconnect() {
        CompletableFuture<HttpResponse<InputStream>> pending;
        InputStream closing;
        synchronized (lifecycle) {
            if (cancelled) return;
            cancelled = true;
            pending = request;
            closing = body;
        }
        if (pending != null) pending.cancel(true);
        close(closing);
    }
}
