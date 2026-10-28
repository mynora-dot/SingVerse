package com.mynora.singverse.media.metadata;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** A similarity heuristic, never a calibrated probability of recording identity. */
@Component
public class MetadataMatcher {
    public double score(String title, List<String> artists, ExternalTrack track) {
        return Math.round((similarity(normalize(title), normalize(track.title())) * .55
                + similarity(normalize(String.join(" ", artists)), normalize(String.join(" ", track.artists()))) * .35
                + .05) * 1000) / 1000.0;
    }
    static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\s·•]+", "");
    }
    static double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.equals(right)) return 1;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return Math.max(0, 1 - previous[right.length()] / (double) Math.max(left.length(), right.length()));
    }
}
