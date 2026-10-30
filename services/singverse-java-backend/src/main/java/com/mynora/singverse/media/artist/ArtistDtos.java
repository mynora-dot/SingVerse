package com.mynora.singverse.media.artist;

import java.util.List;

public final class ArtistDtos {
    private ArtistDtos() {}

    public record Save(
            String requestId,
            String name,
            List<String> aliases,
            String type,
            String biography,
            boolean active,
            long revision) {}

    public record Credit(String songId, String credit, long revision) {}

    public record Bind(String artistId, List<Credit> items) {}

    public record Changed() {}
}
