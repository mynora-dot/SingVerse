package com.mynora.singverse.media.transcoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProgressTimeoutInputStreamTest {
    private ScheduledThreadPoolExecutor timeouts() throws Exception {
        var field = ProgressTimeoutInputStream.class.getDeclaredField("TIMEOUTS");
        field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(null);
    }

    @Test void closedBodiesImmediatelyReleaseTheirScheduledWork() throws Exception {
        var executor = timeouts();
        int prior = executor.getQueue().size();
        for (int index = 0; index < 100; index++) {
            try (var stream = new ProgressTimeoutInputStream(new ByteArrayInputStream(new byte[0]), Duration.ofHours(1))) {
                assertThat(stream.read()).isEqualTo(-1);
            }
        }
        assertThat(executor.getQueue()).hasSize(prior);
    }

    @Test void timeoutsDuringConstructionDoNotLeaveRecurringWork() throws Exception {
        var executor = timeouts();
        int prior = executor.getQueue().size();
        var closed = new AtomicInteger();
        for (int index = 0; index < 200; index++) {
            new ProgressTimeoutInputStream(new ByteArrayInputStream(new byte[0]) {
                @Override public void close() { closed.incrementAndGet(); }
            }, Duration.ofNanos(1));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((closed.get() < 200 || executor.getQueue().size() > prior) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(closed).hasValue(200);
        assertThat(executor.getQueue()).hasSize(prior);
    }
}
