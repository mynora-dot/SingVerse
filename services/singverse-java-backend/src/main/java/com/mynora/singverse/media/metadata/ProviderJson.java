// Adapted from ktv-home (MIT); see docs/third-party/ktv-home-LICENSE.txt.
package com.mynora.singverse.media.metadata;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class ProviderJson {
    private ProviderJson() {}

    static String text(JsonNode node, String field, int max) {
        return clean(node == null ? null : node.path(field).asText(null), max);
    }

    static String clean(String value, int max) {
        if (value == null) return null;
        String text = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        if (text.isEmpty()) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    static List<String> names(JsonNode array, String field) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (array != null && array.isArray()) for (JsonNode item : array) {
            String value = field == null ? clean(item.asText(null), 200) : text(item, field, 200);
            if (value != null && values.size() < 20) values.add(value);
        }
        return List.copyOf(values);
    }

    static List<String> splitArtists(String value) {
        if (value == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split("\\s*(?:、|/|&|,|，|;|；)\\s*")) {
            String clean = clean(part, 200);
            if (clean != null && !result.contains(clean) && result.size() < 20) result.add(clean);
        }
        return result;
    }

    static String https(String value, String... hosts) {
        String clean = clean(value, 2000);
        if (clean == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(clean.replace("http://", "https://"));
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            for (String host : hosts) if (host.equalsIgnoreCase(uri.getHost()) || uri.getHost().endsWith("." + host)) return uri.toString();
        } catch (RuntimeException ignored) {}
        return null;
    }

    static ExternalTrack track(MusicProvider provider, String id, String title, List<String> artists,
                               String album, Integer durationMs, String releaseDate, List<String> aliases,
                               String cover, String availability) {
        String safeId = clean(id, 160);
        String safeTitle = clean(title, 500);
        if (safeId == null || safeTitle == null) return null;
        Integer safeDuration = durationMs != null && durationMs >= 0 && durationMs <= 24 * 60 * 60 * 1000 ? durationMs : null;
        return new ExternalTrack(provider, safeId, safeTitle, artists, clean(album, 500), safeDuration,
                clean(releaseDate, 32), aliases, cover, availability, OffsetDateTime.now());
    }
}
