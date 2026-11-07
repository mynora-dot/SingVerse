package com.mynora.singverse.media.model;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import java.util.Map;
import java.util.Objects;

/** Playback follows observed bytes, independently of the deliberately frozen metadata identity. */
public final class PlaybackFingerprint {
    private PlaybackFingerprint() {}

    public static String content(String id, Long bytes, String modifiedAt, String etag, String scannedAt) {
        String modified = nonblank(modifiedAt), version = nonblank(etag);
        boolean complete = bytes != null && bytes >= 0 && (modified != null || version != null);
        return digest("content:v2", id, Objects.toString(bytes, null), modified, version,
                complete ? null : scannedAt);
    }

    /** Only a digest leaves the repository; encrypted credentials never enter a response DTO. */
    public static String configuration(String id, String type, String location, String username, String encrypted) {
        return digest("source:v2", id, type, location, username, encrypted);
    }

    public static String configuration(String id, Map<String, Object> row) {
        return configuration(id, (String) row.get("source_type"), (String) row.get("location"),
                (String) row.get("username"), (String) row.get("password_encrypted"));
    }

    public static String of(Song song, Source source) {
        String content = song.playbackFingerprint() == null
                ? content(song.id(), song.fileSize(), song.modifiedAt(), null, song.scannedAt()) : song.playbackFingerprint();
        // Directly constructed snapshots without a repository identity remain conservative.
        String configuration = source.configurationFingerprint() == null
                ? digest("source:snapshot", source.id(), source.type(), source.location(), source.username(), source.updatedAt())
                : source.configurationFingerprint();
        return digest("playback:v2", content, song.relativePath(), configuration);
    }

    private static String nonblank(String value) { return value == null || value.isBlank() ? null : value; }
    private static String digest(String... values) {
        var input = new StringBuilder();
        for (String value : values) {
            if (value == null) input.append("-1:");
            else input.append(value.length()).append(':').append(value);
        }
        return AuthCrypto.hash(input.toString());
    }
}
