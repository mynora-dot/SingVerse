package com.mynora.singverse.media.filename;

import com.mynora.singverse.media.filename.FilenameParseResult.RuleId;
import java.util.List;
import java.util.Optional;

/** Rules return candidates; fallback rules do not compete with explicit structures. */
interface FilenameRule {
    Optional<Match> match(Input input);

    record Input(String stem, boolean hasCode, boolean hasTags) {}

    record Match(String title, String artist, RuleId rule, double baseScore, int specificity,
            List<String> unresolved, boolean ambiguous) {
        static Match complete(String title, String artist, RuleId rule, double score, int specificity) {
            return new Match(title.strip(), artist.strip(), rule, score, specificity, List.of(), false);
        }
    }
}
