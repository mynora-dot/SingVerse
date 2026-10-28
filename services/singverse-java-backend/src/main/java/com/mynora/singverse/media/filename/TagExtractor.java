package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class TagExtractor {
    record Tags(String stem, Language language, VersionType version, List<String> quality,
            List<String> unknown, List<String> conflicts, boolean extracted) {}

    static Tags evidence(List<String> evidence) {
        var state = new Accumulator();
        for (String value : evidence) {
            if (value == null || value.isBlank()) continue;
            String raw = value.strip();
            if (TagDictionary.known(raw) || TagDictionary.hint(raw)) state.accept(raw);
            else for (String token : raw.split("[\\s\\[\\]()_,，;；|\\-]+"))
                if (FilenameNormalizer.meaningful(token)) state.accept(token);
        }
        Language language = state.language();
        VersionType version = state.version();
        return new Tags("", language, version, List.copyOf(state.quality), List.copyOf(state.unknown),
                List.copyOf(state.conflicts), !evidence.isEmpty());
    }

    static Tags extract(String stem) {
        var state = new Accumulator();
        var cleaned = new StringBuilder();
        for (int i = 0; i < stem.length();) {
            char c = stem.charAt(i);
            // Explicit book-title contents are names, not tag containers.
            if (c == '《') {
                int end = stem.indexOf('》', i);
                cleaned.append(stem, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c != '[' && c != '(') { cleaned.append(c); i++; continue; }
            int end = bracketEnd(stem, i);
            String raw = stem.substring(i, end + 1);
            String token = stem.substring(i + 1, end).strip();
            boolean metadata = TagDictionary.known(token) || TagDictionary.hint(token);
            boolean contextual = c == '(' && hasCoreStructure(cleaned.toString());
            if (metadata || c == '[' || contextual) {
                if (metadata || contextual) state.accept(token);
                else {
                    // Support a list of exact tags within one bracket without breaking "卡拉 OK".
                    String[] tokens = token.split("[\\s,，;；|]+");
                    if (tokens.length > 1 && java.util.Arrays.stream(tokens).allMatch(TagDictionary::known))
                        for (String item : tokens) state.accept(item);
                    else state.unknown.add(raw);
                }
                state.extracted = true;
                // Drop one separator owned by a standalone tag, not an arbitrary empty field.
                String prefix = cleaned.toString().stripTrailing();
                if (prefix.endsWith("-") || prefix.endsWith("_")) cleaned.setLength(prefix.length() - 1);
                if (cleaned.isEmpty() && end + 1 < stem.length()
                        && (stem.charAt(end + 1) == '-' || stem.charAt(end + 1) == '_')) end++;
                cleaned.append(' ');
            } else cleaned.append(raw);
            i = end + 1;
        }
        String remaining = FilenameNormalizer.normalize(cleaned.toString());
        // Only suffix segments are metadata. Internal title words are not searched for tags.
        while (true) {
            int end = Math.max(remaining.lastIndexOf('-'), remaining.lastIndexOf('_'));
            if (end < 0) break;
            String suffix = remaining.substring(end + 1).strip();
            String prefix = remaining.substring(0, end).strip();
            if (!hasCoreStructure(prefix) || !(TagDictionary.known(suffix) || TagDictionary.hint(suffix)
                    || libraryCopySuffix(prefix, suffix))) break;
            state.accept(suffix);
            state.extracted = true;
            remaining = prefix;
        }
        Language language = state.language();
        VersionType version = state.version();
        return new Tags(remaining, language, version, List.copyOf(state.quality),
                List.copyOf(state.unknown), List.copyOf(state.conflicts), state.extracted);
    }

    private static boolean libraryCopySuffix(String prefix, String suffix) {
        // A copy counter is auxiliary only after a language + metadata tail, never in "A-Song-2".
        if (!suffix.matches("[0-9]+|副本")) return false;
        String[] pieces = prefix.split("[-_]");
        if (pieces.length < 4) return false;
        String tail = pieces[pieces.length - 1].strip();
        if (!(TagDictionary.known(tail) || TagDictionary.hint(tail) || tail.equals("副本"))) return false;
        for (int i = 2; i < pieces.length - 1; i++)
            if (TagDictionary.language(pieces[i].strip()) != null) return true;
        return false;
    }

    private static boolean hasCoreStructure(String text) {
        String prefix = text.strip();
        return prefix.contains("-") || prefix.contains("_") || prefix.contains("》")
                || prefix.endsWith(")") || prefix.matches("(?i)^(?:(?:编号|ID):)?[0-9]+ .+ .+$");
    }

    private static int bracketEnd(String stem, int start) {
        int depth = 0;
        for (int i = start; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if ((c == ']' || c == ')') && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unbalanced input must be rejected before tag extraction");
    }

    private static final class Accumulator {
        final LinkedHashSet<Language> languages = new LinkedHashSet<>();
        final LinkedHashSet<VersionType> versions = new LinkedHashSet<>();
        final LinkedHashSet<String> quality = new LinkedHashSet<>();
        final List<String> languageTokens = new ArrayList<>();
        final List<String> versionTokens = new ArrayList<>();
        final List<String> unknown = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        boolean extracted;

        void accept(String token) {
            Language language = TagDictionary.language(token);
            VersionType version = TagDictionary.version(token);
            String resolution = TagDictionary.quality(token);
            if (language != null) { languages.add(language); languageTokens.add(token); }
            else if (version != null) { versions.add(version); versionTokens.add(token); }
            else if (resolution != null) quality.add(resolution);
            else unknown.add(token);
        }

        Language language() {
            if (languages.size() > 1) { conflicts.addAll(languageTokens); return Language.UNKNOWN; }
            return languages.isEmpty() ? Language.UNKNOWN : languages.getFirst();
        }

        VersionType version() {
            if (versions.size() > 1) { conflicts.addAll(versionTokens); return VersionType.UNKNOWN; }
            return versions.isEmpty() ? VersionType.UNKNOWN : versions.getFirst();
        }
    }
}
