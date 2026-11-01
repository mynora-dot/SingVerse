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
            String ruleId, String resultJson, boolean needsReview, String embeddedTagsJson, String etag) {
        public Stored(String id, String sourceId, String relativePath, String pathHash, Long fileSize, String modifiedAt,
                String scannedAt, String profileId, long revision, String ruleId, String resultJson, boolean needsReview, String embeddedTagsJson) {
            this(id,sourceId,relativePath,pathHash,fileSize,modifiedAt,scannedAt,profileId,revision,ruleId,resultJson,needsReview,embeddedTagsJson,null);
        }
        public Stored(String id, String sourceId, String relativePath, String pathHash, Long fileSize, String modifiedAt,
                String scannedAt, String profileId, long revision, String ruleId, String resultJson, boolean needsReview) {
            this(id, sourceId, relativePath, pathHash, fileSize, modifiedAt, scannedAt, profileId, revision, ruleId, resultJson, needsReview, null, null);
        }
    }
    private final MediaSongMapper mapper;
    private final com.mynora.singverse.media.artist.ArtistMapper artists;
    private final ObjectMapper json;
    private final com.mynora.singverse.media.metadata.MetadataMapper metadata;

    public MediaSongRepository(MediaSongMapper mapper, ObjectMapper json, com.mynora.singverse.media.metadata.MetadataMapper metadata, com.mynora.singverse.media.artist.ArtistMapper artists) {
        this.artists=artists; this.mapper = mapper; this.json = json; this.metadata = metadata;
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
    public void delete(String sourceId) { metadata.lock(); metadata.cleanupSource(sourceId); artists.clearSource(sourceId); mapper.delete(sourceId); }
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
    public static String fingerprint(Song song) { return song.contentFingerprint()==null?fingerprint(song.id(), song.fileSize(), song.modifiedAt()):song.contentFingerprint(); }
    private static String contentFingerprint(Map<String,Object> row) {
        boolean remote=row.get("remote_resource_id")!=null;
        Long length=remote?(row.get("identity_size") instanceof Number n?n.longValue():null):size(row);
        return fingerprint(row.get("id").toString(),length,(String)row.get(remote?"identity_modified":"modified_at"));
    }
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
                    json.readValue(row.get("result_json").toString(), FilenameParseResult.class), artistOverride(row,effectiveApplied(row)), new com.mynora.singverse.media.classification.ClassificationDtos.State(
                            row.get("id").toString(), row.get("classification_revision")==null?0:((Number)row.get("classification_revision")).longValue(),
                            row.get("classification_state")==null?java.util.Map.of():json.readValue(row.get("classification_state").toString(),new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,com.mynora.singverse.media.classification.ClassificationDtos.Value>>() {})), contentFingerprint(row));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored song result is invalid", error);
        }
    }
    private com.mynora.singverse.media.metadata.MetadataDtos.Applied artistOverride(Map<String,Object> row, com.mynora.singverse.media.metadata.MetadataDtos.Applied prior) throws JsonProcessingException {
        var value=artists.override(row.get("source_id").toString(),com.mynora.singverse.auth.crypto.AuthCrypto.hash(row.get("relative_path").toString()));
        if(value==null)return prior;
        if(value.get("artists_json")==null){
            if(prior==null||!prior.origins().containsKey("artists")||!"MANUAL".equals(prior.origins().get("artists").kind()))return prior;
            var f=new java.util.HashSet<>(prior.fields());f.remove("artists");var o=new java.util.HashMap<>(prior.origins());o.remove("artists");
            return new com.mynora.singverse.media.metadata.MetadataDtos.Applied(prior.track(),f,prior.appliedAt(),prior.stale(),o);
        }
        var parsed=json.readValue(row.get("result_json").toString(),FilenameParseResult.class);
        var t=prior!=null&&!prior.stale()?prior.track():null;
        List<String> names=json.readValue(value.get("artists_json").toString(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
        var fields=new java.util.HashSet<String>(t==null?java.util.Set.of():prior.fields());fields.add("artists");
        var origins=new java.util.HashMap<String,com.mynora.singverse.media.metadata.MetadataDtos.Origin>(t==null?java.util.Map.of():prior.origins());
        String now=row.get("scanned_at").toString();origins.put("artists",new com.mynora.singverse.media.metadata.MetadataDtos.Origin(null,null,now,"MANUAL"));
        var track=new com.mynora.singverse.media.metadata.ExternalTrack(t==null?null:t.provider(),t==null?null:t.externalId(),t==null?parsed.title():t.title(),names,t==null?null:t.album(),t==null?null:t.durationMs(),t==null?null:t.releaseDate(),t==null?java.util.List.of():t.aliases(),t==null?null:t.coverUrl(),"UNKNOWN",t==null?java.time.OffsetDateTime.parse(now):t.fetchedAt());
        return new com.mynora.singverse.media.metadata.MetadataDtos.Applied(track,fields,now,false,origins);
    }
    private com.mynora.singverse.media.metadata.MetadataDtos.Applied effectiveApplied(Map<String,Object> row) throws JsonProcessingException {
        var prior = applied(row);
        if (row.get("embedded_tags_json") == null) return prior;
        var remote = row.get("remote_metadata_json")==null?json.nullNode():json.readTree(row.get("remote_metadata_json").toString());
        String originKind=remote.path("origin").asText().equals("SHARED")?"SHARED":"FILE";
        Integer duration = null;
        try { double seconds=Double.parseDouble(remote.path("technical").path("duration").asText("0")); if(Double.isFinite(seconds)&&seconds>0&&seconds<604800) duration=(int)Math.round(seconds*1000); } catch(NumberFormatException ignored) {}
        String embeddedCover=remote.path("cover").asText("").matches("[a-f0-9]{64}")?"embedded:"+row.get("id"):null;
        var tags = json.readValue(row.get("embedded_tags_json").toString(), com.mynora.singverse.infrastructure.compute.MediaTagsClient.Tags.class);
        var valid = prior != null && !prior.stale() ? prior : null;
        var track = valid == null ? null : valid.track();
        var fields = new java.util.HashSet<String>(valid == null ? java.util.Set.of() : valid.fields());
        var origins = new java.util.HashMap<String, com.mynora.singverse.media.metadata.MetadataDtos.Origin>(valid == null ? java.util.Map.of() : valid.origins());
        java.util.List<String> sharedAliases=originKind.equals("SHARED")&&remote.path("aliases").isArray()?json.convertValue(remote.path("aliases"),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){}):null;
        String now = row.get("scanned_at").toString();
        if(originKind.equals("SHARED") && valid!=null) {
            var manual=new java.util.HashSet<String>();
            valid.origins().forEach((field,origin)->{if(!"SHARED".equals(origin.kind()))manual.add(field);});
            tags=new com.mynora.singverse.infrastructure.compute.MediaTagsClient.Tags(manual.contains("title")?null:tags.title(),manual.contains("artists")?List.of():tags.artists(),manual.contains("album")?null:tags.album(),manual.contains("releaseDate")?null:tags.releaseDate());
            if(manual.contains("durationMs"))duration=null;
            if(manual.contains("cover"))embeddedCover=null;
            if(manual.contains("aliases"))sharedAliases=null;
        }
        for (String field : tags.fields()) {
            fields.add(field); origins.put(field, new com.mynora.singverse.media.metadata.MetadataDtos.Origin(null,null,now,originKind));
        }
        if(duration!=null) { fields.add("durationMs"); origins.put("durationMs",new com.mynora.singverse.media.metadata.MetadataDtos.Origin(null,null,now,originKind)); }
        if(embeddedCover!=null) { fields.add("cover"); origins.put("cover",new com.mynora.singverse.media.metadata.MetadataDtos.Origin(null,null,now,originKind)); }
        if(sharedAliases!=null) { fields.add("aliases"); origins.put("aliases",new com.mynora.singverse.media.metadata.MetadataDtos.Origin(null,null,now,originKind)); }
        if (tags.fields().isEmpty() && duration==null && embeddedCover==null && sharedAliases==null) return prior;
        var merged = new com.mynora.singverse.media.metadata.ExternalTrack(null,null,
            tags.title() != null ? tags.title() : track == null ? null : track.title(),
            !tags.artists().isEmpty() ? tags.artists() : track == null ? java.util.List.of() : track.artists(),
            tags.album() != null ? tags.album() : track == null ? null : track.album(),
            duration != null ? duration : track == null ? null : track.durationMs(),
            tags.releaseDate() != null ? tags.releaseDate() : track == null ? null : track.releaseDate(),
            sharedAliases!=null ? sharedAliases : track == null ? java.util.List.of() : track.aliases(), embeddedCover != null ? embeddedCover : track == null ? null : track.coverUrl(),"UNKNOWN",java.time.OffsetDateTime.parse(now));
        if(track!=null)merged=merged.withArtistRefs(track.artistRefs());
        return new com.mynora.singverse.media.metadata.MetadataDtos.Applied(merged,fields,now,false,origins);
    }
    private com.mynora.singverse.media.metadata.MetadataDtos.Applied applied(Map<String, Object> row) throws JsonProcessingException {
        if (row.get("metadata_track") == null) return null;
        var track = json.readValue(row.get("metadata_track").toString(), com.mynora.singverse.media.metadata.ExternalTrack.class);
        java.util.Set<String> fields = json.readValue(row.get("metadata_fields").toString(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Set<String>>() {});
        var origins = json.readValue(row.get("metadata_origins").toString(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, com.mynora.singverse.media.metadata.MetadataDtos.Origin>>() {});
        fields = new java.util.HashSet<>(fields);
        fields.removeIf(field -> origins.containsKey(field) && "FILE".equals(origins.get(field).kind()));
        origins.keySet().retainAll(fields);
        if (fields.isEmpty()) return null;
        String current = contentFingerprint(row);
        return new com.mynora.singverse.media.metadata.MetadataDtos.Applied(track, fields,
                row.get("metadata_applied_at").toString(), !current.equals(row.get("metadata_fingerprint")),
                origins);
    }

}
