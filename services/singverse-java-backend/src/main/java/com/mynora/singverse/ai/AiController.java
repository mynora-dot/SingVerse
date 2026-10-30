package com.mynora.singverse.ai;

import static com.mynora.singverse.ai.AiDtos.*;
import com.mynora.singverse.media.classification.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiSettingsService settings;
    private final AiModelClient models;
    private final AiSearchClient search;
    private final ClassificationModel classification;
    private final ClassificationService songs;
    private final ConcurrentHashMap<String,Long> cooldowns=new ConcurrentHashMap<>();
    private final Set<String> testing=ConcurrentHashMap.newKeySet();
    public AiController(AiSettingsService settings,AiModelClient models,AiSearchClient search,ClassificationModel classification,ClassificationService songs) {this.settings=settings;this.models=models;this.search=search;this.classification=classification;this.songs=songs;}
    @GetMapping("/providers") public List<Provider> providers() {return AiProviders.ALL;}
    @GetMapping("/models") public List<ServiceView> models() {return settings.list("MODEL");}
    @GetMapping("/search-services") public List<ServiceView> searchServices() {return settings.list("SEARCH");}
    @PostMapping("/models") @ResponseStatus(HttpStatus.CREATED) public ServiceView createModel(@RequestBody ServiceInput v) {return settings.save("MODEL",null,v);}
    @PutMapping("/models/{id}") public ServiceView saveModel(@PathVariable String id,@RequestBody ServiceInput v) {return settings.save("MODEL",id,v);}
    @DeleteMapping("/models/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void removeModel(@PathVariable String id) {settings.remove("MODEL",id);}
    @PostMapping("/search-services") @ResponseStatus(HttpStatus.CREATED) public ServiceView createSearch(@RequestBody ServiceInput v) {return settings.save("SEARCH",null,v);}
    @PutMapping("/search-services/{id}") public ServiceView saveSearch(@PathVariable String id,@RequestBody ServiceInput v) {return settings.save("SEARCH",id,v);}
    @DeleteMapping("/search-services/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void removeSearch(@PathVariable String id) {settings.remove("SEARCH",id);}
    @GetMapping("/features") public List<Feature> features() {return settings.features();}
    @PutMapping("/features/{id}") public Feature feature(@PathVariable String id,@RequestBody Feature v) {return settings.bind(id,v);}
    @PostMapping("/models/{id}/test") public TestResult testModel(@PathVariable String id,@RequestBody TestInput v) {return test(id,"MODEL",v);}
    @PostMapping("/search-services/{id}/test") public TestResult testSearch(@PathVariable String id,@RequestBody TestInput v) {return test(id,"SEARCH",v);}
    private TestResult test(String id,String kind,TestInput input) {
        settings.get(id,kind);
        if(input==null)throw com.mynora.singverse.media.metadata.MetadataJson.bad("请填写测试参数");
        if(cooldowns.getOrDefault(id,0L)>System.currentTimeMillis())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"服务仍在限流冷却，请稍后重试");
        if(!testing.add(id))throw new ResponseStatusException(HttpStatus.CONFLICT,"该服务正在测试，请勿重复提交");
        long start=System.nanoTime();
        try {
            var config=settings.snapshot(id);String key=settings.key(config);List<SearchHit> hits=List.of();String message;
            if(kind.equals("SEARCH")) {
                if(input.query()==null || input.query().isBlank() || input.query().length()>200)throw com.mynora.singverse.media.metadata.MetadataJson.bad("搜索测试关键词应为 1～200 字符");
                hits=search.search(config,key,input.query().trim());message=hits.isEmpty()?"查询成功，未找到结果":"查询成功，返回 "+hits.size()+" 条结果";
            } else if("classification".equals(input.mode())) {
                var c=new ClassificationDtos.ModelConfig(true,config.endpoint(),config.model(),config.encryptedKey(),config.timeoutSeconds(),3,0,config.protocol(),config.outputMode(),config.credentialPurpose(),id);
                classification.generate(c,new ClassificationDtos.Input("connection-test","",List.of(),null,null,"UNKNOWN","UNKNOWN",List.of()),songs.tags(),Set.of("GENRE"));
                message="分类结构化输出验证通过；不代表分类准确率";
            } else if("generation".equals(input.mode())) {
                models.generate(config,key,"这是服务连接测试。请简短回复连接成功。","连接测试",null);message="普通生成连接成功；分类能力需单独验证";
            } else throw com.mynora.singverse.media.metadata.MetadataJson.bad("未知模型测试类型");
            return new TestResult(true,message,(System.nanoTime()-start)/1_000_000,hits);
        } catch(AiTransport.Failure e) {
            if(e.status()==429)cooldowns.put(id,System.currentTimeMillis()+60_000);
            throw new ResponseStatusException(e.status()==429?HttpStatus.TOO_MANY_REQUESTS:e.status()==504?HttpStatus.GATEWAY_TIMEOUT:HttpStatus.BAD_GATEWAY,e.getMessage());
        } catch(ClassificationModel.ModelFailure e) {
            if(e.rateLimited())cooldowns.put(id,System.currentTimeMillis()+60_000);
            throw new ResponseStatusException(e.rateLimited()?HttpStatus.TOO_MANY_REQUESTS:HttpStatus.BAD_GATEWAY,e.getMessage());
        } finally {testing.remove(id);}
    }
}
