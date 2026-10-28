package com.mynora.singverse.media.dto;

import com.mynora.singverse.media.filename.BuiltinFilenameRules;
import com.mynora.singverse.media.filename.FilenameRuleDefinition;
import com.mynora.singverse.media.filename.FilenameRulePreview;
import com.mynora.singverse.media.model.FilenameParserDocument.*;
import java.util.List;

public final class FilenameParserDtos {
    private FilenameParserDtos() {}

    public record Configuration(long revision, String updatedAt, List<FilenameRuleDefinition> rules,
            List<BuiltinFilenameRules.Preset> builtinRules) {}
    public record Save(Long revision, List<FilenameRuleDefinition> rules) {}
    public record Preview(String filename, List<FilenameRuleDefinition> rules) {}

    public record Reference(String id, String name) {}
    public record DefinitionView(String id, String name, FilenameRuleDefinition.Type type,
            String expression, String builtinId, List<Reference> referencedBy) {}
    public record Workspace(long revision, String updatedAt, List<DefinitionView> definitions,
            List<Profile> profiles, List<BuiltinFilenameRules.Preset> builtinRules, String changedId) {}
    public record RuleInput(String name, FilenameRuleDefinition.Type type, String expression, String builtinId) {}
    public record RuleWrite(Long revision, RuleInput definition) {}
    public record ProfileInput(String name, List<Entry> entries) {}
    public record ProfileWrite(Long revision, ProfileInput profile) {}
    public record ProfilePreviewInput(String filename, String profileId, Long revision, List<Entry> entries) {}
    public record ProfilePreview(long revision, String profileId, String profileName, FilenameRulePreview preview) {}
}
