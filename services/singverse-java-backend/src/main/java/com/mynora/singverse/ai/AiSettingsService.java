package com.mynora.singverse.ai;

import static com.mynora.singverse.ai.AiDtos.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.classification.ClassificationMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiSettingsService {
    private final AiMapper mapper;
    private final AuthCrypto crypto;
    private final ClassificationMapper classification;
    public AiSettingsService(AiMapper mapper,AuthCrypto crypto,ClassificationMapper classification) {this.mapper=mapper;this.crypto=crypto;this.classification=classification;}
    public List<ServiceView> list(String kind) {kind(kind);return mapper.services(kind).stream().map(this::visible).toList();}
    public ServiceView get(String id,String kind) {var row=required(id);if(!str(row,"kind").equals(kind))throw bad("服务类型不匹配");return visible(row);}
    public Snapshot snapshot(String id) {
        var row=required(id);
        return new Snapshot(id,str(row,"protocol"),str(row,"endpoint"),str(row,"model"),str(row,"output_mode"),(String)row.get("secret_encrypted"),str(row,"credential_purpose"),bool(row,"enabled"),integer(row,"timeout_seconds"),integer(row,"max_results"));
    }
    public String key(Snapshot snapshot) {return snapshot.encryptedKey()==null?null:crypto.decrypt(snapshot.encryptedKey(),snapshot.credentialPurpose());}
    public List<Feature> features() {return mapper.features().stream().map(this::feature).toList();}
    public Feature feature(String id) {var row=mapper.feature(id);if(row==null)throw bad("未知 AI 功能");return feature(row);}
    @Transactional
    public ServiceView save(String kind,String id,ServiceInput input) {
        kind(kind);validate(kind,input);mapper.lock();classification.lock();
        Map<String,Object> old=id==null?null:required(id);
        if(old!=null && !str(old,"kind").equals(kind))throw bad("服务类型不匹配");
        if(old!=null && !input.enabled() && mapper.references(id)>0)throw conflict("服务被功能引用，请先替换或解除绑定再停用");
        String secret=old==null?null:(String)old.get("secret_encrypted");
        boolean destinationChanged=old!=null && (!str(old,"provider").equals(input.provider()) || !str(old,"protocol").equals(input.protocol()) || !str(old,"endpoint").equals(input.endpoint()));
        if(destinationChanged && secret!=null && !input.clearKey() && blank(input.apiKey()))throw bad("更换厂商、协议或地址时请重填密钥或明确清除");
        if(id==null)id=UUID.randomUUID().toString();
        String purpose=old==null?"singverse:ai:"+id+":v1":str(old,"credential_purpose");
        if(input.clearKey())secret=null;
        if(!blank(input.apiKey()))secret=crypto.encrypt(input.apiKey(),purpose);
        if(old==null){if(mapper.services(kind).size()>=100)throw conflict("每类最多配置 100 个服务");mapper.insert(id,kind,input,secret,purpose);}
        else {
            if(mapper.update(id,input,secret,purpose)!=1)throw conflict("配置已变化，请重新加载后编辑");
            if(mapper.references(id)>0 && kind.equals("MODEL"))classification.pauseAll(now());
        }
        return visible(required(id));
    }
    @Transactional
    public void remove(String kind,String id) {mapper.lock();classification.lock();get(id,kind);if(mapper.references(id)>0)throw conflict("服务被功能引用，请先替换或解除绑定");mapper.remove(id);}
    @Transactional
    public Feature bind(String id,Feature input) {
        if(input==null || !id.equals(input.id()) || !Set.of("classification","default-search","assistant").contains(id))throw bad("未知 AI 功能");
        if(input.intervalSeconds()<2 || input.intervalSeconds()>300)throw bad("请求间隔应为 2～300 秒");
        mapper.lock();classification.lock();
        if(id.equals("classification")) {
            if(input.searchServiceId()!=null)throw bad("歌曲分类暂不支持联网搜索");
            if(input.modelServiceId()!=null) {var service=get(input.modelServiceId(),"MODEL");if(!service.enabled())throw bad("请选择已启用模型服务");}
            if(input.enabled() && input.modelServiceId()==null)throw bad("启用分类前请选择模型服务");
            classification.pauseAll(now());
        } else if(id.equals("assistant")) {
            if(input.modelServiceId()!=null || input.searchServiceId()!=null)throw bad("助手模型与工具能力尚未开放配置");
            if(!Set.of("PET","CHAT").contains(input.displayMode()) || !Set.of("music-sprite").contains(input.petId()))throw bad("未知助手形态或宠物形象");
        } else {
            if(input.modelServiceId()!=null)throw bad("默认搜索不能绑定模型");
            if(input.searchServiceId()!=null && !get(input.searchServiceId(),"SEARCH").enabled())throw bad("请选择已启用搜索服务");
            if(input.enabled() && input.searchServiceId()==null)throw bad("请选择默认搜索服务");
        }
        if(mapper.saveFeature(input)!=1)throw conflict("功能配置已变化，请重新加载");
        return feature(id);
    }
    private void validate(String kind,ServiceInput v) {
        if(v==null || v.protocol()==null || v.outputMode()==null || v.outputMode().length()>20 || v.model()==null || v.model().length()>200 || blank(v.name()) || v.name().length()>100 || v.name().chars().anyMatch(Character::isISOControl)
                || v.timeoutSeconds()<5 || v.timeoutSeconds()>180 || v.maxResults()<1 || v.maxResults()>10
                || v.apiKey()!=null && (v.apiKey().length()>4096 || v.apiKey().chars().anyMatch(Character::isISOControl)))throw bad("名称、密钥、超时或结果数量无效");
        if(AiProviders.ALL.stream().noneMatch(p->p.id().equals(v.provider()) && p.kind().equals(kind)))throw bad("未知服务厂商");
        AiTransport.endpoint(v.endpoint());
        if(kind.equals("MODEL")) {
            if(blank(v.model()) || v.model().length()>200 || v.model().chars().anyMatch(Character::isISOControl)
                    || !Set.of("CHAT_COMPLETIONS","RESPONSES","ANTHROPIC","GEMINI").contains(v.protocol()) || !Set.of("JSON_SCHEMA","JSON_OBJECT").contains(v.outputMode()))throw bad("模型、接口协议或输出模式无效");
            if(v.protocol().equals("ANTHROPIC") && !v.outputMode().equals("JSON_SCHEMA"))throw bad("Anthropic 原生协议使用 JSON Schema 模式");
        } else if(!v.protocol().equals(v.provider().toUpperCase(Locale.ROOT)))throw bad("搜索协议与厂商不匹配");
    }
    private static void kind(String kind) {if(!Set.of("MODEL","SEARCH").contains(kind))throw bad("未知服务类型");}
    private Map<String,Object> required(String id) {var row=mapper.service(id);if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"AI 服务不存在");return row;}
    private ServiceView visible(Map<String,Object> r) {return new ServiceView(str(r,"id"),str(r,"kind"),str(r,"name"),str(r,"provider"),str(r,"protocol"),str(r,"endpoint"),str(r,"model"),str(r,"output_mode"),r.get("secret_encrypted")!=null,bool(r,"enabled"),integer(r,"timeout_seconds"),integer(r,"max_results"),number(r,"revision"));}
    private Feature feature(Map<String,Object> r) {return new Feature(str(r,"id"),(String)r.get("model_service_id"),(String)r.get("search_service_id"),bool(r,"enabled"),integer(r,"interval_seconds"),number(r,"revision"),str(r,"display_mode"),str(r,"pet_id"));}
    private static String str(Map<String,Object> r,String k) {return Objects.toString(r.get(k),"");}
    private static long number(Map<String,Object> r,String k) {return ((Number)r.get(k)).longValue();}
    private static int integer(Map<String,Object> r,String k) {return ((Number)r.get(k)).intValue();}
    private static boolean bool(Map<String,Object> r,String k) {return integer(r,k)!=0;}
    private static boolean blank(String value) {return value==null || value.isBlank();}
}
