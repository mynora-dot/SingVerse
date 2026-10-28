package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;

import java.util.List;
import java.util.stream.Stream;

/** One explicit expectation per naming convention; add fixtures here when adding a rule. */
final class FilenameSamples {
    record Sample(String filename, String title, List<String> artists, Language language,
            VersionType version, String code, List<String> quality, RuleId rule, ParseStatus status) {}

    static Stream<Sample> required() {
        return Stream.of(
                sample("周杰伦-晴天.mkv", "晴天", "周杰伦", Language.UNKNOWN, VersionType.UNKNOWN, RuleId.ARTIST_TITLE),
                sample("周杰伦-晴天-国语-原唱.mkv", "晴天", "周杰伦", Language.MANDARIN, VersionType.ORIGINAL, RuleId.ARTIST_TITLE_TAGGED),
                sample("晴天_周杰伦_伴奏.mp4", "晴天", "周杰伦", Language.UNKNOWN, VersionType.ACCOMPANIMENT, RuleId.TITLE_ARTIST_UNDERSCORE),
                new Sample("00128 周杰伦 晴天.mpg", "晴天", List.of("周杰伦"), Language.UNKNOWN,
                        VersionType.UNKNOWN, "00128", List.of(), RuleId.CODE_ARTIST_TITLE, ParseStatus.SUCCESS),
                sample("周杰伦《晴天》.mkv", "晴天", "周杰伦", Language.UNKNOWN, VersionType.UNKNOWN, RuleId.ARTIST_BOOK_TITLE),
                sample("晴天（周杰伦）.mkv", "晴天", "周杰伦", Language.UNKNOWN, VersionType.UNKNOWN, RuleId.TITLE_PAREN_ARTIST),
                sample("陈奕迅 - 富士山下 [粤语].mkv", "富士山下", "陈奕迅", Language.CANTONESE, VersionType.UNKNOWN, RuleId.ARTIST_TITLE_TAGGED),
                new Sample("周杰伦&温岚-屋顶-国语.mkv", "屋顶", List.of("周杰伦", "温岚"), Language.MANDARIN,
                        VersionType.UNKNOWN, null, List.of(), RuleId.ARTIST_TITLE_TAGGED, ParseStatus.SUCCESS),
                new Sample("周杰伦-晴天-国语-原唱-1080P.mkv", "晴天", List.of("周杰伦"), Language.MANDARIN,
                        VersionType.ORIGINAL, null, List.of("1080P"), RuleId.ARTIST_TITLE_TAGGED, ParseStatus.SUCCESS),
                new Sample("晴天.mkv", "晴天", List.of(), Language.UNKNOWN, VersionType.UNKNOWN,
                        null, List.of(), RuleId.TITLE_ONLY, ParseStatus.PARTIAL),
                sample("周杰伦 － 晴天 （国语） [原唱].mkv", "晴天", "周杰伦", Language.MANDARIN, VersionType.ORIGINAL, RuleId.ARTIST_TITLE_TAGGED));
    }

    private static Sample sample(String filename, String title, String artist, Language language,
            VersionType version, RuleId rule) {
        return new Sample(filename, title, List.of(artist), language, version, null, List.of(), rule, ParseStatus.SUCCESS);
    }
}
