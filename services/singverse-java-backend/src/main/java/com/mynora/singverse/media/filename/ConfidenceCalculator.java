package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;

import java.util.List;

final class ConfidenceCalculator {
    record Assessment(double confidence, ParseStatus status) {}

    static Assessment assess(FilenameRule.Match match, String title, List<String> artists,
            TagExtractor.Tags tags, boolean missingExtension, boolean ambiguous,
            List<String> unknown, List<Diagnostic> diagnostics) {
        boolean noCore = title == null && artists.isEmpty();
        if (noCore && !ambiguous) return new Assessment(0, ParseStatus.FAILED);
        double score = match.baseScore();
        diagnostics.add(new Diagnostic(DiagnosticCode.MATCHED_RULE, match.rule().name(), score));
        if (tags.language() != Language.UNKNOWN)
            add(diagnostics, DiagnosticCode.RECOGNIZED_LANGUAGE, tags.language().name(), 0);
        if (tags.version() != VersionType.UNKNOWN)
            add(diagnostics, DiagnosticCode.RECOGNIZED_VERSION, tags.version().name(), 0);
        if (!tags.quality().isEmpty())
            add(diagnostics, DiagnosticCode.RECOGNIZED_QUALITY, tags.quality().toString(), 0);
        // Confidence describes the artist/title interpretation. Auxiliary metadata is evidence only.
        if (missingExtension) add(diagnostics, DiagnosticCode.MISSING_EXTENSION, "未提供媒体扩展名，不影响歌手歌名判断", 0);
        if (!unknown.isEmpty()) add(diagnostics, DiagnosticCode.UNKNOWN_TOKEN, unknown.toString(), 0);
        if (ambiguous) score += add(diagnostics, DiagnosticCode.AMBIGUOUS_STRUCTURE, "名称顺序或片段归属无法可靠确定", -0.25);
        if (!tags.conflicts().isEmpty()) add(diagnostics, DiagnosticCode.CONFLICTING_TAGS, tags.conflicts().toString(), 0);
        double bounded = Math.max(0, Math.min(0.99, score));
        if (Math.abs(bounded - score) > 0.000001)
            add(diagnostics, DiagnosticCode.SCORE_CLAMPED, "置信度限制在 0～0.99", bounded - score);
        score = Math.round(bounded * 100) / 100.0;
        ParseStatus status;
        if (ambiguous) status = ParseStatus.NEEDS_REVIEW;
        else if (title == null || artists.isEmpty() || score < 0.80) status = ParseStatus.PARTIAL;
        else status = ParseStatus.SUCCESS;
        if (title == null || artists.isEmpty()) add(diagnostics, DiagnosticCode.INCOMPLETE_FIELDS, "歌名或歌手尚未可靠识别", 0);
        return new Assessment(score, status);
    }

    private static double add(List<Diagnostic> diagnostics, DiagnosticCode code, String detail, double delta) {
        diagnostics.add(new Diagnostic(code, detail, delta));
        return delta;
    }
}
