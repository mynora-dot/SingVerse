package com.mynora.singverse.media.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.filename.FilenameParseResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class MediaSongRepository {
    public record Stored(String id, String sourceId, String relativePath, String pathHash,
            Long fileSize, String modifiedAt, String scannedAt, String profileId, long revision,
            String ruleId, String resultJson, boolean needsReview) {}
    private final MediaSongMapper mapper;
    private final ObjectMapper json;
    private final com.mynora.singverse.media.metadata.MetadataMapper metadata;

    public MediaSongRepository(MediaSongMapper mapper, ObjectMapper json, com.mynora.singverse.media.metadata.MetadataMapper metadata) {
        this.mapper = mapper; this.json = json; this.metadata = metadata;
    }

    public String encode(FilenameParseResult result) {
        try { return json.writeValueAsString(result); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode parsed song", error); }
    }

    public List<Song> list(String sourceId, int page, int size) {
        return mapper.list(sourceId, size, (long) page * size).stream().map(this::song).toList();
    }
    public com.mynora.singverse.media.dto.MediaSongDtos.Page search(String sourceId, String keyword,
            Integer review, int page, int size) { return search(sourceId, keyword, review, page, size, java.util.Map.of(), false); }
    public com.mynora.singverse.media.dto.MediaSongDtos.Page search(String sourceId, String keyword,
            Integer review, int page, int size, java.util.Map<String,java.util.List<String>> tagGroups, boolean unclassified) {
        return new com.mynora.singverse.media.dto.MediaSongDtos.Page(
                mapper.search(sourceId, keyword, review, size, (long) page * size, tagGroups, unclassified).stream().map(this::song).toList(),
                page, size, mapper.searchCount(sourceId, keyword, review, tagGroups, unclassified));
    }
    public long count(String sourceId) { return mapper.count(sourceId); }
    public void delete(String sourceId) { metadata.lock(); metadata.cleanupSource(sourceId); mapper.delete(sourceId); }
    public void replace(String sourceId, List<Stored> songs) {
        metadata.lock();
        mapper.delete(sourceId);
        for (Stored song : songs) mapper.insert(song);
        metadata.cleanupOrphans(sourceId);
    }
    public Song get(String id) {
        var rows = mapper.find(id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "歌曲不存在或已从来源中移除");
        return song(rows.getFirst());
    }
    public static String fingerprint(String id, Long size, String modifiedAt) {
        return com.mynora.singverse.auth.crypto.AuthCrypto.hash(id + ":" + size + ":" + modifiedAt);
    }
    public static String fingerprint(Song song) { return fingerprint(song.id(), song.fileSize(), song.modifiedAt()); }
    private static Long size(Map<String, Object> row) {
        return row.get("file_size") == null ? null : ((Number) row.get("file_size")).longValue();
    }
    public Song song(Map<String, Object> row) {
        try {
            return new Song(row.get("id").toString(), row.get("source_id").toString(), row.get("source_name").toString(),
                    row.get("relative_path").toString(), size(row),
                    (String) row.get("modified_at"), row.get("scanned_at").toString(),
                    row.get("parser_profile_id").toString(), ((Number) row.get("parser_revision")).longValue(),
                    (String) row.get("selected_rule_id"),
                    json.readValue(row.get("result_json").toString(), FilenameParseResult.class), applied(row), new com.mynora.singverse.media.classification.ClassificationDtos.State(
                            row.get("id").toString(), row.get("classification_revision")==null?0:((Number)row.get("classification_revision")).longValue(),
                            row.get("classification_state")==null?java.util.Map.of():json.readValue(row.get("classification_state").toString(),new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,com.mynora.singverse.media.classification.ClassificationDtos.Value>>() {})));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored song result is invalid", error);
        }
    }
    private com.mynora.singverse.media.metadata.MetadataDtos.Applied applied(Map<String, Object> row) throws JsonProcessingException {
        if (row.get("metadata_track") == null) return null;
        var track = json.readValue(row.get("metadata_track").toString(), com.mynora.singverse.media.metadata.ExternalTrack.class);
        java.util.Set<String> fields = json.readValue(row.get("metadata_fields").toString(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Set<String>>() {});
        String current = fingerprint(row.get("id").toString(), size(row), (String) row.get("modified_at"));
        return new com.mynora.singverse.media.metadata.MetadataDtos.Applied(track, fields,
                row.get("metadata_applied_at").toString(), !current.equals(row.get("metadata_fingerprint")),
                json.readValue(row.get("metadata_origins").toString(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, com.mynora.singverse.media.metadata.MetadataDtos.Origin>>() {}));
    }

}
