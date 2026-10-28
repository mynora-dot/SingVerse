package com.mynora.singverse.media.filename;

import java.util.List;
import java.util.Map;

public record FilenameRulePreview(FilenameParseResult result, Selection selectedRule,
        String matchTarget, List<Attempt> attempts) {
    public FilenameRulePreview { attempts = List.copyOf(attempts); }

    public record Selection(String id, String name, FilenameRuleDefinition.Type type) {}
    public enum Status { DISABLED, NO_MATCH, PARTIAL, AMBIGUOUS, MATCHED }

    public record Attempt(String id, String name, FilenameRuleDefinition.Type type, Status status,
            Map<String, String> captures, List<Map<String, String>> candidates, String detail) {
        public Attempt {
            captures = Map.copyOf(captures);
            candidates = candidates.stream().map(Map::copyOf).toList();
        }
    }
}
