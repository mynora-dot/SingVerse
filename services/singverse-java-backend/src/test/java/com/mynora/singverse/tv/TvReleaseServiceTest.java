package com.mynora.singverse.tv;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class TvReleaseServiceTest {
    @TempDir Path root;

    @Test
    void hashesBytesRejectsBrokenManifestAndSymlinks() throws Exception {
        var releases = new TvReleaseService(root.toString(), new ObjectMapper());
        assertThatThrownBy(releases::current).hasMessageContaining("503");
        Files.writeString(
                root.resolve("release.json"), "{\"versionCode\":2,\"versionName\":\"0.2\"}");
        Files.writeString(root.resolve("singverse-tv.apk"), "abc");
        var result = releases.current();
        assertThat(result.get("size")).isEqualTo(3L);
        assertThat(result.get("sha256"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        Files.delete(root.resolve("singverse-tv.apk"));
        Files.createSymbolicLink(root.resolve("singverse-tv.apk"), root.resolve("release.json"));
        assertThatThrownBy(releases::current).hasMessageContaining("503");
    }

    @Test
    void normalizationDoesNotInventChineseMetadata() {
        assertThat(TvLibrary.searchable("周杰伦 晴天")).contains("zhoujielunqingtian", "zjlqt");
        assertThat(TvLibrary.normalize(" ＡＢＣ １２３ ")).isEqualTo("abc123");
    }
}
