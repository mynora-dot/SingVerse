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
    public record Location(String name, String path, boolean mounted, boolean readable, boolean writable) {}
    public record DirectoryPage(String path, String parent, List<Entry> items, boolean hasMore,
            List<Location> locations, boolean containerized) {}
    private static final int PAGE_SIZE = 100;

    public DirectoryPage list(String location, int page) {
        return list(location, page, "");
    }

    public DirectoryPage list(String location, int page, String query) {
        if (page < 0 || page > 10000) throw bad("目录页码超出范围");
        String filter = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (filter.length() > 100 || filter.chars().anyMatch(Character::isISOControl)) throw bad("文件夹筛选条件无效");
        boolean containerized = Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"));
        try {
            if (location == null || location.isBlank()) {
                var roots = new ArrayList<Entry>();
                for (Path root : FileSystems.getDefault().getRootDirectories()) {
                    if (Files.isReadable(root)) roots.add(new Entry(root.toString(), root.toString()));
                }
                return new DirectoryPage("", null, roots, false, locations(), containerized);
            }
            if (location.length() > 2048 || location.chars().anyMatch(Character::isISOControl)) throw bad("目录路径无效");
            Path requested = Path.of(location);
            if (!requested.isAbsolute()) throw bad("请选择服务端绝对目录");
            Path path = requested.toRealPath();
            if (!Files.isDirectory(path) || !Files.isReadable(path)) throw bad("目录不存在或没有读取权限");
            try (var children = Files.list(path)) {
                var entries = children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(p))
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(filter))
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()))
                        .skip((long) page * PAGE_SIZE).limit(PAGE_SIZE + 1L)
                        .map(p -> new Entry(p.getFileName().toString(), p.toString())).toList();
                return new DirectoryPage(path.toString(), path.getParent() == null ? "" : path.getParent().toString(),
                        entries.subList(0, Math.min(entries.size(), PAGE_SIZE)), entries.size() > PAGE_SIZE, List.of(), containerized);
            }
        } catch (IOException | java.io.UncheckedIOException | InvalidPathException | SecurityException error) {
            throw bad("目录不存在或无法读取，请检查挂载和权限");
        }
    }

    private List<Location> locations() {
        var mounts = new LinkedHashMap<Path, Boolean>();
        try {
            // Only container-visible targets are returned; never reveal mount sources or require a Docker socket.
            for (String line : Files.readAllLines(Path.of("/proc/self/mountinfo"))) {
                var mount = parseMount(line);
                if (mount != null) mounts.put(mount.getKey(), mount.getValue());
            }
        } catch (IOException | SecurityException ignored) { /* Native platforms can use paths and filesystem roots. */ }
        var paths = new LinkedHashSet<Path>();
        paths.add(Path.of("/media")); paths.add(Path.of("/downloads"));
        paths.addAll(mounts.keySet());
        if (!Files.exists(Path.of("/proc/self/mountinfo"))) paths.add(Path.of("/Volumes"));
        var result = new ArrayList<Location>();
        for (Path path : paths) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
            String name = path.toString().equals("/media") ? "媒体目录" : path.toString().equals("/downloads") ? "下载目录"
                    : path.toString().equals("/Volumes") ? "外部磁盘" : path.getFileName().toString();
            result.add(new Location(name, path.toString(), mounts.containsKey(path), Files.isReadable(path),
                    !Boolean.TRUE.equals(mounts.get(path)) && Files.isWritable(path)));
        }
        return List.copyOf(result);
    }

    static Map.Entry<Path, Boolean> parseMount(String line) {
        String[] fields = line.split(" ");
        if (fields.length < 7 || !line.contains(" - ")) return null;
        String target = fields[4].replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\");
        try {
            Path path = Path.of(target).normalize();
            if (!path.isAbsolute() || path.getParent() == null || target.chars().anyMatch(Character::isISOControl)) return null;
            for (String internal : List.of("/proc", "/sys", "/dev", "/run", "/etc", "/secrets"))
                if (path.startsWith(internal)) return null;
            if (Set.of("/data", "/opt/singverse").contains(path.toString())) return null;
            return Map.entry(path, Arrays.asList(fields[5].split(",")).contains("ro"));
        } catch (InvalidPathException ignored) { return null; }
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
