package com.mynora.singverse.compute;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public final class ComputeNodeDtos {
    private ComputeNodeDtos() {}
    public record PathMapping(String localRoot, String remoteRoot) {}
    public record Settings(String mode, String endpoint, boolean hasToken, List<PathMapping> mappings,
            String automaticEndpoint, boolean automaticHasToken, long revision) {}
    public record Save(String mode, String endpoint, String token, boolean clearToken, List<PathMapping> mappings, long revision) {}
    public record Check(String status, String message, List<String> capabilities, long elapsedMs) {}
    public record Connection(URI origin, String token, List<PathMapping> mappings) {
        public String mediaPath(Path path) {
            var local = path.toAbsolutePath().normalize();
            for (var mapping : mappings) {
                var root = Path.of(mapping.localRoot()).normalize();
                if (local.startsWith(root)) {
                    String remote=mapping.remoteRoot();boolean windows=remote.matches("^[A-Za-z]:[\\\\/].*");
                    String relative=root.relativize(local).toString().replace('\\','/');
                    String joined=remote.replace('\\','/').replaceAll("/+$", "")+"/"+relative;
                    return windows?joined.replace('/','\\'):joined;
                }
            }
            return local.toString();
        }
    }
}
