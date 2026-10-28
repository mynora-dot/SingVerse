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
        OffsetDateTime fetchedAt
) {
    public ExternalTrack {
        artists = artists == null ? List.of() : List.copyOf(artists);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        availability = availability == null ? "UNKNOWN" : availability;
        fetchedAt = (fetchedAt == null ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : fetchedAt)
                .withOffsetSameInstant(java.time.ZoneOffset.UTC);
    }
}
