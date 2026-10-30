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
    private final com.mynora.singverse.ai.AiSettingsService ai;
    public ClassificationSettings(ClassificationMapper mapper, AuthCrypto crypto, com.mynora.singverse.ai.AiSettingsService ai) { this.mapper=mapper; this.crypto=crypto; this.ai=ai; }
    public ModelConfig config() {
        var feature=ai.feature("classification");
        if(feature.modelServiceId()==null) return new ModelConfig(false,"","",null,60,feature.intervalSeconds(),feature.revision());
        var model=ai.snapshot(feature.modelServiceId());
        return new ModelConfig(feature.enabled() && model.enabled(),model.endpoint(),model.model(),model.encryptedKey(),model.timeoutSeconds(),feature.intervalSeconds(),feature.revision(),model.protocol(),model.outputMode(),model.credentialPurpose(),model.id());
    }
    public Settings read() {
        var c=config();
        String name=c.serviceId()==null?"":ai.get(c.serviceId(),"MODEL").name();
        return new Settings(c.enabled(),c.endpoint(),c.model(),c.encryptedKey()!=null,c.timeoutSeconds(),c.intervalSeconds(),c.revision(),c.serviceId(),name,c.protocol(),c.outputMode());
    }
    public static Settings visible(ModelConfig c) { return new Settings(c.enabled(),c.endpoint(),c.model(),c.encryptedKey()!=null,c.timeoutSeconds(),c.intervalSeconds(),c.revision(),c.serviceId(),"",c.protocol(),c.outputMode()); }
    public String key(ModelConfig config) { return config.encryptedKey()==null?null:crypto.decrypt(config.encryptedKey(),config.credentialPurpose()); }
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
        var feature=ai.feature("classification");
        var old=feature.modelServiceId()==null?null:ai.get(feature.modelServiceId(),"MODEL");
        if(old==null)endpoint(input.endpoint());
        var saved=ai.save("MODEL",feature.modelServiceId(),new com.mynora.singverse.ai.AiDtos.ServiceInput(
                old==null?"原歌曲分类模型":old.name(),old==null?"custom":old.provider(),old==null?"CHAT_COMPLETIONS":old.protocol(),input.endpoint(),input.model(),old==null?"JSON_SCHEMA":old.outputMode(),input.apiKey(),input.clearKey(),true,input.timeoutSeconds(),5,old==null?0:old.revision()));
        ai.bind("classification",new com.mynora.singverse.ai.AiDtos.Feature("classification",saved.id(),null,input.enabled(),input.intervalSeconds(),feature.revision()));
        String key=ai.key(ai.snapshot(saved.id()));
        mapper.saveSettings(new ModelConfig(input.enabled(),input.endpoint(),input.model().trim(),key==null?null:crypto.encrypt(key,PURPOSE),input.timeoutSeconds(),input.intervalSeconds(),feature.revision()));
        return read();
    }
    public ModelConfig require() { var c=config(); if(!c.enabled()) throw conflict("请先保存并启用分类模型"); com.mynora.singverse.ai.AiTransport.endpoint(c.endpoint()); return c; }
}
