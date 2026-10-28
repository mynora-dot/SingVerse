package com.mynora.singverse.media.metadata;

import java.util.*;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import static com.mynora.singverse.media.metadata.MetadataDtos.*;
public final class SingleReviewDtos {
    private SingleReviewDtos() {}
    public record Review(Song song, String fingerprint, String version, Map<String,Object> values) {}
    public record Apply(String requestId, String fingerprint, String version, String candidateId,
            Set<String> fields, Map<String,Object> edits, String coverCandidateId, String coverAssetId) {}
    public record Result(Applied metadata, Set<String> updated, Set<String> skipped, Set<String> retained, Map<String,Object> before, Map<String,Object> after) {
        public Result {
            updated=Collections.unmodifiableSet(new TreeSet<>(updated)); skipped=Collections.unmodifiableSet(new TreeSet<>(skipped)); retained=Collections.unmodifiableSet(new TreeSet<>(retained));
            if (metadata!=null) metadata=new Applied(metadata.track(),Collections.unmodifiableSet(new TreeSet<>(metadata.fields())),metadata.appliedAt(),metadata.stale(),new TreeMap<>(metadata.origins()));
        }
    }
    public record Asset(String id, String mime, int width, int height, String createdAt) {}
    public record CoverChoice(String candidateId, MusicProvider provider, String externalId, String title,
            List<String> artists, String album, String coverUrl, Integer width, Integer height) {}
    public record Covers(List<CoverChoice> items, List<ProviderError> errors) {}
}
