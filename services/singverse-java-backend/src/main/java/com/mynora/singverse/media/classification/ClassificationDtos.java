package com.mynora.singverse.media.classification;

import java.util.*;

public final class ClassificationDtos {
    private ClassificationDtos() {}
    public static final Set<String> DIMENSIONS = Set.of("LANGUAGE", "DECADE", "GENRE", "MOOD", "SCENE", "CUSTOM");
    public record Settings(boolean enabled, String endpoint, String model, boolean hasKey, int timeoutSeconds, int intervalSeconds, long revision) {}
    public record SaveSettings(boolean enabled, String endpoint, String model, String apiKey, boolean clearKey, int timeoutSeconds, int intervalSeconds) {}
    public record ModelConfig(boolean enabled, String endpoint, String model, String encryptedKey, int timeoutSeconds, int intervalSeconds, long revision) {}
    public record Tag(String id, String dimension, String name, List<String> aliases, boolean active, long revision, long count) {}
    public record SaveTag(String dimension, String name, List<String> aliases, boolean active, long revision) {}
    public record Value(List<String> tagIds, String origin, boolean locked, boolean confirmed) {}
    public record State(String songId, long revision, Map<String,Value> dimensions) {}
    public record Change(String requestId, long revision, Map<String,List<String>> dimensions, Set<String> lockedDimensions) {}
    public record Input(String songId, String title, List<String> artists, String album, String releaseDate, String language, String version, List<String> qualityTags) {}
    public record Suggestion(Map<String,List<String>> dimensions, Map<String,String> reasons, List<String> newTagSuggestions) {}
    public record Start(boolean all, List<String> songIds, Set<String> dimensions, Set<String> autoDimensions) {}
    public record Preview(List<Input> items, int total, Settings model) {}
    public record Item(String id, String songId, String status, Input input, long revision, Suggestion suggestion, String error, State current) {}
    public record Task(String id, String status, Set<String> dimensions, Set<String> autoDimensions, String model, int total, int completed, Map<String,Long> counts, List<Item> items, int page, int size, long totalElements, String createdAt, String updatedAt) {}
    public record Review(String requestId, long revision, Set<String> dimensions) {}
    public record History(String id, String songId, String taskId, String origin, Map<String,Value> before, Map<String,Value> after, long afterRevision, boolean reverted, String createdAt) {}
    public record Undo(String requestId, long revision) {}
}
