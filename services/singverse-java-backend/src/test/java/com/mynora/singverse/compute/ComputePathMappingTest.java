package com.mynora.singverse.compute;

import static org.assertj.core.api.Assertions.assertThat;

import com.mynora.singverse.compute.ComputeNodeDtos.*;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

class ComputePathMappingTest {
    @Test
    void remoteWindowsDriveIsNotNormalizedAsALocalLinuxPath() {
        var root = Path.of("media").toAbsolutePath();
        var connection =
                new Connection(
                        URI.create("http://127.0.0.1:8090"),
                        "fixture",
                        List.of(new PathMapping(root.toString(), "D:\\KTV")));
        assertThat(connection.mediaPath(root.resolve("歌手/歌曲.mkv")))
                .isEqualTo("D:\\KTV\\歌手\\歌曲.mkv");
    }
}
