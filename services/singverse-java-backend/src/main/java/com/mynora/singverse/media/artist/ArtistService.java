package com.mynora.singverse.media.artist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.image.UploadedImage;
import com.mynora.singverse.media.repository.MediaSongRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class ArtistService {
    private final ArtistMapper mapper;
    private final ObjectMapper json;
    private final MediaSongRepository songs;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public ArtistService(
            ArtistMapper mapper,
            ObjectMapper json,
            MediaSongRepository songs,
            PlatformTransactionManager manager,
            ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.json = json;
        this.songs = songs;
        this.tx = new TransactionTemplate(manager);
        this.events = events;
    }

    public List<ObjectNode> profiles() {
        return mapper.list().stream().map(this::profile).toList();
    }

    public List<Map<String, Object>> bindings() {
        return mapper.bindings();
    }

    private Map<String, Object> required(String id) {
        var row = mapper.find(id);
        if (row == null) throw fail(404, "歌手不存在");
        return row;
    }

    public ObjectNode get(String id) {
        return profile(required(id));
    }

    private ObjectNode profile(Map<String, Object> row) {
        var n =
                json.createObjectNode()
                        .put("id", row.get("id").toString())
                        .put("name", row.get("name").toString())
                        .put("type", row.get("artist_type").toString())
                        .put("biography", row.get("biography").toString())
                        .put("active", ((Number) row.get("active")).intValue() == 1)
                        .put("revision", ((Number) row.get("revision")).longValue())
                        .put("createdAt", row.get("created_at").toString())
                        .put("updatedAt", row.get("updated_at").toString())
                        .put("managed", true);
        try {
            n.set("aliases", json.readTree(row.get("aliases_json").toString()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (String kind : List.of("portrait", "background")) {
            var version = row.get(kind + "_version");
            if (version != null) {
                n.put(kind + "Version", version.toString());
                n.put(kind + "Path", "/api/v1/artists/" + row.get("id") + "/images/" + kind);
            }
        }
        n.put("automatic", row.get("name_key") != null)
                .put("fetchState", Objects.toString(row.get("fetch_state"), "PENDING"));
        for (String k : List.of("portrait", "background")) {
            n.put(k + "FetchState", Objects.toString(row.get(k + "_fetch_state"), "PENDING"));
            if (row.get(k + "_origin") != null)
                n.put(k + "Origin", row.get(k + "_origin").toString());
            if (row.get(k + "_source") != null)
                n.put(k + "Source", row.get(k + "_source").toString());
            if (row.get(k + "_attribution") != null)
                n.put(k + "Attribution", row.get(k + "_attribution").toString());
        }
        String portraitState = Objects.toString(row.get("portrait_fetch_state"), "PENDING");
        String backgroundState = Objects.toString(row.get("background_fetch_state"), "PENDING");
        n.put("fetchState", List.of(portraitState, backgroundState).contains("PENDING") ? "PENDING"
                : List.of(portraitState, backgroundState).contains("RETRY") ? "RETRY"
                : List.of(portraitState, backgroundState).contains("NEEDS_REVIEW") ? "NEEDS_REVIEW"
                : List.of(portraitState, backgroundState).contains("FAILED") ? "FAILED" : "READY");
        return n;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String name(String value) {
        return java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFKC)
                .strip();
    }

    public ObjectNode ensure(String raw) {
        String name = name(raw), key = AuthCrypto.hash(name);
        var prior = mapper.byName(key);
        if (prior != null) {
            if ("DORMANT".equals(prior.get("fetch_state"))) {
                mapper.retry(prior.get("id").toString());
                for (String k : List.of("PORTRAIT", "BACKGROUND")) {
                    var state = mapper.imageFetch(prior.get("id").toString(), k);
                    if (state != null && "DORMANT".equals(state.get("state"))) mapper.retryImage(prior.get("id").toString(), k);
                }
            }
            return profile(prior);
        }
        return tx.execute(
                status -> {
                    mapper.lock();
                    var found = mapper.byName(key);
                    if (found != null) return profile(found);
                    var legacy =
                            mapper.legacy().stream()
                                    .filter(
                                            r ->
                                                    r.get("name_key") == null
                                                            && name(r.get("name").toString())
                                                                    .equals(name))
                                    .sorted(
                                            java.util.Comparator.comparing(
                                                    r -> r.get("id").toString()))
                                    .toList();
                    String id;
                    if (!legacy.isEmpty()) {
                        id =
                                legacy.stream()
                                        .filter(
                                                r ->
                                                        r.get("portrait_version") != null
                                                                || r.get("background_version")
                                                                        != null)
                                        .findFirst()
                                        .orElse(legacy.getFirst())
                                        .get("id")
                                        .toString();
                    } else {
                        id =
                                UUID.nameUUIDFromBytes(
                                                ("artist:" + key)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))
                                        .toString();
                        mapper.insert(
                                id,
                                new ArtistDtos.Save(id, name, List.of(), "UNKNOWN", "", true, 0),
                                "[]",
                                key,
                                Instant.now().toString());
                    }
                    mapper.adopt(id, key);
                    mapper.ensureFetch(id, "PORTRAIT");
                    mapper.ensureFetch(id, "BACKGROUND");
                    return get(id);
                });
    }

    public ObjectNode save(String id, ArtistDtos.Save input) {
        throw fail(410, "歌手库已改为从歌曲自动生成，请在歌曲中订正歌手信息");
    }

    public ObjectNode category(String id, String type, long revision) {
        if (type == null || !Set.of("MALE", "FEMALE", "GROUP", "UNKNOWN").contains(type)) throw fail(400, "歌手分类无效");
        return tx.execute(status -> {
            mapper.lock(); required(id);
            if (mapper.category(id, type, revision, java.time.Instant.now().toString()) != 1) throw fail(409, "歌手分类已变化，请刷新");
            events.publishEvent(new ArtistDtos.Changed());
            return get(id);
        });
    }

    public void bind(ArtistDtos.Bind input) {
        throw fail(410, "无需手动关联，请在歌曲中订正歌手信息");
    }

    public void retry(String id) {
        tx.executeWithoutResult(
                s -> {
                    mapper.lock();
                    required(id);
                    for (String k : List.of("PORTRAIT", "BACKGROUND")) mapper.deletePolicy(id, k);
                    mapper.retry(id);
                    for (String k : List.of("PORTRAIT", "BACKGROUND")) mapper.retryImage(id, k);
                    events.publishEvent(new ArtistDtos.Changed());
                });
    }

    public List<ArtistArtworkSources.Choice> candidates(String id, String imageKind) {
        required(id);
        var row = mapper.imageFetch(id, kind(imageKind));
        if (row == null || row.get("candidates_json") == null) return List.of();
        try { return List.of(json.readValue(row.get("candidates_json").toString(), ArtistArtworkSources.Choice[].class)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public ObjectNode selectCandidate(String id, String imageKind, String externalId, String provider) throws Exception {
        String k = kind(imageKind);
        var choice = candidates(id, imageKind).stream().filter(c -> c.externalId().equals(externalId) && c.provider().equals(provider)).findFirst()
                .orElseThrow(() -> fail(404, "图片候选不存在"));
        String url = "PORTRAIT".equals(k) ? choice.portrait() : choice.background();
        if (url == null || url.isBlank()) throw fail(400, "候选没有此类图片");
        byte[] bytes = ArtistArtworkWorker.download(url);
        String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return tx.execute(s -> {
            mapper.lock();
            if (candidates(id, imageKind).stream().noneMatch(c -> c.externalId().equals(externalId) && c.provider().equals(provider)))
                throw fail(409, "图片候选已变化，请刷新");
            if (mapper.policy(id,k) > 0 || mapper.image(id,k) != null) throw fail(409, "已有图片或暂停补图，请刷新");
            mapper.insertImage(id,k,version,bytes);
            mapper.imageSource(id,k,"SELECTED",choice.provider(),choice.externalId(),url);
            mapper.attribution(id,k,choice.attribution());
            var state=mapper.imageFetch(id,k);
            mapper.imageFetchState(id,k,"READY",0,"",((Number)state.get("generation")).longValue());
            events.publishEvent(new ArtistDtos.Changed());
            return get(id);
        });
    }

    public ResponseEntity<byte[]> candidatePreview(String id, String imageKind, String externalId, String provider) throws Exception {
        String k = kind(imageKind);
        var choice = candidates(id,imageKind).stream().filter(c -> c.externalId().equals(externalId) && c.provider().equals(provider)).findFirst()
                .orElseThrow(() -> fail(404,"图片候选不存在"));
        String url = "PORTRAIT".equals(k) ? choice.portrait() : choice.background();
        if (url == null || url.isBlank()) throw fail(404,"候选没有此类图片");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(ArtistArtworkWorker.download(url));
    }

    public ObjectNode override(String songId) {
        var song = songs.get(songId);
        var row = mapper.override(song.sourceId(), AuthCrypto.hash(song.relativePath()));
        var out =
                json.createObjectNode()
                        .put(
                                "revision",
                                row == null ? 0 : ((Number) row.get("revision")).longValue())
                        .put("overridden", row != null && row.get("artists_json") != null);
        return out;
    }

    public void reset(String songId, long revision) {
        tx.executeWithoutResult(
                s -> {
                    mapper.lock();
                    var song = songs.get(songId);
                    String path = AuthCrypto.hash(song.relativePath());
                    var old = mapper.override(song.sourceId(), path);
                    if ((old == null ? 0 : ((Number) old.get("revision")).longValue()) != revision)
                        throw fail(409, "歌手订正已变化，请刷新");
                    mapper.deleteOverride(song.sourceId(), path);
                    mapper.insertOverride(song.sourceId(), path, null, revision + 1);
                    events.publishEvent(new ArtistDtos.Changed());
                });
    }

    private static String kind(String kind) {
        if (kind == null || !Set.of("portrait", "background").contains(kind))
            throw fail(400, "图片类型只能是portrait或background");
        return kind.toUpperCase(Locale.ROOT);
    }

    public ObjectNode upload(String id, String imageKind, String expected, MultipartFile file) {
        String k = kind(imageKind);
        byte[] bytes = UploadedImage.normalize(file);
        String version;
        try {
            version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return tx.execute(
                status -> {
                    mapper.lock();
                    required(id);
                    var old = mapper.image(id, k);
                    if (old != null && version.equals(old.get("version"))) {
                        mapper.imageSource(id, k, "UPLOAD", null, null, null);
                        mapper.deletePolicy(id, k);
                        mapper.insertPolicy(id, k, true);
                        events.publishEvent(new ArtistDtos.Changed());
                        return get(id);
                    }
                    check(old, expected);
                    if (old == null) mapper.insertImage(id, k, version, bytes);
                    else mapper.updateImage(id, k, version, bytes);
                    mapper.imageSource(id, k, "UPLOAD", null, null, null);
                    mapper.deletePolicy(id, k);
                    mapper.insertPolicy(id, k, true);
                    events.publishEvent(new ArtistDtos.Changed());
                    return get(id);
                });
    }

    public ObjectNode removeImage(String id, String imageKind, String expected) {
        String k = kind(imageKind);
        return tx.execute(
                status -> {
                    mapper.lock();
                    required(id);
                    var old = mapper.image(id, k);
                    mapper.deletePolicy(id, k);
                    mapper.insertPolicy(id, k, true);
                    if (old != null) {
                        check(old, expected);
                        mapper.removeImage(id, k);
                        events.publishEvent(new ArtistDtos.Changed());
                    }
                    return get(id);
                });
    }

    private static void check(Map<String, Object> old, String version) {
        if (!Objects.equals(old == null ? "" : old.get("version"), version))
            throw fail(409, "图片已变化，请刷新歌手资料后重试");
    }

    public ResponseEntity<byte[]> image(String id, String imageKind, boolean publicView) {
        var row = mapper.image(id, kind(imageKind));
        if (row == null || publicView && ((Number) row.get("active")).intValue() != 1)
            throw fail(404, "没有可用的歌手图片");
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body((byte[]) row.get("image_bytes"));
    }

    public static ResponseStatusException fail(int status, String detail) {
        return new ResponseStatusException(HttpStatus.valueOf(status), detail);
    }
}
