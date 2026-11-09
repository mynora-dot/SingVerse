package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Immutable, persisted request recipe. Model paths and URLs never come from callers. */
public record AudioRecipe(String separationProfile,String modelId,String outputFormat,boolean preview,String sourceAudioId) {
    public static final String LEGACY="LEGACY_DEMUCS", DSP="DSP_FAST", CLEAN="AI_CLEAN";
    public AudioRecipe {
        separationProfile=blank(separationProfile)?LEGACY:separationProfile;
        outputFormat=blank(outputFormat)?"aac":outputFormat;
        String expected=switch(separationProfile){case LEGACY->"htdemucs";case DSP->"center-cancel-v1";case CLEAN->"uvr-mdxnet-9482";default->throw AudioJobService.fail(400,"未知伴奏方案");};
        if(!blank(modelId)&&!modelId.equals(expected))throw AudioJobService.fail(400,"模型与伴奏方案不匹配");
        modelId=expected;
        if(!Set.of("aac","flac").contains(outputFormat))throw AudioJobService.fail(400,"未知伴奏输出格式");
        if(sourceAudioId!=null&&!sourceAudioId.matches("stream:[0-9]{1,3}(?::(?:LEFT|RIGHT))?"))throw AudioJobService.fail(400,"源伴奏音轨标识无效");
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    public static AudioRecipe legacy(){return new AudioRecipe(null,null,null,false,null);}
    public static AudioRecipe from(JsonNode value){
        if(value.has("preview")&&!value.path("preview").isBoolean())throw AudioJobService.fail(400,"试听参数必须是布尔值");
        return new AudioRecipe(value.path("separationProfile").asText(null),value.path("modelId").asText(null),
                value.path("outputFormat").asText(null),value.path("preview").asBoolean(),value.path("sourceAudioId").asText(null));
    }
    public static AudioRecipe stored(ObjectMapper json,Object value){
        try{return from(json.readTree(value==null?"{}":value.toString()));}
        catch(java.io.IOException error){throw new IllegalStateException("Invalid persisted audio recipe",error);}
    }
    public AudioRecipe source(String identifier){return new AudioRecipe(separationProfile,modelId,outputFormat,preview,identifier);}
    public boolean legacyDefault(){return separationProfile.equals(LEGACY)&&outputFormat.equals("aac")&&!preview&&sourceAudioId==null;}
    public boolean playable(){return !preview&&outputFormat.equals("aac");}
    public String extension(){return outputFormat.equals("flac")?"flac":"m4a";}
    public ObjectNode view(ObjectMapper json){
        var result=json.createObjectNode().put("separationProfile",separationProfile).put("modelId",modelId)
                .put("outputFormat",outputFormat).put("preview",preview).put("recipeVersion",1);
        result.put("precisionPolicy","backend-default-v1");
        if(separationProfile.equals(CLEAN))result.put("modelDigest","9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d");
        if(separationProfile.equals(DSP))result.put("dspFilterVersion",1);
        if(sourceAudioId!=null)result.put("sourceAudioId",sourceAudioId);
        return result;
    }
    public String fingerprint(ObjectMapper json,String backend){
        try{
            String value=view(json).put("backend",backend==null?"":backend).toString();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public void validateBackend(String backend){
        if(!Set.of("","auto","cpu","cuda","rocm","mps","openvino","openvino-cpu","rknn","tensorrt","migraphx","coreml","directml","mlx").contains(backend))throw AudioJobService.fail(400,"未知推理后端");
        if(separationProfile.equals(DSP)&&!Set.of("","auto","cpu").contains(backend))throw AudioJobService.fail(400,"极速档使用 CPU，无需选择 GPU");
        if(separationProfile.equals(CLEAN)&&Set.of("rocm","mps").contains(backend))throw AudioJobService.fail(400,"轻量模型请选择对应 ONNX 后端或 CPU");
    }
    public boolean ready(JsonNode node,String backend){
        if(separationProfile.equals(LEGACY)&&sourceAudioId==null){
            if(!legacyDefault()&&node.path("separation").path("version").asInt()<1)return false;
            if(blank(backend)||backend.equals("auto"))return !node.path("selected").asText("").isEmpty();
            for(var item:node.path("backends"))if(item.path("id").asText().equals(backend)&&item.path("state").asText().equals("AVAILABLE"))return true;
            return false;
        }
        if(node.path("separation").path("version").asInt()<1)return false;
        for(var item:node.path("separation").path("profiles"))if(item.path("id").asText().equals(sourceAudioId!=null?DSP:separationProfile)&&item.path("ready").asBoolean()){
            if(!separationProfile.equals(CLEAN)||sourceAudioId!=null||blank(backend)||backend.equals("auto"))return true;
            for(var candidate:node.path("separation").path("backends"))if(candidate.path("id").asText().equals(backend)&&candidate.path("state").asText().equals("AVAILABLE"))return true;
            if(node.path("cpuFallback").asBoolean(true))for(var candidate:node.path("separation").path("backends"))if(candidate.path("id").asText().equals("cpu")&&candidate.path("state").asText().equals("AVAILABLE"))return true;
        }
        return false;
    }
}
