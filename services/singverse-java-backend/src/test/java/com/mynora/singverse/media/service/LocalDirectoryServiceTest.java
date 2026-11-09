package com.mynora.singverse.media.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class LocalDirectoryServiceTest {
    @TempDir Path temp;
    private final LocalDirectoryService service = new LocalDirectoryService();

    @Test void filtersAllChildrenBeforePaginationAndNeverReturnsFilesOrLinks() throws Exception {
        for (int i = 0; i < 102; i++) Files.createDirectory(temp.resolve(String.format("歌曲-%03d", i)));
        Files.createDirectory(temp.resolve("other"));
        Files.writeString(temp.resolve("歌曲.txt"), "not a directory");
        Files.createSymbolicLink(temp.resolve("歌曲-link"), temp.resolve("歌曲-000"));
        var first = service.list(temp.toString(), 0, "歌曲");
        assertThat(first.items()).hasSize(100);
        assertThat(first.hasMore()).isTrue();
        var second = service.list(temp.toString(), 1, "歌曲");
        assertThat(second.items()).extracting(LocalDirectoryService.Entry::name).containsExactly("歌曲-100", "歌曲-101");
        assertThat(second.hasMore()).isFalse();
        assertThat(service.list(temp.toString(), 0, "OTHER").items()).extracting(LocalDirectoryService.Entry::name).containsExactly("other");
        assertThat(service.list(temp.toString(), 0, "missing").items()).isEmpty();
    }

    @Test void decodesMountTargetsAndRetainsReadOnlyStateWithoutHostSources() {
        assertThat(LocalDirectoryService.parseMount("42 30 8:1 /private/host /mnt/我的\\040歌曲 ro,relatime - ext4 /dev/host rw"))
                .isEqualTo(Map.entry(Path.of("/mnt/我的 歌曲"), true));
        assertThat(LocalDirectoryService.parseMount("42 30 8:1 /private/host /downloads rw,relatime - ext4 /dev/host rw"))
                .isEqualTo(Map.entry(Path.of("/downloads"), false));
        assertThat(LocalDirectoryService.parseMount("42 30 8:1 / /data/music rw - ext4 /dev/host rw"))
                .isEqualTo(Map.entry(Path.of("/data/music"), false));
        for (String target : new String[]{"/", "/proc", "/sys/devices", "/dev/shm", "/etc/hosts", "/run/secrets", "/secrets", "/data", "/opt/singverse"})
            assertThat(LocalDirectoryService.parseMount("42 30 8:1 / " + target + " rw - ext4 /dev/host rw")).isNull();
        assertThat(LocalDirectoryService.parseMount("invalid mountinfo")).isNull();
    }

    @Test void rejectsInvalidQueriesAndPreservesDirectoryCanonicalization() throws Exception {
        Path sub = Files.createDirectory(temp.resolve("含 空格"));
        var page = service.list(sub.resolve("..").toString(), 0, "");
        assertThat(page.path()).isEqualTo(temp.toRealPath().toString());
        assertThat(page.locations()).isEmpty();
        assertThat(service.list("", 0, "").items()).isNotEmpty();
        assertThatThrownBy(() -> service.list("relative", 0, "")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(temp.toString(), -1, "")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(temp.toString(), 0, "x".repeat(101))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(temp.toString(), 0, "x\ny")).isInstanceOf(ResponseStatusException.class);
    }
}
