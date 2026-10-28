package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;
import static com.mynora.singverse.media.filename.FilenameRulePreview.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ConfiguredFilenameEngine {
    static FilenameRulePreview preview(String original, FilenameRuleSet rules) {
        if (original != null && original.length() > 1024)
            throw new FilenameRuleValidationException(null, -1, "filename", "文件名最多 1024 个字符");
        String normalized = FilenameNormalizer.normalize(original);
        if (FilenameNormalizer.invalid(original) || !FilenameNormalizer.meaningful(normalized))
            return invalid(original, normalized, null, "无效文件名", DiagnosticCode.INVALID_INPUT);
        var parts = ExtensionHandler.extract(normalized);
        if (!parts.supported()) return invalid(original, parts.normalized(), parts.extension(),
                "不支持的扩展名", DiagnosticCode.UNSUPPORTED_EXTENSION);
        if (!FilenameNormalizer.meaningful(parts.stem()) || !FilenameNormalizer.balanced(parts.stem()))
            return invalid(original, parts.normalized(), parts.extension(), "名称为空或括号不匹配", DiagnosticCode.INVALID_INPUT);
        var attempts = new ArrayList<Attempt>();
        Selection selection = null;
        FilenameParseResult chosen = null;
        FilenameParseResult partial = null;
        boolean ambiguous = false;
        for (ConfiguredFilenameRule rule : rules.compiled()) {
            var definition = rule.definition;
            if (!definition.enabled()) {
                attempts.add(attempt(definition, Status.DISABLED, Map.of(), List.of(), "规则已停用"));
                continue;
            }
            var outcome = rule.match(parts.stem());
            var candidates = outcome.captures().stream().filter(c -> definition.type() != FilenameRuleDefinition.Type.BUILTIN
                    || rule.accepts(c.fields())).toList();
            if (outcome.limited() || candidates.size() > 1) {
                ambiguous = true;
                attempts.add(attempt(definition, Status.AMBIGUOUS, Map.of(), candidates.stream().map(ConfiguredFilenameRule.Capture::fields).toList(),
                        outcome.limited() ? "模板拆分分支过多，请使用更明确的分隔符或正则" : "模板存在多个有效拆分，未任意选择"));
            } else if (candidates.isEmpty()) {
                attempts.add(attempt(definition, Status.NO_MATCH, Map.of(), List.of(), "未完整匹配名称或字段不符合约束"));
            } else {
                var capture = candidates.getFirst();
                var result = result(original, parts, definition, capture);
                boolean complete = result.parseStatus() == ParseStatus.SUCCESS;
                attempts.add(attempt(definition, complete ? Status.MATCHED : Status.PARTIAL, capture.fields(), List.of(),
                        complete ? "完整捕获歌手和歌名" : "已匹配结构，但歌手、歌名或编号捕获无效/不完整"));
                if (complete && chosen == null) {
                    chosen = result;
                    selection = new Selection(definition.id(), definition.name(), definition.type());
                } else if (!complete && partial == null) partial = result;
            }
        }
        if (chosen == null) {
            if (partial != null && !ambiguous) chosen = partial;
            else chosen = new FilenameParseResult(original, parts.normalized(), null, List.of(), Language.UNKNOWN,
                    VersionType.UNKNOWN, null, parts.extension(), List.of(), ambiguous ? 0.30 : 0,
                    ambiguous ? ParseStatus.NEEDS_REVIEW : ParseStatus.FAILED,
                    ambiguous ? RuleId.AMBIGUOUS : RuleId.NONE, List.of(parts.stem()), true,
                    List.of(new Diagnostic(ambiguous ? DiagnosticCode.AMBIGUOUS_STRUCTURE : DiagnosticCode.NO_RULE_MATCH,
                            "没有规则完整解析出歌手和歌名；未调用其他解析器兜底", 0)));
        }
        return new FilenameRulePreview(chosen, selection, parts.stem(), attempts);
    }

    private static Attempt attempt(FilenameRuleDefinition d, Status status, Map<String, String> captures,
            List<Map<String, String>> candidates, String detail) {
        return new Attempt(d.id(), d.name(), d.type(), status, captures, candidates, detail);
    }

    private static FilenameParseResult result(String original, ExtensionHandler.Parts parts,
            FilenameRuleDefinition definition, ConfiguredFilenameRule.Capture capture) {
        Map<String, String> fields = capture.fields();
        var evidence = new ArrayList<>(capture.residue());
        var unknown = new ArrayList<String>();
        addField(fields, "language", TagDictionary.language(fields.getOrDefault("language", "")) != null, evidence, unknown);
        addField(fields, "version", TagDictionary.version(fields.getOrDefault("version", "")) != null, evidence, unknown);
        if (fields.containsKey("quality")) evidence.add(fields.get("quality"));
        if (fields.containsKey("extra")) evidence.add(fields.get("extra"));
        var tags = TagExtractor.evidence(evidence);
        unknown.addAll(tags.unknown());
        unknown.addAll(tags.conflicts());
        String title = fields.get("title");
        if (!FilenameNormalizer.meaningful(title)) title = null;
        var artist = ArtistParser.parse(fields.get("artist"), tags.version() == VersionType.DUET);
        String code = fields.get("code");
        boolean validCode = code == null || code.matches("[0-9]+");
        if (!validCode) { unknown.add(code); code = null; }
        boolean complete = title != null && !artist.values().isEmpty() && validCode;
        double score = complete ? 0.90 : 0.45;
        if (definition.type() == FilenameRuleDefinition.Type.BUILTIN && complete) score = 0.85;
        RuleId ruleId = switch (definition.type()) {
            case BUILTIN -> RuleId.CONFIGURED_BUILTIN;
            case TEMPLATE -> RuleId.CUSTOM_TEMPLATE;
            case REGEX -> RuleId.CUSTOM_REGEX;
        };
        var diagnostics = new ArrayList<Diagnostic>();
        diagnostics.add(new Diagnostic(DiagnosticCode.MATCHED_RULE, definition.id() + "：" + definition.name(), score));
        if (!unknown.isEmpty()) diagnostics.add(new Diagnostic(DiagnosticCode.UNKNOWN_TOKEN, unknown.toString(), 0));
        if (!tags.conflicts().isEmpty()) diagnostics.add(new Diagnostic(DiagnosticCode.CONFLICTING_TAGS, tags.conflicts().toString(), 0));
        if (!complete) diagnostics.add(new Diagnostic(DiagnosticCode.INCOMPLETE_FIELDS, "核心字段不完整或编号无效", 0));
        if (parts.extension() == null) diagnostics.add(new Diagnostic(DiagnosticCode.MISSING_EXTENSION, "未提供扩展名", 0));
        if (artist.ambiguous() && fields.get("artist") != null) unknown.add(fields.get("artist"));
        return new FilenameParseResult(original, parts.normalized(), title, artist.values(), tags.language(), tags.version(),
                code, parts.extension(), tags.quality(), score, complete ? ParseStatus.SUCCESS : ParseStatus.PARTIAL,
                ruleId, unknown, !complete, diagnostics);
    }

    private static void addField(Map<String, String> fields, String name, boolean known,
            List<String> evidence, List<String> unknown) {
        String value = fields.get(name);
        if (value != null && !value.isBlank()) (known ? evidence : unknown).add(value);
    }

    private static FilenameRulePreview invalid(String original, String normalized, String extension, String detail, DiagnosticCode reason) {
        return new FilenameRulePreview(new FilenameParseResult(original, normalized, null, List.of(), Language.UNKNOWN,
                VersionType.UNKNOWN, null, extension, List.of(), 0, ParseStatus.FAILED, RuleId.NONE,
                original == null ? List.of() : List.of(original), true, List.of(new Diagnostic(reason, detail, 0))),
                null, null, List.of());
    }
}
