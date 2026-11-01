package com.mynora.singverse.media.webdav.metadata;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** All directory/content requests share persistent counters/cooldowns and an in-process group gate. */
@Component
public class WebDavAccess {
    private final WebDavMetadataMapper mapper;
    private final MediaSourceRepository sources;
    private final long interval;
    private final ConcurrentHashMap<String,Object> gates = new ConcurrentHashMap<>();
    public WebDavAccess(WebDavMetadataMapper mapper, MediaSourceRepository sources,
            @Value("${singverse.webdav.request-interval-ms:2000}") long interval) {
        this.mapper=mapper; this.sources=sources; this.interval=Math.max(0,interval);
    }
    public static Map<String,Object> args(Object... pairs) {
        Map<String,Object> result=new HashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put(pairs[i].toString(),pairs[i+1]);
        return result;
    }
    public static String text(Map<String,Object> row,String key) { return Objects.toString(row.get(key),""); }
    public static long number(Map<String,Object> row,String key) { return row!=null && row.get(key) instanceof Number n?n.longValue():0; }
    public static String binding(Source source) { return AuthCrypto.hash(source.location()); }
    public Map<String,Object> ensure(Source source) {
        var rows=mapper.config(args("sourceId",source.id()));
        if(rows.isEmpty()) {
            var uri=java.net.URI.create(source.location());
            mapper.configInsert(args("sourceId",source.id(),"binding",binding(source),"group",AuthCrypto.hash(uri.getScheme()+"://"+uri.getAuthority()+"\0"+source.username())));
            rows=mapper.config(args("sourceId",source.id()));
        }
        if(!binding(source).equals(text(rows.getFirst(),"binding"))) throw failure(409,"媒体来源绑定已变化，请重新扫描");
        return rows.getFirst();
    }
    public Map<String,Object> config(String sourceId) { return ensure(sources.get(sourceId)); }
    public Map<String,Object> budget(String group) {
        String day=LocalDate.now(com.mynora.singverse.media.service.StandardScanCron.ZONE).toString();
        mapper.budgetInsert(args("group",group,"day",day)); mapper.budgetReset(args("group",group,"day",day));
        return mapper.budget(args("group",group)).getFirst();
    }
    public <T> T request(String sourceId,Supplier<T> action) {
        String group=text(config(sourceId),"access_group");
        synchronized(gates.computeIfAbsent(group,k->new Object())) {
            var budget=budget(group); Instant now=Instant.now();
            String blocked=text(budget,"blocked_until");
            if(!blocked.isEmpty() && Instant.parse(blocked).isAfter(now)) throw failure(429,"WebDAV 访问冷却中");
            String next=text(budget,"next_request_at");
            long wait=next.isEmpty()?0:Duration.between(now,Instant.parse(next)).toMillis();
            if(wait>0) try { Thread.sleep(wait); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw failure(409,"WebDAV 请求已取消"); }
            try { return action.get(); }
            finally {
                long jitter=interval==0?0:java.util.concurrent.ThreadLocalRandom.current().nextLong(501);
                mapper.nextRequest(args("group",group,"after",Instant.now().plusMillis(interval+jitter).toString()));
            }
        }
    }
    /** Called while the group gate is held, between upstream redirect hops. */
    public void redirect(String sourceId) {
        String group=text(config(sourceId),"access_group");
        long jitter=interval==0?0:java.util.concurrent.ThreadLocalRandom.current().nextLong(501);
        Instant next=Instant.now().plusMillis(interval+jitter);
        mapper.nextRequest(args("group",group,"after",next.toString()));
        long wait=Math.max(0,Duration.between(Instant.now(),next).toMillis());
        if(wait>0)try{Thread.sleep(wait);}catch(InterruptedException error){Thread.currentThread().interrupt();throw failure(409,"WebDAV 请求已取消");}
    }
    public void block(String sourceId,boolean auth,Instant until) {
        var config=config(sourceId);
        if(!auth) {
            String previous=text(budget(text(config,"access_group")),"blocked_until");
            if(!previous.isEmpty()&&Instant.parse(previous).isAfter(until))until=Instant.parse(previous);
            String sourceUntil=text(config,"blocked_until");
            if(!sourceUntil.isEmpty()&&Instant.parse(sourceUntil).isAfter(until))until=Instant.parse(sourceUntil);
        }
        mapper.blockSource(args("sourceId",sourceId,"until",auth?"9999-12-31T23:59:59Z":until.toString(),"reason",auth?"AUTH_REQUIRED":"RATE_LIMITED"));
        if(!auth) mapper.blockGroup(args("group",text(config,"access_group"),"until",until.toString()));
    }
    public static ResponseStatusException failure(int status,String reason) { return new ResponseStatusException(HttpStatus.valueOf(status),reason); }
}
