package com.mynora.singverse.media.transcoding;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds stalled response bodies, including reads after HttpClient has returned response headers. */
public final class ProgressTimeoutInputStream extends FilterInputStream {
    private static final ScheduledThreadPoolExecutor TIMEOUTS = timeouts();

    private static ScheduledThreadPoolExecutor timeouts() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "compute-body-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long progressed = System.nanoTime();
    private volatile boolean timedOut;
    private volatile ScheduledFuture<?> alarm;

    public ProgressTimeoutInputStream(InputStream input, Duration idle) {
        super(input);
        long nanos = idle.toNanos();
        if (nanos <= 0) throw new IllegalArgumentException("Idle timeout must be positive");
        var scheduled = TIMEOUTS.scheduleWithFixedDelay(() -> {
            if (!closed.get() && System.nanoTime() - progressed >= nanos) {
                timedOut = true;
                try { close(); } catch (IOException ignored) { /* The reader receives the timeout. */ }
            }
        }, nanos, Math.min(nanos, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
        alarm = scheduled;
        // A very short timeout may have closed the body before scheduleWithFixedDelay returned.
        if (closed.get()) scheduled.cancel(false);
    }

    private void check() throws IOException {
        if (timedOut) throw new HttpTimeoutException("Compute response body stopped making progress");
        if (Thread.currentThread().isInterrupted()) {
            close();
            throw new java.io.InterruptedIOException("Compute response body interrupted");
        }
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        check();
        final int count;
        try { count = in.read(bytes, offset, length); }
        catch (IOException error) { check(); throw error; }
        check();
        if (count > 0) progressed = System.nanoTime();
        else if (count < 0) close();
        return count;
    }

    @Override public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            if (alarm != null) alarm.cancel(false);
            in.close();
        }
    }

    @Override public long transferTo(java.io.OutputStream output) throws IOException {
        long total = 0;
        byte[] buffer = new byte[65536];
        for (int count; (count = read(buffer)) >= 0;) {
            output.write(buffer, 0, count);
            total += count;
        }
        return total;
    }
}
