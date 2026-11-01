package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AudioRoleService {
    private final AudioRoleMapper mapper;
    private final MediaSongRepository songs;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.mynora.singverse.media.metadata.MetadataFileMapper files;

    public AudioRoleService(AudioRoleMapper mapper, MediaSongRepository songs, ObjectMapper json) {
        this.mapper=mapper; this.songs=songs; this.json=json;
    }

    private static String value(Map<String,Object> row,String key) { return Objects.toString(row.get(key),""); }
    private static ResponseStatusException fail(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
    private String fingerprint(String song) { return MediaSongRepository.fingerprint(songs.get(song)); }

    public ObjectNode role(String song) {
        String current=fingerprint(song);
        var row=mapper.role(song);
        var result=json.createObjectNode().put("songId",song).put("fingerprint",current);
        if(row==null || !current.equals(value(row,"fingerprint")))
            return result.put("state","UNKNOWN").put("revision",row==null?0:((Number)row.get("revision")).longValue());
        return result.put("state","CONFIRMED").put("origin",value(row,"origin"))
                .put("revision",((Number)row.get("revision")).longValue())
                .put("originalId",value(row,"original_id")).put("backingId",value(row,"backing_id"));
    }

    @Transactional
    public synchronized ObjectNode correct(String song,String original,String backing,long expectedRevision) {
        if(original==null || backing==null || !original.matches("stream:[0-9]{1,3}") || !backing.matches("stream:[0-9]{1,3}") || original.equals(backing))
            throw fail(HttpStatus.BAD_REQUEST,"请选择两条不同的源音轨");
        boolean valid=false;
        for(var job:mapper.forSong(song,fingerprint(song)))try{
            var media=json.readTree(value(job,"result_json"));var ids=new java.util.HashSet<String>();
            for(var stream:media.path("streams"))ids.add(stream.path("id").asText());
            if(ids.size()==2&&ids.contains(original)&&ids.contains(backing)){valid=true;break;}
        }catch(java.io.IOException ignored){}
        if(!valid)throw fail(HttpStatus.CONFLICT,"请先完成媒体探测后再标记音轨");
        var prior=mapper.role(song);
        long revision=prior==null?0:((Number)prior.get("revision")).longValue();
        if(revision!=expectedRevision) throw fail(HttpStatus.CONFLICT,"音轨标记已变化，请刷新后重试");
        var entry=new HashMap<String,Object>(); entry.put("song_id",song); entry.put("fingerprint",fingerprint(song));
        entry.put("original_id",original); entry.put("backing_id",backing); entry.put("origin","MANUAL");
        entry.put("previous_revision",revision); entry.put("revision",revision+1); entry.put("updated_at",System.currentTimeMillis());
        if(prior==null) mapper.insertRole(entry);
        else if(mapper.updateRole(entry)!=1) throw fail(HttpStatus.CONFLICT,"音轨标记已变化，请刷新后重试");
        dirty(song);
        return role(song);
    }

    @Transactional
    public synchronized void applySuggestion(String song,String sourceFingerprint,JsonNode result) {
        if(!result.path("state").asText().equals("CONFIDENT")) return;
        String original=result.path("original").asText(),backing=result.path("backing").asText();
        if(!original.matches("stream:[0-9]{1,3}")||!backing.matches("stream:[0-9]{1,3}")||original.equals(backing)) return;
        var prior=mapper.role(song);
        if(prior!=null && java.util.Set.of("MANUAL","SHARED").contains(value(prior,"origin")) && value(prior,"fingerprint").equals(sourceFingerprint)) return;
        if(!fingerprint(song).equals(sourceFingerprint)) return;
        long revision=prior==null?0:((Number)prior.get("revision")).longValue();
        var entry=new HashMap<String,Object>(); entry.put("song_id",song); entry.put("fingerprint",sourceFingerprint);
        entry.put("original_id",original); entry.put("backing_id",backing); entry.put("origin","AUTO");
        entry.put("previous_revision",revision); entry.put("revision",revision+1); entry.put("updated_at",System.currentTimeMillis());
        if(prior==null) mapper.insertRole(entry); else mapper.updateRole(entry);
        dirty(song);
    }
    public ObjectNode portable(String song){
        var value=role(song);value.remove(java.util.List.of("songId","fingerprint","revision"));
        for(var job:mapper.forSong(song,fingerprint(song)))try{
            var result=json.readTree(value(job,"result_json"));
            if(result.path("streams").isArray()){value.set("streams",result.get("streams"));value.set("duration",result.path("duration"));break;}
        }catch(java.io.IOException ignored){}
        value.put("separationPolicy","GENERATE_LOCALLY");return value;
    }
    public void importClaim(String song,JsonNode claim){
        if(!claim.isObject()||claim.toString().length()>16384)return;
        if(mapper.claim(song)==null){
            var stored=json.createObjectNode().put("fingerprint",fingerprint(song));stored.set("claim",claim);mapper.insertClaim(song,stored.toString());
        }
    }
    @Transactional
    public synchronized void verifyImported(String song,String fingerprint,JsonNode probe){
        String stored=mapper.claim(song);if(stored==null)return;
        try{
            var envelope=json.readTree(stored);
            if(!envelope.path("fingerprint").asText().equals(fingerprint))return;
            var claim=envelope.path("claim");
            if(!claim.path("state").asText().equals("CONFIRMED")||!claim.path("streams").equals(probe.path("streams"))
                ||Math.abs(claim.path("duration").asDouble(-1)-probe.path("duration").asDouble())>.25)return;
            var suggestion=json.createObjectNode().put("state","CONFIDENT").put("original",claim.path("originalId").asText()).put("backing",claim.path("backingId").asText());
            if(!fingerprint(song).equals(fingerprint))return;
            var prior=mapper.role(song);if(prior!=null&&value(prior,"fingerprint").equals(fingerprint)&&java.util.Set.of("MANUAL","SHARED").contains(value(prior,"origin")))return;
            long revision=prior==null?0:((Number)prior.get("revision")).longValue();
            var entry=new HashMap<String,Object>();entry.put("song_id",song);entry.put("fingerprint",fingerprint);
            entry.put("original_id",suggestion.path("original").asText());entry.put("backing_id",suggestion.path("backing").asText());entry.put("origin","SHARED");
            if(!entry.get("original_id").toString().matches("stream:[0-9]{1,3}")||!entry.get("backing_id").toString().matches("stream:[0-9]{1,3}")||entry.get("original_id").equals(entry.get("backing_id")))return;
            var ids=new java.util.HashSet<String>();for(var stream:probe.path("streams"))ids.add(stream.path("id").asText());
            if(!ids.contains(entry.get("original_id"))||!ids.contains(entry.get("backing_id")))return;
            entry.put("previous_revision",revision);entry.put("revision",revision+1);entry.put("updated_at",System.currentTimeMillis());
            if(prior==null)mapper.insertRole(entry);else mapper.updateRole(entry);dirty(song);
        }catch(java.io.IOException ignored){}
    }
    private void dirty(String song){
        var args=Map.<String,Object>of("songId",song,"now",java.time.Instant.now().toString());
        files.touch(args);files.enqueue(args);
    }
}
