package com.mynora.singverse.media.filename;

import static com.mynora.singverse.media.filename.FilenameParseResult.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KtvFilenameParserTest {
    private final KtvFilenameParser parser = new KtvFilenameParser();

    @ParameterizedTest
    @MethodSource("com.mynora.singverse.media.filename.FilenameSamples#required")
    void requiredNamingConventions(FilenameSamples.Sample sample) {
        var result = parser.parse(sample.filename());
        assertThat(result.originalFilename()).isEqualTo(sample.filename());
        assertThat(result.title()).isEqualTo(sample.title());
        assertThat(result.artists()).containsExactlyElementsOf(sample.artists());
        assertThat(result.language()).isEqualTo(sample.language());
        assertThat(result.versionType()).isEqualTo(sample.version());
        assertThat(result.songCode()).isEqualTo(sample.code());
        assertThat(result.qualityTags()).containsExactlyElementsOf(sample.quality());
        assertThat(result.parserRule()).isEqualTo(sample.rule());
        assertThat(result.parseStatus()).isEqualTo(sample.status());
        assertThat(result.needsReview()).isEqualTo(sample.status() != ParseStatus.SUCCESS);
        assertThat(result.unparsedTokens()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mkv", "MKV", "mp4", "MP4", "mpg", "MPG", "mpeg", "MPEG", "avi", "AVI",
            "ts", "TS", "vob", "VOB", "mov", "MOV", "wmv", "WMV"})
    void mediaExtensions(String extension) {
        var result = parser.parse("周杰伦-晴天." + extension);
        assertThat(result.title()).isEqualTo("晴天");
        assertThat(result.extension()).isEqualTo(extension.toLowerCase(java.util.Locale.ROOT));
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
    }

    @Test
    void normalizesOnlyTheWorkingCopyAndRetainsValidNamePunctuation() {
        String original = "　周杰伦　＆　温岚　—　屋顶　（国语）　【原唱】．ＭＫＶ　";
        var result = parser.parse(original);
        assertThat(result.originalFilename()).isEqualTo(original);
        assertThat(result.normalizedFilename()).isEqualTo("周杰伦 & 温岚 - 屋顶 (国语) [原唱].mkv");
        assertThat(result.artists()).containsExactly("周杰伦", "温岚");
        assertThat(result.title()).isEqualTo("屋顶");
        assertThat(result.language()).isEqualTo(Language.MANDARIN);
        assertThat(result.versionType()).isEqualTo(VersionType.ORIGINAL);
        assertThat(parser.parse("B.A.D-GET HIGH!.mkv").artists()).containsExactly("B.A.D");
        assertThat(parser.parse("B.A.D-GET HIGH!.mkv").title()).isEqualTo("GET HIGH!");
        assertThat(parser.parse("A《① Love’s Song》.mkv").title()).isEqualTo("① Love’s Song");
    }

    @ParameterizedTest
    @CsvSource({"国语,MANDARIN", "普通话,MANDARIN", "Mandarin,MANDARIN", "mAnDaRiN,MANDARIN",
            "粤语,CANTONESE", "广东话,CANTONESE", "Cantonese,CANTONESE", "英语,ENGLISH", "英文,ENGLISH",
            "English,ENGLISH", "日语,JAPANESE", "Japanese,JAPANESE", "韩语,KOREAN", "韩文,KOREAN",
            "Korean,KOREAN", "其他,OTHER", "未知,UNKNOWN"})
    void languageAliases(String alias, Language expected) {
        var result = parser.parse("歌手-歌名[" + alias + "].mkv");
        assertThat(result.title()).isEqualTo("歌名");
        assertThat(result.language()).isEqualTo(expected);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
    }

    @ParameterizedTest
    @CsvSource({"原唱,ORIGINAL", "原版,ORIGINAL", "伴奏,ACCOMPANIMENT", "纯伴奏,ACCOMPANIMENT",
            "纯音乐,INSTRUMENTAL", "合唱,DUET", "对唱,DUET", "卡拉 OK,KARAOKE", "卡拉OK,KARAOKE", "ktv,KARAOKE"})
    void versionAliases(String alias, VersionType expected) {
        var result = parser.parse("歌手-歌名[" + alias + "].mkv");
        assertThat(result.title()).isEqualTo("歌名");
        assertThat(result.versionType()).isEqualTo(expected);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"480P", "720p", "1080P", "2k", "4K", "uHd", "hd"})
    void qualityAliases(String tag) {
        var result = parser.parse("歌手-歌名-" + tag + ".mkv");
        assertThat(result.title()).isEqualTo("歌名");
        assertThat(result.qualityTags()).containsExactly(tag.toUpperCase(java.util.Locale.ROOT));
    }

    @Test
    void combinesTagsAndDeduplicatesSynonymsWithoutConflicts() {
        var result = parser.parse("A-T[国语 原唱 1080P]-Mandarin-原版-1080p.mkv");
        assertThat(result.title()).isEqualTo("T");
        assertThat(result.language()).isEqualTo(Language.MANDARIN);
        assertThat(result.versionType()).isEqualTo(VersionType.ORIGINAL);
        assertThat(result.qualityTags()).containsExactly("1080P");
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
    }

    @Test
    void conflictingTagsArePreservedAndNotResolvedByLastWins() {
        var result = parser.parse("A-T-国语-粤语-原唱-伴奏.mkv");
        assertThat(result.title()).isEqualTo("T");
        assertThat(result.language()).isEqualTo(Language.UNKNOWN);
        assertThat(result.versionType()).isEqualTo(VersionType.UNKNOWN);
        assertThat(result.unparsedTokens()).contains("国语", "粤语", "原唱", "伴奏");
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains(DiagnosticCode.CONFLICTING_TAGS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"无原唱", "非原唱", "不含原唱", "没有原唱", "无伴奏", "法语", "Remix", "Live", "未知版本"})
    void unsupportedAndNegativeLabelsAreNeverGuessed(String token) {
        var result = parser.parse("周杰伦-晴天[" + token + "].mkv");
        assertThat(result.title()).isEqualTo("晴天");
        assertThat(result.artists()).containsExactly("周杰伦");
        assertThat(result.language()).isEqualTo(Language.UNKNOWN);
        assertThat(result.versionType()).isEqualTo(VersionType.UNKNOWN);
        assertThat(String.join(" ", result.unparsedTokens())).contains(token);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.confidence()).isEqualTo(0.85);
    }

    @Test
    void doesNotFindTagsInsideNamesOrExplicitBookTitles() {
        var result = parser.parse("原唱乐队-我爱国语和纯伴奏1080P.mkv");
        assertThat(result.artists()).containsExactly("原唱乐队");
        assertThat(result.title()).isEqualTo("我爱国语和纯伴奏1080P");
        assertThat(result.language()).isEqualTo(Language.UNKNOWN);
        assertThat(result.versionType()).isEqualTo(VersionType.UNKNOWN);
        assertThat(result.qualityTags()).isEmpty();
        assertThat(parser.parse("周杰伦-原唱.mkv").title()).isEqualTo("原唱");
        assertThat(parser.parse("原唱.mkv").title()).isEqualTo("原唱");
        assertThat(parser.parse("A《歌名(国语)》.mkv").title()).isEqualTo("歌名(国语)");
        assertThat(parser.parse("A《歌名(国语)》.mkv").language()).isEqualTo(Language.UNKNOWN);
    }

    @Test
    void splitsOnlyConfirmedArtistFields() {
        assertThat(parser.parse("A feat. B ft. C-歌名&副标题/Part 2.mkv").artists()).containsExactly("A", "B", "C");
        assertThat(parser.parse("A feat. B ft. C-歌名&副标题/Part 2.mkv").title()).isEqualTo("歌名&副标题/Part 2");
        assertThat(parser.parse("A、B-歌名.mkv").artists()).containsExactly("A", "B");
        assertThat(parser.parse("AC/DC-Hello World.mkv").artists()).containsExactly("AC/DC");
        assertThat(parser.parse("My Band-Hello feat. World.mkv").artists()).containsExactly("My Band");
        assertThat(parser.parse("My Band-Hello feat. World.mkv").title()).isEqualTo("Hello feat. World");
        assertThat(parser.parse("The feat.Band-Title.mkv").artists()).containsExactly("The feat.Band");
        assertThat(parser.parse("A&&B-Title.mkv").artists()).isEmpty();
        assertThat(parser.parse("A&&B-Title.mkv").unparsedTokens()).contains("A&&B");
        assertThat(parser.parse("A&&B-Title.mkv").parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
    }

    @Test
    void preservesNumbersSpacesAndEmbeddedHyphens() {
        var result = parser.parse("AC-DC - Song-Part 2.mkv");
        assertThat(result.artists()).containsExactly("AC-DC");
        assertThat(result.title()).isEqualTo("Song-Part 2");
        assertThat(result.songCode()).isNull();
        assertThat(parser.parse("2Pac-Changes 2000.mkv").artists()).containsExactly("2Pac");
        assertThat(parser.parse("2Pac-Changes 2000.mkv").title()).isEqualTo("Changes 2000");
        assertThat(parser.parse("1984.mkv").title()).isEqualTo("1984");
        assertThat(parser.parse("1984.mkv").songCode()).isNull();
        assertThat(parser.parse("七里香2004.mkv").songCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"编号:00128 周杰伦 晴天.mkv", "编号：00128 周杰伦 晴天.mkv", "ID:00128 周杰伦 晴天.mkv"})
    void explicitCodeMarkers(String filename) {
        var result = parser.parse(filename);
        assertThat(result.songCode()).isEqualTo("00128");
        assertThat(result.artists()).containsExactly("周杰伦");
        assertThat(result.title()).isEqualTo("晴天");
    }

    @Test
    void doesNotInventCodesOrSplitAmbiguousWhitespaceNames() {
        var numeric = parser.parse("1984 周杰伦 晴天.mkv");
        assertThat(numeric.songCode()).isNull();
        assertThat(numeric.title()).isNull();
        assertThat(numeric.artists()).isEmpty();
        assertThat(numeric.unparsedTokens()).contains("1984 周杰伦 晴天");
        assertThat(numeric.parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
        var spaced = parser.parse("00128 My Band My Song.mkv");
        assertThat(spaced.songCode()).isEqualTo("00128");
        assertThat(spaced.title()).isNull();
        assertThat(spaced.artists()).isEmpty();
        assertThat(spaced.unparsedTokens()).containsExactly("My", "Band", "My", "Song");
        assertThat(spaced.parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"歌手-歌名-额外片段.mkv", "A-B_C.mkv", "A-B-C.mkv", "A--B.mkv"})
    void ambiguousDelimitersDoNotForceCoreFields(String filename) {
        var result = parser.parse(filename);
        assertThat(result.title()).isNull();
        assertThat(result.artists()).isEmpty();
        assertThat(result.unparsedTokens()).isNotEmpty();
        assertThat(result.confidence()).isLessThan(0.80);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
        assertThat(result.needsReview()).isTrue();
    }

    @Test
    void missingExtensionDoesNotBlockCoreSuccessButMissingArtistIsPartial() {
        var named = parser.parse("周杰伦-晴天");
        assertThat(named.title()).isEqualTo("晴天");
        assertThat(named.artists()).containsExactly("周杰伦");
        assertThat(named.extension()).isNull();
        assertThat(named.confidence()).isEqualTo(0.85);
        assertThat(named.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        var title = parser.parse("晴天.mkv");
        assertThat(title.title()).isEqualTo("晴天");
        assertThat(title.artists()).isEmpty();
        assertThat(title.confidence()).isEqualTo(0.45);
        assertThat(title.needsReview()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "　", ".mkv", "---.mkv", "[].mkv", "\u0000.mkv", "A\nB.mkv", "\uD800.mkv",
            "A-T[国语.mkv", "A-T).mkv", "A《T).mkv", "[国语].mkv"})
    void invalidNamesReturnFailureWithoutThrowing(String filename) {
        var result = parser.parse(filename);
        assertThat(result.originalFilename()).isEqualTo(filename);
        assertThat(result.title()).isNull();
        assertThat(result.artists()).isEmpty();
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(result.confidence()).isZero();
        assertThat(result.diagnostics()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A-T.txt", "A-T.zip", "A-T.mkv.bak"})
    void unsupportedExtensionsAreExplicitFailures(String filename) {
        var result = parser.parse(filename);
        assertThat(result.title()).isNull();
        assertThat(result.unparsedTokens()).containsExactly(filename);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains(DiagnosticCode.UNSUPPORTED_EXTENSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "wma", "aif", "aiff", "ape"})
    void commonAudioFilesUseTheSameFilenameParsing(String extension) {
        String filename = "周杰伦《晴天》." + extension.toUpperCase(java.util.Locale.ROOT);
        assertThat(parser.supportsMediaFilename(filename)).isTrue();
        var result = parser.parse(filename);
        assertThat(result.title()).isEqualTo("晴天");
        assertThat(result.artists()).containsExactly("周杰伦");
        assertThat(result.extension()).isEqualTo(extension);
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    void unknownBracketsAreRetainedIncludingNestedContents() {
        var result = parser.parse("A-T[[自定义标签]].mkv");
        assertThat(result.title()).isEqualTo("T");
        assertThat(result.unparsedTokens()).containsExactly("[[自定义标签]]");
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parser.parse("A-T-[自定义标签].mkv").title()).isEqualTo("T");
        assertThat(parser.parse("A-T[1080PX].mkv").qualityTags()).isEmpty();
        assertThat(parser.parse("A-T[1080PX].mkv").unparsedTokens()).containsExactly("[1080PX]");
    }

    @Test
    void observedLibraryConventionsRemainEvidenceRatherThanInventedVersions() {
        var result = parser.parse("刀郎-孤独的牧羊人(消伴)-国语-流行.mkv");
        assertThat(result.artists()).containsExactly("刀郎");
        assertThat(result.title()).isEqualTo("孤独的牧羊人");
        assertThat(result.language()).isEqualTo(Language.MANDARIN);
        assertThat(result.versionType()).isEqualTo(VersionType.UNKNOWN);
        assertThat(result.unparsedTokens()).containsExactly("消伴", "流行");
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        var mtv = parser.parse("莫文蔚-海上花(MTV)-国语-流行.mkv");
        assertThat(mtv.title()).isEqualTo("海上花");
        assertThat(mtv.artists()).containsExactly("莫文蔚");
        assertThat(mtv.unparsedTokens()).containsExactly("MTV", "流行");
        var scene = parser.parse("阎维文-英雄颂(风景)-国语-流行.mkv");
        assertThat(scene.artists()).containsExactly("阎维文");
        assertThat(scene.title()).isEqualTo("英雄颂");
        var unknown = parser.parse("周杰伦-晴天(自定义版本)-国语.mkv");
        assertThat(unknown.title()).isEqualTo("晴天");
        assertThat(unknown.artists()).containsExactly("周杰伦");
        assertThat(unknown.unparsedTokens()).contains("自定义版本");
    }

    @Test
    void numberedHyphenRemainsAmbiguousButDuetContextCanSeparateChineseNames() {
        var numbered = parser.parse("069 一场游戏一场梦 - 王杰.mkv");
        assertThat(numbered.songCode()).isEqualTo("069");
        assertThat(numbered.title()).isNull();
        assertThat(numbered.artists()).isEmpty();
        assertThat(numbered.unparsedTokens()).containsExactly("一场游戏一场梦 - 王杰");
        assertThat(numbered.parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
        var duet = parser.parse("于文华 尹相杰-同喜同喜-国语-对唱.mkv");
        assertThat(duet.title()).isEqualTo("同喜同喜");
        assertThat(duet.artists()).containsExactly("于文华", "尹相杰");
        assertThat(duet.unparsedTokens()).isEmpty();
        assertThat(duet.versionType()).isEqualTo(VersionType.DUET);
        assertThat(duet.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parser.parse("id:00128-周杰伦-晴天.mkv").parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
    }

    @Test
    void scoresDescribeCoreStructureAndDiagnosticDeltasExplainTheScore() {
        var result = parser.parse("A《T》[国语][原唱][1080P].mkv");
        assertThat(result.confidence()).isEqualTo(0.95);
        assertThat(result.diagnostics().stream().mapToDouble(Diagnostic::confidenceDelta).sum())
                .isCloseTo(result.confidence(), within(0.000001));
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains(DiagnosticCode.MATCHED_RULE, DiagnosticCode.RECOGNIZED_LANGUAGE);
        var unknown = parser.parse("A-T[x][y].mkv");
        assertThat(unknown.confidence()).isEqualTo(0.85);
        assertThat(unknown.diagnostics().stream().filter(d -> d.code() == DiagnosticCode.UNKNOWN_TOKEN)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A-T.mkv", "A-T", "A-T[MTV]-国语-流行.mkv", "A-T[自定义标签].mkv",
            "A-T-国语-粤语-原唱-伴奏.mkv", "A-T-国语-原唱-1080P.mkv"})
    void coreSuccessDoesNotDependOnAuxiliaryMetadata(String filename) {
        var result = parser.parse(filename);
        assertThat(result.title()).isEqualTo("T");
        assertThat(result.artists()).containsExactly("A");
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.needsReview()).isFalse();
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.diagnostics().stream().mapToDouble(Diagnostic::confidenceDelta).sum())
                .isCloseTo(result.confidence(), within(0.000001));
    }

    @Test
    void duetNamesUseContextWhileFullBandCreditsAndTitleSpacesRemainIntact() {
        var duet = parser.parse("周杰伦 温岚-屋顶-国语-对唱.mkv");
        assertThat(duet.artists()).containsExactly("周杰伦", "温岚");
        assertThat(duet.title()).isEqualTo("屋顶");
        assertThat(duet.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(parser.parse("周杰伦 温岚-屋顶.mkv").artists()).containsExactly("周杰伦 温岚");
        var band = parser.parse("My Band-Hello World-对唱.mkv");
        assertThat(band.artists()).containsExactly("My Band");
        assertThat(band.title()).isEqualTo("Hello World");
        assertThat(band.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(band.diagnostics()).extracting(Diagnostic::code).contains(DiagnosticCode.ARTIST_CREDIT_PRESERVED);
        assertThat(parser.parse("中国 交响乐团-歌名-对唱.mkv").artists()).containsExactly("中国 交响乐团");
        assertThat(parser.parse("TFBOYS 韩庚-歌名-对唱.mkv").artists()).containsExactly("TFBOYS 韩庚");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A-T-国语-流行-2.mkv", "A-T-国语-流行_1.mkv", "A-T-国语-流行 - 副本-2.mkv"})
    void libraryCopyCountersAfterMetadataDoNotObscureTheCore(String filename) {
        var result = parser.parse(filename);
        assertThat(result.artists()).containsExactly("A");
        assertThat(result.title()).isEqualTo("T");
        assertThat(result.language()).isEqualTo(Language.MANDARIN);
        assertThat(result.unparsedTokens()).contains("流行");
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.needsReview()).isFalse();
        assertThat(result.unparsedTokens()).contains(filename.contains("_1") ? "1" : "2");
        if (filename.contains("副本")) assertThat(result.unparsedTokens()).contains("副本");
        assertThat(parser.parse("A-T-2.mkv").parseStatus()).isEqualTo(ParseStatus.NEEDS_REVIEW);
        assertThat(parser.parse("A-Song 2.mkv").title()).isEqualTo("Song 2");
    }

    @Test
    void resultsAreImmutableRepeatableAndThreadSafe() throws Exception {
        String filename = "周杰伦&温岚-屋顶-国语-原唱-1080P.mkv";
        var result = parser.parse(filename);
        for (int i = 0; i < 20; i++) assertThat(parser.parse(filename)).isEqualTo(result);
        assertThatThrownBy(() -> result.artists().add("X")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.qualityTags().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.unparsedTokens().add("X")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.diagnostics().clear()).isInstanceOf(UnsupportedOperationException.class);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<FilenameParseResult>> tasks = IntStream.range(0, 100)
                    .mapToObj(i -> (Callable<FilenameParseResult>) () -> parser.parse(filename)).toList();
            for (var future : executor.invokeAll(tasks)) assertThat(future.get()).isEqualTo(result);
        }
    }

    @Test
    void malformedRandomInputsNeverCrashOrLoseTheOriginal() {
        var random = new Random(128);
        String alphabet = "周歌012-&_《》[]() .原唱语😀";
        for (int i = 0; i < 2000; i++) {
            var input = new StringBuilder();
            for (int n = random.nextInt(80); n > 0; n--) input.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String filename = input + ".mkv";
            var result = parser.parse(filename);
            assertThat(result.originalFilename()).isEqualTo(filename);
            assertThat(result.confidence()).isBetween(0.0, 1.0);
        }
    }
}
