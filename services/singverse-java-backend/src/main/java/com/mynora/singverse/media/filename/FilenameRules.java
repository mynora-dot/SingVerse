package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.RuleId.*;
import static com.mynora.singverse.media.filename.FilenameRule.*;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class FilenameRules {
    // This is the only rule registration point. Rules carry explicit specificity, not a hidden last-wins policy.
    private static final List<FilenameRule> RULES = List.of(
            new BookTitle(), new ParenthesizedArtist(), new CodedWhitespace(), new CodedHyphen(), new Underscore(),
            new TaggedHyphen(), new Hyphen(), new TitleOnly());

    static Match match(Input input) {
        List<Match> matches = RULES.stream().map(r -> r.match(input))
                .flatMap(Optional::stream).toList();
        if (matches.isEmpty()) return new Match(null, null, NONE, 0, 0, List.of(input.stem()), false);
        int specificity = matches.stream().mapToInt(Match::specificity).max().orElse(0);
        List<Match> candidates = matches.stream().filter(m -> m.specificity() == specificity).toList();
        Match first = candidates.getFirst();
        if (candidates.stream().allMatch(c -> Objects.equals(c.title(), first.title())
                && Objects.equals(c.artist(), first.artist()))) return first;
        String title = candidates.stream().allMatch(c -> Objects.equals(c.title(), first.title())) ? first.title() : null;
        String artist = candidates.stream().allMatch(c -> Objects.equals(c.artist(), first.artist())) ? first.artist() : null;
        return new Match(title, artist, AMBIGUOUS, first.baseScore(), specificity, List.of(input.stem()), true);
    }

    private static Optional<Match> pair(String[] pieces, boolean titleFirst, FilenameParseResult.RuleId id) {
        List<String> tokens = Arrays.stream(pieces).map(String::strip).toList();
        if (tokens.size() != 2 || tokens.stream().anyMatch(t -> !FilenameNormalizer.meaningful(t)))
            return Optional.of(new Match(null, null, AMBIGUOUS, 0.85, 2, tokens, true));
        return Optional.of(Match.complete(tokens.get(titleFirst ? 0 : 1), tokens.get(titleFirst ? 1 : 0), id, 0.85, 2));
    }

    private static final class BookTitle implements FilenameRule {
        private static final Pattern FORMAT = Pattern.compile("^([^《》]+)《([^《》]+)》$");
        public Optional<Match> match(Input input) {
            var m = FORMAT.matcher(input.stem());
            return m.matches() && FilenameNormalizer.meaningful(m.group(1)) && FilenameNormalizer.meaningful(m.group(2))
                    ? Optional.of(Match.complete(m.group(2), m.group(1), ARTIST_BOOK_TITLE, 0.95, 3)) : Optional.empty();
        }
    }

    private static final class ParenthesizedArtist implements FilenameRule {
        private static final Pattern FORMAT = Pattern.compile("^([^()《》]+)\\(([^()]+)\\)$");
        public Optional<Match> match(Input input) {
            var m = FORMAT.matcher(input.stem());
            return m.matches() && FilenameNormalizer.meaningful(m.group(1)) && FilenameNormalizer.meaningful(m.group(2))
                    ? Optional.of(Match.complete(m.group(1), m.group(2), TITLE_PAREN_ARTIST, 0.90, 3)) : Optional.empty();
        }
    }

    private static final class CodedWhitespace implements FilenameRule {
        public Optional<Match> match(Input input) {
            if (!input.hasCode() || input.stem().contains("-") || input.stem().contains("_")) return Optional.empty();
            String[] pieces = input.stem().split(" ", -1);
            if (pieces.length == 1) return Optional.empty();
            if (pieces.length == 2 && FilenameNormalizer.meaningful(pieces[0]) && FilenameNormalizer.meaningful(pieces[1]))
                return Optional.of(Match.complete(pieces[1], pieces[0], CODE_ARTIST_TITLE, 0.90, 2));
            return Optional.of(new Match(null, null, AMBIGUOUS, 0.90, 2, List.of(pieces), true));
        }
    }

    private static final class Underscore implements FilenameRule {
        public Optional<Match> match(Input input) {
            return input.stem().contains("_") ? pair(input.stem().split("_", -1), true, TITLE_ARTIST_UNDERSCORE) : Optional.empty();
        }
    }

    private static final class CodedHyphen implements FilenameRule {
        public Optional<Match> match(Input input) {
            // Numbered libraries also use "code title - artist". Neither order is justified here.
            if (!input.hasCode() || !input.stem().contains("-")) return Optional.empty();
            return Optional.of(new Match(null, null, AMBIGUOUS, 0.85, 2, List.of(input.stem()), true));
        }
    }

    private static Optional<Match> hyphen(Input input, FilenameParseResult.RuleId id) {
        if (!input.stem().contains("-")) return Optional.empty();
        String delimiter = input.stem().contains(" - ") ? " +- +" : "-";
        return pair(input.stem().split(delimiter, -1), false, id);
    }

    private static final class TaggedHyphen implements FilenameRule {
        public Optional<Match> match(Input input) { return input.hasTags() ? hyphen(input, ARTIST_TITLE_TAGGED) : Optional.empty(); }
    }

    private static final class Hyphen implements FilenameRule {
        public Optional<Match> match(Input input) { return !input.hasTags() ? hyphen(input, ARTIST_TITLE) : Optional.empty(); }
    }

    private static final class TitleOnly implements FilenameRule {
        public Optional<Match> match(Input input) {
            String stem = input.stem();
            // Unbalanced structural punctuation is evidence, not a trustworthy title-only fallback.
            if (!FilenameNormalizer.meaningful(stem) || stem.chars().anyMatch(c -> "()[]《》".indexOf(c) >= 0)) return Optional.empty();
            return Optional.of(new Match(stem, null, TITLE_ONLY, 0.45, 0, List.of(), false));
        }
    }
}
