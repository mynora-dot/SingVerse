package com.mynora.singverse.media.dto;

import java.util.List;

/** Request and response shapes of the media source API. */
public final class MediaSourceDtos {
    private MediaSourceDtos() {}

    public record Input(
            String name, String type, String location, String username, String password, String parserProfileId, Boolean scanEnabled, String scanCron) {}

    public record Source(
            String id,
            String name,
            String type,
            String location,
            String username,
            String parserProfileId,
            boolean passwordConfigured,
            String createdAt,
            String updatedAt, boolean scanEnabled, String scanCron, String nextScanAt,
            String lastScanAt, String lastScanStatus, String lastScanError) {}

    public static boolean sameScanConfiguration(Source a, Source b) {
        return java.util.Objects.equals(a.id(), b.id()) && java.util.Objects.equals(a.updatedAt(), b.updatedAt())
                && java.util.Objects.equals(a.location(), b.location()) && java.util.Objects.equals(a.parserProfileId(), b.parserProfileId());
    }
    public record Page(List<Source> items, int page, int size, long totalElements) {}

}
