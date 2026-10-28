package com.mynora.singverse.media.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Administrator-only browsing of the filesystem visible to this service. Never reads file contents. */
@Service
public class LocalDirectoryService {
    public record Entry(String name, String path) {}
    public record DirectoryPage(String path, String parent, List<Entry> items, boolean hasMore) {}
    private static final int PAGE_SIZE = 100;

    public DirectoryPage list(String location, int page) {
        if (page < 0 || page > 10000) throw bad("目录页码超出范围");
        try {
            if (location == null || location.isBlank()) {
                var roots = new ArrayList<Entry>();
                for (Path root : FileSystems.getDefault().getRootDirectories()) {
                    if (Files.isReadable(root)) roots.add(new Entry(root.toString(), root.toString()));
                }
                return new DirectoryPage("", null, roots, false);
            }
            if (location.length() > 2048 || location.chars().anyMatch(Character::isISOControl)) throw bad("目录路径无效");
            Path requested = Path.of(location);
            if (!requested.isAbsolute()) throw bad("请选择服务端绝对目录");
            Path path = requested.toRealPath();
            if (!Files.isDirectory(path) || !Files.isReadable(path)) throw bad("目录不存在或没有读取权限");
            try (var children = Files.list(path)) {
                var entries = children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(p))
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()))
                        .skip((long) page * PAGE_SIZE).limit(PAGE_SIZE + 1L)
                        .map(p -> new Entry(p.getFileName().toString(), p.toString())).toList();
                return new DirectoryPage(path.toString(), path.getParent() == null ? "" : path.getParent().toString(),
                        entries.subList(0, Math.min(entries.size(), PAGE_SIZE)), entries.size() > PAGE_SIZE);
            }
        } catch (IOException | java.io.UncheckedIOException | InvalidPathException | SecurityException error) {
            throw bad("目录不存在或无法读取，请检查挂载和权限");
        }
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
