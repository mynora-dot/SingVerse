package com.mynora.singverse.tv;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.classification.ClassificationMapper;
import com.mynora.singverse.media.repository.MediaSongRepository;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.*;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

/** Immutable, shared read index. Rebuilds once per interval, not once per reader or artist page. */
@Service
public class TvLibrary {
    record Entry(ObjectNode song, String search, String code) {}

    record Index(
            List<Entry> songs,
            Map<String, ObjectNode> byId,
            Map<String, List<Entry>> artists,
            Map<String, ObjectNode> displays,
            Map<String, ObjectNode> profiles,
            long built,
            long epoch) {}

    private volatile Index index;
    private final java.util.concurrent.atomic.AtomicLong epoch =
            new java.util.concurrent.atomic.AtomicLong();
    private final com.mynora.singverse.media.artist.ArtistService artistService;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final TvMapper mapper;
    private final MediaSongRepository songs;
    private final TvService tv;
    private final ClassificationMapper tags;

    public TvLibrary(
            TvMapper mapper,
            MediaSongRepository songs,
            TvService tv,
            ClassificationMapper tags,
            com.mynora.singverse.media.artist.ArtistService artistService,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.artistService = artistService;
        this.json = json;
        this.mapper = mapper;
        this.songs = songs;
        this.tv = tv;
        this.tags = tags;
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .strip();
    }

    public static String searchable(String value) {
        var format = new HanyuPinyinOutputFormat();
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        var full = new StringBuilder();
        var initials = new StringBuilder();
        for (char c : normalize(value).toCharArray()) {
            try {
                var py = PinyinHelper.toHanyuPinyinStringArray(c, format);
                String part = py == null ? String.valueOf(c) : py[0];
                full.append(part);
                initials.append(part.charAt(0));
            } catch (Exception error) {
                full.append(c);
                initials.append(c);
            }
        }
        return normalize(value) + " " + full + " " + initials;
    }

    public void invalidate() {
        epoch.incrementAndGet();
        index = null;
    }

    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void artistChanged(com.mynora.singverse.media.artist.ArtistDtos.Changed change) {
        invalidate();
    }

    private Index current() {
        var prior = index;
        if (prior != null
                && prior.epoch() == epoch.get()
                && System.nanoTime() - prior.built() < 30_000_000_000L) return prior;
        synchronized (this) {
            prior = index;
            if (prior != null
                    && prior.epoch() == epoch.get()
                    && System.nanoTime() - prior.built() < 30_000_000_000L) return prior;
            long generation = epoch.get();
            var profiles = new HashMap<String, ObjectNode>();
            var rows = new ArrayList<Entry>();
            var groups = new TreeMap<String, List<Entry>>();
            var displays = new HashMap<String, ObjectNode>();
            var byName = new HashMap<String, ObjectNode>();
            for (var artist : artistService.profiles())
                if (artist.path("automatic").asBoolean())
                    byName.put(com.mynora.singverse.media.artist.ArtistService.name(artist.path("name").asText()), artist);
            var byId = new HashMap<String, ObjectNode>();
            for (var row : mapper.library()) {
                var stored = songs.song(row);
                var projection = tv.projection(stored);
                var names = new LinkedHashSet<String>();
                projection.path("artists").forEach(n -> names.add(n.asText()));
                var entry =
                        new Entry(
                                projection,
                                searchable(
                                        projection.path("title").asText()
                                                + " "
                                                + String.join(" ", names)
                                                + " "
                                                + projection.path("album").asText("")),
                                Objects.toString(stored.result().songCode(), ""));
                rows.add(entry);
                byId.put(stored.id(), projection);
                var grouped = new HashSet<String>();
                for (String name : names) {
                    if (name.isBlank()) continue;
                    String canonical = com.mynora.singverse.media.artist.ArtistService.name(name);
                    var artist = byName.computeIfAbsent(canonical, artistService::ensure);
                    String key = artist.path("id").asText();
                    profiles.put(key, artist);
                    if (grouped.add(key))
                        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                    if (!displays.containsKey(key)) {
                        ObjectNode info = artist.deepCopy();
                        info.put("name", canonical).put("biography", "");
                        info.putArray("aliases");
                        for (String kind : List.of("portrait", "background"))
                            if (info.has(kind + "Path"))
                                info.put(kind + "Path", "/api/v1/tv/library/artists/" + key + "/images/" + kind);
                        displays.put(key, info);
                    }
                }
            }
            var order = Comparator.<Entry, String>comparing(e -> e.song().path("scannedAt").asText())
                    .reversed().thenComparing(e -> e.song().path("id").asText());
            rows.sort(order);
            var frozen = new TreeMap<String, List<Entry>>();
            groups.forEach((k, v) -> frozen.put(k, v.stream().sorted(order).toList()));
            displays.forEach((k, v) -> v.put("count", groups.get(k).size()));
            profiles.forEach(
                    (k, v) -> {
                        v.put("count", groups.get(k).size());
                        v.put("biography", "");
                        v.putArray("aliases");
                    });
            index =
                    new Index(
                            List.copyOf(rows),
                            Map.copyOf(byId),
                            Collections.unmodifiableMap(frozen),
                            Map.copyOf(displays),
                            Map.copyOf(profiles),
                            System.nanoTime(),
                            generation);
            return index;
        }
    }

    public List<ObjectNode> byIds(List<String> ids) {
        var byId = current().byId();
        return ids.stream().filter(byId::containsKey).map(id -> byId.get(id).deepCopy()).toList();
    }

    public Map<String, Object> search(
            String q,
            int page,
            int size,
            List<String> tagIds,
            boolean unclassified,
            String artistId) {
        validate(q, page, size);
        if (tagIds.size() > 40) throw TvService.fail(400, "分类最多选择 40 项");
        var active =
                tags.tags().stream()
                        .filter(t -> ((Number) t.get("active")).intValue() == 1)
                        .toList();
        var groups = new TreeMap<String, Set<String>>();
        for (String id : tagIds) {
            var tag =
                    active.stream()
                            .filter(t -> t.get("id").equals(id))
                            .findFirst()
                            .orElseThrow(() -> TvService.fail(400, "标签不存在或已停用"));
            groups.computeIfAbsent(tag.get("dimension").toString(), k -> new HashSet<>()).add(id);
        }
        var enabled = new HashSet<String>();
        active.forEach(t -> enabled.add(t.get("id").toString()));
        String needle = normalize(q);
        var library = current();
        var result =
                (artistId == null ? library.songs() : library.artists().getOrDefault(artistId, List.of())).stream()
                        .filter(
                                e ->
                                        needle.isEmpty()
                                                || e.search().contains(needle)
                                                || e.code().equals(needle))
                        .filter(
                                e -> {
                                    if (!unclassified && groups.isEmpty()) return true;
                                    var dimensions =
                                            e.song().path("classification").path("dimensions");
                                    var assigned = new HashSet<String>();
                                    dimensions.forEach(
                                            v ->
                                                    v.path("tagIds")
                                                            .forEach(
                                                                    t -> {
                                                                        if (enabled.contains(
                                                                                t.asText()))
                                                                            assigned.add(
                                                                                    t.asText());
                                                                    }));
                                    return (!unclassified || assigned.isEmpty())
                                            && groups.values().stream()
                                                    .allMatch(
                                                            g ->
                                                                    g.stream()
                                                                            .anyMatch(
                                                                                    assigned
                                                                                            ::contains));
                                })
                        .map(Entry::song)
                        .toList();
        return page(result, page, size);
    }

    public Map<String, Object> artists(String q, int page, int size) {
        validate(q, page, size);
        String needle = normalize(q);
        var list =
                current().displays().values().stream()
                        .filter(p -> matchesArtist(p, needle))
                        .sorted(
                                Comparator.comparing((ObjectNode n) -> n.path("name").asText())
                                        .thenComparing(n -> n.path("id").asText()))
                        .toList();
        return page(list, page, size);
    }

    private static boolean matchesArtist(ObjectNode p, String needle) {
        return needle.isEmpty()
                || searchable(p.path("name").asText()).contains(needle)
                || searchable(p.path("aliases").toString()).contains(needle);
    }

    public ObjectNode artistDetail(String id) {
        var info = current().displays().get(id);
        if (info == null) throw TvService.fail(404, "歌手不在当前曲库中");
        return info.deepCopy();
    }

    public Map<String, Object> managedArtists(String q, int page, int size) {
        return managedArtists(q, "", null, page, size);
    }

    public Map<String, Object> managedArtists(String q, String type, Integer countLessThan, int page, int size) {
        validate(q, page, size);
        if (type == null || !java.util.Set.of("", "MALE", "FEMALE", "GROUP", "UNKNOWN").contains(type)
                || countLessThan != null && (countLessThan < 1 || countLessThan > 1000000))
            throw TvService.fail(400, "歌手筛选条件无效");
        String needle = normalize(q);
        return page(
                current().profiles().values().stream()
                        .filter(p -> matchesArtist(p, needle))
                        .filter(p -> type.isEmpty() || ("UNKNOWN".equals(type)
                                ? !java.util.Set.of("MALE", "FEMALE", "GROUP").contains(p.path("type").asText())
                                : type.equals(p.path("type").asText())))
                        .filter(p -> countLessThan == null || p.path("count").asInt() < countLessThan)
                        .sorted(
                                Comparator.comparing((ObjectNode n) -> n.path("name").asText())
                                        .thenComparing(n -> n.path("id").asText()))
                        .toList(),
                page,
                size);
    }

    public Map<String, Object> managedSongs(String id, int page, int size) {
        validate("", page, size);
        var library = current();
        if (!library.profiles().containsKey(id)) throw TvService.fail(404, "歌手不存在");
        var result = library.artists().getOrDefault(id, List.of()).stream().map(Entry::song).toList();
        return page(result, page, size);
    }

    public Map<String, Object> adminArtistSongs(String id, int page, int size) {
        var result = new java.util.HashMap<String, Object>(managedSongs(id, page, size));
        var ids = ((java.util.List<?>)result.get("items")).stream()
                .map(n -> ((com.fasterxml.jackson.databind.JsonNode)n).path("id").asText()).toList();
        var current = new HashMap<String, com.mynora.singverse.media.dto.MediaSongDtos.Song>();
        songs.byIds(ids).forEach(song -> current.put(song.id(),song));
        result.put("items",ids.stream().filter(current::containsKey).map(current::get).toList());
        return result;
    }

    private static void validate(String q, int page, int size) {
        if (q == null
                || q.length() > 200
                || q.chars().anyMatch(Character::isISOControl)
                || page < 0
                || page > 1000000
                || size < 1
                || size > 100) throw TvService.fail(400, "搜索或分页参数无效");
    }

    private static Map<String, Object> page(List<?> items, int page, int size) {
        int from = (int) Math.min(items.size(), (long) page * size);
        return Map.of(
                "items",
                items.subList(from, Math.min(items.size(), from + size)).stream()
                        .map(value -> value instanceof ObjectNode node ? node.deepCopy() : value).toList(),
                "page",
                page,
                "size",
                size,
                "totalElements",
                items.size());
    }
}
