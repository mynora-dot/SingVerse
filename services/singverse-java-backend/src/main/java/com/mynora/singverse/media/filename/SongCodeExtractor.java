package com.mynora.singverse.media.filename;

import java.util.regex.Pattern;

final class SongCodeExtractor {
    private static final Pattern EXPLICIT = Pattern.compile(
            "(?i)^(?:编号|ID)\\s*:\\s*([0-9]+)(?:\\s+|\\s*[-_]\\s*)(.+)$");
    private static final Pattern PREFIX = Pattern.compile("^([0-9]+)\\s+(.+)$");

    record Code(String stem, String value, boolean ambiguous) {}

    static Code extract(String stem) {
        var explicit = EXPLICIT.matcher(stem);
        if (explicit.matches()) return new Code(explicit.group(2).strip(), explicit.group(1), false);
        var prefix = PREFIX.matcher(stem);
        if (!prefix.matches()) return new Code(stem, null, false);
        String digits = prefix.group(1);
        String rest = prefix.group(2);
        boolean structure = rest.contains("-") || rest.contains("_") || rest.contains("《")
                || rest.contains("(") || rest.split(" ").length >= 2;
        if (!structure) return new Code(stem, null, false);
        if (digits.length() > 1 && digits.startsWith("0")) return new Code(rest, digits, false);
        return new Code(stem, null, true);
    }
}
