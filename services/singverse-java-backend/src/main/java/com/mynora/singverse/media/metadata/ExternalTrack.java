// Adapted from ktv-home (MIT); see docs/third-party/ktv-home-LICENSE.txt.
package com.mynora.singverse.media.metadata;

import java.time.OffsetDateTime;
import java.util.List;

public record ExternalTrack(
        MusicProvider provider,
        String externalId,
        String title,
        List<String> artists,
        String album,
        Integer durationMs,
        String releaseDate,
        List<String> aliases,
        String coverUrl,
        String availability,
        OffsetDateTime fetchedAt,
        List<ArtistRef> artistRefs) {
    public record ArtistRef(MusicProvider provider, String id, String name) {}

    public ExternalTrack(
            MusicProvider provider,
            String externalId,
            String title,
            List<String> artists,
            String album,
            Integer durationMs,
            String releaseDate,
            List<String> aliases,
            String coverUrl,
            String availability,
            OffsetDateTime fetchedAt) {
        this(
                provider,
                externalId,
                title,
                artists,
                album,
                durationMs,
                releaseDate,
                aliases,
                coverUrl,
                availability,
                fetchedAt,
                List.of());
    }

    public ExternalTrack withArtistRefs(List<ArtistRef> refs) {
        return new ExternalTrack(
                provider,
                externalId,
                title,
                artists,
                album,
                durationMs,
                releaseDate,
                aliases,
                coverUrl,
                availability,
                fetchedAt,
                refs);
    }

    public ExternalTrack {
        artistRefs = artistRefs == null ? List.of() : List.copyOf(artistRefs);
        artists = artists == null ? List.of() : List.copyOf(artists);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        availability = availability == null ? "UNKNOWN" : availability;
        fetchedAt =
                (fetchedAt == null ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : fetchedAt)
                        .withOffsetSameInstant(java.time.ZoneOffset.UTC);
    }
}
