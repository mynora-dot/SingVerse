package com.mynora.singverse.media.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class MetadataJson {
    private final ObjectMapper mapper;
    public MetadataJson(ObjectMapper mapper) { this.mapper = mapper; }
    public String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Cannot encode metadata", error); }
    }
    public <T> T decode(Object value, Class<T> type) {
        try { return mapper.readValue(value.toString(), type); }
        catch (Exception error) { throw new IllegalStateException("Stored metadata is invalid", error); }
    }
    public <T> T decode(Object value, TypeReference<T> type) {
        try { return mapper.readValue(value.toString(), type); }
        catch (Exception error) { throw new IllegalStateException("Stored metadata is invalid", error); }
    }
    public static String now() { return java.time.Instant.now().toString(); }
    public static org.springframework.web.server.ResponseStatusException bad(String message) {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
    public static org.springframework.web.server.ResponseStatusException conflict(String message) {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, message);
    }
}
