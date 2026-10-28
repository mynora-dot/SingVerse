package com.mynora.singverse.media.filename;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

final class ArtistParser {
    private static final Pattern SEPARATOR = Pattern.compile(
            "\\s*[&、]\\s*|\\s+(?i:feat\\.|ft\\.)\\s+");

    record Artists(List<String> values, boolean ambiguous) {}

    static Artists parse(String text, boolean duet) {
        if (text == null) return new Artists(List.of(), false);
        List<String> parts = Arrays.stream(SEPARATOR.split(text, -1)).map(String::strip).toList();
        if (parts.stream().anyMatch(p -> !FilenameNormalizer.meaningful(p)))
            return new Artists(List.of(), true);
        if (duet && parts.size() == 1 && text.contains(" ")) {
            List<String> names = List.of(text.split(" +"));
            // Only inside an already established artist credit, with an explicit duet tag.
            // Latin band names and Chinese ensemble credits retain their complete spelling.
            if (names.stream().allMatch(n -> n.matches("[\\p{IsHan}]{2,4}"))
                    && names.stream().noneMatch(n -> n.matches(".*(?:乐队|乐团|合唱团|组合|战队|乐坊)$")))
                return new Artists(names.stream().distinct().toList(), false);
        }
        return new Artists(parts.stream().distinct().toList(), false);
    }
}
