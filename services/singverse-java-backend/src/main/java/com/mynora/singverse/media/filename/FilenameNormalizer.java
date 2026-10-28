package com.mynora.singverse.media.filename;

final class FilenameNormalizer {
    private FilenameNormalizer() {}

    static String normalize(String input) {
        if (input == null) return null;
        var result = new StringBuilder();
        boolean space = false;
        for (int point : input.codePoints().toArray()) {
            if (point >= 0xff01 && point <= 0xff5e) point -= 0xfee0;
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = !result.isEmpty();
                continue;
            }
            if (space) result.append(' ');
            space = false;
            point = switch (point) {
                case 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212 -> '-';
                case '【', '〔' -> '[';
                case '】', '〕' -> ']';
                default -> point;
            };
            result.appendCodePoint(point);
        }
        return result.toString();
    }

    static boolean invalid(String input) {
        return input == null || input.codePoints().anyMatch(c -> Character.isISOControl(c)
                || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE));
    }

    static boolean meaningful(String text) {
        return text != null && text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    static boolean balanced(String text) {
        var stack = new java.util.ArrayDeque<Character>();
        for (char c : text.toCharArray()) {
            if ("([《".indexOf(c) >= 0) stack.push(c);
            else if (")]》".indexOf(c) >= 0) {
                if (stack.isEmpty() || "([《".indexOf(stack.pop()) != ")]》".indexOf(c)) return false;
            }
        }
        return stack.isEmpty();
    }
}
