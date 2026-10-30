package com.mynora.singverse.ai;

import java.util.List;

public final class AiDtos {
    private AiDtos() {}
    public record Provider(String id, String name, String kind, String protocol, String endpoint, String outputMode) {}
    public record ServiceInput(String name, String provider, String protocol, String endpoint, String model,
            String outputMode, String apiKey, boolean clearKey, boolean enabled, int timeoutSeconds, int maxResults, long revision) {}
    public record ServiceView(String id, String kind, String name, String provider, String protocol, String endpoint,
            String model, String outputMode, boolean hasKey, boolean enabled, int timeoutSeconds, int maxResults, long revision) {}
    public record Snapshot(String id, String protocol, String endpoint, String model, String outputMode,
            String encryptedKey, String credentialPurpose, boolean enabled, int timeoutSeconds, int maxResults) {}
    public record Feature(String id, String modelServiceId, String searchServiceId, boolean enabled, int intervalSeconds, long revision,
            String displayMode, String petId) {
        public Feature {
            if(displayMode==null)displayMode="PET";
            if(petId==null)petId="music-sprite";
        }
        public Feature(String id,String modelServiceId,String searchServiceId,boolean enabled,int intervalSeconds,long revision) {
            this(id,modelServiceId,searchServiceId,enabled,intervalSeconds,revision,"PET","music-sprite");
        }
    }
    public record TestInput(String mode, String query) {}
    public record SearchHit(String title, String url, String snippet) {}
    public record TestResult(boolean success, String message, long elapsedMs, List<SearchHit> results) {}
}
