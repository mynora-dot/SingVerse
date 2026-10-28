package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;

import java.util.ArrayList;
import java.util.List;

/** Stateless, thread-safe filename parser. No Spring context, IO or external services are used. */
public final class KtvFilenameParser {
    public static boolean supportsMediaFilename(String filename) {
        var parts = ExtensionHandler.extract(FilenameNormalizer.normalize(filename));
        return parts.extension() != null && parts.supported();
    }

    public FilenameParseResult parse(String filename, FilenameRuleSet rules) {
        return preview(filename, rules).result();
    }

    public FilenameRulePreview preview(String filename, FilenameRuleSet rules) {
        return ConfiguredFilenameEngine.preview(filename, java.util.Objects.requireNonNull(rules));
    }

    public FilenameParseResult parse(String filename) {
        String normalized = FilenameNormalizer.normalize(filename);
        if (FilenameNormalizer.invalid(filename) || !FilenameNormalizer.meaningful(normalized))
            return failed(filename, normalized, null, DiagnosticCode.INVALID_INPUT, "文件名为空、含控制字符或没有有效名称");
        var parts = ExtensionHandler.extract(normalized);
        if (!parts.supported()) return failed(filename, parts.normalized(), parts.extension(),
                DiagnosticCode.UNSUPPORTED_EXTENSION, "不支持的媒体扩展名");
        if (!FilenameNormalizer.meaningful(parts.stem())) return failed(filename, parts.normalized(), parts.extension(),
                DiagnosticCode.INVALID_INPUT, "没有有效的歌曲名称");
        if (!FilenameNormalizer.balanced(parts.stem())) return failed(filename, parts.normalized(), parts.extension(),
                DiagnosticCode.INVALID_INPUT, "括号或书名号不匹配");
        var tags = TagExtractor.extract(parts.stem());
        var code = SongCodeExtractor.extract(tags.stem());
        var match = code.ambiguous()
                ? new FilenameRule.Match(null, null, RuleId.AMBIGUOUS, 0.90, 2, List.of(code.stem()), true)
                : FilenameRules.match(new FilenameRule.Input(code.stem(), code.value() != null, tags.extracted()));
        var artists = ArtistParser.parse(match.artist(), tags.version() == VersionType.DUET);
        boolean uncertainArtists = artists.ambiguous();
        List<String> artistValues = uncertainArtists ? List.of() : artists.values();
        var unresolved = new ArrayList<>(tags.unknown());
        unresolved.addAll(tags.conflicts());
        unresolved.addAll(match.unresolved());
        if (uncertainArtists) unresolved.add(match.artist());
        List<String> unknown = List.copyOf(unresolved);
        var diagnostics = new ArrayList<Diagnostic>();
        if (code.ambiguous()) diagnostics.add(new Diagnostic(DiagnosticCode.AMBIGUOUS_STRUCTURE,
                "未标记且无前导零的数字前缀不能确认为歌曲编号", 0));
        if (artists.values().size() == 1 && match.artist().contains(" "))
            diagnostics.add(new Diagnostic(DiagnosticCode.ARTIST_CREDIT_PRESERVED,
                    "保留完整歌手署名；空格未作为通用多人分隔符", 0));
        boolean ambiguous = match.ambiguous() || uncertainArtists;
        List<String> unknownForScore = unknown.stream().filter(t -> !tags.conflicts().contains(t)).toList();
        var assessment = ConfidenceCalculator.assess(match, match.title(), artistValues, tags,
                parts.extension() == null, ambiguous, unknownForScore, diagnostics);
        if (assessment.status() == ParseStatus.FAILED)
            diagnostics.add(new Diagnostic(DiagnosticCode.INVALID_INPUT, "没有可用的歌曲结构", 0));
        return new FilenameParseResult(filename, parts.normalized(), match.title(), artistValues,
                tags.language(), tags.version(), code.value(), parts.extension(), tags.quality(),
                assessment.confidence(), assessment.status(), match.rule(), unknown,
                assessment.status() != ParseStatus.SUCCESS, diagnostics);
    }

    private FilenameParseResult failed(String original, String normalized, String extension,
            DiagnosticCode code, String detail) {
        return new FilenameParseResult(original, normalized, null, List.of(), Language.UNKNOWN,
                VersionType.UNKNOWN, null, extension, List.of(), 0, ParseStatus.FAILED, RuleId.NONE,
                original == null || original.isEmpty() ? List.of() : List.of(original), true,
                List.of(new Diagnostic(code, detail, 0)));
    }
}
