package com.mynora.singverse.media.filename;

import java.util.List;

/**
 * Filename evidence only; fields do not assert anything about the media contents.
 * Confidence, parseStatus and needsReview assess artist/title extraction; auxiliary metadata
 * warnings can remain in unparsedTokens and diagnostics on a SUCCESS result.
 */
public record FilenameParseResult(
        String originalFilename,
        String normalizedFilename,
        String title,
        List<String> artists,
        Language language,
        VersionType versionType,
        String songCode,
        String extension,
        List<String> qualityTags,
        double confidence,
        ParseStatus parseStatus,
        RuleId parserRule,
        List<String> unparsedTokens,
        boolean needsReview,
        List<Diagnostic> diagnostics) {

    public FilenameParseResult {
        artists = List.copyOf(artists);
        qualityTags = List.copyOf(qualityTags);
        unparsedTokens = List.copyOf(unparsedTokens);
        diagnostics = List.copyOf(diagnostics);
    }

    public enum Language { MANDARIN, CANTONESE, ENGLISH, JAPANESE, KOREAN, OTHER, UNKNOWN }

    public enum VersionType { ORIGINAL, ACCOMPANIMENT, INSTRUMENTAL, DUET, KARAOKE, UNKNOWN }

    public enum ParseStatus { SUCCESS, PARTIAL, NEEDS_REVIEW, FAILED }

    public enum RuleId {
        NONE, ARTIST_BOOK_TITLE, TITLE_PAREN_ARTIST, CODE_ARTIST_TITLE,
        TITLE_ARTIST_UNDERSCORE, ARTIST_TITLE_TAGGED, ARTIST_TITLE, TITLE_ONLY, AMBIGUOUS,
        CUSTOM_TEMPLATE, CUSTOM_REGEX, CONFIGURED_BUILTIN
    }

    public enum DiagnosticCode {
        INVALID_INPUT, UNSUPPORTED_EXTENSION, MISSING_EXTENSION, UNKNOWN_TOKEN,
        AMBIGUOUS_STRUCTURE, CONFLICTING_TAGS, INCOMPLETE_FIELDS, MATCHED_RULE,
        RECOGNIZED_LANGUAGE, RECOGNIZED_VERSION, RECOGNIZED_QUALITY, SCORE_CLAMPED,
        ARTIST_CREDIT_PRESERVED, NO_RULE_MATCH
    }

    /** Delta explains the score; evidence-only diagnostics have a zero delta. */
    public record Diagnostic(DiagnosticCode code, String detail, double confidenceDelta) {}
}
