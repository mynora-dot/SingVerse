package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Exact aliases only. Hints are preserved as unknown evidence, never promoted to metadata. */
final class TagDictionary {
    private static final Map<String, Language> LANGUAGES;
    private static final Map<String, VersionType> VERSIONS;
    private static final Set<String> QUALITY = Set.of("480P", "720P", "1080P", "2K", "4K", "UHD", "HD");
    private static final Set<String> HINTS = Set.of(
            "LIVE", "REMIX", "MV", "MTV", "演唱会", "消伴", "风景", "人物",
            "流行", "民歌", "怀旧歌", "军歌", "儿歌", "舞曲", "戏曲", "影视",
            "流行歌曲", "军旅", "怀旧经典", "怀旧", "影视插曲", "情歌对唱", "双音轨KTV");

    static {
        var languages = new HashMap<String, Language>();
        aliases(languages, Language.MANDARIN, "国语", "普通话", "Mandarin");
        aliases(languages, Language.CANTONESE, "粤语", "广东话", "Cantonese");
        aliases(languages, Language.ENGLISH, "英语", "英文", "English");
        aliases(languages, Language.JAPANESE, "日语", "Japanese");
        aliases(languages, Language.KOREAN, "韩语", "韩文", "Korean");
        aliases(languages, Language.OTHER, "其他", "其它");
        aliases(languages, Language.UNKNOWN, "未知");
        LANGUAGES = Map.copyOf(languages);
        var versions = new HashMap<String, VersionType>();
        aliases(versions, VersionType.ORIGINAL, "原唱", "原版");
        aliases(versions, VersionType.ACCOMPANIMENT, "伴奏", "纯伴奏");
        aliases(versions, VersionType.INSTRUMENTAL, "纯音乐");
        aliases(versions, VersionType.DUET, "合唱", "对唱");
        aliases(versions, VersionType.KARAOKE, "卡拉 OK", "卡拉OK", "KTV");
        VERSIONS = Map.copyOf(versions);
    }

    private static <T> void aliases(Map<String, T> map, T value, String... aliases) {
        for (String alias : aliases) map.put(key(alias), value);
    }

    private static String key(String token) { return token.toUpperCase(Locale.ROOT); }

    static Language language(String token) { return LANGUAGES.get(key(token)); }
    static VersionType version(String token) { return VERSIONS.get(key(token)); }
    static String quality(String token) { return QUALITY.contains(key(token)) ? key(token) : null; }

    static boolean known(String token) {
        return language(token) != null || version(token) != null || quality(token) != null;
    }

    static boolean hint(String token) {
        return HINTS.contains(key(token))
                || token.matches("(?:无|非|不含|没有).*(?:原唱|伴奏|人声)")
                || token.matches("[\\p{L}]{1,6}(?:语|版本)");
    }
}
