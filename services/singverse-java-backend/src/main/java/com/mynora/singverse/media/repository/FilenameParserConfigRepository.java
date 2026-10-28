package com.mynora.singverse.media.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.infrastructure.database.DatabaseInitializer;
import com.mynora.singverse.media.filename.FilenameRuleDefinition;
import com.mynora.singverse.media.filename.FilenameRuleSet;
import com.mynora.singverse.media.model.FilenameParserDocument;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class FilenameParserConfigRepository {
    public static final class InvalidConfigurationException extends IllegalStateException {
        public InvalidConfigurationException(Throwable cause) { super("Stored filename configuration is invalid", cause); }
    }
    public record Stored(long revision, String updatedAt, FilenameParserDocument document) {
        public List<FilenameRuleDefinition> rules() { return document.defaultRules(); }
    }
    private final FilenameParserConfigMapper mapper;
    private final ObjectMapper json;

    public FilenameParserConfigRepository(FilenameParserConfigMapper mapper, ObjectMapper json, DatabaseInitializer database) {
        this.mapper = mapper;
        this.json = json;
    }

    public Stored read() {
        var rows = mapper.read();
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        try {
            var tree = json.readTree(row.get("rules_json").toString());
            FilenameParserDocument document;
            if (tree.isArray()) {
                List<FilenameRuleDefinition> legacy = json.convertValue(tree, new TypeReference<List<FilenameRuleDefinition>>() {});
                new FilenameRuleSet(legacy);
                document = FilenameParserDocument.legacy(legacy);
            } else {
                if (!tree.isObject() || tree.path("schemaVersion").asInt() != 2)
                    throw new InvalidConfigurationException(null);
                document = json.treeToValue(tree, FilenameParserDocument.class);
            }
            return new Stored(((Number) row.get("revision")).longValue(), row.get("updated_at").toString(), document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException error) {
            throw new InvalidConfigurationException(error);
        }
    }

    public void save(long revision, FilenameParserDocument document, String updatedAt) {
        String encoded;
        try { encoded = json.writeValueAsString(document); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        try {
            if (revision == 0) {
                mapper.insert(encoded, updatedAt);
            } else if (mapper.update(encoded, updatedAt, revision) != 1) throw conflict();
        } catch (DataAccessException error) {
            if (error instanceof DuplicateKeyException
                    || (error.getMostSpecificCause() instanceof org.sqlite.SQLiteException sqlite
                        && (sqlite.getResultCode().code & 0xff) == 19)) throw conflict();
            throw error;
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "规则已被其他窗口修改，请重新加载；当前草稿可继续保留");
    }
}
