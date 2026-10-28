package com.mynora.singverse.media.metadata;

import java.util.List;
import java.util.Set;

public final class MetadataDtos {
    private MetadataDtos() {}
    public record Settings(boolean enabled, List<MusicProvider> providers, boolean autoApply, double threshold, Integer minIntervalSeconds, Integer maxIntervalSeconds) {}
    public record Origin(MusicProvider provider, String externalId, String appliedAt, String kind) {
        public Origin(MusicProvider provider, String externalId, String appliedAt) { this(provider, externalId, appliedAt, "PLATFORM"); }
    }
    public record Applied(ExternalTrack track, Set<String> fields, String appliedAt, boolean stale, java.util.Map<String, Origin> origins) {}
    public record Candidate(String id, ExternalTrack track, double score) {}
    public record SearchInput(String keyword, List<MusicProvider> providers) {}
    public record ProviderError(MusicProvider provider, String message) {}
    public record SearchResult(List<Candidate> candidates, List<ProviderError> errors) {}
    public record ApplyInput(String candidateId, Set<String> fields) {}
    public record StartInput(boolean all, List<String> songIds) {}
    public record Item(String id, String songId, String title, List<String> artists, String status,
            List<Candidate> candidates, String error, String updatedAt, String confidence, boolean coverAvailable, String sourceId, String relativePath) {}
    public record Batch(String id, String mode, String status, int total, int completed,
            int pending, int processing, int review, int applied, int failed, int skippedExisting,
            boolean autoApply, double threshold, String createdAt, String updatedAt,
            List<Item> items, int page, int size, long totalElements, int processableTotal, int legacySkipped, String observedAt) {}
}
