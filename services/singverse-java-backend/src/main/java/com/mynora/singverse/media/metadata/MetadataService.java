package com.mynora.singverse.media.metadata;

import static com.mynora.singverse.media.metadata.MetadataDtos.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetadataService {
    public static final Set<String> FIELDS = Set.of("title", "artists", "album", "releaseDate", "aliases", "cover", "durationMs");
    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "REVIEW", "FAILED", "AUTO_APPLIED", "MANUAL_APPLIED", "SKIPPED_EXISTING", "APPLIED");
    private final MetadataMapper mapper;
    private final MetadataJson json;
    private final MetadataSettingsService settings;
    private final MediaSongRepository songs;
    private final MetadataMatcher matcher;
    private final Map<MusicProvider, MusicMetadataProvider> providers = new EnumMap<>(MusicProvider.class);
    private final TransactionTemplate transaction;

    public MetadataService(MetadataMapper mapper, MetadataJson json, MetadataSettingsService settings,
            MediaSongRepository songs, MetadataMatcher matcher, List<MusicMetadataProvider> providers,
            PlatformTransactionManager manager) {
        this.mapper = mapper; this.json = json; this.settings = settings; this.songs = songs; this.matcher = matcher;
        providers.forEach(provider -> this.providers.put(provider.provider(), provider));
        this.transaction = new TransactionTemplate(manager);
    }
    public static String title(Song song) {
        return song.metadata() != null && !song.metadata().stale() && song.metadata().fields().contains("title")
                ? song.metadata().track().title() : song.result().title();
    }
    public static List<String> artists(Song song) {
        return song.metadata() != null && !song.metadata().stale() && song.metadata().fields().contains("artists")
                ? song.metadata().track().artists() : song.result().artists();
    }
    double autoThreshold(double snapshot) {
        var current = settings.read();
        return current.enabled() && current.autoApply() ? Math.max(snapshot, current.threshold()) : Double.POSITIVE_INFINITY;
    }
    public SearchResult search(String songId, SearchInput input) {
        var config = settings.require();
        return search(songs.get(songId), input, config.providers());
    }
    SearchResult search(Song song, SearchInput input, List<MusicProvider> configured) {
        var current = settings.require();
        List<MusicProvider> selected = input == null || input.providers() == null || input.providers().isEmpty()
                ? configured : input.providers();
        if (selected.stream().anyMatch(Objects::isNull) || selected.size() > 3
                || !current.providers().containsAll(selected) || !configured.containsAll(selected))
            throw bad("搜索包含未启用的平台");
        String keyword = input == null ? null : input.keyword();
        if (keyword == null || keyword.isBlank()) keyword = Objects.toString(title(song), song.result().originalFilename()) + " " + String.join(" ", artists(song));
        keyword = keyword.trim();
        if (keyword.isBlank() || keyword.length() > 200 || keyword.chars().anyMatch(Character::isISOControl)) throw bad("搜索关键词需要 1～200 个字符");
        List<Candidate> candidates = new ArrayList<>();
        List<ProviderError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var platform : new LinkedHashSet<>(selected)) {
            var fresh = settings.read();
            if (!fresh.enabled() || !fresh.providers().contains(platform)) { errors.add(new ProviderError(platform, "平台已停用")); continue; }
            try {
                for (var track : providers.get(platform).search(keyword, 8, Duration.ofSeconds(5))) {
                    if (!seen.add(track.provider() + ":" + track.externalId())) continue;
                    String id = UUID.randomUUID().toString();
                    double score = matcher.score(title(song), artists(song), track);
                    mapper.insertCandidate(id, song.id(), MediaSongRepository.fingerprint(song), json.encode(track), score, now());
                    candidates.add(new Candidate(id, track, score));
                }
            } catch (RuntimeException error) {
                errors.add(new ProviderError(platform, error instanceof MusicSourceException ? error.getMessage() : "平台暂时不可用"));
            }
        }
        if (errors.size() == new HashSet<>(selected).size())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "所选平台均暂时不可用，请稍后重试或更换平台");
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id));
        return new SearchResult(candidates.stream().limit(10).toList(), List.copyOf(errors));
    }
    public ExternalTrack candidateTrack(String id) { return json.decode(candidateRow(id).get("track_json"), ExternalTrack.class); }
    Map<String, Object> candidateRow(String id) {
        var rows = mapper.candidate(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "候选已过期，请重新搜索");
        var row = rows.getFirst();
        if (java.time.Instant.parse(row.get("fetched_at").toString()).isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(7))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "候选已过期，请重新搜索");
        return row;
    }
    public Applied apply(String songId, ApplyInput input) {
        if (input == null || input.candidateId() == null) throw bad("请选择元数据候选");
        return transaction.execute(status -> { mapper.lock(); return applyLocked(songId, input, null); });
    }
    Applied applyLocked(String songId, ApplyInput input, String expectedFingerprint) {
        settings.require();
        var row = candidateRow(input.candidateId());
        if (!songId.equals(row.get("song_id"))) throw bad("候选不属于当前歌曲");
        var song = songs.get(songId);
        String fingerprint = MediaSongRepository.fingerprint(song);
        if (!fingerprint.equals(row.get("fingerprint")) || (expectedFingerprint != null && !fingerprint.equals(expectedFingerprint)))
            throw conflict("歌曲文件已变化，请重新扫描并匹配元数据");
        if (input.fields() != null && input.fields().stream().anyMatch(Objects::isNull)) throw bad("应用字段无效");
        var fields = input.fields() == null || input.fields().isEmpty() ? FIELDS : Set.copyOf(input.fields());
        if (!FIELDS.containsAll(fields)) throw bad("应用字段无效");
        var track = json.decode(row.get("track_json"), ExternalTrack.class);
        Set<String> present = new LinkedHashSet<>();
        if (track.title() != null && !track.title().isBlank()) present.add("title");
        if (!track.artists().isEmpty()) present.add("artists");
        if (track.album() != null && !track.album().isBlank()) present.add("album");
        if (track.releaseDate() != null && !track.releaseDate().isBlank()) present.add("releaseDate");
        if (!track.aliases().isEmpty()) present.add("aliases");
        if (track.coverUrl() != null) present.add("cover");
        if (track.durationMs() != null && track.durationMs() > 0) present.add("durationMs");
        Set<String> selectedFields = new LinkedHashSet<>(fields); selectedFields.retainAll(present);
        if (selectedFields.isEmpty()) throw bad("候选没有所选字段可写入，原元数据未修改");
        fields = Set.copyOf(selectedFields);
        var prior = song.metadata() != null && !song.metadata().stale() ? song.metadata() : null;
        ExternalTrack previous = prior == null ? track : prior.track();
        var merged = new ExternalTrack(track.provider(), track.externalId(),
                fields.contains("title") ? track.title() : previous.title(),
                fields.contains("artists") ? track.artists() : previous.artists(),
                fields.contains("album") ? track.album() : previous.album(),
                fields.contains("durationMs") ? track.durationMs() : previous.durationMs(),
                fields.contains("releaseDate") ? track.releaseDate() : previous.releaseDate(),
                fields.contains("aliases") ? track.aliases() : previous.aliases(),
                fields.contains("cover") ? track.coverUrl() : previous.coverUrl(), track.availability(), track.fetchedAt());
        Set<String> appliedFields = new LinkedHashSet<>(prior == null ? Set.of() : prior.fields()); appliedFields.addAll(fields);
        Map<String, Origin> origins = new LinkedHashMap<>(prior == null ? Map.of() : prior.origins());
        String appliedAt = now();
        for (String field : fields) origins.put(field, new Origin(track.provider(), track.externalId(), appliedAt));
        String encodedTrack = json.encode(merged), encodedFields = json.encode(appliedFields), encodedOrigins = json.encode(origins);
        mapper.deleteApplied(songId);
        mapper.insertApplied(songId, song.sourceId(), fingerprint, encodedTrack, encodedFields, encodedOrigins, appliedAt);
        return new Applied(merged, Set.copyOf(appliedFields), appliedAt, false, Map.copyOf(origins));
    }

    public Batch start(StartInput input) {
        if (input == null) throw bad("请选择要刮削的歌曲");
        return transaction.execute(status -> {
            mapper.lock(); var config = settings.require();
            if (!mapper.active().isEmpty()) throw conflict("已有运行或暂停的批次，请先完成或恢复当前批次");
            List<String> ids = input.songIds() == null ? List.of() : input.songIds().stream().filter(Objects::nonNull).distinct().toList();
            if (input.all() && !ids.isEmpty()) throw bad("全量与指定歌曲不能同时提供");
            if (ids.stream().anyMatch(id -> !id.matches("[A-Za-z0-9-]{1,36}"))) throw bad("歌曲 ID 无效");
            if (!input.all() && (ids.isEmpty() || ids.size() > 500)) throw bad("请选择 1～500 首歌曲");
            var targets = mapper.targetSongs(ids, input.all());
            if (targets.size() > 5000) throw bad("单批次最多 5000 首，请分批选择歌曲");
            if (!input.all() && targets.size() != ids.size()) throw conflict("部分歌曲已移除，请刷新列表后重试");
            var pending = targets.stream().map(songs::song).filter(song -> song.metadata() == null || song.metadata().stale()).toList();
            if (pending.isEmpty()) throw bad("没有待刮削歌曲；已应用的歌曲可从单曲入口重新匹配");
            String id = UUID.randomUUID().toString(), time = now();
            mapper.insertBatch(id, input.all() ? "ALL" : "SELECTED", json.encode(config.providers()), config.autoApply(), config.threshold(), targets.size() - pending.size(), time);
            for (Song song : targets.stream().map(songs::song).toList()) {
                String itemId=UUID.randomUUID().toString();
                mapper.insertItem(itemId, id, song.id(), MediaSongRepository.fingerprint(song), title(song), json.encode(artists(song)), time);
                if (song.metadata()!=null && !song.metadata().stale()) mapper.itemStatus(itemId,"PENDING","SKIPPED_EXISTING",null,"已有确认元数据且文件未变化，本批次保留原结果",time);
            }
            mapper.pruneCandidates(Instant.now().minus(Duration.ofDays(7)).toString());
            return details(id, null, 0, 20);
        });
    }
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Batch latest() { var rows = mapper.latest(); return rows.isEmpty() ? null : details(rows.getFirst().get("id").toString(), null, 0, 20); }
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Batch details(String id, String filter, int page, int size) { return details(id,filter,page,size,null,"DEFAULT"); }
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Batch details(String id, String filter, int page, int size, String keyword, String sort) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw bad("分页参数无效");
        if (filter != null && filter.isBlank()) filter = null;
        if (filter != null && !STATUSES.contains(filter)) throw bad("批次状态筛选无效");
        if (keyword!=null && (keyword.length()>200 || keyword.chars().anyMatch(Character::isISOControl))) throw bad("搜索文本无效");
        if (!Set.of("DEFAULT","TITLE","SCORE_DESC","UPDATED_DESC").contains(sort)) throw bad("排序参数无效");
        String pattern=keyword==null || keyword.isBlank()?null:"%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var row = batchRow(id);
        Map<String, Integer> counts = new HashMap<>();
        for (var count : mapper.counts(id)) counts.put(count.get("status").toString(), ((Number) count.get("amount")).intValue());
        int pending = counts.getOrDefault("PENDING", 0), processing = counts.getOrDefault("PROCESSING", 0), review = counts.getOrDefault("REVIEW", 0);
        int applied = counts.getOrDefault("AUTO_APPLIED", 0) + counts.getOrDefault("MANUAL_APPLIED", 0), failed = counts.getOrDefault("FAILED", 0);
        double threshold=((Number)row.get("threshold")).doubleValue();
        int skipped=((Number)row.get("skipped_existing")).intValue();
        int processable=pending+processing+review+applied+failed;
        var items = mapper.viewItems(id, filter, pattern, sort, size, (long) page * size).stream().map(item -> itemView(item,threshold)).toList();
        // Item timestamps are independent of the batch lifecycle timestamp; observedAt is this transaction's read time.
        return new Batch(id, row.get("mode").toString(), row.get("status").toString(), processable+skipped,
                review + applied + failed+skipped, pending, processing, review, applied, failed, skipped,
                ((Number) row.get("auto_apply")).intValue() == 1, ((Number) row.get("threshold")).doubleValue(), row.get("created_at").toString(), row.get("updated_at").toString(),
                items, page, size, mapper.viewCount(id, filter, pattern),processable,Math.max(0,skipped-counts.getOrDefault("SKIPPED_EXISTING",0)),now());
    }
    Map<String, Object> batchRow(String id) {
        var rows = mapper.batch(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "刮削批次不存在");
        return rows.getFirst();
    }
    private Item itemView(Map<String, Object> row, double threshold) {
        List<Candidate> candidates=row.get("candidates_json")==null?List.of():json.decode(row.get("candidates_json"), new TypeReference<List<Candidate>>() {});
        String confidence=candidates.isEmpty()?"NONE":candidates.getFirst().score()>=threshold?"HIGH":candidates.getFirst().score()>=.8?"LOW":"NONE";
        boolean cover=false;
        if(row.get("metadata_fields")!=null && row.get("source_id")!=null) {
            Set<String> fields=json.decode(row.get("metadata_fields"),new TypeReference<Set<String>>() {});
            Long fileSize=row.get("file_size")==null?null:((Number)row.get("file_size")).longValue();
            cover=fields.contains("cover") && MediaSongRepository.fingerprint(row.get("song_id").toString(),fileSize,(String)row.get("modified_at")).equals(row.get("metadata_fingerprint"));
        }
        return new Item(row.get("id").toString(), row.get("song_id").toString(), (String) row.get("title"),
                json.decode(row.get("artists_json"), new TypeReference<List<String>>() {}), row.get("status").toString(),
                candidates,
                (String) row.get("error_message"), row.get("updated_at").toString(),confidence,cover,(String)row.get("source_id"),(String)row.get("relative_path"));
    }
    public Batch pause(String id) {
        return transaction.execute(status -> { mapper.lock();
            if (mapper.batchStatus(id, "RUNNING", "PAUSED", now()) != 1) throw conflict("当前批次不是运行状态");
            return details(id, null, 0, 20); });
    }
    public Batch cancel(String id) {
        return transaction.execute(status -> { mapper.lock(); var row = batchRow(id);
            String previous = row.get("status").toString();
            if (!Set.of("RUNNING", "PAUSED").contains(previous)) throw conflict("当前批次已经结束");
            mapper.batchStatus(id, previous, "CANCELLED", now());
            return details(id, null, 0, 20); });
    }
    public Batch resume(String id) {
        return transaction.execute(status -> { mapper.lock(); settings.require();
            var row = batchRow(id);
            List<MusicProvider> selected = json.decode(row.get("providers_json"), new TypeReference<List<MusicProvider>>() {});
            if (!settings.read().providers().containsAll(selected)) throw conflict("批次平台已停用，请先重新启用对应平台");
            if (mapper.batchStatus(id, "PAUSED", "RUNNING", now()) != 1) throw conflict("当前批次不是暂停状态");
            return details(id, null, 0, 20); });
    }
    private Map<String, Object> requireItem(String batchId, String itemId) {
        var rows = mapper.item(itemId);
        if (rows.isEmpty() || !batchId.equals(rows.getFirst().get("batch_id"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "刮削明细不存在");
        return rows.getFirst();
    }
    public Batch retry(String batchId, String itemId) {
        return transaction.execute(status -> { mapper.lock(); settings.require();
            var row = requireItem(batchId, itemId); String previous = row.get("status").toString();
            if (!Set.of("REVIEW", "FAILED").contains(previous)) throw conflict("仅待审核或失败项可以重试");
            var batch = batchRow(batchId);
            if (batch.get("status").equals("CANCELLED")) throw conflict("已终止的批次不能重试，请新建批次");
            if (batch.get("status").equals("COMPLETED")) {
                if (!mapper.active().isEmpty()) throw conflict("已有其他活动批次，请先完成当前批次");
                mapper.batchStatus(batchId, "COMPLETED", "RUNNING", now());
            }
            mapper.itemStatus(itemId, previous, "PENDING", null, null, now());
            return details(batchId, null, 0, 20); });
    }
    public Applied applyItem(String batchId, String itemId, ApplyInput input) {
        if (input == null || input.candidateId() == null) throw bad("请选择候选");
        return transaction.execute(status -> { mapper.lock();
            var row = requireItem(batchId, itemId); String previous = row.get("status").toString();
            if (!Set.of("REVIEW", "FAILED").contains(previous)) throw conflict("该明细当前不能人工应用");
            Applied result = applyLocked(row.get("song_id").toString(), input, row.get("fingerprint").toString());
            mapper.itemStatus(itemId, previous, "MANUAL_APPLIED", (String) row.get("candidates_json"), null, now());
            return result; });
    }
}
