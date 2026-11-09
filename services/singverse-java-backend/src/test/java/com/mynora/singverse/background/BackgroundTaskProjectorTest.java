package com.mynora.singverse.background;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackgroundTaskProjectorTest {
    @Test void stateMappingDistinguishesBusinessCompletionAndWaiting() {
        Map.ofEntries(Map.entry("PREPARING", "RUNNING"), Map.entry("PREVIEW", "AWAITING_CONFIRMATION"),
                Map.entry("WAITING", "QUEUED"), Map.entry("SUCCEEDED", "COMPLETED"),
                Map.entry("PAUSED", "PAUSED"), Map.entry("CANCELLING", "CANCELLING"),
                Map.entry("CANCELLED", "CANCELLED"), Map.entry("INTERRUPTED", "INTERRUPTED"),
                Map.entry("FAILED", "FAILED"), Map.entry("EXPIRED", "EXPIRED"), Map.entry("BLOCKED", "BLOCKED"))
                .forEach((raw, expected) -> assertThat(BackgroundTaskProjector.normalized(raw)).isEqualTo(expected));
        assertThat(BackgroundTaskProjector.id("METADATA", "one")).isEqualTo(BackgroundTaskProjector.id("METADATA", "one"))
                .isNotEqualTo(BackgroundTaskProjector.id("CLASSIFICATION", "one"));
    }

    @Test void executionSnapshotIsBoundedAndDoesNotCopyRawConfiguration() throws Exception {
        var json = new ObjectMapper();
        var projector = new BackgroundTaskProjector(json);
        var result = projector.execution(json.readTree("""
            {"stage":"separate","backend":"cpu","modelVersion":"v1","progress":{"frames":100,"completedFrames":150},
             "timingsMs":{"total":2000,"download":-1,"privateAddress":500},
             "nodeOrigin":"http://internal/private","input":{"apiKey":"secret"},"outputPath":"/srv/private/file"}
            """));
        assertThat(result.path("progress").path("completedFrames").asInt()).isEqualTo(100);
        assertThat(result.path("timingsMs").path("total").asInt()).isEqualTo(2000);
        assertThat(result.toString()).doesNotContain("nodeOrigin", "internal", "privateAddress", "outputPath", "secret", "download");
        assertThat(BackgroundTaskProjector.message("超时 https://user:pass@example.invalid/x token=abc /srv/cache/output.wav"))
                .contains("超时").doesNotContain("example.invalid", "user:pass", "abc", "/srv/cache");
        assertThat(BackgroundTaskProjector.message("Authorization: Bearer private-bearer; 连接 10.1.2.3:8090 失败 {\"apiKey\":\"private-key\"}"))
                .contains("连接", "失败").doesNotContain("private-bearer", "private-key", "10.1.2.3");
    }
}
