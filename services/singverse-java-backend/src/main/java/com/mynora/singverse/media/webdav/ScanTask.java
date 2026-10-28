package com.mynora.singverse.media.webdav;

/** Counts describe discovered work; directory totals are unknown until traversal finishes. */
public record ScanTask(String id, String sourceId, String status, String phase,
        long directories, long entries, int totalSongs, int matchedSongs, int needsReviewSongs,
        long skippedFiles, boolean cancelRequested, String error, String createdAt,
        String startedAt, String finishedAt) {}
