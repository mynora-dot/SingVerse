package com.mynora.singverse.media.filename;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Validated immutable snapshot, compiled once and reusable across threads. */
public final class FilenameRuleSet {
    private final List<FilenameRuleDefinition> definitions;
    private final List<ConfiguredFilenameRule> compiled;

    public FilenameRuleSet(List<FilenameRuleDefinition> definitions) {
        if (definitions == null || definitions.size() > 100)
            throw new FilenameRuleValidationException(null, -1, "rules", "规则列表不能为空值，且最多 100 条");
        var ids = new HashSet<String>();
        var rules = new ArrayList<ConfiguredFilenameRule>();
        for (int i = 0; i < definitions.size(); i++) {
            var rule = definitions.get(i);
            if (rule == null) throw new FilenameRuleValidationException(null, i, "rules", "规则不能为空值");
            if (rule.id() == null || !rule.id().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}") || !ids.add(rule.id()))
                throw new FilenameRuleValidationException(rule.id(), i, "id", "规则 ID 无效或重复");
            rules.add(new ConfiguredFilenameRule(rule, i));
        }
        this.definitions = List.copyOf(definitions);
        this.compiled = List.copyOf(rules);
    }

    public List<FilenameRuleDefinition> definitions() { return definitions; }
    List<ConfiguredFilenameRule> compiled() { return compiled; }
}
