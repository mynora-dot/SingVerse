package com.mynora.singverse.media.filename;

import java.util.Locale;
import java.util.Map;

/** Shared filename allowlist for scanning, parsing and original-media delivery. */
public final class MediaFileTypes {
    private MediaFileTypes() {}
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("mkv", "video/x-matroska"), Map.entry("mp4", "video/mp4"),
            Map.entry("mpg", "video/mpeg"), Map.entry("mpeg", "video/mpeg"),
            Map.entry("avi", "video/x-msvideo"), Map.entry("ts", "video/mp2t"),
            Map.entry("vob", "video/mpeg"), Map.entry("mov", "video/quicktime"),
            Map.entry("wmv", "video/x-ms-wmv"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("flac", "audio/flac"),
            Map.entry("wav", "audio/wav"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("ogg", "audio/ogg"),
            Map.entry("oga", "audio/ogg"), Map.entry("opus", "audio/ogg"),
            Map.entry("wma", "audio/x-ms-wma"), Map.entry("aif", "audio/aiff"),
            Map.entry("aiff", "audio/aiff"), Map.entry("ape", "audio/x-ape"));

    public static boolean supported(String extension) {
        return TYPES.containsKey(extension.toLowerCase(Locale.ROOT));
    }
    public static String contentType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        // Preserve original-video delivery support for previously indexed files.
        return switch (extension) {
            case "m4v" -> "video/mp4"; case "webm" -> "video/webm"; case "m2ts" -> "video/mp2t";
            default -> TYPES.getOrDefault(extension, "application/octet-stream");
        };
    }
}
