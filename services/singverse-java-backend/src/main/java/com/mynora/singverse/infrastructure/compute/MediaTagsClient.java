package com.mynora.singverse.infrastructure.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.compute.ComputeNodeSettings;
import org.springframework.stereotype.Component;

/** Java requests metadata from the authenticated compute service; never launches media subprocesses. */
@Component
public class MediaTagsClient {
    public record Tags(String title, List<String> artists, String album, String releaseDate) {
        public Tags { artists = artists == null ? List.of() : List.copyOf(artists); }
        public Set<String> fields() {
            var fields = new HashSet<String>();
            if (title != null && !title.isBlank()) fields.add("title");
            if (!artists.isEmpty()) fields.add("artists");
            if (album != null && !album.isBlank()) fields.add("album");
            if (releaseDate != null && !releaseDate.isBlank()) fields.add("releaseDate");
            return fields;
        }
    }
    private final ObjectMapper json;
    private final java.util.function.Supplier<Connection> connections;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(2)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    @Autowired
    public MediaTagsClient(ObjectMapper json, ComputeNodeSettings settings) {
        this.json=json; this.connections=settings::current;
    }
    public MediaTagsClient(ObjectMapper json, String baseUrl, String tokenFile) {
        this.json=json; this.connections=()-> {
            if (baseUrl.isBlank() || tokenFile.isBlank()) return null;
            try { return new Connection(ComputeNodeSettings.origin(baseUrl),java.nio.file.Files.readString(Path.of(tokenFile)).strip(),List.of()); }
            catch(Exception error) {return null;}
        };
    }
    /** A scan uses one connection snapshot, even if configuration changes midway. */
    public java.util.function.Function<Path,String> session() {
        var connection=connections.get();
        return path->read(connection,path);
    }
    public boolean hasIdentity(String encoded) {
        if (encoded == null) return false;
        try { var tags = json.readValue(encoded, Tags.class); return tags.fields().containsAll(Set.of("title", "artists")); }
        catch (Exception error) { return false; }
    }
    public String read(Path file) {return read(connections.get(),file);}
    private String read(Connection connection,Path file) {
        if (connection==null || connection.token().isBlank()) return null;
        try {
            ComputeNodeSettings.checkNetwork(connection.origin());
            String token=connection.token();
            if(token.length()<32 || token.length()>256) return null;
            var request = java.net.http.HttpRequest.newBuilder(connection.origin().resolve("/internal/v1/media/tags")).timeout(java.time.Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("path", connection.mediaPath(file))))).build();
            var response = http.send(request, info -> new com.mynora.singverse.media.metadata.BoundedResponse(65536));
            if (response.statusCode() != 200) return null;
            var tags = json.readValue(response.body(), Tags.class);
            if (tags.fields().isEmpty()) return null;
            if (tags.title() != null && tags.title().length() > 500 || tags.album() != null && tags.album().length() > 500
                    || tags.artists().size() > 32 || tags.artists().stream().anyMatch(a -> a == null || a.isBlank() || a.length() > 200)
                    || tags.releaseDate() != null && tags.releaseDate().length() > 32) return null;
            return json.writeValueAsString(tags);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return null; }
        catch (Exception error) { return null; }
    }
}
