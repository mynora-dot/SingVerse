package com.mynora.singverse.media.filename;

import java.util.List;

public final class BuiltinFilenameRules {
    private BuiltinFilenameRules() {}

    public record Preset(String id, String name, FilenameRuleDefinition.Type type,
            String expression, boolean enabledByDefault, String example, String description) {}

    private static final String LANG = "国语|普通话|粤语|广东话|英语|英文|日语|韩语|其他|其它|Mandarin|Cantonese|English|Japanese|Korean";
    private static final String LIBRARY = "(?P<artist>[^-]+)-(?P<title>[^()\\[\\]]+?)(?:\\([^()]*\\)|\\[[^\\[\\]]*\\])*-"
            + "(?P<language>" + LANG + ")-(?P<extra>.+)";
    private static final List<Preset> PRESETS = List.of(
            template("book", "歌手《歌名》", "{artist}《{title}》", true, "周杰伦《晴天》.mkv"),
            template("parentheses", "歌名（歌手）", "{title}({artist})", true, "晴天（周杰伦）.mkv"),
            template("coded-spaces", "编号 歌手 歌名", "{code} {artist} {title}", true, "00128 周杰伦 晴天.mpg"),
            new Preset("coded-library", "编号＋歌手-歌名-语种-曲库标签", FilenameRuleDefinition.Type.REGEX,
                    "(?P<code>[0-9]+) " + LIBRARY, true, "75 薛之谦-绅士[1080P]-国语-流行.mkv", "明确按编号、歌手在前的曲库约定匹配"),
            new Preset("library", "歌手-歌名-语种-曲库标签", FilenameRuleDefinition.Type.REGEX,
                    LIBRARY, true, "刀郎-孤独的牧羊人(消伴)-国语-流行.mkv", "画面及尾部标签保留为附加信息"),
            template("all-tags", "歌手-歌名-语种-版本-清晰度", "{artist}-{title}-{language}-{version}-{quality}", true, "周杰伦-晴天-国语-原唱-1080P.mkv"),
            template("version-tags", "歌手-歌名-语种-版本", "{artist}-{title}-{language}-{version}", true, "周杰伦-晴天-国语-原唱.mkv"),
            template("language", "歌手-歌名-语种", "{artist}-{title}-{language}", true, "周杰伦-晴天-国语.mkv"),
            template("underscore-version", "歌名_歌手_版本", "{title}_{artist}_{version}", true, "晴天_周杰伦_伴奏.mp4"),
            template("underscore", "歌名_歌手", "{title}_{artist}", true, "晴天_周杰伦.mkv"),
            template("hyphen", "歌手-歌名", "{artist}-{title}", true, "周杰伦-晴天.mkv"),
            template("reverse-hyphen", "歌名-歌手（反向）", "{title}-{artist}", false, "晴天-周杰伦.mkv"));

    private static Preset template(String id, String name, String expression, boolean enabled, String example) {
        return new Preset(id, name, FilenameRuleDefinition.Type.TEMPLATE, expression, enabled, example,
                "按模板指定字段顺序，匹配并不核实歌手身份");
    }

    public static List<Preset> catalog() { return PRESETS; }

    public static List<FilenameRuleDefinition> defaults() {
        return PRESETS.stream().map(p -> new FilenameRuleDefinition("builtin-" + p.id(), p.name(),
                FilenameRuleDefinition.Type.BUILTIN, p.enabledByDefault(), null, p.id())).toList();
    }

    static Preset find(String id) {
        return PRESETS.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
}
