package com.mynora.singverse.media.dto;

import com.mynora.singverse.media.filename.FilenameParseResult;
import java.util.List;

public final class MediaSongDtos {
    private MediaSongDtos() {}

    public record Song(String id, String sourceId, String sourceName, String relativePath, Long fileSize,
            String modifiedAt, String scannedAt, String parserProfileId, long parserRevision,
            String selectedRuleId, FilenameParseResult result,
            com.mynora.singverse.media.metadata.MetadataDtos.Applied metadata, com.mynora.singverse.media.classification.ClassificationDtos.State classification) {}
    public record Page(List<Song> items, int page, int size, long totalElements) {}
    public record Scan(String sourceId, String parserProfileId, String parserProfileName,
            long parserRevision, int totalSongs, int matchedSongs, int needsReviewSongs,
            int skippedFiles, String scannedAt) {}
}
