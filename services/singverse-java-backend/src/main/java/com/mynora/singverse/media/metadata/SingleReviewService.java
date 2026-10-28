package com.mynora.singverse.media.metadata;

import static com.mynora.singverse.media.metadata.MetadataDtos.*;
import static com.mynora.singverse.media.metadata.SingleReviewDtos.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SingleReviewService {
    private final MetadataService metadata;
    private final MetadataMapper mapper;
    private final SingleReviewMapper reviewMapper;
    private final MediaSongRepository songs;
    private final MetadataJson json;
    private final TransactionTemplate tx;
    public SingleReviewService(MetadataService metadata, MetadataMapper mapper, SingleReviewMapper reviewMapper,
            MediaSongRepository songs, MetadataJson json, PlatformTransactionManager manager) {
        this.metadata=metadata; this.mapper=mapper; this.reviewMapper=reviewMapper; this.songs=songs; this.json=json; tx=new TransactionTemplate(manager);
    }
    public Review review(String id) {
        return tx.execute(s -> { var song=songs.get(id); return new Review(song, MediaSongRepository.fingerprint(song), version(song), current(song)); });
    }
    private String version(Song song) { return AuthCrypto.hash(MediaSongRepository.fingerprint(song)+":"+json.encode(song.metadata())); }
    public static Map<String,Object> trackValues(ExternalTrack track) {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("title",track.title()); values.put("artists",track.artists()); values.put("album",track.album());
        values.put("releaseDate",track.releaseDate()); values.put("aliases",track.aliases()); values.put("durationMs",track.durationMs()); values.put("cover",track.coverUrl());
        return values;
    }
    public static Map<String,Object> current(Song song) {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("title",song.result().title()); values.put("artists",song.result().artists()); values.put("album",null);
        values.put("releaseDate",null); values.put("aliases",List.of()); values.put("durationMs",null); values.put("cover",null);
        if (song.metadata()!=null && !song.metadata().stale()) {
            var stored=trackValues(song.metadata().track());
            for (String field:song.metadata().fields()) values.put(field,stored.get(field));
        }
        return values;
    }
    public Covers covers(String id, SearchInput input) {
        var result=metadata.search(id,input);
        return new Covers(result.candidates().stream().filter(c -> c.track().coverUrl()!=null)
                .map(c -> new CoverChoice(c.id(),c.track().provider(),c.track().externalId(),c.track().title(),c.track().artists(),c.track().album(),c.track().coverUrl(),null,null)).toList(),result.errors());
    }
    private ExternalTrack ownedCandidate(String songId, String candidateId, String fingerprint) {
        var row=metadata.candidateRow(candidateId);
        if (!songId.equals(row.get("song_id"))) throw bad("候选不属于当前歌曲");
        if (!fingerprint.equals(row.get("fingerprint"))) throw conflict("文件已变化，请重新匹配");
        return json.decode(row.get("track_json"),ExternalTrack.class);
    }
    public Result apply(String songId, Apply input) {
        if (input==null || input.requestId()==null || !input.requestId().matches("[a-fA-F0-9-]{36}")
                || input.fields()==null || input.fields().isEmpty() || input.fields().contains(null) || !MetadataService.FIELDS.containsAll(input.fields())) throw bad("请选择有效的应用字段并提供提交标识");
        if (input.edits()!=null && (!MetadataService.FIELDS.containsAll(input.edits().keySet()) || input.edits().containsKey("cover"))) throw bad("编辑字段无效，封面必须通过受管资源选择");
        String hash=AuthCrypto.hash(json.encode(input));
        return tx.execute(s -> {
            mapper.lock();
            var receipts=reviewMapper.receipt(input.requestId());
            if (!receipts.isEmpty()) {
                var receipt=receipts.getFirst();
                if (!songId.equals(receipt.get("song_id")) || !hash.equals(receipt.get("request_hash"))) throw conflict("提交标识已用于其他操作，请重新确认");
                return json.decode(receipt.get("response_json"),Result.class);
            }
            var song=songs.get(songId); String fingerprint=MediaSongRepository.fingerprint(song);
            if (!fingerprint.equals(input.fingerprint()) || !version(song).equals(input.version())) throw conflict("歌曲文件或确认元数据已变化，请重新加载后审核；编辑内容尚未应用");
            var before=current(song); Map<String,Object> values=new LinkedHashMap<>(before);
            var origins=new LinkedHashMap<String,Origin>();
            ExternalTrack candidate=input.candidateId()==null ? null : ownedCandidate(songId,input.candidateId(),fingerprint);
            if (candidate!=null) { values.putAll(trackValues(candidate)); for (String f:MetadataService.FIELDS) origins.put(f,new Origin(candidate.provider(),candidate.externalId(),now(),"PLATFORM")); }
            // Without a candidate, only explicitly edited fields supply new values.
            Set<String> supplied=new HashSet<>(candidate==null ? Set.of() : MetadataService.FIELDS);
            if (input.edits()!=null) for (var entry:input.edits().entrySet()) {
                if (!input.fields().contains(entry.getKey())) continue;
                values.put(entry.getKey(),validate(entry.getKey(),entry.getValue())); supplied.add(entry.getKey());
                origins.put(entry.getKey(),new Origin(candidate==null?null:candidate.provider(),candidate==null?null:candidate.externalId(),now(),"MANUAL"));
            }
            if (input.fields().contains("cover")) {
                if (input.coverAssetId()!=null && input.coverCandidateId()!=null) throw bad("封面来源只能选择一个");
                if (input.coverAssetId()!=null) {
                    var rows=reviewMapper.asset(input.coverAssetId());
                    if (rows.isEmpty() || !songId.equals(rows.getFirst().get("song_id"))) throw bad("上传封面不存在或不属于当前歌曲");
                    if (!fingerprint.equals(rows.getFirst().get("fingerprint"))) throw conflict("上传后文件已变化，请重新上传封面");
                    values.put("cover","asset:"+input.coverAssetId()); supplied.add("cover");
                    origins.put("cover",new Origin(null,input.coverAssetId(),now(),"UPLOAD"));
                } else if (input.coverCandidateId()!=null) {
                    var cover=ownedCandidate(songId,input.coverCandidateId(),fingerprint);
                    values.put("cover",cover.coverUrl()); supplied.add("cover"); origins.put("cover",new Origin(cover.provider(),cover.externalId(),now(),"PLATFORM"));
                }
            }
            Set<String> updated=new LinkedHashSet<>(),skipped=new LinkedHashSet<>();
            var after=new LinkedHashMap<>(before);
            for (String field:input.fields()) {
                Object value=values.get(field);
                if (!supplied.contains(field) || !present(value) || Objects.equals(before.get(field),value)) skipped.add(field);
                else { updated.add(field); after.put(field,value); }
            }
            Set<String> retained=new LinkedHashSet<>(MetadataService.FIELDS); retained.removeAll(input.fields());
            Applied prior=song.metadata()!=null && !song.metadata().stale()?song.metadata():null;
            Applied applied=prior;
            if (!updated.isEmpty()) {
                Set<String> fields=new LinkedHashSet<>(prior==null?Set.of():prior.fields()); fields.addAll(updated);
                Map<String,Origin> mergedOrigins=new LinkedHashMap<>(prior==null?Map.of():prior.origins());
                for (String field:updated) mergedOrigins.put(field,origins.get(field));
                var track=new ExternalTrack(candidate==null?null:candidate.provider(),candidate==null?null:candidate.externalId(),
                        (String)after.get("title"),strings(after.get("artists")),(String)after.get("album"),(Integer)after.get("durationMs"),
                        (String)after.get("releaseDate"),strings(after.get("aliases")),(String)after.get("cover"),"UNKNOWN",null);
                String time=now();
                String encodedTrack=json.encode(track),encodedFields=json.encode(fields),encodedOrigins=json.encode(mergedOrigins);
                mapper.deleteApplied(songId); mapper.insertApplied(songId,song.sourceId(),fingerprint,encodedTrack,encodedFields,encodedOrigins,time);
                applied=new Applied(track,fields,time,false,mergedOrigins);
            }
            var result=new Result(applied,updated,skipped,retained,before,after);
            reviewMapper.receiptSave(input.requestId(),songId,hash,json.encode(result),now());
            return result;
        });
    }
    private static boolean present(Object value) { return value!=null && (!(value instanceof String v)||!v.isBlank()) && (!(value instanceof List<?> v)||!v.isEmpty()); }
    @SuppressWarnings("unchecked") private static List<String> strings(Object value) { return value==null?List.of():(List<String>)value; }
    private static Object validate(String field,Object value) {
        if (value==null) return null;
        if (field.equals("artists") || field.equals("aliases")) {
            if (!(value instanceof List<?> list) || list.size()>20) throw bad("歌手/别名必须为最多 20 项的文本数组");
            var values=new LinkedHashSet<String>();
            for (Object item:list) { if (!(item instanceof String text)) throw bad("歌手/别名格式无效"); String clean=clean(text,200); if (!clean.isEmpty()) values.add(clean); }
            return List.copyOf(values);
        }
        if (field.equals("durationMs")) {
            if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue()<1 || ((Number)value).longValue()>86_400_000) throw bad("平台时长必须是 1～86400000 的整数毫秒");
            return ((Number)value).intValue();
        }
        if (!(value instanceof String text)) throw bad("元数据字段必须为文本");
        String clean=clean(text,field.equals("releaseDate")?10:500);
        if (field.equals("releaseDate") && !clean.isEmpty()) try {
            if (clean.matches("\\d{4}")) Year.parse(clean);
            else if (clean.matches("\\d{4}-\\d{2}")) YearMonth.parse(clean);
            else if (clean.matches("\\d{4}-\\d{2}-\\d{2}")) java.time.LocalDate.parse(clean);
            else throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw bad("发行日期须为有效的 YYYY、YYYY-MM 或 YYYY-MM-DD"); }
        return clean.isEmpty()?null:clean;
    }
    private static String clean(String text,int max) {
        if (text.length()>max || text.chars().anyMatch(Character::isISOControl)) throw bad("字段过长或包含控制字符"); return text.trim();
    }
}
