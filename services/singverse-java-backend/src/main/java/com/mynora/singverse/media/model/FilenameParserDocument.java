package com.mynora.singverse.media.model;

import com.mynora.singverse.media.filename.FilenameRuleDefinition;
import java.util.List;

/** Versioned portable storage document; executable rule sets are resolved from profile references. */
public record FilenameParserDocument(int schemaVersion, List<Definition> definitions, List<Profile> profiles) {
    public static final String DEFAULT_PROFILE_ID = "default";

    public FilenameParserDocument {
        definitions = List.copyOf(definitions);
        profiles = List.copyOf(profiles);
    }

    public record Definition(String id, String name, FilenameRuleDefinition.Type type, String expression, String builtinId) {
        public FilenameRuleDefinition executable(boolean enabled) {
            return new FilenameRuleDefinition(id, name, type, enabled, expression, builtinId);
        }
        public static Definition from(FilenameRuleDefinition rule) {
            return new Definition(rule.id(), rule.name(), rule.type(), rule.expression(), rule.builtinId());
        }
    }
    public record Entry(String ruleId, Boolean enabled) {}
    public record Profile(String id, String name, List<Entry> entries) {
        public Profile { entries = List.copyOf(entries); }
    }

    public static FilenameParserDocument legacy(List<FilenameRuleDefinition> rules) {
        return new FilenameParserDocument(2, rules.stream().map(Definition::from).toList(),
                List.of(new Profile(DEFAULT_PROFILE_ID, "默认解析方案", rules.stream()
                        .map(r -> new Entry(r.id(), r.enabled())).toList())));
    }

    public List<FilenameRuleDefinition> defaultRules() {
        var profile = profiles.stream().filter(p -> p.id().equals(DEFAULT_PROFILE_ID)).findFirst().orElseThrow();
        return profile.entries().stream().map(e -> definitions.stream().filter(d -> d.id().equals(e.ruleId()))
                .findFirst().orElseThrow().executable(Boolean.TRUE.equals(e.enabled()))).toList();
    }
}
