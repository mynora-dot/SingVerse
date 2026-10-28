package com.mynora.singverse.media.classification;

import static com.mynora.singverse.media.classification.ClassificationDtos.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import java.net.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassificationSettings {
    static final String PURPOSE="singverse:classification:v1";
    private final ClassificationMapper mapper;
    private final AuthCrypto crypto;
    public ClassificationSettings(ClassificationMapper mapper, AuthCrypto crypto) { this.mapper=mapper; this.crypto=crypto; }
    public ModelConfig config() {
        var r=mapper.settings();
        return new ModelConfig(((Number)r.get("enabled")).intValue()==1,r.get("endpoint").toString(),r.get("model").toString(),(String)r.get("secret_encrypted"),((Number)r.get("timeout_seconds")).intValue(),((Number)r.get("interval_seconds")).intValue(),((Number)r.get("revision")).longValue());
    }
    public Settings read() { return visible(config()); }
    public static Settings visible(ModelConfig c) { return new Settings(c.enabled(),c.endpoint(),c.model(),c.encryptedKey()!=null,c.timeoutSeconds(),c.intervalSeconds(),c.revision()); }
    public String key(ModelConfig config) { return config.encryptedKey()==null?null:crypto.decrypt(config.encryptedKey(),PURPOSE); }
    public static URI endpoint(String value) {
        try {
            var uri=URI.create(value);
            if (value.length()>2048 || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !Set.of("http","https").contains(uri.getScheme()) || !uri.getPath().endsWith("/chat/completions")) throw new IllegalArgumentException();
            if (uri.getScheme().equals("http")) {
                var addresses=InetAddress.getAllByName(uri.getHost());
                for(var address:addresses) if(!address.isLoopbackAddress() && !address.isSiteLocalAddress()) throw new IllegalArgumentException();
            }
            return uri;
        } catch(Exception e) { throw bad("填写完整 chat/completions 地址；远程服务使用 HTTPS，HTTP 仅允许本机或局域网"); }
    }
    @Transactional
    public Settings save(SaveSettings input) {
        if(input==null || input.endpoint()==null || input.model()==null || input.model().length()>200 || input.model().isBlank()
                || input.model().chars().anyMatch(Character::isISOControl) || input.timeoutSeconds()<5 || input.timeoutSeconds()>180
                || input.intervalSeconds()<2 || input.intervalSeconds()>300 || input.apiKey()!=null && (input.apiKey().length()>4096 || input.apiKey().chars().anyMatch(Character::isISOControl))) throw bad("模型名称、超时或请求间隔无效");
        endpoint(input.endpoint()); mapper.lock(); var old=config();
        String secret=input.clearKey()?null:old.encryptedKey();
        if(input.apiKey()!=null && !input.apiKey().isBlank()) secret=crypto.encrypt(input.apiKey(),PURPOSE);
        if(!old.endpoint().equals(input.endpoint()) && old.encryptedKey()!=null && !input.clearKey() && (input.apiKey()==null || input.apiKey().isBlank())) throw bad("更换服务地址时请重新填写密钥或明确清除密钥");
        mapper.pauseAll(now());
        mapper.saveSettings(new ModelConfig(input.enabled(),input.endpoint(),input.model().trim(),secret,input.timeoutSeconds(),input.intervalSeconds(),old.revision()));
        return read();
    }
    public ModelConfig require() { var c=config(); if(!c.enabled()) throw conflict("请先保存并启用分类模型"); endpoint(c.endpoint()); return c; }
}
